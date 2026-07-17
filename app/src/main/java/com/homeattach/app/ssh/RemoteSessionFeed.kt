package com.homeattach.app.ssh

import com.homeattach.app.data.HostConfig
import com.homeattach.app.data.SettingsStore
import com.jcraft.jsch.ChannelExec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

/** The host's live session list, as the app last managed to see it. */
sealed interface SessionsSnapshot {
    /** No answer from the host yet. */
    data object Loading : SessionsSnapshot

    data class Live(val sessions: List<RemoteSession>) : SessionsSnapshot

    /** The feed has never produced a list — there is an error to show instead of data. */
    data class Failed(val message: String) : SessionsSnapshot
}

/**
 * The host's session list as one process-wide feed.
 *
 * Both the list screen and the terminal's drawer want exactly this list, and each used to run its
 * own SSH connection and its own `tsess-watch` for it. That did not merely duplicate work, it
 * leaked: cancelling a coroutine cannot interrupt a blocked socket read, so the reader survived
 * its collector and every trip into a terminal and back stranded another connection and another
 * watch process on the host, without bound.
 *
 * One feed fixes both. `WhileSubscribed` tears the channel down once the last collector leaves,
 * and [awaitClose] closing the channel is what actually unblocks the reader — the thing the old
 * code had no way to express.
 */
object RemoteSessionFeed {
    // Process-scoped on purpose: the feed must survive the gap between one screen leaving and the
    // next arriving, or navigating would restart tsess-watch on every hop.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val retryRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Volatile
    private var shared: StateFlow<SessionsSnapshot>? = null

    /** The shared feed, started on first collect and stopped [LINGER_MS] after the last one. */
    @Synchronized
    fun sessions(settingsStore: SettingsStore): StateFlow<SessionsSnapshot> =
        shared ?: feed(settingsStore)
            .flowOn(Dispatchers.IO)
            .stateIn(scope, SharingStarted.WhileSubscribed(LINGER_MS), SessionsSnapshot.Loading)
            .also { shared = it }

    /** Skip the backoff (pull-to-refresh, or the app coming back to the foreground). */
    fun retryNow() {
        retryRequests.tryEmit(Unit)
    }

    private fun feed(settingsStore: SettingsStore): Flow<SessionsSnapshot> = flow {
        var everLive = false
        while (true) {
            try {
                watchStream(settingsStore.load()).collect { list ->
                    everLive = true
                    emit(SessionsSnapshot.Live(list))
                }
                // Clean EOF: the host closed the feed. Re-establish.
            } catch (e: Exception) {
                // Losing the feed must never blank a list the user is reading: keep the last good
                // one on screen and retry quietly. Only a feed that never produced anything has an
                // error worth showing in place of data.
                if (!everLive) emit(SessionsSnapshot.Failed(describe(e)))
            }
            awaitRetry()
        }
    }

    /**
     * One run of `tsess-watch`: the host pushes the full list on start, the instant a session is
     * born or dies (inotify), and every ~5s as a heartbeat.
     */
    private fun watchStream(config: HostConfig): Flow<List<RemoteSession>> = callbackFlow {
        val session = SharedSshSession.acquire(config)
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(SESSION_WATCH_COMMAND)
        val input = channel.inputStream
        channel.connect(CONNECT_TIMEOUT_MS)

        val reader = thread(name = "remote-session-feed", isDaemon = true) {
            try {
                var block: MutableList<RemoteSession>? = null
                var sawAnyBlock = false
                input.bufferedReader(StandardCharsets.UTF_8).forEachLine { line ->
                    when {
                        line == "#BEGIN" -> block = mutableListOf()
                        line == "#END" -> {
                            block?.let {
                                sawAnyBlock = true
                                // Ordered here, once, so the list screen and the terminal's
                                // drawer can never disagree about what order sessions are in.
                                // Newest first: the host emits them in socket-glob order, which
                                // is hash order - stable, but meaning nothing to a human, and a
                                // new session lands in the middle of the list at random.
                                trySend(it.sortedByDescending { s -> s.bornEpochSeconds })
                            }
                            block = null
                        }
                        else -> block?.let { open -> parseSessionLine(line)?.let(open::add) }
                    }
                }
                if (sawAnyBlock) {
                    close()
                } else {
                    // Immediate EOF without a single block means tsess-watch is missing or broke
                    // on the host - surface it rather than spinning on a feed that cannot work.
                    close(SshConnectException("tsess-watch produced no output (exit=${channel.exitStatus})"))
                }
            } catch (e: Exception) {
                close(e)
            }
        }

        awaitClose {
            // The reader is parked in a blocking read that ignores both cancellation and
            // interrupt; closing the channel under it is the only thing that wakes it up. Skip
            // this and the thread — and its tsess-watch on the host — outlive us forever.
            runCatching { channel.disconnect() }
            reader.interrupt()
        }
    }

    /** Waits out the reconnect backoff, cut short by [retryNow]. */
    private suspend fun awaitRetry() {
        withTimeoutOrNull(RETRY_DELAY_MS) { retryRequests.first() }
    }

    private fun describe(e: Exception): String = e.message ?: e::class.simpleName ?: "unknown error"

    private const val LINGER_MS = 5_000L
    private const val RETRY_DELAY_MS = 2_000L
}
