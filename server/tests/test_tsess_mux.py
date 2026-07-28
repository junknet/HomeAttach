"""Behaviour `tsess-mux` has to have, driven through the real protocol.

The point of the mux is to carry every attached session on one SSH channel. So
the tests that matter are not "does it forward bytes" but the two properties
that a single shared channel puts at risk, both of which are regressions of
problems measured on a real device against the current N-channel design:

  * closing one session must not disturb the others — the N-channel version
    tore a pty channel down while opening an exec channel on the same JSch
    transport, and every other session EOF'd and reconnected;
  * a session flooding output must not starve the rest — SSH gives each
    channel its own flow-control window and merging them throws that away, so
    the mux has to schedule fairly itself or it trades one problem for a worse
    one.
"""

from __future__ import annotations

import time

import muxproto
from conftest import pgid_process_count, read_calls

OUT = muxproto.OUTPUT

# Bounded so no session can own the shared channel for longer than one piece.
MAX_OUTPUT_FRAME = 32 * 1024


def output_of(frames, sid: int) -> bytes:
    return b"".join(f.payload for f in frames if f.type == OUT and f.sid == sid)


def test_open_is_acknowledged_with_ready_naming_the_session(mux):
    frame = mux.open_session(1, "quiet-alpha")
    assert frame.payload.decode() == "quiet-alpha"


def test_session_output_arrives_tagged_with_its_slot(mux):
    mux.open_session(1, "quiet-alpha")
    frame = mux.expect(lambda f: f.type == OUT and f.sid == 1)
    assert b"quiet-alpha ready" in frame.payload


def test_two_sessions_never_cross_streams(mux):
    mux.open_session(1, "quiet-alpha")
    mux.open_session(2, "quiet-beta")
    frames = mux.collect(1.0)
    assert b"alpha" in output_of(frames, 1)
    assert b"beta" in output_of(frames, 2)
    assert b"beta" not in output_of(frames, 1)
    assert b"alpha" not in output_of(frames, 2)


def test_input_reaches_only_its_own_session(mux):
    mux.open_session(1, "echo-alpha")
    mux.open_session(2, "echo-beta")
    mux.send(muxproto.input_frame(1, b"ping\n"))
    mux.expect(lambda f: f.type == OUT and f.sid == 1 and b"echo:ping" in f.payload)
    assert b"ping" not in output_of(mux.collect(0.3), 2)


def test_a_bare_keystroke_gets_through_without_a_newline(mux):
    """The session pty has to be raw. Left in canonical mode it buffers until a
    newline, which means arrow keys, Ctrl-C and every single keypress are simply
    swallowed — the terminal looks hung."""
    mux.open_session(1, "echo-alpha")
    mux.send(muxproto.input_frame(1, b"\x03"))
    mux.expect(lambda f: f.type == OUT and f.sid == 1 and b"echo:\x03" in f.payload)


def test_the_session_pty_does_not_echo_input_back(mux):
    """Also raw: with ECHO on, the pty repeats the keystroke and the session
    sends its own copy, so the user sees every character twice."""
    mux.open_session(1, "echo-alpha")
    mux.send(muxproto.input_frame(1, b"z\n"))
    # Every frame, not expect(): a pty echo arrives *before* the session's own
    # reply, so skipping ahead to `echo:z` would step straight over the bug.
    out = output_of(mux.collect(0.8), 1)
    assert b"echo:z" in out, f"session never answered: {out!r}"
    assert out.count(b"z") == 1, f"pty echoed the keystroke back: {out!r}"


def test_an_inherited_zmx_session_is_stripped_before_attaching(mux, zmx_log):
    """ZMX_SESSION makes zmx switch the session it was launched from instead of attaching to the
    one it was told to. The mux is started over SSH from whatever login environment the host has,
    so it cannot assume the variable is absent — it has to strip it."""
    mux.open_session(1, "quiet-alpha")
    mux.collect(0.5)
    leaked = [c for c in read_calls(zmx_log) if c.startswith("leaked-zmx-session")]
    assert leaked == [], f"mux passed ZMX_SESSION through to zmx: {leaked}"


def test_attaching_never_claims_the_pty(mux, zmx_log):
    # A mirror attach must not take size ownership. The device build regressed
    # exactly here: every attach claimed, so a backgrounded session coming back
    # from a radio gap resized the terminal the user was looking at.
    mux.open_session(1, "quiet-alpha")
    mux.collect(0.4)
    assert read_calls(zmx_log) == []


def test_focus_claims_the_pty_at_the_requested_grid(mux, zmx_log):
    mux.open_session(1, "quiet-alpha")
    mux.send(muxproto.focus_frame(1, cols=100, rows=40))
    deadline = time.monotonic() + 3.0
    while time.monotonic() < deadline and not read_calls(zmx_log):
        time.sleep(0.05)
    assert read_calls(zmx_log) == ["claim quiet-alpha 100 40"]


def test_closing_a_session_releases_its_pty_ownership(mux, zmx_log):
    mux.open_session(1, "quiet-alpha")
    mux.send(muxproto.focus_frame(1, cols=80, rows=24))
    mux.send(muxproto.close_frame(1))
    deadline = time.monotonic() + 3.0
    while time.monotonic() < deadline and "release quiet-alpha" not in read_calls(zmx_log):
        time.sleep(0.05)
    assert "release quiet-alpha" in read_calls(zmx_log)


