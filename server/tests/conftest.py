"""Fixtures for the `tsess-mux` suite.

Everything here exists to run the mux against a fake host. The real `zmx` and
the sessions on this machine are the user's; the suite must be safe to run
while they are working in them.
"""

from __future__ import annotations

import os
import queue
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))
import muxproto  # noqa: E402

SERVER_DIR = Path(__file__).resolve().parent.parent
# Overridable so the suite can be pointed at a candidate implementation without
# installing it, and so the harness itself can be exercised against a stub.
MUX = Path(os.environ.get("TSESS_MUX") or SERVER_DIR / "tsess-mux").resolve()
FAKE_ZMX = Path(__file__).resolve().parent / "fake_zmx.py"

DEFAULT_TIMEOUT_S = 5.0


def pytest_report_header(config):
    state = "present" if MUX.exists() else "NOT WRITTEN YET"
    return f"tsess-mux: {MUX} ({state}); host: fake_zmx only"


@pytest.fixture(scope="session")
def require_mux():
    """Guards only the tests that actually spawn the mux; the wire-format tests
    stand on their own and must stay runnable before it exists."""
    if not MUX.exists():
        pytest.fail(
            f"{MUX} does not exist yet.\n"
            "These tests are the specification for it; they are meant to be red "
            "until it is written.",
            pytrace=False,
        )
    if not os.access(MUX, os.X_OK):
        pytest.fail(f"{MUX} is not executable", pytrace=False)


@pytest.fixture
def zmx_log(tmp_path: Path) -> Path:
    """Where fake_zmx records every `claim`/`release` the mux asked for."""
    path = tmp_path / "zmx-calls.log"
    path.touch()
    return path


def read_calls(zmx_log: Path) -> list[str]:
    return [line for line in zmx_log.read_text().splitlines() if line.strip()]


def pgid_process_count(pgid: int) -> int:
    """Live processes in [pgid]. Used to prove the mux leaves no orphans."""
    count = 0
    for entry in Path("/proc").iterdir():
        if not entry.name.isdigit():
            continue
        try:
            stat = (entry / "stat").read_text()
            # comm can contain spaces and parentheses; fields are after the last ')'
            fields = stat[stat.rindex(")") + 2:].split()
            state, process_pgid = fields[0], int(fields[2])
        except (OSError, ValueError, IndexError):
            continue
        if process_pgid == pgid and state != "Z":
            count += 1
    return count


