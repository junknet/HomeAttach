# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Two halves that ship together and must stay in sync:

- `app/` — Android client (Kotlin + Compose, package `com.homeattach.app`), plus the vendored
  Termux terminal engine under `app/src/main/java/com/termux/`.
- `server/` — the PC side: `tsess-*` scripts (bash, plus `tsess-mux` in Python) and a vendored,
  patched fork of **zmx** (Zig) under `server/zmx/`.

The phone SSHes into the PC, lists live `zmx` sessions, and attaches to one as a *mirror* client
that renders VT100 locally.

`AGENTS.md` and `README.md` are partly stale: they still describe `server/sharepty/` (a dtach fork
in C), which was replaced by `server/zmx/`. Trust the code and `server/zmx/PATCHES.md` over them.

## Commands

Android:

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest                     # JVM unit tests
./gradlew :app:testDebugUnitTest --tests "com.homeattach.app.terminal.AttachmentEvictionTest"
./gradlew :app:connectedDebugAndroidTest             # needs a device/emulator
./gradlew :app:lintDebug
```

Server:

```sh
python -m pytest server/tests -q          # tsess-mux protocol + scheduling suite (fast, hermetic)
python -m pytest server/tests/test_muxproto.py::test_name
server/install.sh                         # build zmx if stale, install everything to ~/.local/bin
server/install.sh --build                 # force a zmx rebuild
server/zmx/test-homeattach.py             # functional test of the zmx patches (drives real ptys)
```

`server/install.sh` needs **Zig 0.15.2** (looks in `~/.local/toolchains/zig-x86_64-linux-0.15.2/zig`,
then `PATH`; override with `ZIG=`). Zig 0.16 does not compile this tree.

Release: `./publish-release.sh` (clean tree required — it never stages for you; builds the signed
APK, tags `v$versionName`, creates the GitHub Release).

## Architecture

### One SSH connection, one terminal channel

The client side is a stack of **process-scoped singletons**, deliberately not composition-scoped —
an attachment must outlive the Activity, or every trip to another app costs a reconnect and a wiped
scrollback:

```
SharedSshSession   one JSch Session for the whole process; lazy implicit reconnect
  └─ TerminalMux     one exec channel running `tsess-mux`
       ├─ slot 0 (control)   the host's own state: SESSIONS list + ACTIVITY ticks
       │    └─ RemoteSessionFeed   StateFlow of the list + per-session activity counters
       └─ slots 1..255       one per attached session
            └─ TerminalAttachment   emulator + slot + status; never rebuilt on reconnect
                 └─ AttachedTerminal   LRU pool (max 5); one member is `current`/foreground
                      └─ TerminalService   foreground service alive iff the pool is non-empty
