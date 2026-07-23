package com.homeattach.app.terminal

import android.content.Context
import android.os.Build
import android.util.Log
import com.homeattach.app.data.HostConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The process's one attached terminal.
 *
 * Process-scoped rather than remembered in the composition, because the whole problem being solved
 * is that an attachment must outlive the Activity: leaving the app for ten seconds must not cost a
 * reconnect and a wiped scrollback. [TerminalService] runs for exactly as long as this slot is
 * filled — that foreground service is the only thing that stops Android from freezing the reader
 * thread and dropping the socket while the user is somewhere else.
 *
 * One slot, not a registry: the UI shows one terminal at a time and Back detaches, so a second
 * live attachment would have no consumer.
 */
object AttachedTerminal {
    private val _current = MutableStateFlow<TerminalAttachment?>(null)
    val current: StateFlow<TerminalAttachment?> = _current.asStateFlow()

    /**
     * The attachment for [sessionName], reusing the live one when it is already that session —
     * re-entering a terminal that is still attached must not cost a reconnect. Opening a different
     * session releases the previous one first.
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
        _current.value?.let { live ->
            if (live.sessionName == sessionName) return live
            live.release()
        }
        val attachment = TerminalAttachment(sessionName, sessionLabel, config, androidOwnerIdentifier(), context.applicationContext)
        _current.value = attachment
        TerminalService.start(context.applicationContext)
        return attachment
    }

    /** Detach for good: ends the SSH channel, releases pty focus, and stops the service. */
    @Synchronized
    fun close(context: Context) {
        val live = _current.value ?: return
        _current.value = null
        live.release()
        TerminalService.stop(context.applicationContext)
    }

    /**
     * Identifies this phone to the host's focus bookkeeping (`tsess-focus <name> <owner> …`), so
     * the session list can say which device is looking at a session.
     */
    private fun androidOwnerIdentifier(): String {
        val manufacturer = Build.MANUFACTURER.ifBlank { "android" }
        val model = Build.MODEL.ifBlank { "device" }
        return "android:$manufacturer-$model"
    }

    internal fun logServiceFailure(message: String, e: Exception) {
        Log.e("AttachedTerminal", message, e)
    }
}
