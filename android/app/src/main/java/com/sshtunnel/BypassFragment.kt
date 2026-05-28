package com.sshtunnel

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import com.sshtunnel.databinding.FragmentBypassBinding

class BypassFragment : Fragment() {

    private var _b: FragmentBypassBinding? = null
    private val b get() = _b!!

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) =
        FragmentBypassBinding.inflate(i, c, false).also { _b = it }.root

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)
        loadBypass()

        b.btnAdd.setOnClickListener {
            val d = b.etNewDomain.text.toString().trim()
            if (d.isNotBlank()) {
                val current = getList().toMutableList()
                if (!current.contains(d)) {
                    current.add(d)
                    setList(current)
                    b.etNewDomain.text?.clear()
                    save(current)
                }
            }
        }

        b.btnClear.setOnClickListener {
            setList(emptyList())
            save(emptyList())
        }
    }

    private fun loadBypass() {
        val cfg = ProfileManager.getActive(requireContext())
        setList(cfg.bypassDomains)
    }

    private fun getList(): List<String> =
        b.etDomains.text.toString().lines().map { it.trim() }.filter { it.isNotBlank() }

    private fun setList(list: List<String>) {
        b.etDomains.setText(list.joinToString("\n"))
    }

    private fun save(list: List<String>) {
        val ctx      = requireContext()
        val profiles = ProfileManager.loadProfiles(ctx).toMutableList()
        val idx      = ProfileManager.getActiveIndex(ctx)
        val old      = profiles.getOrElse(idx) { TunnelConfig() }
        profiles[idx] = old.copy(bypassDomains = list)
        ProfileManager.saveProfiles(ctx, profiles)
    }

    override fun onPause() { super.onPause(); save(getList()) }
    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
