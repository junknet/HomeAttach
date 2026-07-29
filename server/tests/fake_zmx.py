#!/usr/bin/env python3
"""A stand-in for the real `zmx`, injected into `tsess-mux` via HOMEATTACH_ZMX.

The suite never runs the real binary and never touches a real session: the
sessions on this machine belong to the user, and a test that floods, resizes or
kills one of them is not a test, it is an accident.

Only the verbs `tsess-mux` depends on are implemented:

    attach --mirror <name>   stream output, accept input
      --resume <epoch>:<offset>  answered on stderr the way real zmx does:
                                 a non-zero epoch matching FAKE_ZMX_EPOCH and an
                                 offset at or below FAKE_ZMX_OFFSET continues,
                                 anything else is a snapshot
      --tail <rows>              recorded in $FAKE_ZMX_ATTACH_LOG, not acted on
    claim <name> <cols> <rows>
    release <name>
    stat                     bulk status, read verbatim from $FAKE_ZMX_STAT

`claim` and `release` append one line to $FAKE_ZMX_LOG so a test can assert what
the mux asked the host to do, and how many times. Attach-time arguments go to
$FAKE_ZMX_ATTACH_LOG instead: mixing them into the same file would make every
"the mux asked for nothing" assertion depend on how attaches are spelled.

The session *name* selects the behaviour, which is what lets one test ask for a
flood and another for silence without a second fixture:

    flood-*    write forever, as fast as the pipe takes it
    beat-*     one short marker every 50ms — visible only if not starved
    echo-*     mirror stdin back as `echo:<data>`
    die-*      greet once, then exit 0 (an ended session)
    linger-*   ignore SIGHUP and keep writing through errors, so only an
               explicit kill from the mux stops it
    anything   greet once, then idle
"""

from __future__ import annotations

import os
import signal
import sys
import time

GREETING = b"%s ready\n"
FLOOD_CHUNK = b"F" * 1024
BEAT_INTERVAL_S = 0.05


def log_call(verb: str, args: list[str], env_key: str = "FAKE_ZMX_LOG") -> None:
    path = os.environ.get(env_key)
    if not path:
        return
    with open(path, "a", encoding="utf-8") as handle:
        handle.write(f"{verb} {' '.join(args)}\n")
        handle.flush()


def write_out(data: bytes, tolerate_errors: bool = False) -> bool:
    try:
        os.write(1, data)
        return True
    except OSError:
        return tolerate_errors


def report_resume(args: list[str]) -> None:
    """The one control line real zmx prints, on the same channel it uses."""
    spec = None
    for i, arg in enumerate(args):
        if arg == "--resume" and i + 1 < len(args):
            spec = args[i + 1]
        if arg == "--tail" and i + 1 < len(args):
            log_call("tail", [name_of(args), args[i + 1]], "FAKE_ZMX_ATTACH_LOG")
    if spec is None:
        return
    epoch_str, _, offset_str = spec.partition(":")
    asked_epoch, asked_offset = int(epoch_str or 0), int(offset_str or 0)
    live_epoch = int(os.environ.get("FAKE_ZMX_EPOCH", "7777"))
    live_offset = int(os.environ.get("FAKE_ZMX_OFFSET", "0"))
    continued = asked_epoch == live_epoch and 0 < asked_offset <= live_offset
    mode = "continued" if continued else "snapshot"
    log_call("resume", [name_of(args), mode, str(asked_epoch), str(asked_offset)],
             "FAKE_ZMX_ATTACH_LOG")
    if os.environ.get("FAKE_ZMX_SILENT_RESUME"):
        # A zmx too old to know about resuming: attaches, says nothing.
        return
    replay = max(live_offset - asked_offset, 0) if continued else 0
    sys.stderr.write(
        f"zmx-resume mode={mode} epoch={live_epoch} offset={live_offset} bytes={replay}\n"
    )
    sys.stderr.flush()


def name_of(args: list[str]) -> str:
    skip = False
    for arg in args:
        if skip:
            skip = False
            continue
        if arg in ("--resume", "--tail"):
            skip = True
            continue
        if not arg.startswith("--"):
            return arg
    return "?"


def run_attach(name: str) -> int:
    # The real zmx would switch the inherited session instead of attaching to the one it was
    # asked for, so the mux has to strip this before spawning us. Record it rather than guess.
    leaked = os.environ.get("ZMX_SESSION")
    if leaked:
        log_call("leaked-zmx-session", [leaked])

    linger = name.startswith("linger-")
    if linger:
        signal.signal(signal.SIGHUP, signal.SIG_IGN)

    if not write_out(GREETING % name.encode(), linger) and not linger:
        return 0

    if name.startswith("die-"):
        return 0

    if name.startswith("flood-"):
        while write_out(FLOOD_CHUNK, linger):
            pass
        return 0

    if name.startswith("beat-"):
        beat = 0
        while True:
            time.sleep(BEAT_INTERVAL_S)
            beat += 1
            if not write_out(b"beat %d\n" % beat, linger) and not linger:
                return 0

    if name.startswith("echo-"):
        while True:
            try:
                data = os.read(0, 4096)
            except OSError:
                return 0
            if not data:
                return 0
            if not write_out(b"echo:" + data, linger) and not linger:
                return 0

    if linger:
        while True:
            time.sleep(BEAT_INTERVAL_S)
            write_out(b"linger\n", True)

    # Idle but attached: hold the slot open until the mux tears us down.
    while True:
        try:
            if not os.read(0, 4096):
                return 0
        except OSError:
            return 0


def main(argv: list[str]) -> int:
    if not argv:
        print("fake_zmx: no verb", file=sys.stderr)
        return 2
    verb, args = argv[0], argv[1:]

    if verb == "attach":
        # name_of, not "the first non-flag": --resume 0:0 and --tail 200 are
        # values, and taking one of those as the session name silently attaches
        # to a session nobody asked for.
        name = name_of(args)
        if name == "?":
            print("fake_zmx: attach without a session name", file=sys.stderr)
            return 2
        if "--mirror" not in args:
            # The mux must never take pty ownership; catching it here is
            # cheaper than discovering it against a real session.
            print("fake_zmx: attach without --mirror", file=sys.stderr)
            return 2
        report_resume(args)
        return run_attach(name)

    if verb in ("claim", "release"):
        log_call(verb, args)
        return 0

    if verb == "stat":
        if args:
            # Single-session form: the mux asks this before attaching, to find
            # out whether the daemon knows how to resume at all. $FAKE_ZMX_NO_EPOCH
            # stands in for a host that predates the resume patches.
            if os.environ.get("FAKE_ZMX_NO_EPOCH"):
                sys.stdout.write("pid=1 cols=80 rows=24 owners=1 mirrors=0 bound=1\n")
                return 0
            epoch = os.environ.get("FAKE_ZMX_EPOCH", "7777")
            end = os.environ.get("FAKE_ZMX_OFFSET", "0")
            sys.stdout.write(
                f"pid=1 cols=80 rows=24 owners=1 mirrors=0 bound=1 output_seq=1"
                f" epoch={epoch} stream_start=0 stream_end={end}\n"
            )
            return 0
        # Bulk form: one process answering for every session is the whole reason
        # the mux may poll this several times a second.
        path = os.environ.get("FAKE_ZMX_STAT")
        if path and os.path.exists(path):
            with open(path, "r", encoding="utf-8") as handle:
                sys.stdout.write(handle.read())
        return 0

    print(f"fake_zmx: unsupported verb {verb}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
