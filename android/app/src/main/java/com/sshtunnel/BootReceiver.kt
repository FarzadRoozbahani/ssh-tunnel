package com.sshtunnel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val cfg = ProfileManager.getActive(ctx)
        if (!cfg.autoConnect) return

        val svcIntent = when (cfg.mode) {
            TunnelMode.VPN    -> Intent(ctx, SshVpnService::class.java).apply { action = SshVpnService.ACTION_START }
            TunnelMode.SOCKS5 -> Intent(ctx, SshProxyService::class.java).apply { action = SshProxyService.ACTION_START }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(svcIntent)
        } else {
            ctx.startService(svcIntent)
        }
    }
}
