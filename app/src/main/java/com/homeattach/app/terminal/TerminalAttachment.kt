package com.homeattach.app.terminal

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.homeattach.app.BuildConfig
import com.homeattach.app.data.HostConfig
import com.homeattach.app.ssh.MuxReady
import com.homeattach.app.ssh.MuxResume
import com.homeattach.app.ssh.MuxSessionListener
import com.homeattach.app.ssh.MuxState
import com.homeattach.app.ssh.RemoteTerminalSize
import com.homeattach.app.ssh.TerminalMux
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
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

/**
 * One attached remote session: the terminal emulator, plus a slot on the shared mux channel.
 *
 * The emulator is built once here and never rebuilt, which is the point: a dropped transport costs
 * a blank moment, not the session's scrollback. zmx re-hydrates the visible screen from its tracked
 * terminal state on every re-attach, so the terminal comes back exactly where it was.
 *
 * This owns no connection and no thread. Every session shares one channel and one reconnect loop in
 * [TerminalMux], so a radio gap costs a single handshake for all of them rather than one each, and
 * closing one session is a CLOSE frame that the others never notice.
 *
 * Owned by [AttachedTerminal] and deliberately process-scoped, never composition-scoped: on mobile
 * the transport dies constantly (Doze, network handoff, the radio dropping while the user reads a
 * message in another app), and an attachment that died with the Activity would turn every one of
 * those into a manual reconnect.
 */
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

    // Declared above [init]: Kotlin initializes properties in declaration order, and the restore
    // that init kicks off posts to this.
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Set once the host has replaced this session's picture. A restore that was still waiting for
     * first layout must not then paint the old screen over the new one.
     */
    private val screenReplaced = AtomicBoolean(false)

    /** This session's tail on disk, and the cursor into the host's stream that it ends at. */
    private val store = SessionStreamStore(File(context.applicationContext.filesDir, STORE_DIR))
    private val saved = store.load(sessionName)

    /**
     * Where this attachment is in the host's byte stream. Advanced by every OUTPUT byte, which is
     * exactly how the host counts, and saved with the bytes so the next process can carry on.
     */
    private val cursor = AtomicLong(saved?.offset ?: 0L)
    private val epoch = AtomicLong(saved?.epoch ?: 0L)

    /** Bytes still to arrive that [cursor] already counts; see [MuxReady.replayBytes]. */
    private val replayRemaining = AtomicLong(0)

    val terminal = RemoteTerminalSession(
        context = context.applicationContext,
        onInput = { bytes -> slot.get()?.let { TerminalMux.sendInput(it, bytes) } },
        onResize = { columns, rows ->
            val size = RemoteTerminalSize(columns, rows)
            val previous = measuredSize.getAndSet(size)
            // Only the visible terminal drives the remote size — a mirror attach never owns it, so
            // for a backgrounded session there is nothing to send. And only on a real change: the
            // IME opening and closing re-measures the grid constantly, and re-claiming on every one
            // would put a frame and a host-side resize behind each keyboard flap.
            if (foreground.get() && previous != size) claimFocus()
        },
    ).apply {
        onFirstOutput = { _hasOutput.value = true }
    }

    /**
     * The mux side of this attachment, kept as a private member rather than implemented by the
     * class itself: the wire protocol is an implementation detail, and letting it onto this type's
     * public surface would make every caller of [TerminalAttachment] a caller of the frame layer.
     */
    private val muxListener = object : MuxSessionListener {
        override fun onReady(ready: MuxReady) = handleReady(ready)
        override fun onOutput(data: ByteArray) = handleOutput(data)
        override fun onEnded(reason: String) = handleEnded()
        override fun onError(message: String) = handleError(message)
        override fun onTransportState(state: MuxState) = handleTransportState(state)
    }

    init {
        // The saved screen goes up before anything is asked of the host: it is already on this
        // phone, so there is nothing to wait for, and it is what makes reopening the app feel like
        // returning to a terminal rather than loading one.
        restoreSavedScreen()
        val registered = TerminalMux.register(
            config,
            sessionName,
            muxListener,
            MuxResume(epoch = epoch.get(), offset = cursor.get()),
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
        if (value) claimFocus()
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
        store.close()
        terminal.finish()
    }

    // ---------- driven by the mux reader thread ----------

    private fun handleReady(ready: MuxReady) {
        if (released.get()) return

        epoch.set(ready.epoch)
        cursor.set(ready.offset)
        replayRemaining.set(ready.replayBytes)
        if (!ready.continued) {
            // The host started this session's picture over: what was restored from disk describes a
            // screen that no longer exists, and leaving it up would put stale content above the
            // content that replaced it.
            screenReplaced.set(true)
            mainHandler.post { terminal.resetScreen() }
            store.reset(sessionName, ready.epoch, ready.offset)
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
        if (released.get()) return
        // Saved before it is shown, and the cursor moved with it: these bytes are the stream, and
        // the count has to match the host's byte for byte or a later resume splices a hole into the
        // terminal. Runs on the mux reader thread, off the main thread's path.
        //
        // Replay is saved but not counted - it is content that rebuilds the screen, at a position
        // the host already told us about.
        val replay = replayRemaining.get()
        val counted = if (replay <= 0) data.size.toLong() else {
            val skipped = minOf(replay, data.size.toLong())
            replayRemaining.addAndGet(-skipped)
            data.size - skipped
        }
        val at = if (counted > 0) cursor.addAndGet(counted) else cursor.get()
        val live = epoch.get()
        if (live != 0L) store.append(sessionName, live, at, data)
        // The slot carries the cursor so a reconnect asks to continue from where the terminal
        // actually is, not from where this attachment started.
        slot.get()?.resume = MuxResume(epoch = live, offset = at)
        terminal.appendRemoteOutput(data, 0, data.size)
    }

    /**
     * Paints what this phone last saw, before the host has said anything.
     *
     * Retried until the view has laid out and the emulator exists — the same wait the live drain
     * does — because an attachment is built before the terminal is measured.
     */
    private fun restoreSavedScreen() {
        val bytes = saved?.bytes ?: return
        mainHandler.post(object : Runnable {
            override fun run() {
                if (released.get() || screenReplaced.get()) return
                if (!terminal.replaySaved(bytes)) {
                    mainHandler.postDelayed(this, RESTORE_RETRY_MS)
                }
            }
        })
    }

    private fun handleEnded() {
        if (released.get()) return
        // The session is gone for good, so its saved tail describes a terminal that no longer
        // exists. Keeping it would restore a dead screen if the host ever reuses the name.
        store.clear(sessionName)
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
        const val STORE_DIR = "session-stream"
        const val RESTORE_RETRY_MS = 16L
    }
}
