package com.homeattach.app.terminal

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import com.homeattach.app.ssh.MuxHistoryPage
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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

    /**
     * Fired for input the user sent from outside the terminal view — the extra-keys row, paste.
     * The view scrolls itself back to the live edge when it handles a key, but these never reach
     * it, and output no longer drags the viewport down on its own.
     */
    var onUserInput: () -> Unit = {}

    var currentColumns = 0
        private set
    var currentRows = 0
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    var onBacklogExceeded: () -> Unit = {}
    var onSnapshotStarted: () -> Unit = {}
    var onSnapshotRendered: () -> Unit = {}

    private val renderQueue = TerminalRenderQueue()
    private val schedulingLock = Any()
    private var drainScheduled = false
    private val finished = AtomicBoolean(false)
    private var snapshotEmulator: TerminalEmulator? = null
    private var snapshotRemaining = 0L
    private val historyWorker = Executors.newSingleThreadExecutor { operation ->
        Thread(operation, "TerminalHistory").apply { isDaemon = true }
    }

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

    private val drainRunnable = Runnable { drainOutput() }

    private fun scheduleDrain() {
        synchronized(schedulingLock) {
            if (!drainScheduled && !finished.get()) {
                drainScheduled = true
                mainHandler.post(drainRunnable)
            }
        }
    }

    // Finally guarantees balanced tracing through exceptions.
    @SuppressLint("UnclosedTrace")
    private fun drainOutput() {
        if (finished.get()) return
        var screenChanged = false
        val started = SystemClock.elapsedRealtimeNanos()
        Trace.beginSection("HomeAttach.terminalParse")
        try {
            if (session.emulator != null) {
                var parsedBytes = 0
                while (parsedBytes < MAX_BYTES_PER_DRAIN &&
                    SystemClock.elapsedRealtimeNanos() - started < PARSE_BUDGET_NANOS) {
                    val chunkLimit = if (snapshotEmulator != null && snapshotRemaining > 0)
                        minOf(PARSE_CHUNK_BYTES.toLong(), snapshotRemaining).toInt()
                    else PARSE_CHUNK_BYTES
                    when (val operation = renderQueue.take(chunkLimit) ?: break) {
                        is TerminalRenderOperation.Snapshot -> {
                            onSnapshotStarted()
                            snapshotEmulator = session.createRemoteSnapshotEmulator()
                            snapshotRemaining = operation.replayBytes
                            if (snapshotRemaining == 0L) {
                                publishSnapshot()
                                screenChanged = true
                            }
                        }
                        is TerminalRenderOperation.Output -> {
                            val destination = snapshotEmulator ?: session.emulator
                            destination.append(operation.bytes, operation.bytes.size)
                            parsedBytes += operation.bytes.size
                            if (snapshotEmulator != null) {
                                snapshotRemaining -= operation.bytes.size
                                if (snapshotRemaining == 0L) {
                                    publishSnapshot()
                                    screenChanged = true
                                }
                            } else {
                                screenChanged = true
                            }
                        }
                        is TerminalRenderOperation.Control -> operation.action()
                    }
                }
            }
        } finally {
            Trace.endSection()
            synchronized(schedulingLock) {
                drainScheduled = false
                if (!finished.get() && renderQueue.hasPending()) {
                    drainScheduled = true
                    mainHandler.postDelayed(drainRunnable, DRAIN_YIELD_MS)
                }
            }
        }
        if (screenChanged) {
            onScreenUpdated()
            onFirstOutput()
        }
    }

    private fun publishSnapshot() {
        val restored = snapshotEmulator ?: return
        session.replaceRemoteEmulator(restored)
        snapshotEmulator = null
        snapshotRemaining = 0
        onSnapshotRendered()
    }

    internal fun beginSnapshot(replayBytes: Long) {
        renderQueue.beginSnapshot(replayBytes.coerceAtLeast(0))
        scheduleDrain()
    }

    internal fun enqueueControl(action: () -> Unit) {
        renderQueue.enqueueControl(action)
        scheduleDrain()
    }

    fun appendRemoteOutput(buffer: ByteArray, offset: Int = 0, count: Int = buffer.size) {
        if (count <= 0 || finished.get()) return
        if (!renderQueue.append(buffer, offset, count)) {
            renderQueue.clear()
            onBacklogExceeded()
            return
        }
        scheduleDrain()
    }

    internal fun prependHistory(page: MuxHistoryPage, canApply: () -> Boolean, completed: (Int) -> Unit) {
        val expectedEmulator = session.emulator ?: return completed(0)
        if (page.columns != expectedEmulator.mColumns || expectedEmulator.isAlternateBufferActive) {
            completed(0)
            return
        }
        historyWorker.execute {
            val historyRows = runCatching {
                val decoder = TerminalEmulator(
                    historyOutput, page.columns, 2, 0, 0, 128, sessionClient,
                )
                page.rows.map { historyRow ->
                    val content = ("\u001bc" + historyRow.text).toByteArray(Charsets.UTF_8)
                    decoder.append(content, content.size)
                    if (historyRow.wrapped) decoder.screen.setLineWrap(0)
                    else decoder.screen.clearLineWrap(0)
                    decoder.screen.copyRow(0)
                }.toTypedArray()
            }
            mainHandler.post {
                if (finished.get()) return@post
                if (!canApply() || expectedEmulator !== session.emulator ||
                    page.columns != expectedEmulator.mColumns ||
                    expectedEmulator.isAlternateBufferActive) {
                    completed(0)
                    return@post
                }
                val inserted = historyRows.fold(
                    onSuccess = { expectedEmulator.screen.prependTranscriptRows(it) },
                    onFailure = { failure ->
                        Log.w(TAG, "History decoding failed", failure)
                        0
                    },
                )
                completed(inserted)
                if (inserted > 0) onScreenUpdated()
            }
        }
    }

    private val historyOutput = object : TerminalOutput() {
        override fun write(bytes: ByteArray, offset: Int, count: Int) {}
        override fun titleChanged(previous: String?, current: String?) {}
        override fun onCopyTextToClipboard(content: String?) {}
        override fun onPasteTextFromClipboard() {}
        override fun onBell() {}
        override fun onColorsChanged() {}
    }

    /** User input from the ExtraKeys row (Esc, Ctrl-C/D, arrows). Routed out to SSH via the session. */
    fun write(data: ByteArray, offset: Int, count: Int) {
        onUserInput()
        session.write(data, offset, count)
    }

    fun finish() {
        if (!finished.compareAndSet(false, true)) return
        renderQueue.clear()
        mainHandler.removeCallbacksAndMessages(null)
        historyWorker.shutdownNow()
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
        onUserInput()
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

        const val MAX_BYTES_PER_DRAIN = 16384
        const val PARSE_CHUNK_BYTES = 2048
        const val PARSE_BUDGET_NANOS = 4_000_000L
        const val DRAIN_YIELD_MS = 8L
        // Deep scrollback: the buffer is a lazily-allocated row-pointer array, so a big cap
        // costs ~80KB of references up front and real memory only as history fills.
        const val TRANSCRIPT_ROWS = 10000
    }
}
