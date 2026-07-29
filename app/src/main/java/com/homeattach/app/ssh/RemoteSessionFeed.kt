package com.homeattach.app.ssh

import com.homeattach.app.data.HostConfig
import com.homeattach.app.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The host's live session list, as the app last managed to see it. */
sealed interface SessionsSnapshot {
    /** No answer from the host yet. */
    data object Loading : SessionsSnapshot

    data class Live(val sessions: List<RemoteSession>) : SessionsSnapshot

    /** The feed has never produced a list — there is an error to show instead of data. */
    data class Failed(val message: String) : SessionsSnapshot
}

/**
 * The host's own state — which sessions exist, and which of them are producing output — as one
 * process-wide feed riding the shared mux channel.
 *
 * It reads the connection slot of [TerminalMux] rather than running a channel of its own. That is
 * the whole point: the list screen, the terminal's drawer and the attached terminals are then one
 * connection, one reconnect loop and one clock. When this was a separate `tsess-watch` channel, an
 * attached session's activity came from its arriving bytes while every other session's came from a
 * five-second list poll, so no two lamps on screen could agree about the same host.
 */
object RemoteSessionFeed {
    // Process-scoped on purpose: the feed must survive the gap between one screen leaving and the
    // next arriving, or navigating would drop the subscription on every hop.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val retryRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Volatile
    private var shared: StateFlow<SessionsSnapshot>? = null

    private val _activity = MutableStateFlow<Map<String, Long>>(emptyMap())

    /**
     * Session name to a counter that increases every time that session emitted output. A counter
     * rather than a timestamp because the UI wants "it moved again", and a repeated timestamp
     * cannot say that; how long a lamp stays lit for one tick is the screen's business.
     */
    val activity: StateFlow<Map<String, Long>> = _activity.asStateFlow()

    /** The shared feed, started on first collect and stopped [LINGER_MS] after the last one. */
    @Synchronized
    fun sessions(settingsStore: SettingsStore): StateFlow<SessionsSnapshot> =
        shared ?: feed(settingsStore)
            .stateIn(scope, SharingStarted.WhileSubscribed(LINGER_MS), SessionsSnapshot.Loading)
            .also { shared = it }

    /** Skip the backoff (pull-to-refresh, or the app coming back to the foreground). */
    fun retryNow() {
        // Both: the request re-reads settings for whoever is collecting, and the direct call still
        // cuts the backoff when nothing is.
        retryRequests.tryEmit(Unit)
        TerminalMux.retryNow()
    }

    private fun feed(settingsStore: SettingsStore): Flow<SessionsSnapshot> = callbackFlow {
        var everLive = false
        val listener = object : MuxHostListener {
            override fun onSessions(tsv: String) {
                val sessions = parseSessionList(tsv)
                everLive = true
                pruneActivity(sessions)
                trySend(SessionsSnapshot.Live(sessions))
            }

            override fun onActivity(sessionNames: List<String>) = bumpActivity(sessionNames)

            override fun onControlError(message: String) {
                // The host answered but could not describe itself. A list already on screen is
                // stale, not wrong, and blanking it would cost the user more than the staleness.
                if (!everLive) trySend(SessionsSnapshot.Failed(message))
            }

            override fun onTransportState(state: MuxState) {
                // Reconnects are the mux's business and it retries on its own; only a state
                // nothing can revive from replaces a list the user is reading.
                if (state is MuxState.Failed && !everLive) {
                    trySend(SessionsSnapshot.Failed(state.cause))
                }
            }
        }

        var subscribedWith = settingsStore.load()
        TerminalMux.subscribeHost(subscribedWith, listener)

        val hostTooOld = launch {
            // A channel that is up and still has not said a word about the host is a PC running a
            // `tsess-mux` from before the session list moved onto this channel. Nothing will ever
            // arrive, and an empty list forever is the least useful way to say so.
            while (!everLive) {
                delay(HOST_SILENCE_TIMEOUT_MS)
                if (everLive) break
                if (TerminalMux.transportState() is MuxState.Connected) {
                    trySend(SessionsSnapshot.Failed(HOST_TOO_OLD))
                    break
                }
            }
        }

        val retries = launch {
            retryRequests.collect {
                // Settings can have changed the host under us since we subscribed; re-subscribing
                // with the new one is what makes "pull to refresh" mean the machine the user just
                // typed in rather than the one they left.
                val latest = settingsStore.load()
                if (latest != subscribedWith) {
                    TerminalMux.unsubscribeHost(listener)
                    subscribedWith = latest
                    TerminalMux.subscribeHost(latest, listener)
                }
                TerminalMux.retryNow()
            }
        }

        awaitClose {
            retries.cancel()
            hostTooOld.cancel()
            TerminalMux.unsubscribeHost(listener)
        }
    }

    private fun bumpActivity(sessionNames: List<String>) {
        _activity.update { current ->
            val next = HashMap(current)
            for (name in sessionNames) next[name] = (next[name] ?: 0L) + 1L
            next
        }
    }

    /** Sessions that no longer exist must not keep a counter alive for a name that may come back. */
    private fun pruneActivity(sessions: List<RemoteSession>) {
        val live = sessions.mapTo(HashSet()) { it.name }
        _activity.update { current ->
            if (current.keys.all { it in live }) current else current.filterKeys { it in live }
        }
    }

    private const val LINGER_MS = 5_000L

    /** Generous: the host sends its first list within a tick, so this only ever expires on a
     * host that is never going to send one. */
    private const val HOST_SILENCE_TIMEOUT_MS = 8_000L

    private const val HOST_TOO_OLD =
        "The PC answered but never sent its session list. Its HomeAttach scripts are older than " +
            "this app - run server/install.sh there."
}

/** One `tsess-list` TSV block, in the order the screens show it. */
internal fun parseSessionList(tsv: String): List<RemoteSession> =
    tsv.lineSequence()
        .mapNotNull { parseSessionLine(it) }
        .toList()
        .sortedForDisplay()

/** Groups sessions by working directory, then by the process running in that directory. */
internal fun List<RemoteSession>.sortedForDisplay(): List<RemoteSession> = sortedWith(
    compareBy<RemoteSession, String>(String.CASE_INSENSITIVE_ORDER, RemoteSession::cwd)
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.command }
        // A process can have multiple sessions in the same directory. Keep those stable too.
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
)
