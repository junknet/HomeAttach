#!/usr/bin/env python3
"""Functional tests for HomeAttach's resume patches: byte ring, epoch, cursor.

The property that matters is not "fewer bytes" but "the same bytes". A resume
that drops or duplicates one byte splices a hole into someone's terminal, so
every check here compares reconstructed streams, not sizes.

Usage: ./test-resume.py [path-to-zmx]
"""
import fcntl
import os
import pty
import re
import signal
import struct
import subprocess
import sys
import termios
import time

ZMX = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else
                      os.path.join(os.path.dirname(__file__), "zig-out/bin/zmx"))
ZMX_DIR = "/tmp/zmx-resume-test"
ENV = {**os.environ, "ZMX_DIR": ZMX_DIR, "SHELL": "/bin/bash"}
ENV.pop("ZMX_SESSION", None)

FAILURES = []


def check(name, cond, detail=""):
    print(f"  [{'ok' if cond else 'FAIL'}] {name}" + (f"  ({detail})" if detail and not cond else ""))
    if not cond:
        FAILURES.append(name)


class Client:
    """One `zmx attach` on its own pty, with stdout and stderr kept apart."""

    def __init__(self, args, cols=100, rows=40):
        self.master, slave = pty.openpty()
        fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
        self.err_r, err_w = os.pipe()
        self.proc = subprocess.Popen([ZMX] + args, stdin=slave, stdout=slave,
                                     stderr=err_w, env=ENV, start_new_session=True)
        os.close(slave)
        os.close(err_w)
        os.set_blocking(self.master, False)
        os.set_blocking(self.err_r, False)
        self.out = b""
        self.err = b""

    def pump(self, seconds=1.0):
        end = time.time() + seconds
        while time.time() < end:
            for fd, attr in ((self.master, "out"), (self.err_r, "err")):
                try:
                    data = os.read(fd, 1 << 20)
                    if data:
                        setattr(self, attr, getattr(self, attr) + data)
                except (BlockingIOError, OSError):
                    pass
            time.sleep(0.02)
        return self.out

    def send(self, data: bytes):
        os.write(self.master, data)

    def resume_line(self):
        m = re.search(rb"zmx-resume mode=(\w+) epoch=(\d+) offset=(\d+)", self.err)
        return (m.group(1).decode(), int(m.group(2)), int(m.group(3))) if m else None

    def close(self):
        try:
            self.proc.terminate()
            self.proc.wait(timeout=5)
        except (ProcessLookupError, subprocess.TimeoutExpired):
            try:
                self.proc.kill()
            except ProcessLookupError:
                pass
        for fd in (self.master, self.err_r):
            try:
                os.close(fd)
            except OSError:
                pass


def stat(name):
    out = subprocess.run([ZMX, "stat", name], capture_output=True, text=True,
                         env=ENV, timeout=10).stdout.strip()
    return dict(kv.split("=", 1) for kv in out.split()) if out else {}


def wait_for(cond, seconds=5.0):
    end = time.time() + seconds
    while time.time() < end:
        if cond():
            return True
        time.sleep(0.05)
    return cond()


