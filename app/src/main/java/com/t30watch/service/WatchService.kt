package com.t30watch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.t30watch.MainActivity
import com.t30watch.R
import com.t30watch.ble.ConnectionState
import com.t30watch.ble.RawLog
import com.t30watch.data.WatchRepository
import com.t30watch.data.sync.SyncEngine
import com.t30watch.util.BlePermissions
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/**
 * Keeps the app process alive so the watch stays connected (and notifications reach it) while the app is
 * in the background. Android requires a visible notification for this ("foreground service").
 * The actual connect/reconnect/sync logic lives in [WatchRepository] and [SyncEngine]; this only hosts it.
 */
@AndroidEntryPoint
class WatchService : Service() {
    @Inject lateinit var repo: WatchRepository
    @Inject lateinit var sync: SyncEngine
    @Inject lateinit var log: RawLog

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build("Starting…", null), type)
        } catch (e: Exception) {
            // Android 14+ refuses a connectedDevice service without the Bluetooth permission.
            log.error("startForeground", e)
            stopSelf()
            return
        }
        log.app("WatchService started")
        scope.launch {
            combine(repo.connection, repo.battery, sync.status) { c, b, s -> Triple(c, b, s) }.collect { (c, b, s) ->
                val title = when (c) {
                    is ConnectionState.Ready -> "${c.name ?: "Watch"} connected" + (b?.let { " · ${it.percent}%" } ?: "")
                    is ConnectionState.Connecting -> "${c.step}…"
                    is ConnectionState.Disconnected -> "Watch not connected"
                }
                val text = when {
                    s.running -> "Syncing…"
                    s.lastSuccessAt != null -> "Synced " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.lastSuccessAt))
                    c is ConnectionState.Disconnected -> c.reason
                    else -> null
                }
                notificationManager().notify(NOTIFICATION_ID, build(title, text))
            }
        }
        scope.launch { repo.autoConnectLast() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        log.app("WatchService stopped")
        scope.cancel()
        super.onDestroy()
    }

    private fun build(title: String, text: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_watch)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Watch connection", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows while the app keeps the watch connected"
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(ch)
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    companion object {
        private const val CHANNEL_ID = "watch_connection"
        private const val NOTIFICATION_ID = 30

        /** Starts the service if the Bluetooth permission is there (Android won't allow it otherwise). */
        fun start(context: Context) {
            if (!BlePermissions.granted(context)) return
            // Can throw if the app slipped into the background at this exact moment (Android 12+ rule).
            runCatching { ContextCompat.startForegroundService(context, Intent(context, WatchService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchService::class.java))
        }
    }
}
