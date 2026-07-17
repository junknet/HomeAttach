package com.homeattach.app.terminal

import android.util.Log
import com.homeattach.app.BuildConfig
import com.homeattach.app.data.HostConfig
import com.homeattach.app.ssh.RemoteTerminalSize
import com.homeattach.app.ssh.SshAuthException
import com.homeattach.app.ssh.TerminalAttachRequest
import com.homeattach.app.ssh.TerminalConnection
import com.homeattach.app.ssh.fetchRemoteSessions
import com.homeattach.app.ssh.releaseRemoteSessionFocus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** What [TerminalAttachment]'s reconnect loop is doing right now. */
sealed interface AttachStatus {
    /** Attaching, with nothing drawn yet — the screen shows a blocking spinner. */
    data object Connecting : AttachStatus

    data object Connected : AttachStatus

    /**
     * The transport dropped and the loop is retrying by itself. The emulator keeps every row it
     * had, so once anything has been drawn the screen stays readable behind a banner rather than
     * blanking back to a spinner.
     */
    data class Reconnecting(val attempt: Int, val message: String) : AttachStatus

    /** Retrying cannot help: the host rejected our credentials. */
    data class Failed(val message: String) : AttachStatus

    /** The session ended on the PC — its terminal tab was closed, or it was killed. */
    data object Ended : AttachStatus
}

/**
 * One live attachment to a remote session: the terminal emulator plus the SSH channel feeding it,
 * kept alive by a self-driving reconnect loop.
 *
 * The emulator is built once here and never rebuilt, which is the whole point: a dropped transport
 * costs a blank moment, not the session's scrollback. zmx re-hydrates the visible screen from its
 * tracked terminal state on every re-attach, so the user sees the terminal come back exactly where
 * it was.
 *
 * Owned by [AttachedTerminal] and deliberately process-scoped, never composition-scoped: on mobile
 * the transport dies constantly (Doze, network handoff, the radio dropping while the user reads a
 * message in another app), and an attachment that died with the Activity would turn every one of
 * those into a manual reconnect.
 */
