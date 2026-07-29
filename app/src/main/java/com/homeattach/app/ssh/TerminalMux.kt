package com.homeattach.app.ssh

import android.util.Log
import com.homeattach.app.BuildConfig
import com.homeattach.app.data.HostConfig
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** What the shared mux channel is doing. Session state is layered on top of this by each slot. */
internal sealed interface MuxState {
    data object Connecting : MuxState
    data object Connected : MuxState
    data class Reconnecting(val attempt: Int, val cause: String) : MuxState
    data class Failed(val cause: String, val hostSetup: Boolean) : MuxState
}

/**
 * How a subscriber hears about the host itself rather than about one session.
 *
 * The session list and the activity ticks arrive on the same channel as terminal output, so a
 * screen showing lamps and a screen showing a terminal are reading one connection, one reconnect
 * loop and one clock. Two channels is what used to let an attached session's lamp and its
 * neighbour's disagree about what was happening on the same host.
 */
internal interface MuxHostListener {
    /** The host's session list, verbatim `tsess-list` TSV. */
    fun onSessions(tsv: String)

    /** Sessions that produced output since the previous tick — attached or not. */
    fun onActivity(sessionNames: List<String>)

    /** The host could not answer for itself; the list on screen is stale, not empty. */
    fun onControlError(message: String)

    fun onTransportState(state: MuxState)
}

/** How one registered session hears about its own frames and about the channel underneath it. */
internal interface MuxSessionListener {
    /**
     * The host attached this session; output is about to follow. [ready] says whether that output
     * continues the screen this session already has or replaces it, and where its cursor now is.
     */
    fun onReady(ready: MuxReady)
    fun onOutput(data: ByteArray)

    /** The session is gone on the host — its tab was closed or it was killed. Terminal. */
    fun onEnded(reason: String)

    /** The host rejected a frame for this slot. Not terminal; the session stays registered. */
    fun onError(message: String)

    /** The channel carrying every session changed state. */
    fun onTransportState(state: MuxState)
}

/**
 * The process's single multiplexed terminal channel.
 *
 * Every attached session is a slot inside one `tsess-mux` invocation rather than an SSH channel of
 * its own. Two things follow, and they are the whole reason for the design:
 *
 *  * opening and closing a terminal is a frame, so closing one cannot disturb the others;
 *  * there is one reconnect loop instead of one per session, so a radio gap costs a single
 *    handshake and every terminal comes back together.
 *
 * Slot numbers are assigned here because this is what knows which are live. They are safe to reuse
 * the moment a session closes: frames are ordered on the channel, so the host processes the CLOSE
 * before any OPEN that follows it.
 */
internal object TerminalMux {

    /** One registered session. Identity, not equality — the owner holds this instance. */
    internal class Slot(
        val sid: Int,
        val sessionName: String,
        val listener: MuxSessionListener,
        /** What the phone already holds of this session, re-sent on every re-open. */
        @Volatile var resume: MuxResume,
    ) {
        @Volatile
        var ready: Boolean = false
            internal set
    }

    private val lock = Any()
    private val slots = LinkedHashMap<Int, Slot>()
    private val hostListeners = LinkedHashSet<MuxHostListener>()
    private val connection = AtomicReference<MuxConnection?>(null)
    private val retryGate = Object()

    private var worker: Thread? = null
    private var hostConfig: HostConfig? = null

    @Volatile
    private var state: MuxState = MuxState.Connecting

    /**
     * Registers [sessionName] and starts the channel if this is the first session. Returns null
     * only when no slot is free, which means more terminals than the protocol's 255 can address.
     */
    fun register(
        config: HostConfig,
        sessionName: String,
        listener: MuxSessionListener,
        resume: MuxResume,
    ): Slot? {
        val slot: Slot
        val live: MuxConnection?
        synchronized(lock) {
            val sid = (1..MAX_SLOT).firstOrNull { it !in slots } ?: return null
            slot = Slot(sid, sessionName, listener, resume)
            slots[sid] = slot
            live = ensureChannel(config)
        }
        listener.onTransportState(state)
        // Already connected: this session can be opened without waiting for a reconnect.
        live?.takeIf { !it.closed }?.send(openFrame(slot))
        return slot
    }

    /** Detaches one session. The channel and every other session keep running. */
    fun unregister(slot: Slot) {
        synchronized(lock) {
            if (slots.remove(slot.sid) == null) return
        }
        connection.get()?.takeIf { !it.closed }?.send(MuxProtocol.close(slot.sid))
        stopIfUnused()
    }

    /**
     * Starts listening to the host's own state, bringing the channel up if nothing else has. The
     * session list is a subscriber like any terminal is, which is what lets the list screen run
     * with no session attached and still share one connection with the terminals.
     */
    fun subscribeHost(config: HostConfig, listener: MuxHostListener) {
        synchronized(lock) {
            hostListeners.add(listener)
            ensureChannel(config)
        }
        listener.onTransportState(state)
    }

    fun unsubscribeHost(listener: MuxHostListener) {
        synchronized(lock) {
            if (!hostListeners.remove(listener)) return
        }
        stopIfUnused()
    }

