package com.sshtunnel

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.*

class TunnelTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        scope.launch {
            TunnelStateHolder.status.collect { status ->
                updateTile(status.state)
            }
        }
    }

    override fun onStopListening() {
        scope.coroutineContext.cancelChildren()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val cfg = ProfileManager.getActive(applicationContext)

        if (TunnelStateHolder.isConnected) {
            // Stop whichever service is running
            stopService(Intent(applicationContext, SshProxyService::class.java))
            stopService(Intent(applicationContext, SshVpnService::class.java))
        } else {
            // Need to unlock device first on secure lockscreens
            if (isLocked) {
                unlockAndRun { launchTunnel(cfg) }
            } else {
                launchTunnel(cfg)
            }
        }
    }

    private fun launchTunnel(cfg: TunnelConfig) {
        TunnelStateHolder.setState(TunnelState.CONNECTING, cfg.mode)
        updateTile(TunnelState.CONNECTING)

        val intent = when (cfg.mode) {
            TunnelMode.VPN -> Intent(applicationContext, SshVpnService::class.java).apply {
                action = SshVpnService.ACTION_START
            }
            TunnelMode.SOCKS5 -> Intent(applicationContext, SshProxyService::class.java).apply {
                action = SshProxyService.ACTION_START
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(intent)
        } else {
            applicationContext.startService(intent)
        }
    }

    private fun updateTile(state: TunnelState) {
        qsTile?.apply {
            when (state) {
                TunnelState.CONNECTED -> {
                    this.state = Tile.STATE_ACTIVE
                    label = "SSH Tunnel"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        subtitle = "Connected"
                    }
                }
                TunnelState.CONNECTING -> {
                    this.state = Tile.STATE_UNAVAILABLE
                    label = "SSH Tunnel"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        subtitle = "Connecting…"
                    }
                }
                TunnelState.DISCONNECTED -> {
                    this.state = Tile.STATE_INACTIVE
                    label = "SSH Tunnel"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        subtitle = "Tap to connect"
                    }
                }
            }
            updateTile()
        }
    }
}
