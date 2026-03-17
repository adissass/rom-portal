package com.romportal.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.app.ServiceCompat
import com.romportal.app.R
import com.romportal.app.server.RomPortalServer

class RomPortalForegroundService : Service() {
    private lateinit var romPortalServer: RomPortalServer
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastActivityAtMs: Long = 0L
    private var warningShown = false
    private var activeTransferCount = 0

    private val warningRunnable = Runnable { maybeShowWarning() }
    private val stopRunnable = Runnable { maybeStopForInactivity() }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ServiceRuntimeStore.registerAuthenticatedActivityListener { touchActivity() }
        ServiceRuntimeStore.registerTransferListeners(
            onTransferStarted = { onTransferStarted() },
            onTransferFinished = { onTransferFinished() }
        )
        romPortalServer = RomPortalServer(
            context = applicationContext,
            contentResolver = contentResolver,
            rootUriProvider = { readSelectedRootUri() },
            onAuthenticatedFileApiSuccess = { ServiceRuntimeStore.notifyAuthenticatedFileApiSuccess() },
            onTransferStarted = { ServiceRuntimeStore.notifyTransferStarted() },
            onTransferFinished = { ServiceRuntimeStore.notifyTransferFinished() }
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
        mainHandler.removeCallbacks(warningRunnable)
        mainHandler.removeCallbacks(stopRunnable)
        ServiceRuntimeStore.clearAuthenticatedActivityListener()
        ServiceRuntimeStore.clearTransferListeners()
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
            resetIdleTimerLocked()
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
        mainHandler.removeCallbacks(warningRunnable)
        mainHandler.removeCallbacks(stopRunnable)
        activeTransferCount = 0
        romPortalServer.stop()
        ServiceRuntimeStore.onServerStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun touchActivity() {
        runOnMain {
            if (ServiceRuntimeStore.serverState.value == null) return@runOnMain
            resetIdleTimerLocked()
            ServiceRuntimeStore.serverState.value?.let { state ->
                ServiceCompat.startForeground(
                    this,
                    ServiceConfig.NOTIFICATION_ID,
                    buildRunningNotification(state.lanUrl, state.pin),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            }
        }
    }

    private fun keepAliveStub() {
        touchActivity()
    }

    private fun onTransferStarted() {
        runOnMain {
            if (ServiceRuntimeStore.serverState.value == null) return@runOnMain
            activeTransferCount += 1
            lastActivityAtMs = System.currentTimeMillis()
            warningShown = false
            mainHandler.removeCallbacks(warningRunnable)
            mainHandler.removeCallbacks(stopRunnable)
            ServiceRuntimeStore.serverState.value?.let { state ->
                ServiceCompat.startForeground(
                    this,
                    ServiceConfig.NOTIFICATION_ID,
                    buildRunningNotification(state.lanUrl, state.pin),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            }
        }
    }

    private fun onTransferFinished() {
        runOnMain {
            if (activeTransferCount > 0) {
                activeTransferCount -= 1
            }
            if (ServiceRuntimeStore.serverState.value == null) return@runOnMain
            if (activeTransferCount == 0) {
                lastActivityAtMs = System.currentTimeMillis()
                warningShown = false
                scheduleIdleCallbacksLocked()
            }
        }
    }

    private fun resetIdleTimerLocked() {
        lastActivityAtMs = System.currentTimeMillis()
        warningShown = false
        mainHandler.removeCallbacks(warningRunnable)
        mainHandler.removeCallbacks(stopRunnable)
        if (activeTransferCount > 0) return
        scheduleIdleCallbacksLocked()
    }

    private fun scheduleIdleCallbacksLocked() {
        val warningDelay = (ServiceConfig.IDLE_TIMEOUT_MS - ServiceConfig.WARNING_LEAD_MS).coerceAtLeast(0L)
        mainHandler.postDelayed(warningRunnable, warningDelay)
        mainHandler.postDelayed(stopRunnable, ServiceConfig.IDLE_TIMEOUT_MS)
    }

    private fun maybeShowWarning() {
        val state = ServiceRuntimeStore.serverState.value ?: return
        if (activeTransferCount > 0) return
        if (warningShown) return
        val idleMs = System.currentTimeMillis() - lastActivityAtMs
        if (idleMs >= (ServiceConfig.IDLE_TIMEOUT_MS - ServiceConfig.WARNING_LEAD_MS)) {
            warningShown = true
            ServiceCompat.startForeground(
                this,
                ServiceConfig.NOTIFICATION_ID,
                buildWarningNotification(state.lanUrl, state.pin),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            val remaining = (ServiceConfig.IDLE_TIMEOUT_MS - ServiceConfig.WARNING_LEAD_MS) - idleMs
            mainHandler.postDelayed(warningRunnable, remaining.coerceAtLeast(0L))
        }
    }

    private fun maybeStopForInactivity() {
        val state = ServiceRuntimeStore.serverState.value ?: return
        if (activeTransferCount > 0) return
        val idleMs = System.currentTimeMillis() - lastActivityAtMs
        if (idleMs >= ServiceConfig.IDLE_TIMEOUT_MS) {
            stopServer()
            return
        }
        mainHandler.postDelayed(stopRunnable, (ServiceConfig.IDLE_TIMEOUT_MS - idleMs).coerceAtLeast(0L))
        if (!warningShown) {
            ServiceCompat.startForeground(
                this,
                ServiceConfig.NOTIFICATION_ID,
                buildRunningNotification(state.lanUrl, state.pin),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        }
    }

    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
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

    private fun buildWarningNotification(url: String, pin: String): android.app.Notification {
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
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(getString(R.string.notification_warning_title))
            .setContentText(getString(R.string.notification_warning_body, url, pin))
            .setOngoing(true)
            .addAction(0, getString(R.string.notification_keep_alive), keepAlivePending)
            .addAction(0, getString(R.string.notification_stop_now), stopPending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
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