    /** Caller holds [lock]. Returns the live connection, if there already is one. */
    private fun ensureChannel(config: HostConfig): MuxConnection? {
        // A different host means the held channel is answering for somewhere else.
        val configChanged = hostConfig != null && hostConfig != config
        hostConfig = config
        if (configChanged) connection.get()?.close()

        if (worker == null) {
            state = MuxState.Connecting
            worker = thread(name = "terminal-mux", isDaemon = true) { runChannelLoop() }
        }
        return connection.get()
    }

    /** The channel exists for its subscribers; with none left there is nothing to hold open. */
    private fun stopIfUnused() {
        val stopping = synchronized(lock) { slots.isEmpty() && hostListeners.isEmpty() }
        if (stopping) shutdownChannel()
    }

    fun sendInput(slot: Slot, data: ByteArray) {
        connection.get()?.takeIf { !it.closed }?.send(MuxProtocol.input(slot.sid, data))
    }

    /**
     * Claims remote pty size ownership for [slot]. Only the session actually on screen may do this:
     * the claim is exclusive per session on the host, and every registered session stays live here.
     */
    fun claimFocus(slot: Slot, columns: Int, rows: Int) {
        if (columns <= 0 || rows <= 0) return
        connection.get()?.takeIf { !it.closed }?.send(MuxProtocol.focus(slot.sid, columns, rows))
    }

    /** What the channel is doing, for callers that have to tell "not answering" from "not up". */
    fun transportState(): MuxState = state

    /** Cuts the backoff short — the app resumed and the radio is usually back already. */
    fun retryNow() {
        synchronized(retryGate) { retryGate.notifyAll() }
    }

    private fun shutdownChannel() {
        val stale: Thread?
        synchronized(lock) {
            stale = worker
            worker = null
        }
        connection.getAndSet(null)?.close()
        retryNow()
        stale?.interrupt()
    }

    /**
     * Every open declares the cursor the session has reached, not the one it started with. A
     * reconnect mid-session must continue from where the terminal actually is, or the phone is
     * handed the whole screen again for the sake of a dropped radio.
     */
    private fun openFrame(slot: Slot): ByteArray {
        val resume = slot.resume
        return MuxProtocol.open(
            slot.sid,
            slot.sessionName,
            epoch = resume.epoch,
            offset = resume.offset,
            tailRows = resume.tailRows,
            columns = resume.columns,
            rows = resume.rows,
        )
    }

    private fun currentSlots(): List<Slot> = synchronized(lock) { slots.values.toList() }

    private fun currentHostListeners(): List<MuxHostListener> =
        synchronized(lock) { hostListeners.toList() }

    private fun isRunning(): Boolean = synchronized(lock) { worker === Thread.currentThread() }

    private fun moveTo(next: MuxState) {
        if (state == next) return
        state = next
        if (BuildConfig.DEBUG) Log.i(TAG, "mux $next")
        for (slot in currentSlots()) slot.listener.onTransportState(next)
        for (listener in currentHostListeners()) listener.onTransportState(next)
    }

    // ---------- the channel's life ----------

    private fun runChannelLoop() {
        var attempt = 0
        while (isRunning()) {
            val config = synchronized(lock) { hostConfig } ?: return
            var carriedTraffic = false
            val failure: Exception? = try {
                carriedTraffic = pumpOneConnection(config)
                null
            } catch (e: SshAuthException) {
                // A bad key stays a bad key; make the user fix it rather than spin.
                moveTo(MuxState.Failed(describe(e), hostSetup = false))
                return
            } catch (e: MuxUnavailableException) {
                // The host cannot run the mux. No amount of retrying installs it.
                moveTo(MuxState.Failed(describe(e), hostSetup = true))
                return
            } catch (e: Exception) {
                e
            }
            if (!isRunning()) return

            connection.getAndSet(null)?.close()
            for (slot in currentSlots()) slot.ready = false

            // Restarting the backoff is keyed on the channel having actually carried frames, not on
            // it having opened. A channel that opens and dies immediately — a host mid-upgrade, a
            // captive portal answering the port — would otherwise reset the count every round and
            // spin at the shortest delay forever, burning battery and never backing off.
            attempt = if (carriedTraffic) 1 else attempt + 1
            moveTo(MuxState.Reconnecting(attempt, failure?.let(::describe) ?: CHANNEL_CLOSED))
            if (!sleepBackoff(attempt)) return
        }
    }

