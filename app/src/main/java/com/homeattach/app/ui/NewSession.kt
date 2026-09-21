package com.homeattach.app.ui

import com.homeattach.app.data.SettingsStore
import com.homeattach.app.ssh.RemoteSessionFeed
import com.homeattach.app.ssh.SessionsSnapshot
import com.homeattach.app.ssh.createRemoteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** How long a caller waits for the new session to appear in the feed before opening on its id. */
private const val NEW_SESSION_LABEL_WAIT_MS = 2_000L

/**
 * Asks the PC to open a tab and returns the session it became, with the title to show for it.
 *
 * Shared rather than written where it is used, because there are now two places that start a
 * session - the list screen's button and the terminal's key row - and the interesting part is not
 * the call to `tsess-new` but what follows it: the name that comes back is an internal id
 * (`s0b24c6597`), and opening on that shows the user a title they have never seen. The feed knows
 * the real one within a tick of the socket existing, so this waits briefly for it. Bounded, because
 * a session that has started is worth opening even if the list is slow to say so.
 *
 * Slow by nature - yakuake has to spawn the tab and its profile has to bring the session up - so
 * every caller has to show that it is working, or the control reads as dead and gets pressed twice.
 */
internal suspend fun createSessionAndResolveLabel(settingsStore: SettingsStore): Pair<String, String> {
    val config = settingsStore.load()
    val name = withContext(Dispatchers.IO) { createRemoteSession(config) }
    val label = withTimeoutOrNull(NEW_SESSION_LABEL_WAIT_MS) {
        RemoteSessionFeed.sessions(settingsStore)
            .mapNotNull { snapshot ->
                (snapshot as? SessionsSnapshot.Live)?.sessions?.firstOrNull { it.name == name }
            }
            .first()
            .displayLabel()
    } ?: name
    return name to label
}
