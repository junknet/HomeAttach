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

NAMES = {
    OPEN: "OPEN", CLOSE: "CLOSE", INPUT: "INPUT", FOCUS: "FOCUS",
    READY: "READY", OUTPUT: "OUTPUT", ENDED: "ENDED", ERROR: "ERROR",
}

CONNECTION_SLOT = 0

HEADER = struct.Struct(">BBI")
HEADER_LEN = HEADER.size

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


def open_frame(sid: int, session_name: str) -> bytes:
    return encode(OPEN, sid, session_name.encode("utf-8"))


def close_frame(sid: int) -> bytes:
    return encode(CLOSE, sid)


def input_frame(sid: int, data: bytes) -> bytes:
    return encode(INPUT, sid, data)


def focus_frame(sid: int, cols: int, rows: int) -> bytes:
    return encode(FOCUS, sid, struct.pack(">HH", cols, rows))


def decode_focus(payload: bytes) -> tuple[int, int]:
    return struct.unpack(">HH", payload)


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
