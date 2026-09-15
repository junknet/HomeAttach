#!/usr/bin/env python3
"""Exercise transactional daemon replacement using isolated terminal sessions.

Usage: python3 test-hot-upgrade.py /absolute/path/to/zmx [replacement-binary]
"""
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import time

specification = importlib.util.spec_from_file_location(
    "resume_fixture", Path(__file__).with_name("test-resume.py"))
fixture = importlib.util.module_from_spec(specification)
specification.loader.exec_module(fixture)

PRODUCER = r'''
import os
import sys
import threading
import time
import tty

tty.setraw(sys.stdin.fileno())
for number in range(180):
    os.write(1, f"HISTORY{number:06d}\r\n".encode())
os.write(1, b"PRODUCER_READY\r\n")

def produce():
    for number in range(1200):
        os.write(1, f"NUMBER{number:06d}\r\n".encode())
        time.sleep(0.003)
    os.write(1, b"NUMBER_FINISHED\r\n")

for command in sys.stdin.buffer:
    command = command.strip()
    if command == b"START":
        threading.Thread(target=produce).start()
    elif command == b"ANSI_FIRST":
        os.write(1, b"\x1b[38;2;211;")
    elif command == b"ANSI_LAST":
        os.write(1, b"31;17mSTYLE_MARKER\x1b[0m\r\n")
    elif command == b"UTF8_FIRST":
        os.write(1, b"\xe4\xb8")
    elif command == b"UTF8_LAST":
        os.write(1, b"\xad\xe6\x96\x87\r\n")
    elif command == b"WRAP":
        os.write(1, b"W" * 137 + b"\r\n\r\nBLANK_FINISHED\r\n")
    elif command == b"BURST":
        os.write(1, b"BURST_BEGIN\r\n")
        for number in range(1024):
            os.write(1, f"BURST{number:06d}:".encode() + b"B" * 1008 + b"\r\n")
        os.write(1, b"BURST_FINISHED\r\n")
    elif command == b"JOURNAL":
        for number in range(256):
            os.write(1, b"\x1b[0m" * 16384)
        os.write(1, b"JOURNAL_FINISHED\r\n")
    elif command == b"STATUS":
        os.write(1, b"INPUT_STILL_WORKS\r\n")
'''


def require(condition, description):
    if not condition:
        raise AssertionError(description)
    print(f"PASS {description}", flush=True)


def process_identity(identifier):
    try:
        fields = Path(f"/proc/{identifier}/stat").read_text().rsplit(")", 1)[1].split()
        return fields[0], fields[19]
    except FileNotFoundError:
        return None


def command(arguments, timeout=15):
    return subprocess.run([fixture.ZMX, *arguments], env=fixture.ENV,
                          capture_output=True, timeout=timeout)


def drain_client(client):
    for descriptor, attribute in ((client.master, "out"), (client.err_r, "err")):
        chunks = []
        while True:
            try:
                payload = os.read(descriptor, 65536)
                if not payload:
                    break
                chunks.append(payload)
            except (BlockingIOError, OSError):
                break
        if chunks:
            setattr(client, attribute, getattr(client, attribute) + b"".join(chunks))


def pump_until(clients, predicate, timeout=8):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for client in clients:
            drain_client(client)
        if predicate():
            return True
        time.sleep(0.005)
    return predicate()



class RawMirror:
    """Direct protocol connection permits controlled socket backpressure."""

    def __init__(self, session, initialize=True):
        self.connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.connection.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
        self.connection.connect(str(Path(fixture.ENV["ZMX_DIR"], session)))
        self.connection.setblocking(False)
        self.pending = bytearray()
        self.output = bytearray()
        self.messages = []
        if initialize:
            current = fixture.stat(session)
            payload = struct.pack("<HHHHQQII", 7, 43, 0, 0, int(current["epoch"]),
                                  int(current["stream_end"]), 4, 0)
            self.connection.sendall(self.frame(18, payload))
            require(fixture.wait_for(lambda: self.receive() and any(tag == 19 for tag, _ in self.messages)),
                    "raw mirror receives resume metadata")

    @staticmethod
    def frame(message_type, payload):
        return struct.pack("<BI3x", message_type, len(payload)) + payload

    def receive(self):
        while True:
            try:
                payload = self.connection.recv(65536)
                if not payload:
                    raise AssertionError("raw protocol connection closed unexpectedly")
                self.pending.extend(payload)
            except BlockingIOError:
                break
        while len(self.pending) >= 8:
            message_type, length = struct.unpack("<BI3x", self.pending[:8])
            if len(self.pending) < 8 + length:
                break
            payload = bytes(self.pending[8:8 + length])
            del self.pending[:8 + length]
            if message_type == 1:
                self.output.extend(payload)
            else:
                self.messages.append((message_type, payload))
        return True

    def close(self):
        self.connection.close()