    /**
     * Opens the channel, re-opens every registered session on it, and pumps until it ends. Returns
     * whether it ever carried a frame, which is what tells the caller a real connection existed.
     *
     * Throws [MuxUnavailableException] when the channel died without ever speaking and the host
     * explained why — the one failure that retrying cannot mend.
     */
    private fun pumpOneConnection(config: HostConfig): Boolean {
        val conn = MuxConnection.open(config)
        if (!isRunning()) {
            conn.close()
            return false
        }
        connection.set(conn)
        // Re-open everything the pool still holds. On a reconnect this is what brings all the
        // terminals back at once instead of one handshake each.
        for (slot in currentSlots()) {
            conn.send(openFrame(slot))
        }
        moveTo(MuxState.Connected)

        val reader = MuxFrameReader()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var carriedTraffic = false
        try {
            while (!conn.closed) {
                val read = try {
                    conn.input.read(buffer)
                } catch (e: Exception) {
                    if (conn.closed) break else throw e
                }
                if (read < 0) break
                if (read == 0) continue
                reader.feed(buffer, 0, read) { frame ->
                    carriedTraffic = true
                    dispatch(frame)
                }
            }
        } catch (e: MuxFramingException) {
            // The stream is no longer on a frame boundary; only a fresh channel can fix that.
            conn.invalidateTransport()
            conn.close()
            throw e
        }
        // Diagnose before closing: the exit status and stderr belong to the channel.
        if (!carriedTraffic) {
            val diagnosis = conn.diagnoseSilentExit()
            if (diagnosis != null) {
                conn.close()
                throw MuxUnavailableException(diagnosis)
            }
        }
        conn.close()
        return carriedTraffic
    }

    private fun dispatch(frame: MuxFrame) {
        if (frame.sid == MuxProtocol.CONNECTION_SLOT) {
            dispatchHostState(frame)
            return
        }
        val slot = synchronized(lock) { slots[frame.sid] }
        if (slot == null) {
            if (BuildConfig.DEBUG) Log.w(TAG, "frame for unknown slot: $frame")
            return
        }
        when (frame.type) {
            MuxProtocol.OUTPUT -> slot.listener.onOutput(frame.payload)
            MuxProtocol.READY -> {
                val ready = MuxProtocol.readReady(frame.payload)
                if (ready == null) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "malformed READY: $frame")
                    return
                }
                slot.ready = true
                slot.listener.onReady(ready)
            }
            MuxProtocol.ENDED -> {
                // The session itself is gone, so free the slot; the owner tears itself down off
                // the callback. Its number is reusable straight away.
                synchronized(lock) { slots.remove(slot.sid) }
                slot.listener.onEnded(frame.text.ifBlank { SESSION_ENDED })
            }
            MuxProtocol.ERROR -> slot.listener.onError(frame.text)
            else -> if (BuildConfig.DEBUG) Log.w(TAG, "unexpected frame: $frame")
        }
    }

    private fun dispatchHostState(frame: MuxFrame) {
        val listeners = currentHostListeners()
        when (frame.type) {
            MuxProtocol.SESSIONS -> for (listener in listeners) listener.onSessions(frame.text)
            MuxProtocol.ACTIVITY -> {
                val names = frame.text.lineSequence().filter { it.isNotBlank() }.toList()
                if (names.isNotEmpty()) for (listener in listeners) listener.onActivity(names)
            }
            MuxProtocol.ERROR -> for (listener in listeners) listener.onControlError(frame.text)
            else -> if (BuildConfig.DEBUG) Log.w(TAG, "unexpected control frame: $frame")
        }
    }

    private fun sleepBackoff(attempt: Int): Boolean {
        val waitMs = BACKOFF_MS[(attempt - 1).coerceIn(BACKOFF_MS.indices)]
        synchronized(retryGate) {
            try {
                retryGate.wait(waitMs)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return isRunning()
    }

    private fun describe(e: Exception): String = e.message ?: e::class.simpleName ?: CHANNEL_CLOSED

    private const val TAG = "TerminalMux"
    private const val MAX_SLOT = 255
    private const val READ_BUFFER_BYTES = 32 * 1024
    private const val CHANNEL_CLOSED = "connection dropped"
    private const val SESSION_ENDED = "session ended"

    /** Fast enough that a radio gap is invisible, capped so an hour-long outage costs one probe
     * every 8s rather than a spin. */
    private val BACKOFF_MS = longArrayOf(500, 1_000, 2_000, 4_000, 8_000)
}

/**
 * What the phone holds of one session: the daemon incarnation its saved bytes came from, how many
 * of them it has, and the scrollback cap to apply if the host cannot continue from there.
 *
 * [epoch] 0 means "nothing saved", which is also what an app that has never seen the session sends.
 */
internal data class MuxResume(
    val epoch: Long = 0,
    val offset: Long = 0,
    val tailRows: Int = SNAPSHOT_TAIL_ROWS,
    /**
     * The grid this session will be shown at, or 0x0 when it is not the one on screen. Sent so the
     * host can take the size before drawing: a picture made at the PC's width and painted at the
     * phone's wraps differently, which shifts every line below it and leaves the snapshot's own
     * cursor pointing at content the next output then overwrites.
     */
    val columns: Int = 0,
    val rows: Int = 0,
) {
    companion object {
        /**
         * Scrollback rows to ask for when the host has to start the picture over. The phone keeps
         * its own history; this only has to cover the screen plus a little to scroll into.
         */
        const val SNAPSHOT_TAIL_ROWS = 200

        val NOTHING = MuxResume()
    }
}
