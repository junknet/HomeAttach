package com.homeattach.app.terminal

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.homeattach.app.BuildConfig
import com.homeattach.app.data.HostConfig
import com.homeattach.app.ssh.MuxHistoryPage
import com.homeattach.app.ssh.MuxReady
import com.homeattach.app.ssh.MuxResume
import com.homeattach.app.ssh.MuxSessionListener
import com.homeattach.app.ssh.MuxState
import com.homeattach.app.ssh.RemoteTerminalSize
import com.homeattach.app.ssh.TerminalMux
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Why an attachment gave up for good. The two are worth telling apart because they send the user to
 * completely different places: one is a key to fix in Settings, the other is a script to install on
 * the PC. Reporting a missing host script as an auth failure sends them hunting in the wrong file.
 */
enum class FailureCause {
    /** The host rejected our key. The fix lives in Settings. */
    AUTHENTICATION,

    /** The host answered but cannot serve us — its scripts are older than this app. */
    HOST_SETUP,
}

/** What [TerminalAttachment] is doing right now. */
sealed interface AttachStatus {
    /** Attaching, with nothing drawn yet — the screen shows a blocking spinner. */
    data object Connecting : AttachStatus

    data object Connected : AttachStatus

    /**
     * The channel dropped and [TerminalMux] is retrying by itself. The emulator keeps every row it
     * had, so once anything has been drawn the screen stays readable behind a banner rather than
     * blanking back to a spinner.
     */
    data class Reconnecting(val attempt: Int, val message: String) : AttachStatus

    /** Retrying cannot help. [cause] says where the fix lives, which is all the screen can offer. */
    data class Failed(val message: String, val cause: FailureCause) : AttachStatus

    /** The session ended on the PC — its terminal tab was closed, or it was killed. */
    data object Ended : AttachStatus
}

enum class HistoryLoadState { IDLE, LOADING, EXPIRED, UNAVAILABLE, COMPLETE }

