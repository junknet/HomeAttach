package com.homeattach.app.terminal

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
    private val pending = ArrayDeque<ByteArray>()
    private var drainScheduled = false

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) = onScreenUpdated()
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
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

    // Keeps re-posting until the view has laid out and the emulator exists, then drains all pending
    // output in one pass. TerminalEmulator is not thread-safe, so this runs only on the main thread.
    private val drainRunnable = object : Runnable {
        private var firstOutputFired = false

        override fun run() {
            val emulator = session.emulator
            if (emulator == null) {
                mainHandler.postDelayed(this, FIRST_LAYOUT_RETRY_MS)
                return
            }
            drainScheduled = false
            var appended = false
            while (true) {
                val chunk = synchronized(pending) { pending.removeFirstOrNull() } ?: break
                emulator.append(chunk, chunk.size)
                appended = true
            }
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
        synchronized(pending) { pending.addLast(chunk) }
        scheduleDrain()
    }

    private fun scheduleDrain() {
        if (drainScheduled) return
        drainScheduled = true
        mainHandler.post(drainRunnable)
    }

    /** User input from the ExtraKeys row (Esc, Ctrl-C/D, arrows). Routed out to SSH via the session. */
    fun write(data: ByteArray, offset: Int, count: Int) {
        session.write(data, offset, count)
    }

    fun finish() {
        runCatching { session.finishIfRunning() }
    }

    private companion object {
        const val TAG = "RemoteTerminalSession"
        // Deep scrollback: the buffer is a lazily-allocated row-pointer array, so a big cap
        // costs ~80KB of references up front and real memory only as history fills.
        const val TRANSCRIPT_ROWS = 10000
        const val FIRST_LAYOUT_RETRY_MS = 16L
    }
}