def verify_buffered_protocol(candidate, clients):
    slow_mirror = RawMirror("upgrade")
    try:
        for client in clients:
            client.out = b""
        clients[0].send(b"BURST\n")
        require(pump_until(clients, lambda: b"BURST_FINISHED" in clients[1].out),
                "one mebibyte output completes while raw mirror does not read")
        replace_daemon("upgrade", candidate, clients)
        require(pump_until(clients, lambda: slow_mirror.receive() and b"BURST_FINISHED" in slow_mirror.output),
                "slow mirror drains inherited pending output after replacement")
        require(bytes(slow_mirror.output) == clients[1].out,
                "pending socket output survives replacement without byte loss or duplication")
        clients[0].out = b""
        pending_input = slow_mirror.frame(0, b"STATUS\n")
        slow_mirror.connection.sendall(pending_input[:11])
        clients[0].pump(0.15)
        replace_daemon("upgrade", candidate, clients)
        slow_mirror.connection.sendall(pending_input[11:])
        require(pump_until(clients, lambda: b"INPUT_STILL_WORKS" in clients[0].out),
                "partial incoming protocol frame completes across replacement")
    finally:
        slow_mirror.close()
    control = RawMirror("upgrade", initialize=False)
    try:
        previous = fixture.stat("upgrade")
        clients[0].out = b""
        control.connection.sendall(control.frame(23, os.fsencode(candidate)) +
                                   control.frame(1, b"BUFFERED_OUTPUT\r\n"))
        require(pump_until(clients, lambda: control.receive() and b"BUFFERED_OUTPUT" in clients[0].out),
                "complete messages buffered behind upgrade execute after restoration")
        require((23, b"ok\n") in control.messages, "buffered upgrade receives successful acknowledgement")
        require(fixture.stat("upgrade")["daemon_pid"] != previous["daemon_pid"],
                "buffered messages execute in replacement daemon")
    finally:
        control.close()
    control = RawMirror("upgrade", initialize=False)
    try:
        previous = fixture.stat("upgrade")
        clients[0].out = b""
        control.connection.sendall(control.frame(23, os.fsencode(candidate)) +
                                   control.frame(1, b"IPC_AFTER_SHUTDOWN\r\n"))
        control.connection.shutdown(socket.SHUT_WR)
        require(pump_until(clients, lambda: b"IPC_AFTER_SHUTDOWN" in clients[0].out),
                "buffered complete frame precedes peer shutdown after replacement")
        require(fixture.stat("upgrade")["daemon_pid"] != previous["daemon_pid"],
                "half closed control connection completes replacement")
    finally:
        control.close()


