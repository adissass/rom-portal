package com.romportal.app.service

internal object ServiceConfig {
    const val PREFS_NAME = "romportal_prefs"
    const val KEY_ROOT_URI = "selected_root_uri"

    // Production defaults.
    const val PROD_IDLE_TIMEOUT_MS = 10 * 60 * 1000L
    const val PROD_WARNING_LEAD_MS = 60 * 1000L

    // TEMP testing values for Step 2 verification.
    // Switch back to production by setting:
    // IDLE_TIMEOUT_MS = PROD_IDLE_TIMEOUT_MS
    // WARNING_LEAD_MS = PROD_WARNING_LEAD_MS
    const val IDLE_TIMEOUT_MS = 2 * 60 * 1000L
    const val WARNING_LEAD_MS = 30 * 1000L

    const val NOTIFICATION_CHANNEL_ID_RUNNING = "romportal_server_running_channel"
    const val NOTIFICATION_CHANNEL_ID_WARNING = "romportal_server_warning_channel"
    const val NOTIFICATION_ID = 1001
}
