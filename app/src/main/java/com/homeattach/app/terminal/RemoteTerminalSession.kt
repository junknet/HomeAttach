package com.homeattach.app.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * Bridges an SSH byte stream to the vendored Termux terminal engine. Owns a remote-mode
 * [TerminalSession] (no local PTY fork): bytes read off the SSH channel are fed into the emulator on
 * the main thread, user input flows back out through [onInput], and terminal size changes drive
 * [onResize] (which the screen debounces into a remote WINCH). Built on the vendored Termux engine,
 * which is UTF-8 native and renders 24-bit truecolor and modern TUIs correctly.
 */
class RemoteTerminalSession(
    private val context: Context,
    private val onInput: (ByteArray) -> Unit,
    private val onResize: (columns: Int, rows: Int) -> Unit,
) {
    /** Set by the screen to repaint the TerminalView when new output lands. */
    var onScreenUpdated: () -> Unit = {}

    /** Fired when the emulator receives and processes the first chunk of remote data. */
    var onFirstOutput: () -> Unit = {}

    var currentColumns = 0
        private set
    var currentRows = 0
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Remote bytes waiting to be parsed. Written by the SSH reader thread, read by the main thread,
     * so [pending] is its own lock — and [drainScheduled] and [pendingBytes] are guarded by that
     * same lock rather than being separate atomics. The flag and the queue have to move together:
     * "the queue is non-empty and nobody is draining it" is the state that must never be observable,
     * and splitting the two lets a lost update strand the queue with no drain ever scheduled.
     */
    private val pending = ArrayDeque<ByteArray>()
    private var drainScheduled = false
    private var pendingBytes = 0

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) = onScreenUpdated()
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            copyTextToClipboard(text)
        }
        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            pasteTextFromClipboard()
        }
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Log.e(tag ?: TAG, message, e)
        }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(TAG, "", e) }
    }

    /** The vendored Termux session in remote mode. Hand this to `TerminalView.attachSession`. */
    val session: TerminalSession = TerminalSession(
        TRANSCRIPT_ROWS,
        sessionClient,
        object : TerminalSession.RemoteClient {
            override fun onRemoteWrite(data: ByteArray, offset: Int, count: Int) {
                onInput(data.copyOfRange(offset, offset + count))
            }

            override fun onRemoteResize(columns: Int, rows: Int) {
                if (columns != currentColumns || rows != currentRows) {
                    currentColumns = columns
                    currentRows = rows
                    onResize(columns, rows)
                }
            }
        },
    )

    // Keeps re-posting until the view has laid out and the emulator exists, then parses pending
    // output in bounded passes. TerminalEmulator is not thread-safe, so this runs only on the main
    // thread — which is also why a pass is capped: re-attaching after a long absence hands us the
    // whole restored screen at once, and parsing it in one uninterrupted loop holds the main thread
    // for as long as that takes, which is exactly the freeze it looks like. A pass therefore stops
    // at [MAX_BYTES_PER_DRAIN] and re-posts *delayed*, so the frame it just produced actually gets
    // drawn before the next pass starts. Posting undelayed would not do it: with an otherwise idle
    // queue the continuation runs straight back and the whole backlog still lands inside one frame.
    private val drainRunnable = object : Runnable {
        private var firstOutputFired = false

        override fun run() {
            val emulator = session.emulator
            if (emulator == null) {
                // Still holding the scheduled flag: this pass has not run, so nothing else may post.
                mainHandler.postDelayed(this, FIRST_LAYOUT_RETRY_MS)
                return
            }
            var appended = false
            var bytesProcessed = 0

            while (bytesProcessed < MAX_BYTES_PER_DRAIN) {
                val chunk = synchronized(pending) {
                    pending.removeFirstOrNull()?.also { pendingBytes -= it.size }
                } ?: break
                emulator.append(chunk, chunk.size)
                bytesProcessed += chunk.size
                appended = true
            }

            // Yielding costs throughput, so it is spent only while the backlog is small enough for
            // the user to be watching it arrive. Past the high-water mark a producer is outrunning
            // us and the queue would grow without bound, so catch up at full speed instead.
            val nextDelayMs = synchronized(pending) {
                when {
                    pending.isEmpty() -> {
                        drainScheduled = false
                        null
                    }
                    pendingBytes > CATCH_UP_THRESHOLD_BYTES -> 0L
                    else -> DRAIN_YIELD_MS
                }
            }
            if (nextDelayMs != null) mainHandler.postDelayed(this, nextDelayMs)

            if (appended) {
                onScreenUpdated()
                if (!firstOutputFired) {
                    firstOutputFired = true
                    onFirstOutput()
                }
            }
        }
    }

    /** Called from the SSH reader thread; marshals output onto the main thread for the emulator. */
    fun appendRemoteOutput(buffer: ByteArray, offset: Int = 0, count: Int = buffer.size) {
        if (count <= 0) return
        val chunk = buffer.copyOfRange(offset, offset + count)
        // Claim the right to schedule under the same lock that takes the chunk, so a drain pass
        // finishing concurrently either sees this chunk or leaves the flag for us to claim.
        val startDrain = synchronized(pending) {
            pending.addLast(chunk)
            pendingBytes += chunk.size
            if (drainScheduled) false else true.also { drainScheduled = true }
        }
        if (startDrain) mainHandler.post(drainRunnable)
    }

    /** User input from the ExtraKeys row (Esc, Ctrl-C/D, arrows). Routed out to SSH via the session. */
    fun write(data: ByteArray, offset: Int, count: Int) {
        session.write(data, offset, count)
    }

    fun finish() {
        runCatching { session.finishIfRunning() }
    }

    fun copyTextToClipboard(text: String?) {
        if (text.isNullOrEmpty()) return
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("terminal", text)
            clipboard?.setPrimaryClip(clip)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy text to clipboard", e)
        }
    }

    fun pasteTextFromClipboard() {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipData = clipboard?.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val text = clipData.getItemAt(0).coerceToText(context)?.toString()
                if (!text.isNullOrEmpty()) {
                    val emulator = session.emulator
                    if (emulator != null) {
                        emulator.paste(text)
                    } else {
                        val bytes = text.toByteArray(Charsets.UTF_8)
                        session.write(bytes, 0, bytes.size)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to paste text from clipboard", e)
        }
    }

    private companion object {
        const val TAG = "RemoteTerminalSession"

        /** Most remote bytes one main-thread parse pass may consume before yielding for a frame. */
        const val MAX_BYTES_PER_DRAIN = 16384

        /** Half a 60Hz frame: long enough for the draw this pass dirtied to actually happen. */
        const val DRAIN_YIELD_MS = 8L

        /**
         * Backlog past which yielding is dropped. The budget caps a pass, not the producer, so a
         * remote writing faster than [MAX_BYTES_PER_DRAIN] per [DRAIN_YIELD_MS] would otherwise grow
         * [pending] without bound. Smoothness is worth nothing once the terminal is this far behind.
         */
        const val CATCH_UP_THRESHOLD_BYTES = 512 * 1024
        // Deep scrollback: the buffer is a lazily-allocated row-pointer array, so a big cap
        // costs ~80KB of references up front and real memory only as history fills.
        const val TRANSCRIPT_ROWS = 10000
        const val FIRST_LAYOUT_RETRY_MS = 16L
    }
}
