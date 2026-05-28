package com.sshtunnel

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.sshtunnel.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var pendingVpnStart = false

    // VPN permission launcher
    private val vpnPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) startTunnel()
        else toast("VPN permission denied")
    }

    // Notification permission launcher (Android 13+)
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        requestNotifPermission()
        loadProfile()
        observeState()
        setupListeners()
    }

    private fun setupListeners() {
        b.btnConnect.setOnClickListener {
            if (TunnelStateHolder.isConnected) {
                stopTunnel()
            } else {
                saveProfile()
                startTunnel()
            }
        }

        b.btnSaveProfile.setOnClickListener {
            saveProfile()
            toast("Profile saved")
        }

        b.btnAddBypass.setOnClickListener {
            val domain = b.etBypassInput.text.toString().trim()
            if (domain.isNotBlank()) {
                val current = getBypassList().toMutableList()
                if (!current.contains(domain)) {
                    current.add(domain)
                    setBypassList(current)
                    b.etBypassInput.text?.clear()
                }
            }
        }

        b.rgMode.setOnCheckedChangeListener { _, checkedId ->
            // mode switching handled on save
        }
    }

    private fun startTunnel() {
        val mode = if (b.rbVpn.isChecked) TunnelMode.VPN else TunnelMode.SOCKS5

        if (mode == TunnelMode.VPN) {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                pendingVpnStart = true
                vpnPermLauncher.launch(intent)
                return
            }
        }

        TunnelStateHolder.setState(TunnelState.CONNECTING, mode)
        val svcIntent = when (mode) {
            TunnelMode.VPN -> Intent(this, SshVpnService::class.java).apply {
                action = SshVpnService.ACTION_START
            }
            TunnelMode.SOCKS5 -> Intent(this, SshProxyService::class.java).apply {
                action = SshProxyService.ACTION_START
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svcIntent)
        } else {
            startService(svcIntent)
        }
    }

    private fun stopTunnel() {
        stopService(Intent(this, SshProxyService::class.java))
        stopService(Intent(this, SshVpnService::class.java))
    }

    private fun observeState() {
        lifecycleScope.launch {
            TunnelStateHolder.status.collect { status ->
                updateUi(status)
            }
        }
    }

    private fun updateUi(status: TunnelStatus) {
        when (status.state) {
            TunnelState.DISCONNECTED -> {
                b.btnConnect.text = "▶  Connect"
                b.btnConnect.isEnabled = true
                b.statusDot.setBackgroundResource(R.drawable.dot_red)
                b.tvStatus.text = "Disconnected"
                b.cardSettings.alpha = 1f
                b.cardSettings.isEnabled = true
            }
            TunnelState.CONNECTING -> {
                b.btnConnect.text = "⌛  Connecting…"
                b.btnConnect.isEnabled = false
                b.statusDot.setBackgroundResource(R.drawable.dot_yellow)
                b.tvStatus.text = "Connecting…"
            }
            TunnelState.CONNECTED -> {
                val modeLabel = if (status.mode == TunnelMode.VPN) "VPN" else "SOCKS5"
                b.btnConnect.text = "■  Disconnect"
                b.btnConnect.isEnabled = true
                b.statusDot.setBackgroundResource(R.drawable.dot_green)
                b.tvStatus.text = "Connected — $modeLabel"
                b.cardSettings.alpha = 0.6f
                b.cardSettings.isEnabled = false
            }
        }
    }

    private fun loadProfile() {
        val cfg = ProfileManager.getActive(this)
        b.etHost.setText(cfg.host)
        b.etPort.setText(cfg.port.toString())
        b.etUsername.setText(cfg.username)
        b.etPassword.setText(cfg.password)
        b.etSocksPort.setText(cfg.socksPort.toString())
        b.rbVpn.isChecked    = cfg.mode == TunnelMode.VPN
        b.rbSocks.isChecked  = cfg.mode == TunnelMode.SOCKS5
        setBypassList(cfg.bypassDomains)
    }

    private fun saveProfile() {
        val mode = if (b.rbVpn.isChecked) TunnelMode.VPN else TunnelMode.SOCKS5
        val cfg = TunnelConfig(
            host          = b.etHost.text.toString().trim(),
            port          = b.etPort.text.toString().toIntOrNull() ?: 22,
            username      = b.etUsername.text.toString().trim(),
            password      = b.etPassword.text.toString(),
            mode          = mode,
            socksPort     = b.etSocksPort.text.toString().toIntOrNull() ?: 9000,
            bypassDomains = getBypassList()
        )
        val profiles = ProfileManager.loadProfiles(this).toMutableList()
        val idx      = ProfileManager.getActiveIndex(this)
        if (idx < profiles.size) profiles[idx] = cfg else profiles.add(cfg)
        ProfileManager.saveProfiles(this, profiles)
    }

    private fun getBypassList(): List<String> =
        b.etBypassDomains.text.toString()
            .lines().map { it.trim() }.filter { it.isNotBlank() }

    private fun setBypassList(list: List<String>) {
        b.etBypassDomains.setText(list.joinToString("\n"))
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
