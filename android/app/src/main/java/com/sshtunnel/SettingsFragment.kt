package com.sshtunnel

import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.sshtunnel.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) =
        FragmentSettingsBinding.inflate(i, c, false).also { _b = it }.root

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)
        load()
        b.btnSave.setOnClickListener { save() }
    }

    private fun load() {
        val cfg = ProfileManager.getActive(requireContext())
        b.etSocksPort.setText(cfg.socksPort.toString())
        b.swAutoConnect.isChecked = cfg.autoConnect
    }

    private fun save() {
        val ctx      = requireContext()
        val profiles = ProfileManager.loadProfiles(ctx).toMutableList()
        val idx      = ProfileManager.getActiveIndex(ctx)
        val old      = profiles.getOrElse(idx) { TunnelConfig() }
        profiles[idx] = old.copy(
            socksPort   = b.etSocksPort.text.toString().toIntOrNull() ?: 9000,
            autoConnect = b.swAutoConnect.isChecked
        )
        ProfileManager.saveProfiles(ctx, profiles)
        Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
