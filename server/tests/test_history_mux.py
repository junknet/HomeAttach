"""History pagination remains independent from the live terminal stream."""

import json
import os
import time

import pytest

from conftest import MuxClient, pgid_process_count, read_calls
from muxproto import ERROR, HISTORY, HISTORY_PAGE, OUTPUT, READY, ENDED
from muxproto import close_frame, encode, history_frame, input_frame, open_frame


ANCHOR = "18446744073709551615"


@pytest.fixture
def history_client(require_mux, zmx_log, tmp_path, host):
    clients = []

    def create(**environment):
        client = MuxClient(zmx_log, tmp_path, extra_env={
            **host.env(), "FAKE_HISTORY_ENABLED": "1", **environment,
        })
        clients.append(client)
        return client

    yield create
    for client in clients:
        client.shutdown()


def page_reply(client, slot=1):
    return json.loads(client.expect(
        lambda frame: frame.type == HISTORY_PAGE and frame.sid == slot).payload)


def attach_history(client, name="echo-history"):
    client.open_session(1, name)
    metadata = page_reply(client)
    assert metadata == {
        "status": "ok", "anchor": ANCHOR, "before": 0, "next": 0,
        "columns": 80, "more": True, "rows": [],
    }


def test_history_metadata_waits_for_complete_resume_report(history_client, tmp_path):
    client = history_client(FAKE_HISTORY_SPLIT_REPORT="1")
    attach_history(client)
    assert "history-pages echo-history" in (tmp_path / "zmx-attach.log").read_text()


def test_history_pages_are_ordered_and_retries_idempotent(history_client):
    client = history_client()
    attach_history(client)
    client.send(history_frame(1, ANCHOR, 0, 3))
    first_page = page_reply(client)
    assert first_page["next"] == 3
    assert first_page["rows"] == [
        {"text": "\x1b[31m历史 497\x1b[0m", "wrapped": False},
        {"text": "", "wrapped": True},
        {"text": "\x1b[31m历史 499\x1b[0m", "wrapped": False},
    ]
    client.send(history_frame(1, ANCHOR, 0, 3))
    assert page_reply(client) == first_page
    client.send(history_frame(1, ANCHOR, 3, 3))
    following = page_reply(client)
    assert following["before"] == 3
    assert following["next"] == 6
    assert "494" in following["rows"][0]["text"]
    client.send(history_frame(1, ANCHOR, 499, 3))
    ending = page_reply(client)
    assert ending["next"] == 500
    assert not ending["more"]


def test_delayed_history_does_not_block_input_and_duplicate_is_coalesced(history_client, zmx_log):
    client = history_client(FAKE_HISTORY_DELAY="0.7")
    attach_history(client)
    client.send(history_frame(1, ANCHOR))
    client.send(history_frame(1, ANCHOR))
    client.send(input_frame(1, b"live-input"))
    client.expect(lambda frame: frame.type == OUTPUT and b"echo:live-input" in frame.payload,
                  timeout=0.5)
    assert page_reply(client)["next"] == 128
    assert len([entry for entry in read_calls(zmx_log)
                if entry.startswith("history-page ")]) == 1


@pytest.mark.parametrize("payload", [
    b"{", b"[]", b"null",
    b'{"anchor":1,"before":0}',
    b'{"anchor":"-1","before":0}',
    b'{"anchor":"18446744073709551616","before":0}',
    b'{"anchor":"1","before":true}',
    b'{"anchor":"1","before":-1}',
    b'{"anchor":"1","before":0,"limit":257}',
])
def test_malformed_requests_fail_without_disrupting_terminal(history_client, payload):
    client = history_client()
    attach_history(client)
    client.send(encode(HISTORY, 1, payload))
    client.expect(lambda frame: frame.type == ERROR and frame.sid == 1)
    client.send(input_frame(1, b"still-live"))
    client.expect(lambda frame: frame.type == OUTPUT and b"echo:still-live" in frame.payload)


def test_unsupported_daemon_explicitly_answers_history(mux):
    mux.open_session(1, "echo-legacy")
    assert page_reply(mux)["status"] == "unsupported"
    mux.send(history_frame(1, ANCHOR, 12))
    response = page_reply(mux)
    assert response["status"] == "unsupported"
    assert response["before"] == response["next"] == 12


@pytest.mark.parametrize("environment", [
    {"FAKE_HISTORY_MALFORMED": "1"},
    {"FAKE_HISTORY_OVERSIZED": "1"},
    {"FAKE_HISTORY_DELAY": "10"},
])
def test_failed_history_completes_and_process_is_cleaned(history_client, environment, zmx_log):
    client = history_client(**environment)
    attach_history(client)
    client.send(history_frame(1, ANCHOR))
    response = json.loads(client.expect(
        lambda frame: frame.type == HISTORY_PAGE, timeout=7).payload)
    assert response["status"] == "expired"
    client.expect(lambda frame: frame.type == ERROR)
    processes = [int(entry.split()[1]) for entry in read_calls(zmx_log)
                 if entry.startswith("history-process ")]
    deadline = time.monotonic() + 1
    while any(os.path.exists(f"/proc/{process}") for process in processes) and time.monotonic() < deadline:
        time.sleep(0.02)
    assert processes and all(not os.path.exists(f"/proc/{process}") for process in processes)


def test_closing_history_slot_cancels_page_before_slot_reuse(history_client, zmx_log):
    client = history_client(FAKE_HISTORY_DELAY="0.7")
    attach_history(client)
    client.send(history_frame(1, ANCHOR, 17))
    client.send(close_frame(1))
    client.open_session(1, "echo-replacement")
    page_reply(client)
    frames = client.collect(0.9)
    assert not any(frame.type == HISTORY_PAGE for frame in frames)
    client.send(input_frame(1, b"replacement"))
    client.expect(lambda frame: frame.type == OUTPUT and b"echo:replacement" in frame.payload)


def test_snapshot_refresh_replaces_mirror_without_ending_slot(history_client):
    client = history_client(FAKE_HISTORY_DELAY="0.5")
    attach_history(client)
    client.send(history_frame(1, ANCHOR, 29))
    client.send(open_frame(1, "echo-history", tail_rows=24, cols=80, rows=24))
    client.expect(lambda frame: frame.type == READY and frame.sid == 1)
    assert page_reply(client)["before"] == 0
    client.send(input_frame(1, b"refreshed"))
    client.expect(lambda frame: frame.type == OUTPUT and b"echo:refreshed" in frame.payload)
    frames = client.collect(0.7)
    assert not any(frame.type in (ENDED, HISTORY_PAGE) for frame in frames)


def test_disconnect_terminates_history_workers(history_client):
    client = history_client(FAKE_HISTORY_DELAY="10")
    attach_history(client)
    client.send(history_frame(1, ANCHOR))
    time.sleep(0.1)
    client.close_stdin()
    assert client.wait() == 0
    assert pgid_process_count(client.pgid) == 0