def test_closing_one_session_leaves_the_others_streaming(mux):
    """Regression: on the N-channel design this cost every other session a
    reconnect, measured at 0.6-1.0s each."""
    mux.open_session(1, "beat-alpha")
    mux.open_session(2, "beat-beta")
    mux.expect(lambda f: f.type == OUT and f.sid == 2 and b"beat" in f.payload)

    mux.send(muxproto.close_frame(1))
    frames = mux.collect(0.8)

    assert b"beat" in output_of(frames, 2), "surviving session stopped producing"
    assert not [f for f in frames if f.type == muxproto.ENDED and f.sid == 2]
    assert not [f for f in frames if f.type == muxproto.ERROR]


def test_closed_session_stops_producing(mux):
    mux.open_session(1, "beat-alpha")
    mux.expect(lambda f: f.type == OUT and f.sid == 1 and b"beat" in f.payload)
    mux.send(muxproto.close_frame(1))
    mux.collect(0.3)  # let the teardown settle
    assert output_of(mux.collect(0.4), 1) == b""


def test_output_frames_stay_small_enough_to_interleave(mux):
    """A necessary condition for fairness, and the one that is cheap to check:
    however much a session has buffered, the mux must hand it out in bounded
    pieces. One giant frame is one session owning the channel for its whole
    duration, with nothing else able to slip in behind it."""
    mux.open_session(1, "flood-alpha")
    frames = [f for f in mux.collect(1.0) if f.type == OUT]
    assert frames, "flood produced nothing"
    biggest = max(len(f.payload) for f in frames)
    assert biggest <= MAX_OUTPUT_FRAME, f"a single frame carried {biggest} bytes"


def test_a_flooding_session_does_not_starve_a_quiet_one(slow_mux):
    """The reason to be careful about collapsing N channels into one: SSH gives
    each channel its own flow-control window, and merging them throws that away.

    Read at phone speed on purpose. With a reader that drains instantly the
    mux's stdout never backs up, pty backpressure absorbs everything, and an
    unfair scheduler passes this test just as easily as a fair one.
    """
    slow_mux.open_session(1, "flood-alpha")
    slow_mux.open_session(2, "beat-beta")

    frames = slow_mux.collect(3.0)
    beats = [f for f in frames if f.type == OUT and f.sid == 2 and b"beat" in f.payload]
    flooded = len(output_of(frames, 1))

    assert flooded > 64 * 1024, f"flood was throttled to a crawl ({flooded} bytes)"
    # ~60 beats are produced in 3s. Requiring a sixth of them tolerates real
    # scheduling slack while still failing outright on starvation.
    assert len(beats) >= 10, f"quiet session starved: only {len(beats)} beats got through"


def test_session_that_exits_is_reported_as_ended(mux):
    mux.open_session(1, "die-alpha")
    frame = mux.expect(lambda f: f.type == muxproto.ENDED and f.sid == 1)
    assert frame.sid == 1


def test_ending_one_session_does_not_end_the_others(mux):
    mux.open_session(1, "die-alpha")
    mux.open_session(2, "beat-beta")
    mux.expect(lambda f: f.type == muxproto.ENDED and f.sid == 1)
    frames = mux.collect(0.5)
    assert b"beat" in output_of(frames, 2)
    assert not [f for f in frames if f.type == muxproto.ENDED and f.sid == 2]


def test_input_for_an_unknown_slot_is_an_error_not_a_crash(mux):
    mux.open_session(1, "quiet-alpha")
    mux.send(muxproto.input_frame(200, b"nobody home"))
    mux.expect(lambda f: f.type == muxproto.ERROR)
    # the live session has to survive a bad frame
    mux.send(muxproto.input_frame(1, b"still here"))
    mux.collect(0.3)
    assert mux.proc.poll() is None


def test_reusing_a_live_slot_is_an_error(mux):
    mux.open_session(1, "quiet-alpha")
    mux.send(muxproto.open_frame(1, "quiet-beta"))
    mux.expect(lambda f: f.type == muxproto.ERROR and f.sid == 1)


def test_a_slot_can_be_reused_after_it_is_closed(mux):
    mux.open_session(1, "quiet-alpha")
    mux.send(muxproto.close_frame(1))
    mux.collect(0.3)
    frame = mux.open_session(1, "quiet-beta")
    assert frame.payload.decode() == "quiet-beta"


def test_frames_split_across_writes_are_reassembled(mux):
    wire = muxproto.open_frame(1, "quiet-alpha")
    for i in range(len(wire)):
        mux.send_raw(wire[i:i + 1])
        time.sleep(0.005)
    assert mux.expect(lambda f: f.type == muxproto.READY and f.sid == 1)


def test_closing_the_channel_leaves_no_orphan_host_processes(mux):
    """A resident process that leaks a `zmx attach` per session would pile them
    up on the host every time the phone's connection drops."""
    mux.open_session(1, "linger-alpha")
    mux.open_session(2, "linger-beta")
    mux.expect(lambda f: f.type == OUT and f.sid == 2)
    assert pgid_process_count(mux.pgid) >= 3  # mux + two attaches

    mux.close_stdin()
    assert mux.wait(timeout=5.0) is not None, "mux did not exit when its channel closed"

    deadline = time.monotonic() + 3.0
    while time.monotonic() < deadline and pgid_process_count(mux.pgid) > 0:
        time.sleep(0.05)
    assert pgid_process_count(mux.pgid) == 0, "mux exited but left zmx children behind"
