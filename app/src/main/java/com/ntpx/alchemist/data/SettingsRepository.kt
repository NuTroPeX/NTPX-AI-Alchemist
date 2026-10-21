package com.ntpx.alchemist.data

import android.content.Context
import android.content.SharedPreferences
import com.ntpx.alchemist.agent.Grant
import com.ntpx.alchemist.agent.GrantTable
import com.ntpx.alchemist.agent.Permission
import com.ntpx.alchemist.registry.RoutingPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val allowCloudModels: Boolean = false,
    val allowLocalNetwork: Boolean = true,
    val preferredModelId: String? = null,
) {
    val routingPolicy: RoutingPolicy
        get() = RoutingPolicy(allowCloud = allowCloudModels, allowLocalNetwork = allowLocalNetwork, preferredModelId = preferredModelId)
}

/** Persists routing settings and per-agent permission overrides in private SharedPreferences. */
class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val state = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = state.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(state.value)
        prefs.edit()
            .putBoolean(KEY_CLOUD, next.allowCloudModels)
            .putBoolean(KEY_LAN, next.allowLocalNetwork)
            .putString(KEY_PREFERRED, next.preferredModelId)
            .apply()
        state.value = next
    }

    fun loadGrants(): Map<String, Map<Permission, Grant>> {
        val result = mutableMapOf<String, MutableMap<Permission, Grant>>()
        prefs.all.forEach { (key, value) ->
            if (!key.startsWith(GRANT_PREFIX) || value !is String) return@forEach
            val parts = key.removePrefix(GRANT_PREFIX).split('|')
            if (parts.size != 2) return@forEach
            val permission = Permission.entries.firstOrNull { it.name == parts[1] } ?: return@forEach
            val grant = Grant.entries.firstOrNull { it.name == value } ?: return@forEach
            result.getOrPut(parts[0]) { mutableMapOf() }[permission] = grant
        }
        return result
    }

    fun saveGrants(overrides: Map<String, Map<Permission, Grant>>) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(GRANT_PREFIX) }.forEach(editor::remove)
        overrides.forEach { (agent, grants) ->
            grants.forEach { (permission, grant) -> editor.putString("$GRANT_PREFIX$agent|${permission.name}", grant.name) }
        }
        editor.apply()
    }

    fun grantTable(): GrantTable = GrantTable(initial = loadGrants())

    private fun read() = AppSettings(
        allowCloudModels = prefs.getBoolean(KEY_CLOUD, false),
        allowLocalNetwork = prefs.getBoolean(KEY_LAN, true),
        preferredModelId = prefs.getString(KEY_PREFERRED, null),
    )

    private companion object {
        const val FILE = "alchemist_settings"
        const val KEY_CLOUD = "allow_cloud_models"
        const val KEY_LAN = "allow_local_network"
        const val KEY_PREFERRED = "preferred_model_id"
        const val GRANT_PREFIX = "grant."
    }
}
