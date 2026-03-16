package com.romportal.app.service

internal object ServiceConfig {
    const val PREFS_NAME = "romportal_prefs"
    const val KEY_ROOT_URI = "selected_root_uri"

    // Tunables for timeout behavior (used in later steps).
    const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L
    const val WARNING_LEAD_MS = 60 * 1000L

    const val NOTIFICATION_CHANNEL_ID = "romportal_server_channel"
    const val NOTIFICATION_ID = 1001
}

