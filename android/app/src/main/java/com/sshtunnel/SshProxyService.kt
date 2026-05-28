package com.sshtunnel

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class SshProxyService : Service() {

    companion object {
        const val ACTION_START  = "com.sshtunnel.PROXY_START"
        const val ACTION_STOP   = "com.sshtunnel.PROXY_STOP"
        const val CHANNEL_ID    = "ssh_proxy_channel"
        const val NOTIF_ID      = 1001
        var isRunning           = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startProxy()
            ACTION_STOP  -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startProxy() {
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Connecting…"))
        isRunning = true

        val cfg = ProfileManager.getActive(this)
        scope.launch {
            when (val r = TunnelManager.connect(cfg)) {
                is ConnectResult.Success -> {
                    updateNotification("SOCKS5 active — :${cfg.socksPort}")
                    TunnelStateHolder.setState(TunnelState.CONNECTED, cfg.mode)
                    startWatchdog()
                }
                is ConnectResult.Failure -> {
                    TunnelStateHolder.setState(TunnelState.DISCONNECTED)
                    updateNotification("Failed: ${r.error}")
                    stopSelf()
                }
            }
        }
    }

    private fun startWatchdog() {
        scope.launch(Dispatchers.IO) {
            while (isRunning && TunnelManager.isAlive()) {
                delay(5_000)
            }
            if (isRunning) {
                withContext(Dispatchers.Main) {
                    TunnelStateHolder.setState(TunnelState.DISCONNECTED)
                    updateNotification("Disconnected")
                    stopSelf()
                }
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
            val ch = NotificationChannel(CHANNEL_ID, "SSH Proxy", NotificationManager.IMPORTANCE_LOW)
            ch.description = "SSH Tunnel proxy service"
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, SshProxyService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SSH Tunnel")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentIntent(openPi)
            .addAction(R.drawable.ic_tile, "Disconnect", stopPi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }
}
