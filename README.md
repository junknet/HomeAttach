# HomeAttach

Android app that SSHes into a home Linux PC, shows a live list of shared
terminal sessions running there, and lets you tap one to open a real
interactive VT100 terminal into it — attach to whatever's running at home,
from anywhere, with a real terminal (vim/htop/codex render correctly).

The PC side is `sharepty` (`server/sharepty/`, a small fork of dtach): a
transparent shared-pty supervisor. It is a pure byte pipe — it never
interprets escape sequences, never opens the alternate screen, never touches
the mouse — so a locally attached terminal behaves byte-for-byte like a bare
shell. On top of that it adds what sharing actually needs:

- an in-memory **ring buffer** of session output (default 8 MB) with
  monotonically increasing byte offsets;
- **replay on attach** (`-T <bytes>` tail / `-F <offset>` resume), so a phone
  attaching mid-session gets recent history and can resume gap-free after a
  network drop;
- **passive clients** (`-P`): remote clients never drive the pty window size;
- **focus grants** (`-W <cols> <rows>`): the focused client's size is applied
  and the child gets SIGWINCH, so full-screen TUIs repaint at the new size.

Sharing is **opt-in only**. Local terminal tabs are never wrapped in
anything; a session exists only when you explicitly start one.

## main

- `tsess` — interactive picker for the PC: list sessions, pick a number to
  attach, or type a new name to create one (this is the opt-in entry point).
- `tsess-attach <name> [tail|from=<offset>]` — passive attach with replay;
  what the app runs over SSH.
- `tsess-list` — machine-readable TSV
  (`name\tcmd\tcwd\towner\tcols\trows\tstatus`) that the app polls.
- `tsess-focus <name> <owner> <cols> <rows>` — grant focus: resize + WINCH.
- `tsess-release <name> <owner>` — hand size ownership back to the PC client.
- `tsess-kill <name>` — end a session outright (app's swipe action).
- `tsess-state` — shared helpers + the focus/release implementation.

Session lifecycle is owned by the supervisor process: when the command inside
exits (or is killed), the socket and the ring buffer disappear with it.

## main

1. **Build sharepty**:
   ```
   cd server/sharepty && make
   ```

2. **Install**: copy the scripts and the binary somewhere on `$PATH`, e.g.:
   ```
   cp server/tsess server/tsess-* server/sharepty/sharepty ~/.local/bin/
   chmod +x ~/.local/bin/tsess ~/.local/bin/tsess-* ~/.local/bin/sharepty
   ```
   The app execs `tsess-list`/`tsess-kill`/`tsess-focus`/`tsess-release`/
   `tsess-watch`/`tsess-attach` from `$HOME/.local/bin`. Non-login SSH exec
   shells do not source `.zshrc`, so relying on `PATH` is intentionally
   avoided.

3. **Optional shell alias** for quick opt-in from any tab:
   ```sh
   s() { ~/.local/bin/tsess; }
   ```
   Run `s codex`-style workflows by picking/typing a name, then start your
   TUI inside. Everything else on the PC stays a completely bare shell.

4. **Quick SSH Setup (QR Code Scan)**:
   We provide a helper script to automatically configure SSH keys and display a configuration QR code. Simply run:
   ```sh
   ./server/tsess-qr-config
   ```
   This script generates a dedicated keypair, authorizes it, and prints a QR code in your terminal containing the full connection payload. On your phone, open the app's Settings, tap **Scan QR Code**, and scan the terminal screen. The host, port, username, and private key will auto-fill instantly!

   *(Alternative manual setup)*:
   If you prefer configuring manually, generate a keypair:
   ```sh
   ssh-keygen -t ed25519 -f ~/.ssh/id_ed25519_homeattach -N "" -C "homeattach-android-app"
   cat ~/.ssh/id_ed25519_homeattach.pub >> ~/.ssh/authorized_keys
   ```
   Then paste the private key's contents into the app's Settings screen (stored in `EncryptedSharedPreferences`).

5. sshd needs to be reachable from wherever the phone actually is. On the
   phone, set the app's host to the PC's real external address, IPv4 or
   IPv6 (note: SLAAC/privacy-extension IPv6 addresses can change — a
   dynamic-DNS setup or a stable local ULA/WireGuard address is more
   durable than hardcoding an address that rotates).

## main

- A pty has exactly one window size ("mirror" semantics): the focused
  client's size wins, and every size change broadcasts a screen clear to all
  clients so the WINCH repaint lands on a clean slate instead of layering
  over frames drawn for the old size. A client narrower than the current
  size still sees wrapped lines until it takes focus
  (`tsess-attach <name> 256k focus`); per-client re-rendering would require
  a server-side terminal state machine, which this design deliberately
  avoids.
- Replay is raw bytes. For inline-output programs (shells, Claude Code) it
  reconstructs scrollback on the phone; for alternate-screen TUIs (codex,
  vim) the WINCH redraw paints the current frame and the app's own
  scrollback/transcript keys cover the rest.
- The ring is memory owned by the supervisor: nothing is written to disk,
  and history dies with the session (`0600` socket in
  `$XDG_RUNTIME_DIR/homeattach-<uid>/`).

## main

```
cd server/sharepty && make test
```
covers replay, offset resume, ring overflow clamping, passive/active size
arbitration, focus WINCH redraw, input push, and socket lifecycle.

## main

Kotlin + Jetpack Compose, `com.homeattach.app`. Build/run the usual way:
```
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Key libraries: `com.github.mwiede:jsch` (SSH; note the BouncyCastle
dependency it needs for ed25519 keys on Android — see the comment in
`SshClient.kt`) plus the vendored Apache-2.0 Jackpal `emulatorview` terminal
component under `app/src/main/java/jackpal/androidterm/emulatorview/`.

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
