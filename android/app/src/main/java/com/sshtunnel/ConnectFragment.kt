package com.sshtunnel

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.sshtunnel.databinding.FragmentConnectBinding
import kotlinx.coroutines.launch

class ConnectFragment : Fragment() {

    private var _b: FragmentConnectBinding? = null
    private val b get() = _b!!

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) =
        FragmentConnectBinding.inflate(i, c, false).also { _b = it }.root

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)
        loadProfile()

        b.btnConnect.setOnClickListener {
            if (TunnelStateHolder.isConnected) {
                (activity as? MainActivity)?.stopTunnel()
            } else {
                saveProfile()
                (activity as? MainActivity)?.startTunnel()
            }
        }

        lifecycleScope.launch {
            TunnelStateHolder.status.collect { status ->
                when (status.state) {
                    TunnelState.DISCONNECTED -> {
                        b.btnConnect.text = "▶  Connect"
                        b.btnConnect.isEnabled = true
                        setInputsEnabled(true)
                    }
                    TunnelState.CONNECTING -> {
                        b.btnConnect.text = "Connecting…"
                        b.btnConnect.isEnabled = false
                        setInputsEnabled(false)
                    }
                    TunnelState.CONNECTED -> {
                        val m = if (status.mode == TunnelMode.VPN) "VPN" else "SOCKS5"
                        b.btnConnect.text = "■  Disconnect ($m)"
                        b.btnConnect.isEnabled = true
                        setInputsEnabled(false)
                    }
                }
            }
        }
    }

    private fun setInputsEnabled(enabled: Boolean) {
        b.etHost.isEnabled     = enabled
        b.etPort.isEnabled     = enabled
        b.etUsername.isEnabled = enabled
        b.etPassword.isEnabled = enabled
        b.rgMode.isEnabled     = enabled
        b.rbSocks.isEnabled    = enabled
        b.rbVpn.isEnabled      = enabled
    }

    private fun loadProfile() {
        val cfg = ProfileManager.getActive(requireContext())
        b.etHost.setText(cfg.host)
        b.etPort.setText(cfg.port.toString())
        b.etUsername.setText(cfg.username)
        b.etPassword.setText(cfg.password)
        b.rbVpn.isChecked   = cfg.mode == TunnelMode.VPN
        b.rbSocks.isChecked = cfg.mode == TunnelMode.SOCKS5
    }

    fun saveProfile() {
        val profiles = ProfileManager.loadProfiles(requireContext()).toMutableList()
        val idx      = ProfileManager.getActiveIndex(requireContext())
        val old      = profiles.getOrElse(idx) { TunnelConfig() }
        val updated  = old.copy(
            host     = b.etHost.text.toString().trim(),
            port     = b.etPort.text.toString().toIntOrNull() ?: 22,
            username = b.etUsername.text.toString().trim(),
            password = b.etPassword.text.toString(),
            mode     = if (b.rbVpn.isChecked) TunnelMode.VPN else TunnelMode.SOCKS5
        )
        if (idx < profiles.size) profiles[idx] = updated else profiles.add(updated)
        ProfileManager.saveProfiles(requireContext(), profiles)
    }

    override fun onPause() {
        super.onPause()
        if (!TunnelStateHolder.isConnected) saveProfile()
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