def verify_candidate_cancellation(candidate, clients, directory):
    clients[0].send(b"JOURNAL\n")
    deadline = time.monotonic() + 20
    completed = False
    while time.monotonic() < deadline and not completed:
        for client in clients:
            drain_client(client)
        completed = b"JOURNAL_FINISHED" in clients[1].out
        for client in clients:
            client.out = client.out[-4096:]
        time.sleep(0.002)
    require(completed, "sixteen mebibyte journal fixture completes")
    previous = fixture.stat("upgrade")
    launcher = Path(directory, "cancel-candidate")
    launcher.write_text("#!/bin/sh\nprintf '%s\\n' \"$$\" > \"$ZMX_DIR/cancel.identifier\"\nsleep 0.5\nexec " +
                        shlex.quote(candidate) + " \"$@\"\n")
    launcher.chmod(0o700)
    upgrade = subprocess.Popen([fixture.ZMX, "upgrade", "upgrade", str(launcher)], env=fixture.ENV,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    signaled = False
    try:
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline and upgrade.poll() is None:
            try:
                identifier = Path(directory, "cancel.identifier").read_text().strip()
                executable = Path(f"/proc/{identifier}/exe").resolve(strict=True)
                counters = dict(line.split(":", 1) for line in Path(f"/proc/{identifier}/io").read_text().splitlines())
                status = dict(line.split(":", 1) for line in Path(f"/proc/{identifier}/status").read_text().splitlines())
                if (executable == Path(candidate).resolve() and int(counters["rchar"]) > 12 * 1024 * 1024 and
                        int(status["SigCgt"].strip(), 16) & (1 << (signal.SIGTERM - 1))):
                    os.kill(int(identifier), signal.SIGTERM)
                    signaled = True
                    break
            except FileNotFoundError:
                pass
            time.sleep(0.001)
        require(signaled, "termination targets actual candidate during journal replay with handler installed")
        require(pump_until(clients, lambda: upgrade.poll() is not None, timeout=12),
                "cancelled candidate transaction completes")
        output, diagnostics = upgrade.communicate(timeout=1)
        require(upgrade.returncode != 0, "candidate termination cancels replacement: " + diagnostics.decode(errors="replace"))
        current = fixture.stat("upgrade")
        require(current["daemon_pid"] == previous["daemon_pid"] and current["pid"] == previous["pid"],
                "candidate cancellation preserves original daemon and shell")
        clients[0].out = b""
        clients[0].send(b"STATUS\n")
        require(pump_until(clients, lambda: b"INPUT_STILL_WORKS" in clients[0].out),
                "candidate cancellation leaves original terminal input functional")
    finally:
        if upgrade.poll() is None:
            upgrade.kill()
            upgrade.wait(timeout=2)


def replace_daemon(session, candidate, clients, succeeds=True):
    previous = fixture.stat(session)
    started = time.monotonic()
    upgrade = subprocess.Popen([fixture.ZMX, "upgrade", session, candidate],
                               env=fixture.ENV, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        require(pump_until(clients, lambda: upgrade.poll() is not None, timeout=15),
                "upgrade transaction completes within deadline")
        output, diagnostics = upgrade.communicate(timeout=1)
        current = fixture.stat(session)
        require((upgrade.returncode == 0) == succeeds,
                f"upgrade returns expected success={succeeds}: {diagnostics.decode(errors='replace')}")
        require(current.get("epoch") == previous["epoch"], "upgrade preserves stream epoch")
        require(current.get("pid") == previous["pid"], "upgrade preserves terminal process identifier")
        require((current.get("daemon_pid") != previous["daemon_pid"]) == succeeds,
                "successful replacement changes daemon while failure preserves original")
        require(all(client.proc.poll() is None for client in clients),
                "existing owner and mirror remain connected")
        print(f"INFO replacement_seconds={time.monotonic() - started:.3f}", flush=True)
        return current
    finally:
        if upgrade.poll() is None:
            upgrade.kill()
            upgrade.wait(timeout=2)


def snapshot(session):
    client = fixture.Client(["attach", "--mirror", "--resume", "0:0", "--tail", "1000", session],
                            cols=40, rows=5)
    try:
        require(pump_until([client], lambda: client.resume_line() is not None),
                "snapshot metadata arrives")
        client.pump(0.15)
        return client.out
    finally:
        client.close()


def main():
    candidate = os.path.abspath(sys.argv[2]) if len(sys.argv) > 2 else fixture.ZMX
    with tempfile.TemporaryDirectory(prefix="zmx-hot-upgrade-") as directory:
        fixture.ENV["ZMX_DIR"] = directory
        fixture.ENV["XDG_CACHE_HOME"] = str(Path(directory, "cache"))
        producer_path = Path(directory, "producer.py")
        producer_path.write_text(PRODUCER)
        sleeper_path = Path(directory, "sleeper.identifier")
        owner = fixture.Client(["attach", "--bind", "upgrade", "bash", "--norc"], cols=40, rows=5)
        clients = [owner]
        try:
            require(fixture.wait_for(lambda: fixture.stat("upgrade").get("hot_upgrade") == "1"),
                    "daemon advertises transactional upgrades")
            initial = fixture.stat("upgrade")
            shell_identity = process_identity(initial["pid"])
            owner.send(f"stty -echo; PS1=''; sleep 300 & echo $! > {sleeper_path}; python3 -u {producer_path}\n".encode())
            require(pump_until(clients, lambda: b"PRODUCER_READY" in owner.out), "controlled producer starts")
            sleeper_identifier = sleeper_path.read_text().strip()
            sleeper_identity = process_identity(sleeper_identifier)
            mirror = fixture.Client(["attach", "--mirror", "--resume", "0:0", "--tail", "4",
                                     "--history-pages", "upgrade"], cols=40, rows=5)
            clients.append(mirror)
            require(pump_until(clients, lambda: mirror.resume_line() is not None), "existing mirror attaches")
            mirror.pump(0.15)
            matched = re.search(rb"history=(\d+) more=1 columns=40", mirror.err)
            require(matched is not None, "initial history cursor has older rows")
            previous_anchor = matched.group(1).decode()
            original_snapshot = snapshot("upgrade")
            replace_daemon("upgrade", candidate, clients)
            require(snapshot("upgrade") == original_snapshot, "stationary snapshot survives replacement byte exactly")
            stale_page = command(["history-page", "upgrade", previous_anchor, "0", "128"])
            require(stale_page.returncode == 0 and json.loads(stale_page.stdout)["status"] in ("ok", "expired"),
                    "previous history cursor is valid or explicitly expired")

            # Snapshot comparison brackets parser states split across replacement.
            for first, last, expected in ((b"ANSI_FIRST", b"ANSI_LAST", b"STYLE_MARKER"),
                                           (b"UTF8_FIRST", b"UTF8_LAST", "中文".encode())):
                owner.send(first + b"\n")
                owner.pump(0.15)
                replace_daemon("upgrade", candidate, clients)
                owner.send(last + b"\n")
                require(pump_until(clients, lambda: expected in owner.out), "split parser input completes after replacement")
                completed_snapshot = snapshot("upgrade")
                require(expected in completed_snapshot, "reconstructed terminal retains completed parser output")
                replace_daemon("upgrade", candidate, clients)
                require(snapshot("upgrade") == completed_snapshot, "styled terminal state remains stable across another replacement")
            owner.send(b"WRAP\n")
            require(pump_until(clients, lambda: b"BLANK_FINISHED" in owner.out), "wrapped output fixture completes")
            wrapped_snapshot = snapshot("upgrade")
            replace_daemon("upgrade", candidate, clients)
            require(snapshot("upgrade") == wrapped_snapshot, "wrapped and blank physical rows survive replacement")

            resized = command(["claim", "upgrade", "43", "7"])
            require(resized.returncode == 0, "terminal resize succeeds before replacement")
            resized_snapshot = snapshot("upgrade")
            replace_daemon("upgrade", candidate, clients)
            require(snapshot("upgrade") == resized_snapshot, "journal restores terminal geometry and resize effects")

            for client in clients:
                client.pump(0.1)
                client.out = b""
            starting_cursor = fixture.stat("upgrade")
            owner.send(b"START\n")
            require(pump_until(clients, lambda: b"NUMBER000010" in mirror.out), "continuous output starts before replacement")
            replace_daemon("upgrade", candidate, clients)
            require(pump_until(clients, lambda: b"NUMBER_FINISHED" in mirror.out), "continuous producer completes")
            owner.pump(0.15)
            mirror.pump(0.15)
            numbered = re.findall(rb"NUMBER(\d{6})\r?\n", mirror.out)
            require(numbered == [f"{number:06d}".encode() for number in range(1200)],
                    "continuous output crosses replacement without missing or duplicate numbers")
            require(owner.out == mirror.out, "owner and mirror receive identical uninterrupted bytes")
            continued = fixture.Client(["attach", "--mirror", "--resume",
                                        f"{starting_cursor['epoch']}:{starting_cursor['stream_end']}", "upgrade"],
                                       cols=40, rows=5)
            try:
                require(pump_until([continued], lambda: b"NUMBER_FINISHED" in continued.out), "resuming mirror receives missed output")
                continued.pump(0.15)
                require(continued.resume_line()[0] == "continued", "replacement preserves resume ring")
                require(continued.out == mirror.out, "resumed bytes exactly equal continuously attached stream")
            finally:
                continued.close()

            fresh = fixture.Client(["attach", "--mirror", "--resume", "0:0", "--tail", "4",
                                    "--history-pages", "upgrade"], cols=40, rows=5)
            try:
                require(pump_until([fresh], lambda: fresh.resume_line() is not None), "replacement creates new history anchors")
                matched = re.search(rb"history=(\d+) more=1", fresh.err)
                require(matched is not None, "replacement retains scrollback")
                anchor = matched.group(1).decode()
                before = 0
                history_numbers = []
                while True:
                    response = command(["history-page", "upgrade", anchor, str(before), "128"])
                    require(response.returncode == 0, "history request succeeds after replacement")
                    page = json.loads(response.stdout)
                    require(page["status"] == "ok", "replacement history cursor remains valid")
                    history_numbers[0:0] = [int(matching.group(1)) for entry in page["rows"]
                                           if (matching := re.search(r"HISTORY(\d{6})", entry["text"]))]
                    if not page["more"]:
                        break
                    require(page["next"] > before, "history cursor makes forward progress")
                    before = page["next"]
                require(history_numbers == list(range(180)), "replacement retains every original historical row")
            finally:
                fresh.close()

            verify_buffered_protocol(candidate, clients)
            verify_candidate_cancellation(candidate, clients, directory)

            incompatible_candidate = Path(directory, "incompatible-candidate")
            incompatible_candidate.write_text(
                "#!/usr/bin/env python3\nimport json, os, sys\n"
                "descriptor = int(sys.argv[2])\n"
                "checkpoint = json.loads(os.pread(descriptor, os.fstat(descriptor).st_size, 0))\n"
                "checkpoint['terminal_version'] = []\n"
                "encoded = json.dumps(checkpoint).encode()\n"
                "os.ftruncate(descriptor, len(encoded))\nos.pwrite(descriptor, encoded, 0)\n"
                f"os.execv({candidate!r}, [{candidate!r}, *sys.argv[1:]])\n")
            incompatible_candidate.chmod(0o700)
            replace_daemon("upgrade", str(incompatible_candidate), clients, succeeds=False)
            owner.out = b""
            owner.send(b"STATUS\n")
            require(pump_until(clients, lambda: b"INPUT_STILL_WORKS" in owner.out),
                    "incompatible terminal checkpoint leaves original session usable")

            timeout_candidate = Path(directory, "timeout-candidate")
            timeout_candidate.write_text("#!/bin/sh\nprintf '%s\\n' \"$$\" > \"$ZMX_DIR/candidate.identifier\"\nexec sleep 30\n")
            timeout_candidate.chmod(0o700)
            for rejected in (str(Path(directory, "missing-binary")), "/bin/false", str(timeout_candidate)):
                replace_daemon("upgrade", rejected, clients, succeeds=False)
                owner.out = b""
                owner.send(b"STATUS\n")
                require(pump_until(clients, lambda: b"INPUT_STILL_WORKS" in owner.out), "failed replacement leaves input usable")
            candidate_identifier = Path(directory, "candidate.identifier").read_text().strip()
            require(fixture.wait_for(lambda: process_identity(candidate_identifier) is None or
                                     process_identity(candidate_identifier)[0] == "Z"),
                    "timed out candidate releases inherited resources")
            require(process_identity(initial["pid"])[1] == shell_identity[1], "original shell retains unchanged start time")
            require(process_identity(sleeper_identifier)[1] == sleeper_identity[1], "background child retains unchanged start time")
            owner.close()
            clients.remove(owner)
            require(fixture.wait_for(lambda: not fixture.stat("upgrade")), "closing final owner still terminates bound session")
            require(fixture.wait_for(lambda: process_identity(sleeper_identifier) is None or
                                     process_identity(sleeper_identifier)[0] == "Z"), "bound teardown terminates preserved descendants")
        finally:
            for client in reversed(clients):
                client.close()
            fixture.wait_for(lambda: not fixture.stat("upgrade"), seconds=3)
    print("ALL HOT UPGRADE CHECKS PASSED", flush=True)


if __name__ == "__main__":
    main()
