package com.romportal.app.service

import com.romportal.app.server.ServerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal object ServiceRuntimeStore {
    private val _serverState = MutableStateFlow<ServerState?>(null)
    private val _serverError = MutableStateFlow<String?>(null)

    val serverState: StateFlow<ServerState?> = _serverState
    val serverError: StateFlow<String?> = _serverError
    private var authenticatedActivityListener: (() -> Unit)? = null
    private var transferStartedListener: (() -> Unit)? = null
    private var transferFinishedListener: (() -> Unit)? = null

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

    fun registerAuthenticatedActivityListener(listener: () -> Unit) {
        authenticatedActivityListener = listener
    }

    fun clearAuthenticatedActivityListener() {
        authenticatedActivityListener = null
    }

    fun notifyAuthenticatedFileApiSuccess() {
        authenticatedActivityListener?.invoke()
    }

    fun registerTransferListeners(onTransferStarted: () -> Unit, onTransferFinished: () -> Unit) {
        transferStartedListener = onTransferStarted
        transferFinishedListener = onTransferFinished
    }

    fun clearTransferListeners() {
        transferStartedListener = null
        transferFinishedListener = null
    }

    fun notifyTransferStarted() {
        transferStartedListener?.invoke()
    }

    fun notifyTransferFinished() {
        transferFinishedListener?.invoke()
    }
}
