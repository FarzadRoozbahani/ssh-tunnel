package com.sshtunnel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TunnelState { DISCONNECTED, CONNECTING, CONNECTED }

data class TunnelStatus(
    val state:   TunnelState = TunnelState.DISCONNECTED,
    val mode:    TunnelMode  = TunnelMode.SOCKS5,
    val message: String      = "Ready"
)

object TunnelStateHolder {
    private val _status = MutableStateFlow(TunnelStatus())
    val status: StateFlow<TunnelStatus> = _status.asStateFlow()

    fun setState(state: TunnelState, mode: TunnelMode = TunnelMode.SOCKS5, msg: String = "") {
        _status.value = TunnelStatus(state, mode, msg)
        // notify tile
        TunnelTileService.requestUpdate()
    }

    val isConnected get() = _status.value.state == TunnelState.CONNECTED
    val current     get() = _status.value
}
