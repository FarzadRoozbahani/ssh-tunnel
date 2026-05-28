package com.sshtunnel

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayoutMediator
import com.sshtunnel.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val vpnPerm = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (it.resultCode == RESULT_OK) doStartTunnel() else toast("VPN permission denied") }

    private val notifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setupTabs()
        observeState()
    }

    private fun setupTabs() {
        val fragments = listOf(ConnectFragment(), BypassFragment(), SettingsFragment())
        val titles    = listOf("Connect", "Bypass", "Settings")

        b.viewPager.adapter = object : androidx.viewpager2.adapter.FragmentStateAdapter(this) {
            override fun getItemCount() = fragments.size
            override fun createFragment(pos: Int): Fragment = fragments[pos]
        }
        b.viewPager.isUserInputEnabled = false

        TabLayoutMediator(b.tabLayout, b.viewPager) { tab, pos ->
            tab.text = titles[pos]
        }.attach()
    }

    private fun observeState() {
        lifecycleScope.launch {
            TunnelStateHolder.status.collect { status ->
                when (status.state) {
                    TunnelState.DISCONNECTED -> {
                        b.statusDot.setBackgroundResource(R.drawable.dot_red)
                        b.tvStatus.text = if (status.message.isNotBlank()) status.message else "Disconnected"
                    }
                    TunnelState.CONNECTING -> {
                        b.statusDot.setBackgroundResource(R.drawable.dot_yellow)
                        b.tvStatus.text = "Connecting…"
                    }
                    TunnelState.CONNECTED -> {
                        b.statusDot.setBackgroundResource(R.drawable.dot_green)
                        b.tvStatus.text = "Connected — ${if (status.mode == TunnelMode.VPN) "VPN" else "SOCKS5"}"
                    }
                }
            }
        }
    }

    fun startTunnel() {
        val cfg = ProfileManager.getActive(this)
        if (cfg.host.isBlank()) { toast("Enter server host first"); return }
        if (cfg.mode == TunnelMode.VPN) {
            val intent = VpnService.prepare(this)
            if (intent != null) { vpnPerm.launch(intent); return }
        }
        doStartTunnel()
    }

    fun stopTunnel() {
        // Send explicit STOP action — startService is needed to reach onStartCommand
        if (SshProxyService.isRunning) {
            startService(Intent(this, SshProxyService::class.java).apply { action = SshProxyService.ACTION_STOP })
        }
        if (SshVpnService.isRunning) {
            startService(Intent(this, SshVpnService::class.java).apply { action = SshVpnService.ACTION_STOP })
        }
        // Fallback: if neither flag is set but we think we're connected, force disconnect
        if (!SshProxyService.isRunning && !SshVpnService.isRunning) {
            TunnelManager.disconnect()
            TunnelStateHolder.setState(TunnelState.DISCONNECTED)
        }
    }

    private fun doStartTunnel() {
        val cfg = ProfileManager.getActive(this)
        val intent = when (cfg.mode) {
            TunnelMode.VPN    -> Intent(this, SshVpnService::class.java).apply { action = SshVpnService.ACTION_START }
            TunnelMode.SOCKS5 -> Intent(this, SshProxyService::class.java).apply { action = SshProxyService.ACTION_START }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
