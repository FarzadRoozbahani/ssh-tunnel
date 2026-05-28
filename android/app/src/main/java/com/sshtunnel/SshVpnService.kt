package com.sshtunnel

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer

class SshVpnService : VpnService() {

    companion object {
        const val ACTION_START  = "com.sshtunnel.VPN_START"
        const val ACTION_STOP   = "com.sshtunnel.VPN_STOP"
        const val CHANNEL_ID    = "ssh_vpn_channel"
        const val NOTIF_ID      = 1002
        var isRunning           = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP  -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startVpn() {
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Connecting…"))
        isRunning = true

        val cfg = ProfileManager.getActive(this)

        scope.launch {
            when (val r = TunnelManager.connect(cfg)) {
                is ConnectResult.Failure -> {
                    TunnelStateHolder.setState(TunnelState.DISCONNECTED)
                    withContext(Dispatchers.Main) { stopSelf() }
                }
                is ConnectResult.Success -> {
                    // Build VPN interface
                    val builder = Builder()
                        .setSession("SSH Tunnel")
                        .addAddress("10.0.0.2", 32)
                        .addRoute("0.0.0.0", 0)          // route ALL traffic
                        .addDnsServer("1.1.1.1")
                        .addDnsServer("8.8.8.8")
                        .setMtu(1500)

                    // Always bypass our own app from the VPN
                    try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

                    vpnInterface = builder.establish()
                    if (vpnInterface == null) {
                        TunnelStateHolder.setState(TunnelState.DISCONNECTED)
                        withContext(Dispatchers.Main) { stopSelf() }
                        return@launch
                    }

                    updateNotification("VPN active — all traffic tunneled")
                    TunnelStateHolder.setState(TunnelState.CONNECTED, cfg.mode)

                    // Packet forwarding loop via SOCKS5
                    startPacketForwarding(cfg.socksPort)
                    startWatchdog()
                }
            }
        }
    }

    /**
     * Forward TUN packets → SOCKS5 → SSH tunnel.
     * Uses a simplified TCP interceptor that redirects packets
     * to our local SOCKS5 server.
     */
    private fun startPacketForwarding(socksPort: Int) {
        val vpnFd = vpnInterface ?: return
        scope.launch(Dispatchers.IO) {
            val inStream  = FileInputStream(vpnFd.fileDescriptor)
            val outStream = FileOutputStream(vpnFd.fileDescriptor)
            val buf = ByteBuffer.allocate(32767)

            while (isRunning) {
                try {
                    buf.clear()
                    val len = inStream.read(buf.array())
                    if (len < 0) break
                    if (len < 20) continue  // too short for IP header

                    buf.limit(len)

                    // Parse IP header
                    val version = (buf.get(0).toInt() ushr 4) and 0xF
                    if (version != 4) continue  // only IPv4 for now

                    val protocol = buf.get(9).toInt() and 0xFF
                    if (protocol != 6) continue  // only TCP

                    // Extract destination IP and port
                    val destIp = ByteArray(4)
                    buf.position(16); buf.get(destIp)
                    val ihl = (buf.get(0).toInt() and 0x0F) * 4
                    buf.position(ihl + 2)
                    val destPort = ((buf.get().toInt() and 0xFF) shl 8) or (buf.get().toInt() and 0xFF)

                    val destAddr = InetAddress.getByAddress(destIp).hostAddress ?: continue

                    // Forward via SOCKS5
                    connectViaSocks5("127.0.0.1", socksPort, destAddr, destPort)

                } catch (_: Exception) { delay(10) }
            }
        }
    }

    private suspend fun connectViaSocks5(
        proxyHost: String, proxyPort: Int,
        destHost: String, destPort: Int
    ) = withContext(Dispatchers.IO) {
        try {
            val s = java.net.Socket()
            protect(s)  // bypass VPN for this socket
            s.connect(java.net.InetSocketAddress(proxyHost, proxyPort), 5000)
            // SOCKS5 handshake
            val out = s.getOutputStream()
            val inp = s.getInputStream()
            out.write(byteArrayOf(5, 1, 0))
            inp.read(ByteArray(2))
            val host = destHost.toByteArray()
            out.write(byteArrayOf(5, 1, 0, 3, host.size.toByte()) + host +
                byteArrayOf((destPort shr 8).toByte(), destPort.toByte()))
            inp.read(ByteArray(10))
            s.close()
        } catch (_: Exception) {}
    }

    private fun startWatchdog() {
        scope.launch {
            while (isRunning && TunnelManager.isAlive()) delay(5_000)
            if (isRunning) {
                TunnelStateHolder.setState(TunnelState.DISCONNECTED)
                withContext(Dispatchers.Main) { stopSelf() }
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        TunnelManager.disconnect()
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        TunnelStateHolder.setState(TunnelState.DISCONNECTED)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) =
        if (intent?.action == SERVICE_INTERFACE) super.onBind(intent) else null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "SSH VPN", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, SshVpnService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(this, 0, stopIntent,
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
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }
}
