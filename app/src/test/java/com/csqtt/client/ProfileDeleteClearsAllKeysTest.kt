package com.csqtt.client

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.csqtt.client.Store253Snapshots.asMap
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B5b1, M-37 and M-40: every per-profile key is declared once with its type and group, and deleting a profile's
 * connection (or its VPS) removes every key of that group from that slot and nothing else.
 */
class ProfileDeleteClearsAllKeysTest {
    private val source = listOf(
        File("app/src/main/java/com/csqtt/client/SettingsStore.kt"),
        File("src/main/java/com/csqtt/client/SettingsStore.kt"),
    ).first(File::isFile).readText()

    private val declaredPerProfile: Map<String, String> =
        Regex("""private val ([A-Z_]+) = profile(?:String|Int|Boolean|Long)\("([a-z0-9_]+)"""")
            .findAll(source).associate { it.groupValues[1] to it.groupValues[2] }
    private val declaredGlobal: Set<String> =
        Regex("""private val ([A-Z_]+) = (?:string|int|boolean|long)PreferencesKey\(""")
            .findAll(source).map { it.groupValues[1] }.toSet()

    @Test
    fun `every key the store reads or writes per profile is declared per profile, and no global key is`() {
        val usedPerProfile = buildSet {
            Regex("""getProfileKey\(([A-Z_]+),""").findAll(source).forEach { add(it.groupValues[1]) }
            Regex("""(?:putSecret|readSecret|readSecretState)\((?:prefs, )?([A-Z_]+), ([A-Z_]+)""").findAll(source)
                .forEach { add(it.groupValues[1]); add(it.groupValues[2]) }
        }
        assertTrue("no per-profile use found", usedPerProfile.size > 60)
        assertEquals(emptySet<String>(), usedPerProfile - declaredPerProfile.keys)
        assertEquals(emptySet<String>(), usedPerProfile intersect declaredGlobal)
    }

    @Test
    fun `the registry holds exactly the declared keys, each in one group`() {
        val groups = SettingsStore.perProfileKeyGroups
        assertEquals(declaredPerProfile.values.toSet(), groups.keys)
        val vps = groups.filterValues { it == ProfileKeyGroup.VPS }.keys
        assertTrue(vps.all { it.startsWith("deploy_") || it.startsWith("ssh_") || it in setOf("docker_install", "manual_ports_enabled") })
        // the connection keeps its password, peer port and every VK and routing key
        for (name in listOf("connection_password_local", "server_peer_port", "vk_hashes", "vk_access_token_encrypted",
            "vk_hash_mode", "vk_hash_check_results", "excluded_apps", "direct_domains_black", "link_hashes_removed",
            "subscription_url", "dpi_split", "server_web_port", "vps_ref")) {
            assertEquals(name, ProfileKeyGroup.CONNECTION, groups[name])
        }
    }

    private fun fullStore() = mutablePreferencesOf().apply {
        for (name in SettingsStore.perProfileKeyGroups.keys) {
            for (profile in listOf(0, 7, 8)) {
                this[stringPreferencesKey(if (profile == 0) name else "${name}_$profile")] = "x"
            }
        }
        this[intPreferencesKey("active_profile")] = 7
        this[stringPreferencesKey("battery_saver_mode")] = "below"
        this[stringPreferencesKey("theme_mode")] = "dark"
    }

    private fun slotKeys(profile: Int, group: ProfileKeyGroup) = SettingsStore.perProfileKeyGroups
        .filterValues { it == group }.keys
        .map { if (profile == 0) it else "${it}_$profile" }
        .toSet()

    @Test
    fun `deleting a connection removes its every key and leaves the VPS, the other slots and the globals`() {
        for (profile in listOf(7, 0)) {
            for (group in ProfileKeyGroup.entries) {
                val prefs = fullStore()
                val before = asMap(prefs)
                SettingsStore.clearProfileKeys(prefs, profile, group)
                assertEquals("profile $profile $group", before - slotKeys(profile, group), asMap(prefs))
            }
        }
    }
}