def main():
    subprocess.run(["pkill", "-f", ZMX_DIR], capture_output=True)
    subprocess.run(["rm", "-rf", ZMX_DIR], check=True)
    os.makedirs(ZMX_DIR, mode=0o700)

    print("== the daemon reports a stream cursor ==")
    owner = Client(["attach", "--bind", "r1", "bash", "--norc"])
    wait_for(lambda: stat("r1").get("epoch") not in (None, "0"))
    owner.send(b"PS1=''\n")
    time.sleep(0.5)
    s = stat("r1")
    check("epoch is set and non-zero", s.get("epoch", "0") != "0", str(s))
    check("stream_end advances with output", int(s.get("stream_end", "0")) > 0, str(s))
    epoch = int(s["epoch"])

    print("== a first mirror gets a snapshot and a cursor ==")
    owner.send(b"echo BEFORE_ATTACH\n")
    time.sleep(0.8)
    m1 = Client(["attach", "--mirror", "--resume", "0:0", "--tail", "50", "r1"])
    m1.pump(1.2)
    info = m1.resume_line()
    check("mirror reported its resume mode", info is not None, repr(m1.err[:200]))
    if info:
        check("cold mirror is told 'snapshot'", info[0] == "snapshot", str(info))
        check("cold mirror is given the live epoch", info[1] == epoch, f"{info[1]} != {epoch}")
    cursor = info[2] if info else 0

    print("== a resuming mirror is handed only what it missed ==")
    m1.close()
    time.sleep(0.3)
    owner.send(b"echo AFTER_DETACH_MARKER\n")
    time.sleep(0.8)
    m2 = Client(["attach", "--mirror", "--resume", f"{epoch}:{cursor}", "--tail", "50", "r1"])
    m2.pump(1.2)
    info2 = m2.resume_line()
    check("resuming mirror is told 'continued'", info2 and info2[0] == "continued", str(info2))
    check("it received the output it missed", b"AFTER_DETACH_MARKER" in m2.out,
          repr(m2.out[-200:]))
    # The delta must be the missed bytes only - not the session's whole picture.
    check("it did NOT get replayed the earlier output",
          b"BEFORE_ATTACH" not in m2.out, repr(m2.out[:300]))
    if info2:
        check("its cursor advanced past the delta", info2[2] >= cursor, str(info2))

    print("== the delta is byte-exact against a client that never left ==")
    watcher = Client(["attach", "--mirror", "--resume", "0:0", "--tail", "50", "r1"])
    watcher.pump(1.0)
    base = watcher.resume_line()[2]
    gone = Client(["attach", "--mirror", "--resume", f"{epoch}:{base}", "--tail", "50", "r1"])
    gone.pump(0.6)
    gone.close()
    watcher.out = b""
    owner.send(b"for i in 1 2 3 4 5; do echo delta-line-$i; done\n")
    time.sleep(1.2)
    live = watcher.pump(0.8)
    after = Client(["attach", "--mirror", "--resume", f"{epoch}:{base}", "--tail", "50", "r1"])
    after.pump(1.2)
    resumed = after.out
    check("resumed bytes equal what the attached client saw",
          resumed == live, f"{len(resumed)}B vs {len(live)}B")
    watcher.close()
    after.close()
    m2.close()

    print("== a cursor the ring no longer holds falls back to a snapshot ==")
    stale = Client(["attach", "--mirror", "--resume", f"{epoch}:999999999", "r1"])
    stale.pump(1.0)
    info3 = stale.resume_line()
    check("an impossible cursor is refused", info3 and info3[0] == "snapshot", str(info3))
    stale.close()

    print("== a cursor from another daemon is refused ==")
    wrong = Client(["attach", "--mirror", "--resume", f"{epoch ^ 1}:0", "r1"])
    wrong.pump(1.0)
    info4 = wrong.resume_line()
    check("a foreign epoch is refused", info4 and info4[0] == "snapshot", str(info4))
    wrong.close()

    print("== --tail caps what a snapshot carries ==")
    owner.send(b"for i in $(seq 1 2000); do echo \"scroll $i: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"; done\n")
    time.sleep(6)
    full = Client(["attach", "--mirror", "r1"])
    full.pump(2.5)
    capped = Client(["attach", "--mirror", "--resume", "0:0", "--tail", "60", "r1"])
    capped.pump(2.5)
    check("a capped snapshot is much smaller than the full one",
          len(capped.out) * 4 < len(full.out),
          f"capped={len(capped.out)}B full={len(full.out)}B")
    print(f"       full snapshot {len(full.out)}B, tail=60 snapshot {len(capped.out)}B")
    full.close()
    capped.close()

    owner.close()
    subprocess.run(["pkill", "-f", ZMX_DIR], capture_output=True)
    subprocess.run(["rm", "-rf", ZMX_DIR], check=True)

    print()
    if FAILURES:
        print(f"FAILED: {len(FAILURES)} check(s): {FAILURES}")
        return 1
    print("ALL CHECKS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
