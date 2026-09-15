# HomeAttach

Android app for attaching over SSH to shared terminal sessions on a Linux PC.
The app uses a vendored Termux terminal engine; the PC runs the vendored zmx
supervisor with Ghostty terminal state tracking. Sessions are explicitly created
through `tsess`.

## Terminal history and input

- Opening a terminal requests the current screen and at most 200 nearby
  scrollback rows. Android prepares that snapshot in a separate emulator and
  displays it when complete, avoiding visible history replay.
- Scrolling within two screens of the oldest loaded content requests up to 128
  earlier physical rows. Pages preserve styles, blank rows and wrapping flags;
  inserting a page keeps the existing viewport and live screen in place.
- New output follows the viewport at the bottom. While reading history, output
  continues below without pulling the viewport down. Typing returns to the live
  edge.
- Sessions retained by the running app resume with their existing byte cursor.
  A cold opening uses a bounded server snapshot instead of parsing an old raw
  stream from disk.
- Terminal parsing yields between bounded passes. If queued output exceeds
  2 MiB, that mirror requests a fresh current-screen snapshot; other sessions
  retain their connections.
- The extra-key row includes Tab (byte `0x09`) and scrolls horizontally on narrow
  screens.

History cursors reference server terminal rows, separately from byte offsets used
for reconnecting. They survive mirror disconnects while the daemon retains the
anchor. Resizing, clearing history, server scrollback eviction, or eviction from
the eight-anchor cache can expire a cursor. The app reports that boundary instead
of inserting mismatched history. Applications using the alternate screen keep
their own history navigation; terminal scrollback paging applies to the primary
screen.

## Build and install the PC helpers

Build `server/zmx` with **Zig 0.15.2** (system Zig 0.16 is incompatible):

```sh
cd server/zmx
~/.local/toolchains/zig-x86_64-linux-0.15.2/zig build -Doptimize=ReleaseSafe -j2
cd ../..
install -d ~/.local/bin
install -m 755 server/zmx/zig-out/bin/zmx ~/.local/bin/zmx
install -m 755 server/tsess server/tsess-* ~/.local/bin/
```

The Android app runs `$HOME/.local/bin/tsess-mux` over SSH. Keep that helper and
the zmx binary updated together. A new daemon advertises `history_pages=1` in
`zmx stat`. Replacing the binary does **not** upgrade already running daemons:
existing older sessions retain their compatibility behavior; create a new
session after updating to use history paging. Updating never terminates existing
sessions automatically.

Run `tsess` on the PC to create or pick a shared session. Session lifetime belongs
to the supervisor and its attached owner. The app lists sessions and attaches as
a mirror; granting focus claims the terminal dimensions for the phone.

For SSH configuration, run `./server/tsess-qr-config` and scan its QR code from the
app settings, or enter the reachable host, username and SSH key manually.
Credentials stay in Android encrypted storage. Keep private keys, host settings,
`local.properties`, keystores and generated outputs out of version control.

## Build and verify Android

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The instrumentation suite uses an isolated terminal fixture without connecting
to a host. It covers snapshot publication, UI-thread responsiveness, history
insertion and Tab transport, and saves terminal images in the test app's external
files directory.

Host-side checks:

```sh
python3 -m pytest -q server/tests
cd server/zmx
~/.local/toolchains/zig-x86_64-linux-0.15.2/zig build test -j2
python3 test-history-pages.py zig-out/bin/zmx
```

The app is GPLv3 because it incorporates the Termux terminal engine. Vendored
zmx retains its MIT license; see `server/zmx/PATCHES.md` for protocol extensions.

## Public release updates

The app uses public GitHub Releases for self-update checks:

- `Settings -> Check update` calls GitHub's latest-release API for the
  repository baked into the build.
- If the latest tag is newer than `BuildConfig.VERSION_NAME`, the app
  downloads the release APK into its private cache and opens Android's system
  installer. Android still requires user confirmation; there is no silent
  update path for a normal sideloaded app.
- Host, port, username and SSH key are saved by the Android app in encrypted
  app storage. They survive normal APK upgrades as long as the package name
  stays `com.homeattach.app` and every upgrade is signed with the same
  certificate. Uninstalling the app, clearing app data, or switching from a
  debug-signed install to a differently signed release install loses that
  local Android data.
- Build the signed APK locally, then upload it to a public GitHub Release.
  The APK must be signed with the same local release keystore every time, or
  Android will reject it as a different app.

Local release signing lives in ignored `.env` or `local.properties`; use
`.env.example` as the template.

Build and publish a public release:

```sh
./gradlew :app:assembleRelease
sha256sum app/build/outputs/apk/release/app-release.apk
git tag v1.0.1
git push origin v1.0.1
```

Then create the GitHub Release for `v1.0.1` and upload:

```text
app/build/outputs/apk/release/app-release.apk
```

Before making the repository public, verify that only placeholders remain:

```sh
rg -n "BEGIN .*PRIVATE|240e:|/home/[A-Za-z0-9_-]+" . --glob '!README.md'
git status --short --ignored
```