class MuxClient:
    """Drives one `tsess-mux` process the way the Android app would."""

    def __init__(self, zmx_log: Path, tmp_path: Path,
                 read_chunk: int = 65536, read_pause_s: float = 0.0):
        # A throttled reader is not an artificial handicap: the phone parses VT100
        # on its main thread under a per-frame byte budget, so it consumes far
        # slower than the host produces. Starvation only becomes observable once
        # the mux's stdout can actually back up — with a reader that drains
        # instantly, pty backpressure hides an unfair scheduler completely.
        self._read_chunk = read_chunk
        self._read_pause_s = read_pause_s
        env = {
            **os.environ,
            "HOMEATTACH_ZMX": f"{sys.executable} {FAKE_ZMX}",
            "FAKE_ZMX_LOG": str(zmx_log),
            "ZMX_DIR": str(tmp_path / "zmx-dir"),
            # Deliberately poisoned. A login shell that has ever been inside a session exports
            # this, and zmx reads it as "switch the current session" instead of "attach to the
            # one I named" — so the mux would silently drive the wrong terminal. Every test runs
            # with it set so the stripping cannot quietly regress.
            "ZMX_SESSION": "leaked-outer-session",
        }
        self.proc = subprocess.Popen(
            [str(MUX)],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=env,
            start_new_session=True,
        )
        self.pgid = self.proc.pid
        self._frames: queue.Queue[muxproto.Frame] = queue.Queue()
        self._reader_error: Exception | None = None
        self._stderr = bytearray()
        self._pump = threading.Thread(target=self._read_stdout, daemon=True)
        self._pump.start()
        self._errpump = threading.Thread(target=self._read_stderr, daemon=True)
        self._errpump.start()

    def _read_stdout(self) -> None:
        reader = muxproto.FrameReader()
        try:
            while True:
                # read1, not read: BufferedReader.read(n) blocks until it has n
                # bytes or EOF, and a READY frame is 17 bytes on an open stream,
                # so read() would sit on it forever.
                chunk = self.proc.stdout.read1(self._read_chunk)
                if not chunk:
                    return
                for frame in reader.feed(chunk):
                    self._frames.put(frame)
                if self._read_pause_s:
                    time.sleep(self._read_pause_s)
        except Exception as exc:  # surfaced by the next expect()
            self._reader_error = exc

    def _read_stderr(self) -> None:
        try:
            while True:
                chunk = self.proc.stderr.read1(4096)
                if not chunk:
                    return
                self._stderr.extend(chunk)
        except Exception:
            return

    @property
    def stderr_text(self) -> str:
        return bytes(self._stderr).decode("utf-8", "replace")

    def send(self, frame: bytes) -> None:
        self.proc.stdin.write(frame)
        self.proc.stdin.flush()

    def send_raw(self, data: bytes) -> None:
        """Write bytes straight through, for framing-robustness tests."""
        self.proc.stdin.write(data)
        self.proc.stdin.flush()

    def next_frame(self, timeout: float = DEFAULT_TIMEOUT_S) -> muxproto.Frame:
        if self._reader_error is not None:
            raise AssertionError(f"framing broke: {self._reader_error}")
        try:
            return self._frames.get(timeout=timeout)
        except queue.Empty:
            raise AssertionError(
                f"no frame within {timeout}s; mux stderr: {self.stderr_text!r}"
            ) from None

    def expect(self, match, timeout: float = DEFAULT_TIMEOUT_S) -> muxproto.Frame:
        """The next frame satisfying [match], skipping over the rest."""
        deadline = time.monotonic() + timeout
        skipped: list[muxproto.Frame] = []
        while time.monotonic() < deadline:
            frame = self.next_frame(timeout=max(0.05, deadline - time.monotonic()))
            if match(frame):
                return frame
            skipped.append(frame)
        raise AssertionError(
            f"expected frame never arrived within {timeout}s; "
            f"saw {skipped[:12]}; mux stderr: {self.stderr_text!r}"
        )

    def collect(self, duration: float) -> list[muxproto.Frame]:
        """Every frame arriving over [duration]. For starvation measurements."""
        deadline = time.monotonic() + duration
        out: list[muxproto.Frame] = []
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return out
            try:
                out.append(self._frames.get(timeout=remaining))
            except queue.Empty:
                return out

    def open_session(self, sid: int, name: str) -> muxproto.Frame:
        self.send(muxproto.open_frame(sid, name))
        return self.expect(lambda f: f.type == muxproto.READY and f.sid == sid)

    def close_stdin(self) -> None:
        self.proc.stdin.close()

    def wait(self, timeout: float = DEFAULT_TIMEOUT_S) -> int | None:
        try:
            return self.proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            return None

    def shutdown(self) -> None:
        for stream in (self.proc.stdin, self.proc.stdout, self.proc.stderr):
            try:
                if stream and not stream.closed:
                    stream.close()
            except OSError:
                pass
        if self.proc.poll() is None:
            try:
                os.killpg(self.pgid, signal.SIGKILL)
            except (ProcessLookupError, PermissionError):
                self.proc.kill()
        self.proc.wait(timeout=DEFAULT_TIMEOUT_S)


@pytest.fixture
def mux(require_mux, zmx_log: Path, tmp_path: Path):
    client = MuxClient(zmx_log, tmp_path)
    try:
        yield client
    finally:
        client.shutdown()


@pytest.fixture
def slow_mux(require_mux, zmx_log: Path, tmp_path: Path):
    """A mux read at roughly phone speed — 8KB per 20ms, ~400KB/s — so its
    stdout backs up the way it does over a real cellular link."""
    client = MuxClient(zmx_log, tmp_path, read_chunk=8192, read_pause_s=0.02)
    try:
        yield client
    finally:
        client.shutdown()
