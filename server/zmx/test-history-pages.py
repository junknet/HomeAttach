#!/usr/bin/env python3
"""Exercise bounded history pages against an isolated live daemon."""
import importlib.util
import json
import os
import re
import subprocess
import tempfile
from pathlib import Path

specification = importlib.util.spec_from_file_location(
    "resume_fixture", Path(__file__).with_name("test-resume.py"))
fixture = importlib.util.module_from_spec(specification)
specification.loader.exec_module(fixture)


def require(condition, description):
    if not condition:
        raise AssertionError(description)
    print(f"PASS {description}", flush=True)


def main():
    with tempfile.TemporaryDirectory(prefix="zmx-history-pages-") as directory:
        fixture.ENV["ZMX_DIR"] = directory
        owner = fixture.Client(["attach", "--bind", "history", "bash", "--norc"], cols=40, rows=5)
        mirror = None
        continued = None
        try:
            require(fixture.wait_for(lambda: fixture.stat("history").get("history_pages") == "1"),
                    "running daemon advertises history pages")
            owner.send(b"stty -echo; PS1=''; for number in $(seq 0 399); do printf 'ROW%06d\\n' \"$number\"; done\n")
            owner.pump(1.0)
            mirror = fixture.Client(["attach", "--mirror", "--resume", "0:0", "--tail", "4", "--history-pages", "history"])
            mirror.pump(0.5)
            matched = re.search(rb"history=(\d+) more=1 columns=40", mirror.err)
            require(matched is not None, "snapshot carries an anchored history boundary")
            anchor = matched.group(1).decode()

            def fetch_page(before=0, limit=128):
                result = subprocess.run([fixture.ZMX, "history-page", "history", anchor, str(before), str(limit)],
                                        env=fixture.ENV, capture_output=True, timeout=5, check=True)
                require(len(result.stdout) <= 512 * 1024, "page response respects byte limit")
                return json.loads(result.stdout)

            initial = fetch_page(0, 17)
            require(initial["status"] == "ok" and len(initial["rows"]) == 17, "requested row limit is respected")
            owner.send(b"printf 'LIVE_MARKER\\n'\n")
            owner.pump(0.3)
            require(fetch_page(0, 17) == initial, "new output does not move an existing cursor")
            mirror.pump(0.2)
            require(b"LIVE_MARKER" in mirror.out, "live output continues during history fetch")
            resume = mirror.resume_line()
            mirror.close()
            mirror = None
            require(fetch_page(0, 17) == initial, "anchor survives mirror disconnect")
            continued = fixture.Client(["attach", "--mirror", "--resume", f"{resume[1]}:{resume[2]}",
                                        "--tail", "4", "--history-pages", "history"])
            continued.pump(0.3)
            require(b"mode=continued" in continued.err, "paged attachment retains byte resume")
            require(fetch_page(0, 17) == initial, "continued attachment preserves old anchor")
            cursor = 0
            numbers = []
            while True:
                response = fetch_page(cursor, 1000)
                require(len(response["rows"]) <= 128, "server caps oversized row requests")
                page_numbers = [int(matching.group(1)) for entry in response["rows"]
                                if (matching := re.search(r"ROW(\d{6})", entry["text"]))]
                numbers[0:0] = page_numbers
                cursor = response["next"]
                if not response["more"]:
                    break
            require(numbers == list(range(numbers[-1] + 1)), "all older rows are contiguous without duplicates")
            require(numbers[-1] < 399, "initial snapshot tail is excluded from history pages")
            subprocess.run([fixture.ZMX, "claim", "history", "41", "5"], env=fixture.ENV,
                           capture_output=True, timeout=5, check=True)
            require(fetch_page()["status"] == "expired", "terminal resize expires fixed-width cursors")
        finally:
            if continued is not None:
                continued.close()
            if mirror is not None:
                mirror.close()
            owner.close()
            fixture.wait_for(lambda: not fixture.stat("history"), seconds=3)


if __name__ == "__main__":
    main()
