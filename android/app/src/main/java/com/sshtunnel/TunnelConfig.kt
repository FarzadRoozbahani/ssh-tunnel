package com.sshtunnel

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

data class TunnelConfig(
    val name: String = "Default",
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val password: String = "",
    val useKey: Boolean = false,
    val privateKey: String = "",
    val mode: TunnelMode = TunnelMode.SOCKS5,
    val socksPort: Int = 9000,
    val bypassDomains: List<String> = emptyList(),
    val autoConnect: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("host", host)
        put("port", port)
        put("username", username)
        put("password", password)
        put("useKey", useKey)
        put("privateKey", privateKey)
        put("mode", mode.name)
        put("socksPort", socksPort)
        put("autoConnect", autoConnect)
        put("bypassDomains", JSONArray(bypassDomains))
    }

    companion object {
        fun fromJson(j: JSONObject) = TunnelConfig(
            name         = j.optString("name", "Default"),
            host         = j.optString("host", ""),
            port         = j.optInt("port", 22),
            username     = j.optString("username", ""),
            password     = j.optString("password", ""),
            useKey       = j.optBoolean("useKey", false),
            privateKey   = j.optString("privateKey", ""),
            mode         = TunnelMode.valueOf(j.optString("mode", TunnelMode.SOCKS5.name)),
            socksPort    = j.optInt("socksPort", 9000),
            autoConnect  = j.optBoolean("autoConnect", false),
            bypassDomains = j.optJSONArray("bypassDomains")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()
        )
    }
}

enum class TunnelMode { SOCKS5, VPN }

object ProfileManager {

    private const val PREF_FILE = "ssh_tunnel_prefs"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_ACTIVE = "active_profile"

    private fun prefs(ctx: Context) = try {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx, PREF_FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
    }

    fun loadProfiles(ctx: Context): List<TunnelConfig> {
        val raw = prefs(ctx).getString(KEY_PROFILES, null) ?: return listOf(TunnelConfig())
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { TunnelConfig.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            listOf(TunnelConfig())
        }
    }

    fun saveProfiles(ctx: Context, profiles: List<TunnelConfig>) {
        val arr = JSONArray(profiles.map { it.toJson() })
        prefs(ctx).edit().putString(KEY_PROFILES, arr.toString()).apply()
    }

    fun getActiveIndex(ctx: Context) = prefs(ctx).getInt(KEY_ACTIVE, 0)

    fun setActiveIndex(ctx: Context, idx: Int) =
        prefs(ctx).edit().putInt(KEY_ACTIVE, idx).apply()

    fun getActive(ctx: Context): TunnelConfig {
        val list = loadProfiles(ctx)
        val idx  = getActiveIndex(ctx).coerceIn(0, list.lastIndex)
        return list.getOrElse(idx) { TunnelConfig() }
    }
}
