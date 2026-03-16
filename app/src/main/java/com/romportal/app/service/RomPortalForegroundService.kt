package com.romportal.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.app.ServiceCompat
import com.romportal.app.R
import com.romportal.app.server.RomPortalServer

class RomPortalForegroundService : Service() {
    private lateinit var romPortalServer: RomPortalServer

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        romPortalServer = RomPortalServer(
            context = applicationContext,
            contentResolver = contentResolver,
            rootUriProvider = { readSelectedRootUri() }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVER -> startServer()
            ACTION_STOP_SERVER -> stopServer()
            ACTION_KEEP_ALIVE -> keepAliveStub()
            else -> startServer()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (::romPortalServer.isInitialized) {
            romPortalServer.stop()
        }
        ServiceRuntimeStore.onServerStopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServer() {
        try {
            // Promote to foreground immediately, then do heavier startup work.
            ServiceCompat.startForeground(
                this,
                ServiceConfig.NOTIFICATION_ID,
                buildStartingNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            val state = romPortalServer.start()
            ServiceRuntimeStore.onServerStarted(state)
            ServiceCompat.startForeground(
                this,
                ServiceConfig.NOTIFICATION_ID,
                buildRunningNotification(state.lanUrl, state.pin),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } catch (e: Exception) {
            ServiceRuntimeStore.onServerStartFailed(e.message ?: "Failed to start server")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopServer() {
        romPortalServer.stop()
        ServiceRuntimeStore.onServerStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun keepAliveStub() {
        // Idle timer logic lands in the next implementation step.
        ServiceRuntimeStore.serverState.value?.let { state ->
            ServiceCompat.startForeground(
                this,
                ServiceConfig.NOTIFICATION_ID,
                buildRunningNotification(state.lanUrl, state.pin),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        }
    }

    private fun buildStartingNotification(): android.app.Notification {
        val stopIntent = Intent(this, RomPortalForegroundService::class.java).apply { action = ACTION_STOP_SERVER }
        val stopPending = PendingIntentCompat.getService(
            this,
            1,
            stopIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            false
        )

        return NotificationCompat.Builder(this, ServiceConfig.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_starting))
            .setOngoing(true)
            .addAction(0, getString(R.string.notification_stop_now), stopPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buildRunningNotification(url: String, pin: String): android.app.Notification {
        val stopIntent = Intent(this, RomPortalForegroundService::class.java).apply { action = ACTION_STOP_SERVER }
        val keepAliveIntent = Intent(this, RomPortalForegroundService::class.java).apply { action = ACTION_KEEP_ALIVE }
        val stopPending = PendingIntentCompat.getService(
            this,
            1,
            stopIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            false
        )
        val keepAlivePending = PendingIntentCompat.getService(
            this,
            2,
            keepAliveIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            false
        )

        return NotificationCompat.Builder(this, ServiceConfig.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_body, url, pin))
            .setOngoing(true)
            .addAction(0, getString(R.string.notification_keep_alive), keepAlivePending)
            .addAction(0, getString(R.string.notification_stop_now), stopPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            ServiceConfig.NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun readSelectedRootUri(): String? {
        return getSharedPreferences(ServiceConfig.PREFS_NAME, MODE_PRIVATE)
            .getString(ServiceConfig.KEY_ROOT_URI, null)
    }

    companion object {
        const val ACTION_START_SERVER = "com.romportal.app.action.START_SERVER"
        const val ACTION_STOP_SERVER = "com.romportal.app.action.STOP_SERVER"
        const val ACTION_KEEP_ALIVE = "com.romportal.app.action.KEEP_ALIVE"

        fun startIntent(context: Context): Intent {
            return Intent(context, RomPortalForegroundService::class.java).apply {
                action = ACTION_START_SERVER
            }
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, RomPortalForegroundService::class.java).apply {
                action = ACTION_STOP_SERVER
            }
        }
    }
}
