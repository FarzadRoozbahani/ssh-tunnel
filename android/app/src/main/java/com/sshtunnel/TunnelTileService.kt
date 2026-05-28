package com.sshtunnel

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.*

class TunnelTileService : TileService() {

    companion object {
        private var instance: TunnelTileService? = null
        fun requestUpdate() { instance?.applyState(TunnelStateHolder.current.state) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        instance = this
        applyState(TunnelStateHolder.current.state)
        scope.launch {
            TunnelStateHolder.status.collect { applyState(it.state) }
        }
    }

    override fun onStopListening() {
        instance = null
        scope.coroutineContext.cancelChildren()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (TunnelStateHolder.isConnected) {
            // Stop — send explicit STOP action to whichever is running
            if (SshProxyService.isRunning)
                applicationContext.startService(
                    Intent(applicationContext, SshProxyService::class.java).apply { action = SshProxyService.ACTION_STOP })
            if (SshVpnService.isRunning)
                applicationContext.startService(
                    Intent(applicationContext, SshVpnService::class.java).apply { action = SshVpnService.ACTION_STOP })
            if (!SshProxyService.isRunning && !SshVpnService.isRunning) {
                TunnelManager.disconnect()
                TunnelStateHolder.setState(TunnelState.DISCONNECTED)
            }
        } else {
            val startAction = Runnable {
                val cfg = ProfileManager.getActive(applicationContext)
                val intent = when (cfg.mode) {
                    TunnelMode.VPN    -> Intent(applicationContext, SshVpnService::class.java).apply { action = SshVpnService.ACTION_START }
                    TunnelMode.SOCKS5 -> Intent(applicationContext, SshProxyService::class.java).apply { action = SshProxyService.ACTION_START }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    applicationContext.startForegroundService(intent)
                else
                    applicationContext.startService(intent)
            }
            if (isLocked) unlockAndRun(startAction) else startAction.run()
        }
    }

    private fun applyState(state: TunnelState) {
        val tile = qsTile ?: return
        when (state) {
            TunnelState.CONNECTED -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "SSH Tunnel"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = "Connected"
            }
            TunnelState.CONNECTING -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = "SSH Tunnel"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = "Connecting…"
            }
            TunnelState.DISCONNECTED -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "SSH Tunnel"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = "Tap to connect"
            }
        }
        tile.updateTile()
    }
}
