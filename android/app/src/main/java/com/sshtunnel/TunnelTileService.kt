package com.sshtunnel

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.*

class TunnelTileService : TileService() {

    companion object {
        private var instance: TunnelTileService? = null
        fun requestUpdate() { instance?.updateTile(TunnelStateHolder.current.state) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        instance = this
        // Sync tile with current state immediately
        updateTile(TunnelStateHolder.current.state)
        // Keep syncing while tile is visible
        scope.launch {
            TunnelStateHolder.status.collect { updateTile(it.state) }
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
            // Disconnect
            applicationContext.stopService(Intent(applicationContext, SshProxyService::class.java))
            applicationContext.stopService(Intent(applicationContext, SshVpnService::class.java))
        } else {
            val action = Runnable {
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
            if (isLocked) unlockAndRun(action) else action.run()
        }
    }

    private fun updateTile(state: TunnelState) {
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
