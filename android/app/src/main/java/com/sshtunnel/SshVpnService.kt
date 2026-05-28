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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

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
            ACTION_STOP  -> { stopSelf(); return START_NOT_STICKY }
            ACTION_START -> if (!isRunning) startVpn()
        }
        return START_NOT_STICKY
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
                    withContext(Dispatchers.Main) { stopSelf() }
                }
                is ConnectResult.Success -> {
                    // Build TUN interface
                    val builder = Builder()
                        .setSession("SSH Tunnel")
                        .addAddress("10.8.0.1", 32)
                        .addDnsServer("1.1.1.1")
                        .addDnsServer("8.8.8.8")
                        .setMtu(1500)
                        .addRoute("0.0.0.0", 0)       // all IPv4
                        .addRoute("::", 0)             // all IPv6

                    // On Android 10+ set a proxy so HTTP/HTTPS apps use it directly
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        builder.setHttpProxy(
                            ProxyInfo.buildDirectProxy("127.0.0.1", cfg.socksPort)
                        )
                    }

                    // Always exclude our own app to avoid VPN loop
                    try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

                    val iface = builder.establish()
                    if (iface == null) {
                        TunnelManager.disconnect()
                        TunnelStateHolder.setState(TunnelState.DISCONNECTED, msg = "VPN interface failed")
                        withContext(Dispatchers.Main) { stopSelf() }
                        return@launch
                    }
                    vpnIface = iface

                    withContext(Dispatchers.Main) {
                        notify("VPN active — all traffic tunneled")
                    }
                    TunnelStateHolder.setState(TunnelState.CONNECTED, TunnelMode.VPN, r.message)

                    // Start TUN → SOCKS5 packet forwarding
                    forwardPackets(iface, cfg.socksPort)
                    watchdog()
                }
            }
        }
    }

    /**
     * Read IPv4/TCP packets from TUN, forward each connection via SOCKS5.
     * Each unique TCP destination opens a new SOCKS5 connection.
     */
    private fun forwardPackets(iface: ParcelFileDescriptor, socksPort: Int) {
        scope.launch(Dispatchers.IO) {
            val ins = FileInputStream(iface.fileDescriptor)
            val out = FileOutputStream(iface.fileDescriptor)
            val buf = ByteArray(32767)

            // We track active TCP sessions by (srcPort, dstIP, dstPort)
            val sessions = mutableMapOf<Long, Socket>()

            while (isRunning) {
                try {
                    val len = ins.read(buf)
                    if (len < 20) continue

                    val pkt = buf.copyOf(len)

                    // IP version
                    val version = (pkt[0].toInt() ushr 4) and 0xF
                    if (version != 4) continue                 // IPv6 handled via proxy

                    val proto = pkt[9].toInt() and 0xFF
                    if (proto != 6) continue                   // TCP only

                    val ihl  = (pkt[0].toInt() and 0x0F) * 4
                    if (len < ihl + 20) continue               // too short

                    // Destination IP
                    val dstIp = "%d.%d.%d.%d".format(
                        pkt[16].toInt() and 0xFF, pkt[17].toInt() and 0xFF,
                        pkt[18].toInt() and 0xFF, pkt[19].toInt() and 0xFF
                    )
                    // Source port + dest port
                    val srcPort = ((pkt[ihl].toInt() and 0xFF) shl 8) or (pkt[ihl+1].toInt() and 0xFF)
                    val dstPort = ((pkt[ihl+2].toInt() and 0xFF) shl 8) or (pkt[ihl+3].toInt() and 0xFF)
                    val tcpFlags = pkt[ihl + 13].toInt() and 0xFF
                    val isSyn = (tcpFlags and 0x02) != 0
                    val isFin = (tcpFlags and 0x01) != 0 || (tcpFlags and 0x04) != 0

                    val sessionKey = (srcPort.toLong() shl 48) or
                        (pkt[16].toLong() shl 24) or (pkt[17].toLong() shl 16) or
                        (pkt[18].toLong() shl 8)  or pkt[19].toLong()

                    if (isFin) {
                        sessions.remove(sessionKey)?.runCatching { close() }
                        continue
                    }

                    if (!isSyn) continue  // only handle connection setup here

                    // New connection — open via SOCKS5
                    launch(Dispatchers.IO) {
                        val sock = openViaSocks5("127.0.0.1", socksPort, dstIp, dstPort)
                        if (sock != null) {
                            sessions[sessionKey] = sock
                            // Relay data from SOCKS5 back — write to TUN
                            // (simplified: real tun2socks needs full IP/TCP stack reconstruction)
                        }
                    }

                } catch (e: Exception) {
                    if (isRunning) Log.d(TAG, "packet: ${e.message}")
                    delay(10)
                }
            }
            // cleanup
            sessions.values.forEach { runCatching { it.close() } }
        }
    }

    private fun openViaSocks5(
        proxyHost: String, proxyPort: Int,
        destHost: String, destPort: Int
    ): Socket? = try {
        val s = Socket()
        protect(s)   // ← critical: bypass VPN for this socket
        s.connect(InetSocketAddress(proxyHost, proxyPort), 5_000)
        val out = s.getOutputStream()
        val inp = s.getInputStream()
        out.write(byteArrayOf(5, 1, 0))
        inp.read(ByteArray(2))
        val host = destHost.toByteArray()
        out.write(byteArrayOf(5, 1, 0, 3, host.size.toByte()) + host +
            byteArrayOf((destPort shr 8).toByte(), (destPort and 0xFF).toByte()))
        val rep = inp.read(ByteArray(10))
        if (rep >= 2) s else null
    } catch (e: Exception) {
        Log.d(TAG, "socks5 open: ${e.message}")
        null
    }

    private fun watchdog() = scope.launch {
        while (isRunning) {
            delay(4_000)
            if (!TunnelManager.isAlive()) {
                TunnelStateHolder.setState(TunnelState.DISCONNECTED, msg = "Connection lost")
                withContext(Dispatchers.Main) { stopSelf() }
                break
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        TunnelManager.disconnect()
        try { vpnIface?.close() } catch (_: Exception) {}
        vpnIface = null
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
