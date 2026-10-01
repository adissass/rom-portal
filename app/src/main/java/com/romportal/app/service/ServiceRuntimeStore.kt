package com.romportal.app.service

import com.romportal.app.server.ServerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal sealed interface HostRuntimeState {
    data object Stopped : HostRuntimeState
    data object Starting : HostRuntimeState
    data class Running(val server: ServerState) : HostRuntimeState
    data object Stopping : HostRuntimeState
    data class Error(val message: String) : HostRuntimeState
}

internal object ServiceRuntimeStore {
    private val _serverState = MutableStateFlow<ServerState?>(null)
    private val _hostState = MutableStateFlow<HostRuntimeState>(HostRuntimeState.Stopped)

    val serverState: StateFlow<ServerState?> = _serverState
    val hostState: StateFlow<HostRuntimeState> = _hostState
    private var authenticatedActivityListener: (() -> Unit)? = null
    private var transferStartedListener: (() -> Unit)? = null
    private var transferFinishedListener: (() -> Unit)? = null

    fun onServerStarting() {
        _hostState.value = HostRuntimeState.Starting
    }

    fun onServerStarted(state: ServerState) {
        _serverState.value = state
        _hostState.value = HostRuntimeState.Running(state)
    }

    fun onServerStopping() {
        _hostState.value = HostRuntimeState.Stopping
    }

    fun onServerStartFailed(message: String) {
        _serverState.value = null
        _hostState.value = HostRuntimeState.Error(message)
    }

    fun onServerStopped() {
        _serverState.value = null
        if (_hostState.value !is HostRuntimeState.Error) {
            _hostState.value = HostRuntimeState.Stopped
        }
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
