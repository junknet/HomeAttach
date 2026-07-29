#!/usr/bin/env python3
"""A stand-in for `tsess-list`, injected into `tsess-mux` via HOMEATTACH_TSESS_LIST.

Prints whatever $FAKE_LIST_FILE holds, so a test can change the host's session
list between two frames without a real session existing. Exits non-zero when
$FAKE_LIST_FAIL is set, which is how the "the host cannot list sessions" path
gets exercised — an empty list and a broken script look identical on the wire
unless the mux says which one it saw.
"""

from __future__ import annotations

import os
import sys


def main() -> int:
    if os.environ.get("FAKE_LIST_FAIL"):
        print("fake_list: cannot list sessions", file=sys.stderr)
        return 3
    path = os.environ.get("FAKE_LIST_FILE")
    if path and os.path.exists(path):
        with open(path, "r", encoding="utf-8") as handle:
            sys.stdout.write(handle.read())
    return 0


if __name__ == "__main__":
    sys.exit(main())
