package com.romportal.app.service

import com.romportal.app.server.ServerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal object ServiceRuntimeStore {
    private val _serverState = MutableStateFlow<ServerState?>(null)
    private val _serverError = MutableStateFlow<String?>(null)

    val serverState: StateFlow<ServerState?> = _serverState
    val serverError: StateFlow<String?> = _serverError

    fun onServerStarted(state: ServerState) {
        _serverState.value = state
        _serverError.value = null
    }

    fun onServerStartFailed(message: String) {
        _serverState.value = null
        _serverError.value = message
    }

    fun onServerStopped() {
        _serverState.value = null
        _serverError.value = null
    }
}

