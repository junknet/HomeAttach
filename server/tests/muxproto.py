"""Wire format for the `tsess-mux` channel.

This module is the executable specification. The tests speak the protocol only
through here, so whatever this encodes is exactly what a client — the Android
app — has to encode. Keep it the single definition; do not inline frame layout
into a test.

One SSH exec channel carries every attached session. Framing therefore has to
be ours, and it has to be self-delimiting over a byte stream that may split
anywhere:

    +--------+--------+------------------+--------------------+
    | type:1 | sid:1  | length:4 (big-e) | payload: <length>  |
    +--------+--------+------------------+--------------------+

`sid` is the client-assigned session slot, 1..255. Slot 0 is reserved for
frames that belong to the connection rather than to a session, so an ERROR with
no session context has somewhere to go.

OPEN carries what the client already holds of that session - the daemon epoch
its bytes came from and how many of them it has - plus the grid it will show it
at, if this terminal is the one on screen. READY answers with what the host did
about it. `continued` means the OUTPUT frames that follow pick up
exactly where the client left off; `snapshot` means they replace its screen. The
client's cursor is the offset in READY plus every OUTPUT byte it then receives
beyond the first `replay` of them, which the offset already accounts for. Which
is why an OUTPUT frame may carry nothing but session output.

The client assigns slots because it already keeps the pool that decides which
sessions are live; making the server hand them out would add a request/response
round trip to every open for no gain.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass
from typing import Iterator

# client -> mux
OPEN = 0x01
CLOSE = 0x02
INPUT = 0x03
FOCUS = 0x04

# mux -> client
READY = 0x81
OUTPUT = 0x82
ENDED = 0x83
ERROR = 0x84

# mux -> client, on the connection slot only: the host's own state, which
# belongs to the connection rather than to any one session.
SESSIONS = 0x85
ACTIVITY = 0x86

NAMES = {
    OPEN: "OPEN", CLOSE: "CLOSE", INPUT: "INPUT", FOCUS: "FOCUS",
    READY: "READY", OUTPUT: "OUTPUT", ENDED: "ENDED", ERROR: "ERROR",
    SESSIONS: "SESSIONS", ACTIVITY: "ACTIVITY",
}

CONNECTION_SLOT = 0

HEADER = struct.Struct(">BBI")
HEADER_LEN = HEADER.size

# `| epoch:8 | offset:8 | tail_rows:4 | cols:2 | rows:2 |` ahead of the name in OPEN.
OPEN_HEADER = struct.Struct(">QQIHH")
# `| mode:1 | epoch:8 | offset:8 | replay:8 |` ahead of the name in READY.
READY_HEADER = struct.Struct(">BQQQ")

RESUME_SNAPSHOT = 0
RESUME_CONTINUED = 1

# A payload cap is part of the protocol, not a local guard: without it a
# corrupted length header makes the reader allocate whatever it was handed and
# then block forever waiting for bytes that will never come.
MAX_PAYLOAD = 1 << 20


@dataclass(frozen=True)
class Frame:
    type: int
    sid: int
    payload: bytes

    def __repr__(self) -> str:  # keeps assertion output readable
        kind = NAMES.get(self.type, hex(self.type))
        return f"Frame({kind}, sid={self.sid}, payload={self.payload[:48]!r})"


def encode(type_: int, sid: int, payload: bytes = b"") -> bytes:
    if not 0 <= sid <= 255:
        raise ValueError(f"slot out of range: {sid}")
    if len(payload) > MAX_PAYLOAD:
        raise ValueError(f"payload too large: {len(payload)}")
    return HEADER.pack(type_, sid, len(payload)) + payload


def open_frame(sid: int, session_name: str, epoch: int = 0, offset: int = 0,
               tail_rows: int = 0, cols: int = 0, rows: int = 0) -> bytes:
    """Open a slot, declaring what the client already holds of that session.

    A non-zero grid means "this terminal is on screen and its size is mine" - the
    host takes it before it draws anything, so the picture it sends is made for
    the geometry it will be shown at.
    """
    return encode(
        OPEN,
        sid,
        OPEN_HEADER.pack(epoch, offset, tail_rows, cols, rows)
        + session_name.encode("utf-8"),
    )


@dataclass(frozen=True)
class Ready:
    mode: int
    epoch: int
    offset: int
    #: Bytes of this reply that are replay, and so already counted by [offset].
    replay_bytes: int
    name: str

    @property
    def continued(self) -> bool:
        return self.mode == RESUME_CONTINUED


def decode_ready(payload: bytes) -> Ready:
    mode, epoch, offset, replay = READY_HEADER.unpack_from(payload, 0)
    return Ready(mode, epoch, offset, replay, payload[READY_HEADER.size:].decode("utf-8"))


def close_frame(sid: int) -> bytes:
    return encode(CLOSE, sid)


def input_frame(sid: int, data: bytes) -> bytes:
    return encode(INPUT, sid, data)


def focus_frame(sid: int, cols: int, rows: int) -> bytes:
    return encode(FOCUS, sid, struct.pack(">HH", cols, rows))


def decode_focus(payload: bytes) -> tuple[int, int]:
    return struct.unpack(">HH", payload)


def decode_sessions(payload: bytes) -> list[list[str]]:
    """The session list as rows of `tsess-list` TSV fields."""
    return [
        line.split("\t")
        for line in payload.decode("utf-8", "replace").splitlines()
        if line.strip()
    ]


def decode_activity(payload: bytes) -> list[str]:
    """Names of the sessions that produced output since the previous frame."""
    return [
        line for line in payload.decode("utf-8", "replace").splitlines() if line.strip()
    ]


class FrameReader:
    """Reassembles frames from a byte stream that splits at arbitrary points."""

    def __init__(self) -> None:
        self._buffer = bytearray()

    def feed(self, chunk: bytes) -> Iterator[Frame]:
        self._buffer.extend(chunk)
        while True:
            if len(self._buffer) < HEADER_LEN:
                return
            type_, sid, length = HEADER.unpack_from(self._buffer, 0)
            if length > MAX_PAYLOAD:
                raise ValueError(f"framing lost: length {length} over cap")
            end = HEADER_LEN + length
            if len(self._buffer) < end:
                return
            payload = bytes(self._buffer[HEADER_LEN:end])
            del self._buffer[:end]
            yield Frame(type_, sid, payload)

    @property
    def pending_bytes(self) -> int:
        return len(self._buffer)
