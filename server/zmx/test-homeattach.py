#!/usr/bin/env python3
"""Functional tests for the HomeAttach zmx patches (mirror / --bind /
claim / release / stat). Drives real `zmx attach` clients through ptys.

Usage: ./test-homeattach.py [path-to-zmx]
"""
import os
import pty
import signal
import subprocess
import sys
import time

ZMX = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else
                      os.path.join(os.path.dirname(__file__), "zig-out/bin/zmx"))
ZMX_DIR = "/tmp/zmx-homeattach-test"
ENV = {**os.environ, "ZMX_DIR": ZMX_DIR, "SHELL": "/bin/bash"}
ENV.pop("ZMX_SESSION", None)  # never "switch" instead of attach

FAILURES = []


def check(name, cond, detail=""):
    status = "ok" if cond else "FAIL"
    print(f"  [{status}] {name}" + (f"  ({detail})" if detail and not cond else ""))
    if not cond:
        FAILURES.append(name)


class PtyClient:
    """A zmx attach client running on its own pty."""

    def __init__(self, args, cols, rows):
        self.master, slave = pty.openpty()
        os.set_blocking(self.master, False)
        import fcntl, struct, termios
        fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
        self.proc = subprocess.Popen(
            [ZMX] + args, stdin=slave, stdout=slave, stderr=slave,
            env=ENV, start_new_session=True)
        os.close(slave)

    def resize(self, cols, rows):
        import fcntl, struct, termios
        fcntl.ioctl(self.master, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
        # Deliver WINCH to the client process (it owns no controlling tty here)
        self.proc.send_signal(signal.SIGWINCH)

    def send(self, data: bytes):
        os.write(self.master, data)

    def drain(self, seconds=0.4) -> bytes:
        out = b""
        end = time.time() + seconds
        while time.time() < end:
            try:
                out += os.read(self.master, 65536)
            except (BlockingIOError, OSError):
                time.sleep(0.02)
        return out

    def close(self):
        try:
            self.proc.terminate()
        except ProcessLookupError:
            pass
        try:
            os.close(self.master)
        except OSError:
            pass
        self.proc.wait(timeout=5)


def zmx(*args) -> str:
    r = subprocess.run([ZMX] + list(args), env=ENV, capture_output=True,
                       text=True, timeout=10)
    return r.stdout.strip()


def stat(name) -> dict:
    line = zmx("stat", name)
    return dict(kv.split("=", 1) for kv in line.split()) if line else {}


def session_exists(name) -> bool:
    return os.path.exists(os.path.join(ZMX_DIR, name))


def wait_for(cond, seconds=3.0):
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

    print("== owner attach --bind creates session ==")
    owner = PtyClient(["attach", "--bind", "t1"], cols=120, rows=40)
    ok = wait_for(lambda: stat("t1").get("owners") == "1")
    check("session created, owners=1", ok, str(stat("t1")))
    s = stat("t1")
    check("bound=1", s.get("bound") == "1", str(s))
    check("pty size = owner size (120x40)",
          s.get("cols") == "120" and s.get("rows") == "40", str(s))

    print("== mirror attach ==")
    mirror = PtyClient(["attach", "--mirror", "t1"], cols=60, rows=20)
    ok = wait_for(lambda: stat("t1").get("mirrors") == "1")
    check("mirrors=1", ok, str(stat("t1")))
    s = stat("t1")
    check("mirror did not resize pty (still 120x40)",
          s.get("cols") == "120" and s.get("rows") == "40", str(s))

    print("== mirror typing reaches the pty without claiming size ==")
    owner.drain(0.6)
    mirror.drain(0.2)
    mirror.send(b"echo MIRROR_TYPED_$((40+2))\r")
    out = owner.drain(1.2)
    check("owner sees mirror's command output", b"MIRROR_TYPED_42" in out,
          out[-200:].decode(errors="replace"))
    s = stat("t1")
    check("pty size still owner's after mirror typed",
          s.get("cols") == "120" and s.get("rows") == "40", str(s))

    print("== mirror auto-replies are dropped ==")
    # A cursor-position report as a terminal would send it
    mirror.send(b"\x1b[24;80R")
    out = owner.drain(0.6)
    check("CPR from mirror does not echo at the shell", b"24;80R" not in out,
          out[-200:].decode(errors="replace"))

    print("== mirror WINCH does not resize pty ==")
    mirror.resize(61, 21)
    time.sleep(0.5)
    s = stat("t1")
    check("pty unchanged after mirror resize",
          s.get("cols") == "120" and s.get("rows") == "40", str(s))

    print("== zmx claim resizes externally ==")
    zmx("claim", "t1", "100", "30")
    ok = wait_for(lambda: stat("t1").get("cols") == "100")
    s = stat("t1")
    check("pty took claimed size 100x30",
          s.get("cols") == "100" and s.get("rows") == "30", str(s))

    print("== owner typing reclaims its size ==")
    owner.send(b"true\r")
    ok = wait_for(lambda: stat("t1").get("cols") == "120", 3.0)
    check("pty back to 120x40 after owner typed", ok, str(stat("t1")))

    print("== zmx claim + release returns size to owner ==")
    zmx("claim", "t1", "100", "30")
    wait_for(lambda: stat("t1").get("cols") == "100")
    zmx("release", "t1")
    ok = wait_for(lambda: stat("t1").get("cols") == "120", 3.0)
    check("release returned pty to owner size", ok, str(stat("t1")))

    print("== view-bound: owner leaving ends session despite mirror ==")
    owner.close()
    ok = wait_for(lambda: not session_exists("t1"), 5.0)
    check("session gone after owner close", ok)
    mirror.close()

    print("== unbound session survives owner leaving ==")
    owner2 = PtyClient(["attach", "t2"], cols=80, rows=24)
    wait_for(lambda: stat("t2").get("owners") == "1")
    owner2.close()
    time.sleep(1.0)
    check("unbound session still alive", session_exists("t2"))
    zmx("kill", "t2")
    wait_for(lambda: not session_exists("t2"), 5.0)

    print("== view-bound session with command ==")
    owner3 = PtyClient(["attach", "--bind", "t3", "bash", "--norc"], cols=90, rows=25)
    ok = wait_for(lambda: stat("t3").get("owners") == "1")
    check("bind+command session up", ok, str(stat("t3")))
    owner3.send(b"exit\r")
    ok = wait_for(lambda: not session_exists("t3"), 5.0)
    check("session gone after child exit", ok)
    owner3.close()

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
