package com.csqtt.client

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.json.JSONObject

/**
 * Stores as 2.5.3 left them, keyed and typed the way its writers wrote them (B5b1). A snapshot is JSON:
 * `{"strings": {...}, "ints": {...}, "booleans": {...}, "longs": {...}}`.
 */
internal object Store253Snapshots {
    val linkHashA = "A".repeat(20)
    val linkHashB = "B".repeat(20)
    val ownHash = "C".repeat(20)
    val participantLink =
        "csqtt://connect?v=2&host=198.51.100.9&peer=46000&password=link-pass&hashes=$linkHashA+$linkHashB"

    /** An owner on a second server (46100), the stored default of 18 workers, the old «auto» hash mode, V2 chosen. */
    val owner = """
        {"strings": {"peer": "203.0.113.5", "vk_hashes": "${"D".repeat(20)}", "vk_hash_mode": "auto",
                     "vk_auth_mode": "vkcalls", "connection_password_local": "owner-pass", "ui_design": "v2",
                     "deploy_ip": "203.0.113.5", "deploy_login": "root", "deploy_password_encrypted": "c1",
                     "deploy_main_password_encrypted": "c2", "turn_transport": "tcp", "excluded_apps": "a.b,c.d",
                     "battery_saver_mode": "below", "theme_mode": "dark"},
         "ints": {"server_peer_port": 46100, "server_web_port": 46102, "workers_per_hash": 18,
                  "battery_saver_percent": 30, "active_profile": 0},
         "booleans": {"manual_ports_enabled": true, "default_role_decided": true, "extra_workers": false,
                      "deploy_server_configured": true, "split_tunnel_whitelist_migrated": true},
         "longs": {"deploy_last_inspection_at": 1761000000000, "connection_generation": 1761000001}}
    """.trimIndent()

    /** A participant on slot 3: its own hashes repeat both of the link's, and it took one of the owner's out. */
    val participant = """
        {"strings": {"csqtt_link_3": "$participantLink", "vk_hashes_3": "$linkHashA, $ownHash,$linkHashB",
                     "link_hashes_removed_3": "$linkHashA", "vk_hash_mode_3": "manual", "profile_name_3": "Друг"},
         "ints": {"active_profile": 3, "workers_per_hash_3": 5},
         "booleans": {"csqtt_link_mode_3": true, "default_role_decided": true}}
    """.trimIndent()

    /** A slot from a subscription, a VK-token account, a whitelist on Wi-Fi pause. */
    val subscriptionAndToken = """
        {"strings": {"peer_5": "192.0.2.10", "subscription_url_5": "https://sub.example.invalid/s/abc",
                     "subscription_title_5": "Sub", "subscription_node_5": "node A", "connection_password_local_5": "s",
                     "subscription_meta_v1": "{}",
                     "peer_2": "192.0.2.20", "vk_access_token_encrypted_2": "c3", "vk_access_token_user_id_2": "42",
                     "vk_hash_mode_2": "auto_api", "vk_auth_mode_2": "bogus"},
         "ints": {"subscription_power_5": 40, "workers_per_hash_5": 40, "workers_per_hash_2": 75},
         "booleans": {"default_role_decided": true, "auto_pause_on_wifi": true}}
    """.trimIndent()

    /** All 32 slots filled, each with 18 workers. */
    val allSlots: String = JSONObject().apply {
        val strings = JSONObject()
        val ints = JSONObject()
        for (p in CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX) {
            val suffix = if (p == 0) "" else "_$p"
            strings.put("peer$suffix", "192.0.2.${p + 1}")
            strings.put("connection_password_local$suffix", "pass$p")
            ints.put("workers_per_hash$suffix", 18)
        }
        put("strings", strings)
        put("ints", ints)
        put("booleans", JSONObject().put("default_role_decided", true))
    }.toString()

    fun load(json: String): MutablePreferences {
        val root = JSONObject(json)
        val prefs = mutablePreferencesOf()
        root.optJSONObject("strings")?.let { o -> o.keys().forEach { prefs[stringPreferencesKey(it)] = o.getString(it) } }
        root.optJSONObject("ints")?.let { o -> o.keys().forEach { prefs[intPreferencesKey(it)] = o.getInt(it) } }
        root.optJSONObject("booleans")?.let { o -> o.keys().forEach { prefs[booleanPreferencesKey(it)] = o.getBoolean(it) } }
        root.optJSONObject("longs")?.let { o -> o.keys().forEach { prefs[longPreferencesKey(it)] = o.getLong(it) } }
        return prefs
    }

    fun asMap(prefs: Preferences): Map<String, Any> = prefs.asMap().mapKeys { it.key.name }

    /** The keys whose value differs, appeared or went. */
    fun changedKeys(before: Map<String, Any>, after: Map<String, Any>): Set<String> =
        (before.keys + after.keys).filter { before[it] != after[it] }.toSet()
}