class TerminalAttachment(
    val sessionName: String,
    val sessionLabel: String,
    private val config: HostConfig,
    private val ownerIdentifier: String,
) {
    private val _status = MutableStateFlow<AttachStatus>(AttachStatus.Connecting)
    val status: StateFlow<AttachStatus> = _status.asStateFlow()

    /**
     * Every state change funnels through here so the loop's whole life is one greppable trace.
     * Without it the interesting property — that backgrounding the app costs *no* transitions —
     * is invisible: a silent success and a silent reconnect look identical from outside.
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

    /**
     * Latches true the first time the emulator processes any remote output, and never clears. The
     * screen keys its blocking spinner off this rather than off [status], so reconnects repaint a
     * banner over a live terminal instead of hiding it.
     */
    private val _hasOutput = MutableStateFlow(false)
    val hasOutput: StateFlow<Boolean> = _hasOutput.asStateFlow()

    private val released = AtomicBoolean(false)
    private val connection = AtomicReference<TerminalConnection?>(null)
    private val measuredSize = AtomicReference<RemoteTerminalSize?>(null)
    private val sentSize = AtomicReference<RemoteTerminalSize?>(null)

    /** Wakes the loop out of its backoff sleep early (app resumed, user tapped retry). */
    private val retryGate = Object()

    val terminal = RemoteTerminalSession(
        onInput = { bytes -> connection.get()?.send(bytes) },
        onResize = { columns, rows ->
            val size = RemoteTerminalSize(columns, rows)
            measuredSize.set(size)
            // Before the first attach the measured size rides in the attach request itself; after
            // it, every real grid change is a WINCH on the live channel.
            val live = connection.get()
            if (live != null && sentSize.getAndSet(size) != size) {
                live.resizePty(columns, rows)
            }
        },
    ).apply {
        onFirstOutput = { _hasOutput.value = true }
    }

    private val worker = thread(name = "terminal-attachment-$sessionName", isDaemon = true) {
        runReconnectLoop()
    }

    /**
     * Re-claim pty size ownership for this phone, because the terminal view just took focus. The
     * PC takes the size back the moment someone types there, so this has to be re-asserted rather
     * than assumed. No-op while nothing is attached: the next attach carries the claim itself.
     */
    fun claimFocus() {
        val live = connection.get() ?: return
        val size = measuredSize.get() ?: return
        live.focusPty(size.columns, size.rows)
    }

    /** Retry now instead of waiting out the backoff. The screen calls this on resume: the radio is
     * usually back the instant the user returns, and making them watch an 8s timer would waste the
     * one moment they are actually looking. */
    fun retryNow() {
        synchronized(retryGate) { retryGate.notifyAll() }
    }

    /** Tear down for good. Releases pty size ownership so the PC's tab is not left at phone size. */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        connection.getAndSet(null)?.close()
        retryNow()
        worker.interrupt()
        terminal.onScreenUpdated = {}
        terminal.onFirstOutput = {}
        terminal.finish()
        releaseFocusInBackground()
    }

    private fun runReconnectLoop() {
        var attempt = 0
        while (!released.get()) {
            val size = awaitMeasuredSize() ?: return
            val request = TerminalAttachRequest(sessionName, ownerIdentifier, size)
            val failure: Exception? = try {
                openAndPump(request, size)
                null
            } catch (e: SshAuthException) {
                // A bad key will still be a bad key on the tenth try; make the user fix it.
                moveTo(AttachStatus.Failed(describe(e)))
                return
            } catch (e: Exception) {
                e
            }
            if (released.get()) return

            // Only openAndPump sets Connected, and nothing clears it, so the status still standing
            // here says whether this round got a working channel before it ended.
            val hadConnected = _status.value is AttachStatus.Connected

            // Clean EOF and a dead transport look identical from here — both just end the read
            // loop. Only the host can tell them apart, and it is the authority on whether the
            // session still exists, so ask it rather than guessing from the local socket state.
            if (sessionStillOnHost() == false) {
                moveTo(AttachStatus.Ended)
                return
            }
            // A drop after a good connection starts its backoff over: the common case is a brief
            // radio gap, and it should recover in half a second, not inherit an old attempt count.
            attempt = if (hadConnected) 1 else attempt + 1
            moveTo(AttachStatus.Reconnecting(attempt, failure?.let(::describe) ?: DROPPED_BY_HOST))
            if (!sleepBackoff(attempt)) return
        }
    }

    /**
     * Attaches, marks [AttachStatus.Connected], and pumps remote output into the emulator until the
     * stream ends. Returns normally on EOF; throws on transport failure.
     */
    private fun openAndPump(request: TerminalAttachRequest, size: RemoteTerminalSize) {
        val conn = TerminalConnection.attach(config, request)
        if (released.get()) {
            conn.close()
            return
        }
        connection.set(conn)
        sentSize.set(size)
        // Insets may still have been animating when the size was measured: reconcile once so the
        // remote pty matches the grid the user actually sees.
        measuredSize.get()?.takeIf { it != size }?.let { settled ->
            sentSize.set(settled)
            conn.resizePty(settled.columns, settled.rows)
        }
        moveTo(AttachStatus.Connected)
        try {
            val buffer = ByteArray(READ_BUFFER_BYTES)
            while (!conn.closed) {
                val read = try {
                    conn.input.read(buffer)
                } catch (e: Exception) {
                    if (conn.closed) break else throw e
                }
                if (read < 0) break
                terminal.appendRemoteOutput(buffer, 0, read)
            }
        } finally {
            connection.compareAndSet(conn, null)
            conn.close()
        }
    }

    /**
     * Whether the host still lists this session. Null means the host itself was unreachable, which
     * says nothing about the session — that is a transport problem and the loop should keep trying.
     */
    private fun sessionStillOnHost(): Boolean? =
        runCatching { fetchRemoteSessions(config).any { it.name == sessionName } }.getOrNull()

    private fun awaitMeasuredSize(): RemoteTerminalSize? {
        var size = measuredSize.get()
        while (size == null) {
            if (released.get()) return null
            try {
                Thread.sleep(LAYOUT_POLL_MS)
            } catch (e: InterruptedException) {
                return null
            }
            size = measuredSize.get()
        }
        return size
    }

    /** Waits out the backoff for [attempt]. False means stop looping — released, or interrupted. */
    private fun sleepBackoff(attempt: Int): Boolean {
        val waitMs = BACKOFF_MS[(attempt - 1).coerceIn(BACKOFF_MS.indices)]
        synchronized(retryGate) {
            try {
                retryGate.wait(waitMs)
            } catch (e: InterruptedException) {
                return false
            }
        }
        return !released.get()
    }

    private fun releaseFocusInBackground() {
        thread(name = "terminal-release-focus-$sessionName", isDaemon = true) {
            runCatching { releaseRemoteSessionFocus(config, sessionName, ownerIdentifier) }
        }
    }

    private fun describe(e: Exception): String = e.message ?: e::class.simpleName ?: DROPPED_BY_HOST

    private companion object {
        const val TAG = "TerminalAttachment"
        const val READ_BUFFER_BYTES = 8192
        const val LAYOUT_POLL_MS = 16L
        const val DROPPED_BY_HOST = "connection dropped"

        /** Fast enough that a radio gap is invisible, capped low enough that a host that is off
         * for an hour costs a probe every 8s rather than a spin. */
        val BACKOFF_MS = longArrayOf(500, 1_000, 2_000, 4_000, 8_000)
    }
}
