# HomeAttach patches on top of upstream zmx

Vendored from upstream zmx commit `6fabec06141d9e4bcda22c982df34166cfe506ee`
(v0.6.0, MIT).

Build: `~/.local/toolchains/zig-x86_64-linux-0.15.2/zig build -Doptimize=ReleaseSafe`
(requires Zig 0.15.2; system Zig 0.16 does not compile this tree).

## Why zmx

Replaces the sharepty (dtach fork) supervisor. zmx already provides the two
hardest pieces: server-side terminal state snapshots via ghostty-vt (correct
re-attach, mode restoration), and leader arbitration where typing claims the
pty size ("newest typist wins"). Our patches add the HomeAttach policy layer.

## Patch list

1. **Mirror clients** (`zmx attach --mirror`, IPC tag `InitMirror=14`).
   A mirror receives output and may type, but never becomes leader, never
   resizes the pty, and its terminal's auto-replies (CPR/DA/DSR/focus/mouse
   reports, OSC/DCS responses — `util.isTerminalReply`) are dropped so the
   child never sees duplicate query answers. The Android app attaches as a
   mirror.

2. **View-bound lifetime** (`zmx attach --bind`).
   Once an owner (non-mirror) client has attached, the daemon shuts down —
   SIGHUP to the child via the existing `handleKill` defer — when the last
   owner disconnects. Mirrors don't keep a session alive. The PC yakuake tab
   is the session's life.

3. **External focus** (`zmx claim <name> <cols> <rows>` / `zmx release
   <name>`, IPC tags `Claim=15`, `Release=16`).
   Claim resizes the pty (kernel SIGWINCHes the child; TUIs repaint) and
   leaves leadership vacant; the next owner that types claims the size back
   automatically. Release hands the size straight back to the first attached
   owner. Used by tsess-focus / tsess-release for the phone.

4. **Script-friendly status** (`zmx stat <name>`, IPC tag `Stat=17`).
   One `pid= cols= rows= owners= mirrors= bound=` line; used by tsess-list.

Upstream wire-compat: all additions are new IPC tags; old daemons ignore
unknown tags by design (`Tag` is non-exhaustive).
