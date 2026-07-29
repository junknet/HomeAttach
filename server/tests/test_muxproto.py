"""The wire format itself. No mux process involved.

These run green from the start on purpose: if the framing spec is wrong, every
behavioural failure downstream is noise, so this is the part that has to be
trustworthy first.
"""

from __future__ import annotations

import pytest

import muxproto


def read_all(chunks: list[bytes]) -> list[muxproto.Frame]:
    reader = muxproto.FrameReader()
    out: list[muxproto.Frame] = []
    for chunk in chunks:
        out.extend(reader.feed(chunk))
    return out


def test_round_trips_a_frame():
    wire = muxproto.encode(muxproto.OUTPUT, 7, b"hello")
    (frame,) = read_all([wire])
    assert frame == muxproto.Frame(muxproto.OUTPUT, 7, b"hello")


def test_reassembles_a_frame_split_byte_by_byte():
    # An SSH stream splits wherever it likes; one byte at a time is the worst
    # case and the one a hand-rolled reader gets wrong.
    wire = muxproto.encode(muxproto.OUTPUT, 3, b"abcdefghij")
    frames = read_all([wire[i:i + 1] for i in range(len(wire))])
    assert frames == [muxproto.Frame(muxproto.OUTPUT, 3, b"abcdefghij")]


def test_splits_a_chunk_holding_several_frames():
    wire = (
        muxproto.encode(muxproto.OUTPUT, 1, b"one")
        + muxproto.encode(muxproto.OUTPUT, 2, b"two")
        + muxproto.encode(muxproto.ENDED, 1, b"bye")
    )
    assert read_all([wire]) == [
        muxproto.Frame(muxproto.OUTPUT, 1, b"one"),
        muxproto.Frame(muxproto.OUTPUT, 2, b"two"),
        muxproto.Frame(muxproto.ENDED, 1, b"bye"),
    ]


def test_holds_an_incomplete_frame_instead_of_yielding_it():
    wire = muxproto.encode(muxproto.OUTPUT, 1, b"payload")
    reader = muxproto.FrameReader()
    assert list(reader.feed(wire[:-1])) == []
    assert reader.pending_bytes == len(wire) - 1
    assert list(reader.feed(wire[-1:])) == [muxproto.Frame(muxproto.OUTPUT, 1, b"payload")]


def test_carries_an_empty_payload():
    (frame,) = read_all([muxproto.close_frame(4)])
    assert frame == muxproto.Frame(muxproto.CLOSE, 4, b"")


def test_carries_arbitrary_binary_including_nul():
    payload = bytes(range(256)) * 4
    (frame,) = read_all([muxproto.encode(muxproto.INPUT, 9, payload)])
    assert frame.payload == payload


def test_rejects_a_length_past_the_cap_rather_than_allocating_it():
    # A corrupt header must fail loudly here; the alternative is a reader that
    # blocks forever waiting for bytes that are never coming.
    poisoned = muxproto.HEADER.pack(muxproto.OUTPUT, 1, muxproto.MAX_PAYLOAD + 1)
    with pytest.raises(ValueError, match="framing lost"):
        read_all([poisoned])


def test_refuses_to_encode_an_out_of_range_slot():
    with pytest.raises(ValueError, match="slot out of range"):
        muxproto.encode(muxproto.OUTPUT, 256, b"")


def test_focus_payload_round_trips_the_grid():
    frame_bytes = muxproto.focus_frame(5, cols=120, rows=48)
    (frame,) = read_all([frame_bytes])
    assert muxproto.decode_focus(frame.payload) == (120, 48)


def test_open_carries_a_utf8_session_name():
    (frame,) = read_all([muxproto.open_frame(2, "会话-å")])
    assert frame.payload[muxproto.OPEN_HEADER.size:].decode("utf-8") == "会话-å"


def test_open_declares_the_clients_cursor_ahead_of_the_name():
    (frame,) = read_all([muxproto.open_frame(3, "alpha", epoch=9, offset=4096, tail_rows=200)])
    epoch, offset, tail = muxproto.OPEN_HEADER.unpack_from(frame.payload, 0)
    assert (epoch, offset, tail) == (9, 4096, 200)
    assert frame.payload[muxproto.OPEN_HEADER.size:] == b"alpha"


def test_ready_answers_with_the_mode_and_the_cursor():
    wire = muxproto.encode(
        muxproto.READY, 2,
        muxproto.READY_HEADER.pack(muxproto.RESUME_CONTINUED, 9, 4096, 512) + b"alpha",
    )
    (frame,) = read_all([wire])
    ready = muxproto.decode_ready(frame.payload)
    assert (ready.continued, ready.epoch, ready.offset, ready.name) == (True, 9, 4096, "alpha")
    # The bytes about to arrive that [offset] already counts. Counting them again
    # is what puts a client's cursor past the stream, permanently unresumable.
    assert ready.replay_bytes == 512
