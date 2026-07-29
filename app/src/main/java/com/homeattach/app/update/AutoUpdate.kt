package com.homeattach.app.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.homeattach.app.BuildConfig
import com.homeattach.app.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Where the app is in noticing, fetching and offering a new version. */
sealed interface AutoUpdateState {
    data object Idle : AutoUpdateState

    /** A newer version exists but has not been fetched — the connection is metered. */
    data class Available(val update: AvailableAppUpdate) : AutoUpdateState

    data class Downloading(val update: AvailableAppUpdate) : AutoUpdateState

    /** The APK is on disk, verified, and can be handed to the system installer. */
    data class Ready(val downloaded: DownloadedAppUpdate) : AutoUpdateState
}

/**
 * Finds and fetches new versions by itself.
 *
 * The app ships as a sideloaded APK, so nothing else is going to tell the user a version exists —
 * and an update they have to remember to go looking for is an update they do not get. Checking is
 * therefore something the app does on its own: on launch and on coming back to the foreground, at
 * most every [CHECK_INTERVAL_MS].
 *
 * Fetching is automatic too, but only on an unmetered connection: this is an app for reaching a
 * home PC from anywhere, so "anywhere" is frequently someone's cellular data, and 28MB of it is not
 * something to spend without being asked. On a metered link the update is reported and downloads
 * when the user says so.
 *
 * The install itself is the one step that cannot be automated: Android requires the user to
 * confirm every sideloaded package in a system dialog. [AutoUpdateState.Ready] is the app's cue to
 * put that dialog up at a moment that does not interrupt anything - see the session list, which is
 * the one screen where nothing is running.
 *
 * Failures are deliberately quiet. A version check that could not reach the network is not
 * something to interrupt someone's terminal for; it retries on the next foreground.
 */
object AutoUpdate {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    private val _state = MutableStateFlow<AutoUpdateState>(AutoUpdateState.Idle)
    val state: StateFlow<AutoUpdateState> = _state.asStateFlow()

    /**
     * Versions whose installer this process has already put on screen. Android gives no signal for
     * "the user declined", so without this a dismissed dialog would come straight back every time
     * the list screen recomposed.
     */
    private val offered = mutableSetOf<String>()

    /**
     * Check now unless one ran recently. Called when the app comes to the foreground; safe to call
     * as often as that happens.
     */
    fun refresh(context: Context, settingsStore: SettingsStore, force: Boolean = false) {
        if (BuildConfig.UPDATE_MANIFEST_URL.isBlank()) return
        if (_state.value is AutoUpdateState.Ready) return
        val appContext = context.applicationContext
        val since = System.currentTimeMillis() - settingsStore.loadLastUpdateCheck()
        if (!force && since < CHECK_INTERVAL_MS) return
        if (!running.compareAndSet(false, true)) return

        scope.launch {
            try {
                val updater = GithubReleaseUpdater(appContext)
                val result = withContext(Dispatchers.IO) { updater.checkForUpdate() }
                settingsStore.saveLastUpdateCheck(System.currentTimeMillis())
                val available = (result as? AppUpdateCheckResult.Available)?.update ?: run {
                    _state.value = AutoUpdateState.Idle
                    return@launch
                }
                if (!isUnmetered(appContext)) {
                    // Reported, not fetched: see the class comment.
                    _state.value = AutoUpdateState.Available(available)
                    return@launch
                }
                fetch(appContext, available, updater)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.i(TAG, "update check failed: ${e.message}")
            } finally {
                running.set(false)
            }
        }
    }

    /** Fetch an update that was found on a metered connection. */
    fun download(context: Context) {
        val available = (_state.value as? AutoUpdateState.Available)?.update ?: return
        if (!running.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        scope.launch {
            try {
                fetch(appContext, available, GithubReleaseUpdater(appContext))
            } catch (e: Exception) {
                _state.value = AutoUpdateState.Available(available)
                if (BuildConfig.DEBUG) Log.i(TAG, "update download failed: ${e.message}")
            } finally {
                running.set(false)
            }
        }
    }

    private suspend fun fetch(
        context: Context,
        available: AvailableAppUpdate,
        updater: GithubReleaseUpdater,
    ) {
        _state.value = AutoUpdateState.Downloading(available)
        val downloaded = withContext(Dispatchers.IO) { updater.downloadApk(available) }
        _state.value = AutoUpdateState.Ready(downloaded)
    }

    /**
     * Puts the system installer up for a ready update, at most once per version per app run.
     * Returns false when there is nothing to install or this version was already offered.
     */
    fun offerInstall(context: Context): Boolean {
        val ready = (_state.value as? AutoUpdateState.Ready)?.downloaded ?: return false
        if (!offered.add(ready.update.tagName)) return false
        return try {
            GithubReleaseUpdater(context).launchInstaller(ready)
            true
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.i(TAG, "installer failed: ${e.message}")
            false
        }
    }

    private fun isUnmetered(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private const val TAG = "AutoUpdate"

    /** Often enough to catch a release the same day, rarely enough to be free. */
    private const val CHECK_INTERVAL_MS = 4L * 60 * 60 * 1000
}
