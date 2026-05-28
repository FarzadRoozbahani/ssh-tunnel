package com.sshtunnel

import android.app.*
import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
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
    private var vpnIface: ParcelFileDescriptor? = null

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
        TunnelManager.disconnect()
        // Close VPN interface — this removes the key icon
        try { vpnIface?.close() } catch (_: Exception) {}
        vpnIface = null
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
            when (val r = TunnelManager.connect(cfg)) {
                is ConnectResult.Failure -> {
                    TunnelStateHolder.setState(TunnelState.DISCONNECTED, msg = r.error)
                    withContext(Dispatchers.Main) { doStop() }
                }
                is ConnectResult.Success -> {
                    val builder = Builder()
                        .setSession("SSH Tunnel")
                        .addAddress("10.8.0.1", 32)
                        .addDnsServer("1.1.1.1")
                        .addDnsServer("8.8.8.8")
                        .setMtu(1500)
                        .addRoute("0.0.0.0", 0)
                        .addRoute("::", 0)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        builder.setHttpProxy(
                            ProxyInfo.buildDirectProxy("127.0.0.1", cfg.socksPort)
                        )
                    }

                    try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

                    val iface = builder.establish()
                    if (iface == null) {
                        TunnelManager.disconnect()
                        TunnelStateHolder.setState(TunnelState.DISCONNECTED, msg = "VPN interface failed")
                        withContext(Dispatchers.Main) { doStop() }
                        return@launch
                    }
                    vpnIface = iface

                    withContext(Dispatchers.Main) { notify("VPN active") }
                    TunnelStateHolder.setState(TunnelState.CONNECTED, TunnelMode.VPN, r.message)
                    watchdog()
                }
            }
        }
    }

    private fun watchdog() = scope.launch {
        while (isRunning) {
            delay(4_000)
            if (!TunnelManager.isAlive()) {
                TunnelStateHolder.setState(TunnelState.DISCONNECTED, msg = "Connection lost")
                withContext(Dispatchers.Main) { doStop() }
                break
            }
        }
    }

    override fun onDestroy() {
        doStop()
        super.onDestroy()
    }

    override fun onRevoke() {
        // Called by system when VPN is revoked (e.g. another VPN takes over)
        doStop()
        super.onRevoke()
    }

    override fun onBind(intent: Intent?) =
        if (intent?.action == SERVICE_INTERFACE) super.onBind(intent) else null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "SSH VPN", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotif(text: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 200,
            Intent(this, SshVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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
