package com.sshtunnel

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class SshProxyService : Service() {

    companion object {
        const val ACTION_START = "com.sshtunnel.PROXY_START"
        const val ACTION_STOP  = "com.sshtunnel.PROXY_STOP"
        const val CHANNEL_ID   = "ssh_proxy"
        const val NOTIF_ID     = 1001
        var isRunning = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_START -> if (!isRunning) startProxy()
        }
        return START_NOT_STICKY
    }

    private fun startProxy() {
        createChannel()
        isRunning = true
        startForeground(NOTIF_ID, buildNotif("Connecting…"))
        TunnelStateHolder.setState(TunnelState.CONNECTING)

        val cfg = ProfileManager.getActive(this)
        scope.launch {
            when (val r = TunnelManager.connect(cfg)) {
                is ConnectResult.Success -> {
                    notify("SOCKS5 active — :${cfg.socksPort}")
                    TunnelStateHolder.setState(TunnelState.CONNECTED, cfg.mode, r.message)
                    watchdog()
                }
                is ConnectResult.Failure -> {
                    TunnelStateHolder.setState(TunnelState.DISCONNECTED, msg = r.error)
                    stopSelf()
                }
            }
        }
    }

    private fun watchdog() = scope.launch(Dispatchers.IO) {
        while (isRunning) {
            delay(4_000)
            if (!TunnelManager.isAlive()) {
                withContext(Dispatchers.Main) {
                    TunnelStateHolder.setState(TunnelState.DISCONNECTED, msg = "Connection lost")
                    stopSelf()
                }
                break
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        TunnelManager.disconnect()
        TunnelStateHolder.setState(TunnelState.DISCONNECTED)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "SSH Tunnel", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotif(text: String): Notification {
        // Disconnect PendingIntent — explicit action
        val stopIntent = Intent(this, SshProxyService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 100, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SSH Tunnel — SOCKS5")
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
