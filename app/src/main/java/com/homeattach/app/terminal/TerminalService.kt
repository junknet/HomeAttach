package com.homeattach.app.terminal

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.homeattach.app.MainActivity
import com.homeattach.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while [AttachedTerminal] holds a live attachment.
 *
 * That is its entire job. Without it, leaving the app puts the process in the cached state: the
 * reader thread is frozen, the socket is torn down without a FIN ever reaching us, and coming back
 * means a reconnect — which is exactly the "every time I switch back it has to reconnect" problem.
 *
 * Type is `specialUse`, not `dataSync`: since API 35 the system only permits a dataSync foreground
 * service six hours per 24, then calls `onTimeout` and kills it. An interactive terminal that the
 * user chose to keep attached has no business inheriting that budget, and it is not a data-sync
 * workload in the first place. `specialUse` has no timeout. Its only cost is a Google Play review
 * of the declared subtype, which does not apply to this app — it ships as a sideloaded APK from
 * GitHub Releases and never touches Play.
 *
 * Started, never bound: the attachment itself lives in [AttachedTerminal] and is reachable
 * directly, so a binder round trip would buy nothing. This service is a lifetime hint to the OS.
 */
class TerminalService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var statusWatch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.terminal_notification_channel_name),
            // LOW: an ongoing service notification must never make a sound or push a heads-up.
            NotificationManager.IMPORTANCE_LOW,
        )
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DETACH) {
            AttachedTerminal.close(this)
            return START_NOT_STICKY
        }
        // Racing a close(): nothing to hold the process for, so do not become foreground at all.
        val attachment = AttachedTerminal.current.value ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(attachment, attachment.status.value),
            foregroundServiceType(),
        )
        statusWatch?.cancel()
        statusWatch = scope.launch {
            attachment.status.collect { status -> postStatus(attachment, status) }
        }
        // NOT_STICKY: if the OS kills the process, the SSH channel and the emulator die with it.
        // Reviving a bare service with no terminal state and no UI would strand a notification
        // over nothing.
        return START_NOT_STICKY
    }

    /** Swiping the app out of recents must not strand a live SSH channel and an ongoing notification. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        AttachedTerminal.close(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        statusWatch?.cancel()
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /**
     * Refreshes the ongoing notification. A denied POST_NOTIFICATIONS only costs the user the
     * status text and the Detach shortcut — the service keeps holding the process either way,
     * which is the part the terminal actually depends on.
     */
    private fun postStatus(attachment: TerminalAttachment, status: AttachStatus) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(attachment, status))
    }

    private fun buildNotification(attachment: TerminalAttachment, status: AttachStatus): Notification {
        val open = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val detach = PendingIntent.getService(
            this,
            REQUEST_DETACH,
            Intent(this, TerminalService::class.java).setAction(ACTION_DETACH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminal_notification)
            .setContentTitle(attachment.sessionLabel)
            .setContentText(statusText(status))
            .setContentIntent(open)
            .addAction(0, getString(R.string.terminal_notification_detach), detach)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun statusText(status: AttachStatus): String = when (status) {
        is AttachStatus.Connecting -> getString(R.string.terminal_status_connecting)
        is AttachStatus.Connected -> getString(R.string.terminal_status_connected)
        is AttachStatus.Reconnecting ->
            getString(R.string.terminal_notification_reconnecting_attempt, status.attempt)
        is AttachStatus.Failed -> status.message
        is AttachStatus.Ended -> getString(R.string.terminal_session_ended_on_pc)
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            // Before Android 14 a type is neither required nor meaningful; the manifest
            // declaration is what the platform reads.
            0
        }

    companion object {
        private const val CHANNEL_ID = "attached_terminal"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_OPEN = 0
        private const val REQUEST_DETACH = 1
        private const val ACTION_DETACH = "com.homeattach.app.action.DETACH"

        /** Caller must be in the foreground — starting a foreground service from the background
         * is blocked since API 31. Failing to start it costs process protection, not the terminal,
         * so it is reported rather than thrown. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, TerminalService::class.java))
            } catch (e: Exception) {
                AttachedTerminal.logServiceFailure(
                    "startForegroundService rejected; the attachment will run unprotected",
                    e,
                )
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TerminalService::class.java))
        }
    }
}
