package com.homeattach.app.terminal

import android.content.Context
import android.util.Log
import com.homeattach.app.data.HostConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The process's pool of attached terminals.
 *
 * Process-scoped rather than remembered in the composition, because the whole problem being solved
 * is that an attachment must outlive the Activity: leaving the app for ten seconds must not cost a
 * reconnect and a wiped scrollback. [TerminalService] runs for exactly as long as this pool is
 * non-empty — that foreground service is the only thing that stops Android from freezing the reader
 * threads and dropping the socket while the user is somewhere else.
 *
 * A pool rather than one slot, because switching sessions is the operation this exists for. Only
 * one attachment is on screen at a time, but the ones behind it keep their channel, their emulator
 * and their scrollback, so returning to a session is a state flip instead of a handshake followed
 * by a full screen restore. Exactly one member is [current]; see [TerminalAttachment.setForeground]
 * for why that distinction has to reach the host.
 */
object AttachedTerminal {
    /**
     * How many attachments stay live at once. Each one costs a background SSH channel, a reader
     * thread and a 10k-row transcript, so the pool is capped rather than unbounded — past a handful
     * of sessions the user is not switching between them fast enough for the cache to pay for
     * itself.
     */
    private const val MAX_CACHED_ATTACHMENTS = 5

    private val _current = MutableStateFlow<TerminalAttachment?>(null)
    val current: StateFlow<TerminalAttachment?> = _current.asStateFlow()

    /** Live attachments in access order, least recently opened first. Guarded by this object. */
    private val attachments =
        LinkedHashMap<String, TerminalAttachment>(MAX_CACHED_ATTACHMENTS, 0.75f, true)

    /**
     * The attachment for [sessionName], reusing the pooled one when it can still serve. Whatever is
     * returned becomes [current], which is what grants it the right to own the remote pty size.
     *
     * Must be called from a foreground context: it starts a foreground service.
     */
    @Synchronized
    fun open(
        context: Context,
        sessionName: String,
        sessionLabel: String,
        config: HostConfig,
    ): TerminalAttachment {
        val attachment = reusable(sessionName, config)
            ?: admit(sessionName, sessionLabel, config, context)
        makeCurrent(attachment)
        attachment.retryNow()
        TerminalService.start(context.applicationContext)
        return attachment
    }

    /**
     * The pooled attachment for [sessionName] if it is still worth handing back.
     *
     * Two things disqualify one. A loop that reached [AttachStatus.Failed] or [AttachStatus.Ended]
     * has returned for good — its thread is gone and [TerminalAttachment.retryNow] has nothing left
     * to wake — so reusing it would hand back a terminal that can never reconnect. And an
     * attachment built against a different [HostConfig] is pointed at a host the user has since
     * changed away from in Settings; it would keep reconnecting to the old one.
     */
    private fun reusable(sessionName: String, config: HostConfig): TerminalAttachment? {
        val cached = attachments[sessionName] ?: return null
        val stillServing = when (cached.status.value) {
            is AttachStatus.Failed, is AttachStatus.Ended -> false
            else -> cached.config == config
        }
        if (stillServing) return cached
        attachments.remove(sessionName)
        cached.release()
        return null
    }

    /** Makes room, then builds and pools a fresh attachment for [sessionName]. */
    private fun admit(
        sessionName: String,
        sessionLabel: String,
        config: HostConfig,
        context: Context,
    ): TerminalAttachment {
        val onScreen = _current.value?.sessionName
        for (evicted in sessionsToEvict(attachments.keys.toList(), sessionName, onScreen, MAX_CACHED_ATTACHMENTS)) {
            attachments.remove(evicted)?.release()
        }
        return TerminalAttachment(
            sessionName = sessionName,
            sessionLabel = sessionLabel,
            config = config,
            context = context.applicationContext,
        ).also { attachments[sessionName] = it }
    }

    /**
     * Hands the foreground over to [next]. The handover is explicit in both directions because
     * `tsess-focus` is exclusive per session on the host: the outgoing attachment has to stop
     * claiming size ownership on reconnect before the incoming one starts, or a session the user
     * cannot see keeps resizing the one they are looking at.
     */
    private fun makeCurrent(next: TerminalAttachment) {
        val previous = _current.value
        if (previous !== next) previous?.setForeground(false)
        // Not conditional on the swap: [closeSession] can leave a survivor as [current] without
        // putting it on screen, so re-opening it is a no-op here yet still has to take the
        // foreground. [TerminalAttachment.setForeground] absorbs the repeat.
        next.setForeground(true)
        _current.value = next
    }

    /**
     * Detach one session for good. The rest of the pool keeps running: leaving a terminal is the
     * usual way to reach another one, and tearing the whole pool down on the way out is what would
     * make the next switch pay for a handshake again.
     */
    @Synchronized
    fun closeSession(context: Context, sessionName: String) {
        val closed = attachments.remove(sessionName) ?: return
        closed.release()
        if (_current.value === closed) {
            // Access-ordered, so the last value is the most recently used survivor. It becomes
            // [current] so the service notification still has something to report, but stays
            // backgrounded: nothing is on screen now, so nothing should be claiming the pty size.
            _current.value = attachments.values.lastOrNull()
        }
        if (attachments.isEmpty()) TerminalService.stop(context.applicationContext)
    }

    /** Detach everything for good: ends every SSH channel, releases focus, and stops the service. */
    @Synchronized
    fun close(context: Context) {
        _current.value = null
        for (attachment in attachments.values) {
            attachment.release()
        }
        attachments.clear()
        TerminalService.stop(context.applicationContext)
    }

    internal fun logServiceFailure(message: String, e: Exception) {
        Log.e("AttachedTerminal", message, e)
    }
}

/**
 * Which pooled sessions must go so that admitting [incoming] leaves at most [maxSize] attachments.
 * [lruOldestFirst] is the pool's key order, least recently used first.
 *
 * [onScreen] is never evicted: a screen is holding that attachment and releasing it would blank a
 * live terminal. Neither is [incoming] — it is about to be replaced anyway, and releasing it here
 * would drop the very connection the caller is asking to reuse.
 */
internal fun sessionsToEvict(
    lruOldestFirst: List<String>,
    incoming: String,
    onScreen: String?,
    maxSize: Int,
): List<String> {
    // Admitting a session already in the pool replaces it rather than growing it.
    val admittedSize = lruOldestFirst.size + if (incoming in lruOldestFirst) 0 else 1
    val overflow = admittedSize - maxSize
    if (overflow <= 0) return emptyList()
    return lruOldestFirst.asSequence()
        .filter { it != incoming && it != onScreen }
        .take(overflow)
        .toList()
}
