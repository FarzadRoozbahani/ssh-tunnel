package com.sshtunnel

import android.app.*
import android.content.Intent
import android.net.InetAddresses
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.InterfaceUpdateListener
import kotlinx.coroutines.*
import java.io.File
import java.net.InetAddress

private const val TAG = "SshVpnService"

class SshVpnService : VpnService(), PlatformInterface {

    companion object {
        const val ACTION_START = "com.sshtunnel.VPN_START"
        const val ACTION_STOP  = "com.sshtunnel.VPN_STOP"
        const val CHANNEL_ID   = "ssh_vpn"
        const val NOTIF_ID     = 1002
        var isRunning = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var box: io.nekohasekai.libbox.BoxService? = null
    private var vpnIface: ParcelFileDescriptor? = null

    // ── PlatformInterface ─────────────────────────────────────
    override fun usePlatformAutoDetectInterfaceControl() = true
    override fun autoDetectInterfaceControl(fd: Int) { protect(fd) }

    override fun openTun(options: TunOptions): Int {
        val builder = Builder()
            .setSession("SSH Tunnel")
            .setMtu(options.mtu.toInt())

        // IPv4 addresses — use GetInet4Address() iterator
        var iter = options.inet4Address
        while (iter.hasNext()) {
            val prefix = iter.next()
            builder.addAddress(prefix.address, prefix.prefix.toInt())
        }

        // IPv6 addresses
        iter = options.inet6Address
        while (iter.hasNext()) {
            val prefix = iter.next()
            builder.addAddress(prefix.address, prefix.prefix.toInt())
        }

        // DNS server
        val dnsAddr = runCatching { options.dnsServerAddress }.getOrNull()
        if (!dnsAddr.isNullOrBlank()) {
            builder.addDnsServer(dnsAddr)
        } else {
            builder.addDnsServer("1.1.1.1")
            builder.addDnsServer("8.8.8.8")
        }

        // Routes
        iter = options.inet4RouteAddress
        while (iter.hasNext()) {
            val r = iter.next()
            builder.addRoute(r.address, r.prefix.toInt())
        }
        iter = options.inet6RouteAddress
        while (iter.hasNext()) {
            val r = iter.next()
            builder.addRoute(r.address, r.prefix.toInt())
        }

        // HTTP proxy
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            options.isHTTPProxyEnabled) {
            runCatching {
                builder.setHttpProxy(
                    ProxyInfo.buildDirectProxy(
                        options.httpProxyServer,
                        options.httpProxyServerPort.toInt()
                    )
                )
            }
        }

        try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

        vpnIface = builder.establish()
        return vpnIface?.fd ?: -1
    }

    override fun writeLog(message: String) { Log.d(TAG, "box: $message") }
    override fun useProcFS() = false

    override fun findConnectionOwner(
        ipProtocol: Int, sourceAddress: String, sourcePort: Int,
        destinationAddress: String, destinationPort: Int
    ) = 0

    override fun packageNameByUid(uid: Int) = ""
    override fun uidByPackageName(packageName: String) = 0

    override fun usePlatformDefaultInterfaceMonitor() = true
    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {}
    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {}

    override fun usePlatformInterfaceGetter() = false
    override fun getInterfaces(): NetworkInterfaceIterator = EmptyNetworkIterator()

    override fun underNetworkExtension() = false
    override fun includeAllNetworks() = false
    override fun clearDNSCache() {}
    override fun readWIFIState() = null
    override fun sendNotification(notification: Notification?) {}

    // ── Service lifecycle ─────────────────────────────────────
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP  -> { doStop(); return START_NOT_STICKY }
            ACTION_START -> if (!isRunning) startVpn()
        }
        return START_NOT_STICKY
    }

    private fun doStop() {
        isRunning = false
        try { box?.close() } catch (_: Exception) {}
        box = null
        try { vpnIface?.close() } catch (_: Exception) {}
        vpnIface = null
        scope.cancel()
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
            when (val r = TunnelManager.connect(cfg)) {
                is ConnectResult.Failure -> {
                    TunnelStateHolder.setState(TunnelState.DISCONNECTED, msg = r.error)
                    withContext(Dispatchers.Main) { doStop() }
                    return@launch
                }
                is ConnectResult.Success -> Log.i(TAG, "SSH ready :${cfg.socksPort}")
            }

            val configFile = File(filesDir, "singbox.json")
            configFile.writeText(buildConfig(cfg.socksPort, cfg.bypassDomains))

            try {
                Libbox.setup(filesDir.absolutePath, filesDir.absolutePath,
                             filesDir.absolutePath, false)
                val service = Libbox.newService(configFile.absolutePath, this@SshVpnService)
                service.start()
                box = service
                withContext(Dispatchers.Main) { notify("VPN active") }
                TunnelStateHolder.setState(TunnelState.CONNECTED, TunnelMode.VPN)
                watchdog()
            } catch (e: Exception) {
                Log.e(TAG, "libbox: ${e.message}")
                TunnelStateHolder.setState(TunnelState.DISCONNECTED, msg = "VPN error: ${e.message}")
                withContext(Dispatchers.Main) { doStop() }
            }
        }
    }

    private fun buildConfig(socksPort: Int, bypass: List<String>): String {
        val bypassList = bypass.filter { it.isNotBlank() && !it.startsWith("ext:") }
            .joinToString(",") { "\"$it\"" }
        val bypassRule = if (bypassList.isNotBlank())
            """{"domain_suffix":[$bypassList],"outbound":"direct"},""" else ""
        return """{"log":{"level":"warn"},"dns":{"servers":[{"tag":"r","address":"tls://1.1.1.1","detour":"proxy"},{"tag":"l","address":"223.5.5.5","detour":"direct"}],"rules":[{"outbound":"any","server":"l"}],"final":"r"},"inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"mtu":9000,"auto_route":true,"strict_route":true,"stack":"system","sniff":true}],"outbounds":[{"type":"socks","tag":"proxy","server":"127.0.0.1","server_port":$socksPort},{"type":"direct","tag":"direct"},{"type":"block","tag":"block"},{"type":"dns","tag":"dns-out"}],"route":{"rules":[{"protocol":"dns","outbound":"dns-out"},$bypassRule{"ip_is_private":true,"outbound":"direct"}],"final":"proxy","auto_detect_interface":true}}"""
    }

    private fun watchdog() = scope.launch {
        while (isRunning) {
            delay(5_000)
            if (!TunnelManager.isAlive()) {
                TunnelStateHolder.setState(TunnelState.DISCONNECTED, msg = "Connection lost")
                withContext(Dispatchers.Main) { doStop() }
                break
            }
        }
    }

    override fun onDestroy() { doStop(); super.onDestroy() }
    override fun onRevoke()  { doStop(); super.onRevoke() }
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
        val openPi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SSH Tunnel — VPN").setContentText(text)
            .setSmallIcon(R.drawable.ic_tile).setContentIntent(openPi)
            .addAction(R.drawable.ic_tile, "Disconnect", stopPi)
            .setOngoing(true).build()
    }
    private fun notify(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(text))
}

class EmptyNetworkIterator : NetworkInterfaceIterator {
    override fun hasNext() = false
    override fun next() = throw NoSuchElementException()
}