class TerminalAttachment(
    val sessionName: String,
    val sessionLabel: String,
    val config: HostConfig,
    context: Context,
) {

    private val _status = MutableStateFlow<AttachStatus>(AttachStatus.Connecting)
    val status: StateFlow<AttachStatus> = _status.asStateFlow()

    /**
     * Latches true the first time the emulator processes any remote output, and never clears. The
     * screen keys its blocking spinner off this rather than off [status], so reconnects repaint a
     * banner over a live terminal instead of hiding it.
     */
    private val _hasOutput = MutableStateFlow(false)
    val hasOutput: StateFlow<Boolean> = _hasOutput.asStateFlow()

    private val released = AtomicBoolean(false)

    /**
     * Whether this is the attachment on screen. Set by [AttachedTerminal], which owns the pool and
     * is the only thing that knows. It gates every focus claim: the claim is exclusive per session
     * on the host, and pooled sessions all stay live on the channel, so without this gate a
     * backgrounded session coming back from a radio gap would resize the terminal the user is
     * looking at.
     */
    private val foreground = AtomicBoolean(false)

    private val measuredSize = AtomicReference<RemoteTerminalSize?>(null)

    /** Terminal state that is neither connecting nor reconnecting: nothing revives from these. */
    @Volatile
    private var finished = false

    private val slot = AtomicReference<TerminalMux.Slot?>(null)
    private val attached = AtomicBoolean(false)

    // Initialized before the attachment schedules its first layout timeout.
    private val mainHandler = Handler(Looper.getMainLooper())

    private val cursor = AtomicLong(0)
    private val epoch = AtomicLong(0)
    private val awaitingSnapshot = AtomicBoolean(false)
    private val requestedSnapshotSize = AtomicReference<RemoteTerminalSize?>(null)
    private val historyCursor = TerminalHistoryCursor()
    private var historyReady = false
    private var historySupported = false
    private val _historyState = MutableStateFlow(HistoryLoadState.IDLE)
    val historyState: StateFlow<HistoryLoadState> = _historyState.asStateFlow()

    /** Bytes still to arrive that [cursor] already counts; see [MuxReady.replayBytes]. */
    private val replayRemaining = AtomicLong(0)

    val terminal = RemoteTerminalSession(
        context = context.applicationContext,
        onInput = { bytes -> slot.get()?.let { TerminalMux.sendInput(it, bytes) } },
        onResize = { columns, rows ->
            val size = RemoteTerminalSize(columns, rows)
            val previous = measuredSize.getAndSet(size)
            // The first measurement is what the attach was waiting for.
            if (previous == null) attachOnce()
            if (foreground.get()) declareGrid(size)
            // Only the visible terminal drives the remote size — a mirror attach never owns it, so
            // for a backgrounded session there is nothing to send. And only on a real change: the
            // IME opening and closing re-measures the grid constantly, and re-claiming on every one
            // would put a frame and a host-side resize behind each keyboard flap.
            if (foreground.get() && previous != size) claimFocus()
            // Only a *width* change invalidates the picture the host sent. A row count change is a
            // pty resize the emulator absorbs locally, pulling rows out of the transcript or
            // pushing them back into it, so the screen stays the screen. Asking for a fresh
            // snapshot there would tear the mirror down on the host and redraw all 200 tail rows —
            // and the one thing that changes rows and nothing else is the IME, several times a
            // minute. Re-snapshotting on a keyboard flap is what made every keystroke cost a
            // re-attach.
            if (previous != null && previous.columns != size.columns) requestFreshSnapshot()
        },
    ).apply {
        onFirstOutput = { _hasOutput.value = true }
        onBacklogExceeded = { requestFreshSnapshot() }
        onSnapshotStarted = {
            historyCursor.reset()
            historyReady = false
            historySupported = false
            _historyState.value = HistoryLoadState.IDLE
        }
        onSnapshotRendered = { historyReady = true }
    }

    /**
     * The mux side of this attachment, kept as a private member rather than implemented by the
     * class itself: the wire protocol is an implementation detail, and letting it onto this type's
     * public surface would make every caller of [TerminalAttachment] a caller of the frame layer.
     */
    private val muxListener = object : MuxSessionListener {
        override fun onReady(ready: MuxReady) = handleReady(ready)
        override fun onOutput(data: ByteArray) = handleOutput(data)
        override fun onHistoryPage(page: MuxHistoryPage) = handleHistoryPage(page)
        override fun onEnded(reason: String) = handleEnded()
        override fun onError(message: String) = handleError(message)
        override fun onTransportState(state: MuxState) = handleTransportState(state)
    }

    init {
        mainHandler.postDelayed({ attachOnce() }, ATTACH_WITHOUT_GRID_MS)
    }

    /**
     * Attaches to the host, once, carrying the grid this terminal will be drawn at.
     *
     * Deliberately not in [init]: the picture the host sends back is serialized for whatever width
     * it believes the terminal is, and painting a picture made at the PC's width onto the phone's
     * wraps lines differently - which shifts everything below them and leaves the snapshot's own
     * cursor pointing at a line of content that the next output then overwrites. Waiting the one
     * frame it takes to measure the grid means the host is told the size first and the picture
     * arrives already correct.
     *
     * The wait is bounded: a session that somehow never measures still attaches, just without the
     * size, which is the behaviour this replaced.
     */
    private fun attachOnce() {
        if (released.get() || !attached.compareAndSet(false, true)) return
        val size = measuredSize.get()
        val claim = size?.takeIf { foreground.get() }
        requestedSnapshotSize.set(size)
        val registered = TerminalMux.register(
            config,
            sessionName,
            muxListener,
            MuxResume(
                epoch = epoch.get(),
                offset = cursor.get(),
                columns = claim?.columns ?: 0,
                rows = claim?.rows ?: 0,
            ),
        )
        if (registered == null) {
            moveTo(AttachStatus.Failed(NO_FREE_SLOT, FailureCause.HOST_SETUP))
            finished = true
        } else {
            slot.set(registered)
        }
    }

    /**
     * Move this attachment on or off screen. Taking the foreground re-claims pty size ownership,
     * because the PC takes it back the moment someone types there; giving it up simply stops
     * claiming, leaving the size to whoever asks next.
     */
    fun setForeground(value: Boolean) {
        if (foreground.getAndSet(value) == value) return
        // The declared grid follows the foreground. A slot is re-opened on every reconnect, and a
        // size in that frame is a claim: from a session nobody is looking at, that would resize the
        // terminal the user *is* looking at.
        declareGrid(if (value) measuredSize.get() else null)
        if (value) claimFocus()
    }

    private fun declareGrid(size: RemoteTerminalSize?) {
        slot.get()?.let {
            it.resume = it.resume.copy(columns = size?.columns ?: 0, rows = size?.rows ?: 0)
        }
    }

    /**
     * Re-claim pty size ownership for this phone. No-op while backgrounded — that is what keeps a
     * pooled session from resizing the visible one — and no-op before the first layout, since the
     * grid is not known yet.
     */
    fun claimFocus() {
        if (!foreground.get() || released.get()) return
        val live = slot.get() ?: return
        val size = measuredSize.get() ?: return
        TerminalMux.claimFocus(live, size.columns, size.rows)
    }

    /** Retry now instead of waiting out the backoff. The screen calls this on resume: the radio is
     * usually back the instant the user returns, and making them watch an 8s timer would waste the
     * one moment they are actually looking. */
    fun retryNow() {
        TerminalMux.retryNow()
    }

    /** Tear down for good. The host releases this session's pty ownership when it sees the CLOSE. */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        foreground.set(false)
        slot.getAndSet(null)?.let(TerminalMux::unregister)
        terminal.onScreenUpdated = {}
        terminal.onFirstOutput = {}
        terminal.onUserInput = {}
        terminal.onBacklogExceeded = {}
        terminal.onSnapshotStarted = {}
        terminal.onSnapshotRendered = {}
        mainHandler.removeCallbacksAndMessages(null)
        terminal.finish()
    }

    // ---------- driven by the mux reader thread ----------

    private fun handleReady(ready: MuxReady) {
        if (released.get()) return

        awaitingSnapshot.set(false)
        // The grid can move between asking for a picture and being given one. Only a width that no
        // longer matches makes the answer unusable — same reason as the resize path above, and
        // re-requesting on a row count would loop a keyboard flap against the host's round trip.
        if (!ready.continued && foreground.get() &&
            requestedSnapshotSize.get()?.columns != measuredSize.get()?.columns) {
            requestFreshSnapshot()
            return
        }
        epoch.set(ready.epoch)
        cursor.set(ready.offset)
        replayRemaining.set(ready.replayBytes)
        if (!ready.continued) {
            terminal.beginSnapshot(ready.replayBytes)
            if (BuildConfig.DEBUG) Log.i(TAG, "session=$sessionName restarted at ${ready.offset}")
        } else if (BuildConfig.DEBUG) {
            Log.i(TAG, "session=$sessionName continued at ${ready.offset}")
        }

        moveTo(AttachStatus.Connected)
        // A re-attach lands with the host's idea of the size, so the visible terminal has to say
        // again that the grid is the phone's.
        claimFocus()
    }

    private fun handleOutput(data: ByteArray) {
        if (released.get() || awaitingSnapshot.get()) return
        val replay = replayRemaining.get()
        val counted = if (replay <= 0) data.size.toLong() else {
            val skipped = minOf(replay, data.size.toLong())
            replayRemaining.addAndGet(-skipped)
            data.size - skipped
        }
        val current = if (counted > 0) cursor.addAndGet(counted) else cursor.get()
        slot.get()?.let { it.resume = it.resume.copy(epoch = epoch.get(), offset = current) }
        terminal.appendRemoteOutput(data, 0, data.size)
    }

    private fun requestFreshSnapshot() {
        if (released.get() || !awaitingSnapshot.compareAndSet(false, true)) return
        val current = slot.get()
        if (current == null) {
            awaitingSnapshot.set(false)
            return
        }
        requestedSnapshotSize.set(measuredSize.get())
        TerminalMux.requestSnapshot(current)
    }

    fun onHistoryScroll(topRow: Int, historyRows: Int, visibleRows: Int) {
        if (released.get() || !historyReady || terminal.session.emulator?.isAlternateBufferActive != false) return
        if (topRow == 0) {
            if (_historyState.value != HistoryLoadState.LOADING) _historyState.value = HistoryLoadState.IDLE
            return
        }
        if (historyRows + topRow > maxOf(16, visibleRows * 2)) return
        if (historyCursor.anchor == "0") {
            _historyState.value = if (historySupported) HistoryLoadState.COMPLETE else HistoryLoadState.UNAVAILABLE
            return
        }
        val request = historyCursor.request(topRow, historyRows, visibleRows) ?: return
        val current = slot.get()
        if (current == null) {
            historyCursor.failed()
            return
        }
        _historyState.value = HistoryLoadState.LOADING
        val generation = historyCursor.generation
        TerminalMux.requestHistory(current, request.anchor, request.before, request.limit)
        mainHandler.postDelayed({
            if (historyCursor.loading && historyCursor.anchor == request.anchor &&
                historyCursor.before == request.before && historyCursor.generation == generation) {
                historyCursor.failed()
                _historyState.value = HistoryLoadState.UNAVAILABLE
            }
        }, HISTORY_TIMEOUT_MS)
    }

    private fun handleHistoryPage(page: MuxHistoryPage) {
        if (released.get() || awaitingSnapshot.get()) return
        terminal.enqueueControl {
            if (released.get()) return@enqueueControl
            if (page.rows.isEmpty() && page.before == 0L && page.next == 0L &&
                !historyCursor.accepts(page)) {
                historySupported = page.status == "ok"
                historyCursor.metadata(page)
                return@enqueueControl
            }
            if (!historyCursor.accepts(page)) return@enqueueControl
            if (page.status != "ok") {
                historyCursor.completed(page, 0)
                _historyState.value = HistoryLoadState.EXPIRED
                return@enqueueControl
            }
            val generation = historyCursor.generation
            terminal.prependHistory(page, canApply = {
                historyCursor.generation == generation && historyCursor.accepts(page)
            }) { inserted ->
                if (historyCursor.generation != generation || !historyCursor.accepts(page)) return@prependHistory
                historyCursor.completed(page, inserted)
                _historyState.value = when {
                    inserted != page.rows.size -> HistoryLoadState.EXPIRED
                    page.more -> HistoryLoadState.IDLE
                    else -> HistoryLoadState.COMPLETE
                }
            }
        }
    }

    private fun handleEnded() {
        if (released.get()) return
        // The slot is already gone on the mux side; drop ours so nothing tries to speak for it.
        slot.set(null)
        finished = true
        moveTo(AttachStatus.Ended)
    }

    private fun handleError(message: String) {
        // Not terminal: a rejected frame costs that frame, not the session.
        if (BuildConfig.DEBUG) Log.w(TAG, "session=$sessionName host rejected a frame: $message")
    }

    private fun handleTransportState(state: MuxState) {
        if (released.get() || finished) return
        if (state is MuxState.Reconnecting) {
            mainHandler.post {
                historyCursor.failed()
                _historyState.value = HistoryLoadState.IDLE
            }
            if (replayRemaining.get() > 0 || awaitingSnapshot.get()) {
                slot.get()?.let { it.resume = it.resume.copy(epoch = 0, offset = 0) }
            }
        }
        when (state) {
            is MuxState.Failed -> {
                finished = true
                val cause =
                    if (state.hostSetup) FailureCause.HOST_SETUP else FailureCause.AUTHENTICATION
                moveTo(AttachStatus.Failed(state.cause, cause))
            }
            is MuxState.Reconnecting -> moveTo(AttachStatus.Reconnecting(state.attempt, state.cause))
            is MuxState.Connecting -> moveTo(AttachStatus.Connecting)
            // Connected means the channel is up, not that this session is attached yet; the READY
            // frame is what says that, and it is the only thing that reports Connected.
            is MuxState.Connected -> Unit
        }
    }

    /**
     * Every state change funnels through here so the attachment's whole life is one greppable
     * trace. Without it the interesting property — that backgrounding the app costs *no*
     * transitions — is invisible: a silent success and a silent reconnect look identical.
     */
    private fun moveTo(next: AttachStatus) {
        val previous = _status.value
        if (previous == next) return
        _status.value = next
        if (BuildConfig.DEBUG) Log.i(TAG, "session=$sessionName ${trace(previous)} -> ${trace(next)}")
    }

    private fun trace(status: AttachStatus): String = when (status) {
        is AttachStatus.Connecting -> "connecting"
        is AttachStatus.Connected -> "connected"
        is AttachStatus.Reconnecting -> "reconnecting[attempt=${status.attempt} cause=${status.message}]"
        is AttachStatus.Failed -> "failed[cause=${status.message}]"
        is AttachStatus.Ended -> "ended"
    }

    private companion object {
        const val TAG = "TerminalAttachment"
        const val NO_FREE_SLOT = "too many terminals open"
        const val HISTORY_TIMEOUT_MS = 7_000L

        /** How long the attach waits for a grid before going without one. Two frames. */
        const val ATTACH_WITHOUT_GRID_MS = 32L
    }
}
