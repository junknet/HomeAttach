package com.homeattach.app.terminal

import android.content.Context
import android.util.Log
import com.homeattach.app.BuildConfig
import com.homeattach.app.data.HostConfig
import com.homeattach.app.ssh.MuxSessionListener
import com.homeattach.app.ssh.MuxState
import com.homeattach.app.ssh.RemoteTerminalSize
import com.homeattach.app.ssh.TerminalMux
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
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
        override fun onReady() = handleReady()
        override fun onOutput(data: ByteArray) = handleOutput(data)
        override fun onEnded(reason: String) = handleEnded()
        override fun onError(message: String) = handleError(message)
        override fun onTransportState(state: MuxState) = handleTransportState(state)
    }

    init {
        val registered = TerminalMux.register(config, sessionName, muxListener)
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
        terminal.finish()
    }

    // ---------- driven by the mux reader thread ----------

    private fun handleReady() {
        if (released.get()) return
        moveTo(AttachStatus.Connected)
        // A re-attach lands with the host's idea of the size, so the visible terminal has to say
        // again that the grid is the phone's.
        claimFocus()
    }

    private fun handleOutput(data: ByteArray) {
        if (released.get()) return
        terminal.appendRemoteOutput(data, 0, data.size)
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
    }
}
