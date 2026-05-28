package com.sshtunnel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class TunnelState { DISCONNECTED, CONNECTING, CONNECTED }

data class TunnelStatus(
    val state: TunnelState = TunnelState.DISCONNECTED,
    val mode:  TunnelMode  = TunnelMode.SOCKS5,
    val message: String    = ""
)

object TunnelStateHolder {
    private val _status = MutableStateFlow(TunnelStatus())
    val status: StateFlow<TunnelStatus> = _status

    fun setState(state: TunnelState, mode: TunnelMode = TunnelMode.SOCKS5, msg: String = "") {
        _status.value = TunnelStatus(state, mode, msg)
    }

    val isConnected get() = _status.value.state == TunnelState.CONNECTED
}
