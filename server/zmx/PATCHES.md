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
   One `pid= cols= rows= owners= mirrors= bound= output_seq=` line; used by
   tsess-list.

5. **Bulk status** (`zmx stat` with no session name).
   One line per live session, `name=` first, from a single process. The phone's
   activity feed polls this several times a second so every session — not only
   the attached ones — has a current activity signal; spawning one `zmx stat`
   per session would make that poll cost scale with the session count.

6. **A mirror never clears or resets its caller's terminal.**
   `zmx attach` writes `ESC [2J ESC [H` before the state snapshot and `ESC c`
   (RIS) on detach. Both are right for a local terminal and wrong for a mirror,
   whose stdout is HomeAttach's byte pipe to a phone that keeps the session on
   screen across detaches: the clear wiped the phone's scrollback on every
   re-attach and the RIS reset its emulator on every dropped connection. The
   snapshot repaints the screen by itself, so a mirror needs neither.

7. **Resumable output** (`zmx attach --mirror --resume <epoch>:<offset>`,
   `--tail <rows>`, IPC tags `InitResume=18`, `ResumeInfo=19`).
   The daemon keeps a 2MB ring of the raw bytes it broadcast, a monotonic
   `output_bytes` cursor over them, and a random per-incarnation `epoch`. A
   mirror that already holds part of the session asks to continue from its
   cursor; when the ring still covers it the daemon sends exactly the missing
   bytes, otherwise it sends a snapshot capped at `--tail` rows. Either way it
   answers first with `ResumeInfo` — mode, epoch, the client's new cursor, and
   how many of the bytes about to arrive are replay that the cursor already
   counts. The client prints that as one `zmx-resume` line on **stderr**, which
   is why `tsess-mux` gives each attach its own stderr pipe: stdout is the
   terminal stream and nothing else may appear in it.

   `epoch` is deliberately not `created_at`: that has second resolution, and a
   session killed and recreated inside the same second would hand a client an
   epoch that matches while the stream behind it is a different terminal.

   `handleInitResume` also clears the client's write buffer before answering. A
   client joins the broadcast list when it connects, one or more loop iterations
   before its init arrives, so output from that window is already queued for it —
   and everything that follows accounts for those bytes. Left queued they print
   ahead of a snapshot that then clears the screen (the line vanishes) or ahead
   of a replay that repeats them.

   `zmx stat` reports `epoch=`, `stream_start=` and `stream_end=` so a caller can
   see the window before asking.

Note that 5, 6 and 7 are what make reopening the phone cheap: 6 stops a
reconnect from costing the user their scrollback, 5 stops the status feed from
costing the host a process per session per poll, and 7 turns reopening the app
from "re-send the whole session" (measured at 134KB-850KB each) into "send the
few hundred bytes it missed".

Upstream wire-compat: all additions are new IPC tags; old daemons ignore
unknown tags by design (`Tag` is non-exhaustive).
