#!/usr/bin/env python3
"""A stand-in for the real `zmx`, injected into `tsess-mux` via HOMEATTACH_ZMX.

The suite never runs the real binary and never touches a real session: the
sessions on this machine belong to the user, and a test that floods, resizes or
kills one of them is not a test, it is an accident.

Only the verbs `tsess-mux` depends on are implemented:

    attach --mirror <name>   stream output, accept input
    claim <name> <cols> <rows>
    release <name>
    stat                     bulk status, read verbatim from $FAKE_ZMX_STAT

`claim` and `release` append one line to $FAKE_ZMX_LOG so a test can assert
what the mux asked the host to do, and how many times.

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


def log_call(verb: str, args: list[str]) -> None:
    path = os.environ.get("FAKE_ZMX_LOG")
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
        positional = [a for a in args if not a.startswith("--")]
        if not positional:
            print("fake_zmx: attach without a session name", file=sys.stderr)
            return 2
        if "--mirror" not in args:
            # The mux must never take pty ownership; catching it here is
            # cheaper than discovering it against a real session.
            print("fake_zmx: attach without --mirror", file=sys.stderr)
            return 2
        return run_attach(positional[0])

    if verb in ("claim", "release"):
        log_call(verb, args)
        return 0

    if verb == "stat":
        # Bulk form only: one process answering for every session is the whole
        # reason the mux may poll this several times a second.
        path = os.environ.get("FAKE_ZMX_STAT")
        if path and os.path.exists(path):
            with open(path, "r", encoding="utf-8") as handle:
                sys.stdout.write(handle.read())
        return 0

    print(f"fake_zmx: unsupported verb {verb}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