```

Consequences that constrain edits:

- Adding an SSH channel per feature is the anti-pattern this design removed — twice: once for the
  terminals, then again for the session list, whose separate `tsess-watch` channel gave the UI two
  clocks that could not agree. Route new per-session traffic through a mux frame and new host-wide
  state through a slot-0 control frame; only one-shot commands (`tsess-kill`, `tsess-new`) still
  open their own exec channel via `SharedSshSession.acquire()`.
- Only the foreground attachment may claim the remote pty size (`TerminalMux.claimFocus`). The claim
  is exclusive per session on the host, so a backgrounded session claiming would resize the terminal
  the user is looking at. `AttachedTerminal.makeCurrent` does the handover explicitly in both
  directions.
- The reconnect loop lives in `TerminalMux` only. `AttachStatus.Connected` is reported on the
  session's READY frame, not on the channel coming up.
- A coroutine cancel cannot interrupt a blocked socket read; closing the channel is what unblocks
  the reader. `RemoteSessionFeed` therefore drops its `TerminalMux` subscription in `awaitClose`,
  and the mux worker stops once no slot and no host subscriber is left.
- Activity (the blinking lamps) has exactly one source: ACTIVITY frames. Attached sessions are
  reported from their own arriving bytes, unattached ones from the host's `zmx stat` poll, both on
  the same tick. Do not reintroduce a per-attachment activity clock.

### The mux protocol lives in three places

`| type:1 | sid:1 | length:4 BE | payload |` — OPEN/CLOSE/INPUT/FOCUS up, READY/OUTPUT/ENDED/ERROR
down, plus SESSIONS/ACTIVITY down on slot 0. Any change must land in all three, and `server/tests/muxproto.py` is the spec the tests speak:

- `server/tsess-mux` (host, Python) — also owns fair scheduling (`CHUNK_BYTES` per session turn,
  stop reading a session past `SESSION_BUFFER_LIMIT` so its own pty applies backpressure) and the
  control-slot status feed (`SessionStatus`: `zmx stat` every 250ms, `tsess-list` on membership
  change or every 5s, both collected inside the select loop so the data path never blocks).
- `app/.../ssh/MuxProtocol.kt` + `MuxConnection.kt` (client).
- `server/tests/muxproto.py` + `test_tsess_mux.py`.

### Host script contract

The app execs **absolute paths** under `$HOME/.local/bin/` (`tsess-mux`, `tsess-list`, `tsess-kill`,
`tsess-new`) — an sshd exec channel is not a login shell, so `PATH` is intentionally
not relied on. Every script therefore re-exports a sane `PATH` itself and sources `tsess-state` by
`$script_dir`. Keep them executable, and keep `install.sh`'s script list updated when adding one.

Session model: a session is one `zmx` daemon holding a pty. PC tabs attach as *owners* with
`--bind` (closing the last tab ends the session); the phone attaches as `--mirror` (sees and types,
never resizes, never answers terminal queries). `tsess-auto` is the Konsole/yakuake profile command
that makes every tab a session; `tsess-new` asks yakuake over DBus for a real tab rather than
pushing a command into one, so "a tab is a session" has exactly one definition.

`server/zmx/` is a vendored fork of upstream zmx v0.6.0 (MIT). The HomeAttach patches
(mirror clients, view-bound lifetime, external claim/release, `zmx stat` single and bulk, and
mirrors never clearing or RIS-resetting the caller's terminal) are documented in
`server/zmx/PATCHES.md` — update it with any further divergence.

## Conventions

- Comments in this codebase explain *why a shape was chosen and what breaks otherwise*, usually at
  the top of a type. Match that register; the existing headers are the best specification of intent
  and are worth reading before changing a component.
- Testability comes from lifting pure logic out of Android types into top-level `internal` functions
  (`sessionsToEvict`, `parseSessionLine`, `parseSessionList`, `sortedForDisplay`, `parseManifest`) and unit-testing those. Unit tests are plain JVM — no Robolectric.
- Keep SSH/mux/terminal transport out of `ui/`. Compose entry points are `*Screen.kt`.
- Kotlin, 4-space indent, `jvmTarget = 11`.
- Commit subjects are imperative and describe the behavior change, not the file touched
  (e.g. "Attach every terminal through one channel, pooled and arbitrated").

## Build config and secrets

- Debug-only `BuildConfig` fields (`HOMEATTACH_DEBUG_*`, including the developer's private key read
  from `HOMEATTACH_HOME_PRIVATE_KEY_FILE`) are declared **per build type**, with release getting
  empty strings. Do not move them to `defaultConfig`: there, only R8 pruning keeps the key out of
  the release APK, and disabling minification to chase a bug would ship it. See the comment in
  `app/build.gradle.kts`.
- Values come from `local.properties`, then `.env`, then the environment (see `.env.example`).
  Both files are ignored; keystores live in `keystores/`.
- Release builds run R8 full mode; `proguard-rules.pro` keeps the reflection-resolved JSch/BouncyCastle
  ed25519 crypto that R8 cannot see. Verify ed25519 connect on a real device after touching it.
- Self-update reads a static `update.json` manifest from the release download URL, never the GitHub
  REST API (rate limits + draft 404s). `versionCode`/`versionName` in `app/build.gradle.kts` and
  `update.json` must agree; bump both plus the manifest URL for a release.

## Licensing

The vendored Termux engine is GPLv3, so **the app as a whole is GPLv3** (`LICENSE`). Keep the
original Termux header on each vendored file. The vendored zmx is MIT.
