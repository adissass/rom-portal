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
    private val idlePolicy = IdleTimeoutPolicy(
        idleTimeoutMs = ServiceConfig.IDLE_TIMEOUT_MS,
        warningLeadMs = ServiceConfig.WARNING_LEAD_MS
    )

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
            applySchedule(idlePolicy.onServerStarted(System.currentTimeMillis()))
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
            idlePolicy.onTransferStarted(System.currentTimeMillis())
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
            if (ServiceRuntimeStore.serverState.value == null) return@runOnMain
            val schedule = idlePolicy.onTransferFinished(System.currentTimeMillis())
            applySchedule(schedule)
        }
    }

    private fun resetIdleTimerLocked() {
        val schedule = idlePolicy.onActivity(System.currentTimeMillis())
        mainHandler.removeCallbacks(warningRunnable)
        mainHandler.removeCallbacks(stopRunnable)
        applySchedule(schedule)
    }

    private fun applySchedule(schedule: IdleSchedule) {
        mainHandler.removeCallbacks(warningRunnable)
        mainHandler.removeCallbacks(stopRunnable)
        schedule.warningDelayMs?.let { delay ->
            mainHandler.postDelayed(warningRunnable, delay.coerceAtLeast(0))
        }
        schedule.stopDelayMs?.let { delay ->
            mainHandler.postDelayed(stopRunnable, delay.coerceAtLeast(0))
        }
    }

    private fun maybeShowWarning() {
        val state = ServiceRuntimeStore.serverState.value ?: return
        val now = System.currentTimeMillis()
        if (idlePolicy.warningDue(now)) {
            ServiceCompat.startForeground(
                this,
                ServiceConfig.NOTIFICATION_ID,
                buildWarningNotification(state.lanUrl, state.pin),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            mainHandler.postDelayed(warningRunnable, idlePolicy.warningRemainingMs(now))
        }
    }

    private fun maybeStopForInactivity() {
        val state = ServiceRuntimeStore.serverState.value ?: return
        val now = System.currentTimeMillis()
        if (idlePolicy.stopDue(now)) {
            stopServer()
            return
        }
        mainHandler.postDelayed(stopRunnable, idlePolicy.stopRemainingMs(now))
        ServiceCompat.startForeground(
            this,
            ServiceConfig.NOTIFICATION_ID,
            buildRunningNotification(state.lanUrl, state.pin),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
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

        return NotificationCompat.Builder(this, ServiceConfig.NOTIFICATION_CHANNEL_ID_RUNNING)
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

        return NotificationCompat.Builder(this, ServiceConfig.NOTIFICATION_CHANNEL_ID_WARNING)
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

        return NotificationCompat.Builder(this, ServiceConfig.NOTIFICATION_CHANNEL_ID_RUNNING)
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
        val runningChannel = NotificationChannel(
            ServiceConfig.NOTIFICATION_CHANNEL_ID_RUNNING,
            getString(R.string.notification_channel_running_name),
            NotificationManager.IMPORTANCE_LOW
        )
        val warningChannel = NotificationChannel(
            ServiceConfig.NOTIFICATION_CHANNEL_ID_WARNING,
            getString(R.string.notification_channel_warning_name),
            NotificationManager.IMPORTANCE_HIGH
        )
        manager.createNotificationChannel(runningChannel)
        manager.createNotificationChannel(warningChannel)
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
