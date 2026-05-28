package com.sshtunnel

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tim06.vpnprotocols.singbox.SingBoxVpnService
import kotlinx.coroutines.*

private const val TAG = "SshVpnService"

class SshVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.sshtunnel.VPN_START"
        const val ACTION_STOP  = "com.sshtunnel.VPN_STOP"
        const val CHANNEL_ID   = "ssh_vpn"
        const val NOTIF_ID     = 1002
        var isRunning = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP  -> { doStop(); return START_NOT_STICKY }
            ACTION_START -> if (!isRunning) startVpn()
        }
        return START_NOT_STICKY
    }

    private fun doStop() {
        isRunning = false
        scope.cancel()
        // Stop sing-box VPN
        try {
            val stopIntent = Intent(this, SingBoxVpnService::class.java)
            stopService(stopIntent)
        } catch (e: Exception) {
            Log.w(TAG, "sing-box stop: ${e.message}")
        }
        TunnelManager.disconnect()
        TunnelStateHolder.setState(TunnelState.DISCONNECTED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startVpn() {
        createChannel()
        isRunning = true
        startForeground(NOTIF_ID, buildNotif("Connecting…"))
        TunnelStateHolder.setState(TunnelState.CONNECTING, TunnelMode.VPN)

        val cfg = ProfileManager.getActive(this)

        scope.launch {
            // 1. Connect SSH + start SOCKS5
            when (val r = TunnelManager.connect(cfg)) {
                is ConnectResult.Failure -> {
                    TunnelStateHolder.setState(TunnelState.DISCONNECTED, msg = r.error)
                    withContext(Dispatchers.Main) { doStop() }
                    return@launch
                }
                is ConnectResult.Success -> Log.i(TAG, "SSH + SOCKS5 ready on :${cfg.socksPort}")
            }

            // 2. Build sing-box config that routes ALL traffic through our SOCKS5
            val singboxConfig = buildSingboxConfig(cfg.socksPort, cfg.bypassDomains)

            // 3. Start sing-box VPN service
            withContext(Dispatchers.Main) {
                try {
                    val intent = SingBoxVpnService.createStartIntent(
                        context = this@SshVpnService,
                        config  = singboxConfig
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        startForegroundService(intent)
                    else
                        startService(intent)

                    notify("VPN active — all traffic tunneled")
                    TunnelStateHolder.setState(TunnelState.CONNECTED, TunnelMode.VPN)
                    scope.launch { watchdog() }
                } catch (e: Exception) {
                    Log.e(TAG, "sing-box start failed: ${e.message}")
                    TunnelStateHolder.setState(TunnelState.DISCONNECTED, msg = "VPN failed: ${e.message}")
                    doStop()
                }
            }
        }
    }

    /**
     * Generate a sing-box config that:
     * - Routes all traffic through a TUN interface
     * - Forwards everything to our local SOCKS5 (SSH tunnel)
     * - Bypass list routes directly without VPN
     */
    private fun buildSingboxConfig(socksPort: Int, bypass: List<String>): String {
        val bypassRules = bypass
            .filter { it.isNotBlank() && !it.startsWith("ext:") }
            .joinToString(",\n") { "          \"domain_suffix\": [\"$it\"]" }

        val bypassSection = if (bypassRules.isNotBlank()) """
        {
          "type": "rule",
          "domain_suffix": [${bypass.filter { it.isNotBlank() && !it.startsWith("ext:") }.joinToString(",") { "\"$it\"" }}],
          "outbound": "direct"
        },""" else ""

        return """
{
  "log": { "level": "warn" },
  "dns": {
    "servers": [
      { "tag": "remote", "address": "tls://1.1.1.1", "detour": "proxy" },
      { "tag": "direct", "address": "223.5.5.5",     "detour": "direct" }
    ],
    "rules": [
      { "outbound": "any", "server": "direct" }
    ],
    "final": "remote"
  },
  "inbounds": [
    {
      "type": "tun",
      "tag": "tun-in",
      "address": ["172.19.0.1/30", "fdfe:dcba:9876::1/126"],
      "mtu": 1500,
      "auto_route": true,
      "strict_route": true,
      "stack": "system"
    }
  ],
  "outbounds": [
    {
      "type": "socks",
      "tag": "proxy",
      "server": "127.0.0.1",
      "server_port": $socksPort
    },
    { "type": "direct", "tag": "direct" },
    { "type": "block",  "tag": "block"  },
    { "type": "dns",    "tag": "dns-out" }
  ],
  "route": {
    "rules": [
      { "protocol": "dns", "outbound": "dns-out" },$bypassSection
      { "ip_is_private": true, "outbound": "direct" }
    ],
    "final": "proxy",
    "auto_detect_interface": true
  }
}
""".trimIndent()
    }

    private fun watchdog() = scope.launch {
        while (isRunning) {
            delay(5_000)
            if (!TunnelManager.isAlive()) {
                Log.w(TAG, "SSH lost")
                TunnelStateHolder.setState(TunnelState.DISCONNECTED, msg = "Connection lost")
                withContext(Dispatchers.Main) { doStop() }
                break
            }
        }
    }

    override fun onDestroy() { doStop(); super.onDestroy() }
    override fun onRevoke()  { doStop(); super.onRevoke()  }

    override fun onBind(intent: Intent?) =
        if (intent?.action == SERVICE_INTERFACE) super.onBind(intent) else null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "SSH VPN", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotif(text: String): Notification {
        val stopPi = PendingIntent.getService(this, 200,
            Intent(this, SshVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openPi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SSH Tunnel — VPN")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentIntent(openPi)
            .addAction(R.drawable.ic_tile, "Disconnect", stopPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notify(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(text))
}
