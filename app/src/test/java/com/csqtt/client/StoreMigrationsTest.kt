package com.csqtt.client

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.csqtt.client.Store253Snapshots.asMap
import com.csqtt.client.Store253Snapshots.changedKeys
import com.csqtt.client.Store253Snapshots.load
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** B5b1: the version-1 steps on stores as 2.5.3 left them; every key a step does not name stays as it was. */
class StoreMigrationsTest {
    private fun migrate(json: String): Pair<Map<String, Any>, Map<String, Any>> {
        val prefs = load(json)
        val before = asMap(prefs)
        SettingsStore.migrateStore(prefs)
        return before to asMap(prefs)
    }

    @Test
    fun `an owner keeps its role, port and secrets, while the design key goes and 18 workers become 10`() {
        val (before, after) = migrate(Store253Snapshots.owner)
        assertEquals(
            // vps_ref: step 2 (B5b2) links its deploy data as record 0
            // ui_owner_mode: step 4 (B5e1, M-75) records the role the switch showed
            setOf("ui_design", "workers_per_hash", "vk_hash_mode", "store_schema_version", "vps_ref", "ui_owner_mode"),
            changedKeys(before, after),
        )
        assertNull(after["ui_design"])
        assertEquals(10, after["workers_per_hash"])
        assertEquals(CsqttConstants.VkAutoHash.MODE_AUTO_API, after["vk_hash_mode"])
        assertEquals(SettingsStore.STORE_SCHEMA, after["store_schema_version"])
        // owner: no link mode appears, the port and the switch stay
        assertNull(after["csqtt_link_mode"])
        assertEquals(46100, after["server_peer_port"])
        assertEquals(true, after["manual_ports_enabled"])
        assertEquals(0, after["vps_ref"])
    }

    @Test
    fun `a participant's own hashes lose the link's copies, so a taken-out owner hash stays out`() {
        val (before, after) = migrate(Store253Snapshots.participant)
        assertEquals(setOf("vk_hashes_3", "workers_per_hash_3", "store_schema_version", "ui_owner_mode"), changedKeys(before, after))
        assertEquals(Store253Snapshots.ownHash, after["vk_hashes_3"])
        assertEquals(10, after["workers_per_hash_3"])
        // what the tunnel then uses: the owner's B (A was taken out), then the participant's C
        val link = com.csqtt.client.ui.legacy.utils.parseCsqttLink(Store253Snapshots.participantLink)!!
        assertEquals(
            "${Store253Snapshots.linkHashB},${Store253Snapshots.ownHash}",
            participantHashes(link.hashes, setOf(Store253Snapshots.linkHashA), after["vk_hashes_3"] as String),
        )
    }

    @Test
    fun `own hashes that are only the link's are removed outright`() {
        val prefs = load(Store253Snapshots.participant)
        prefs[stringPreferencesKey("vk_hashes_3")] = "${Store253Snapshots.linkHashB},${Store253Snapshots.linkHashA}"
        SettingsStore.migrateStore(prefs)
        assertNull(prefs[stringPreferencesKey("vk_hashes_3")])
    }

    @Test
    fun `an owner slot's hashes are never touched, nor a participant's whose link has none`() {
        val prefs = load(Store253Snapshots.participant)
        prefs[booleanPreferencesKey("csqtt_link_mode_3")] = false
        SettingsStore.migrateStore(prefs)
        assertEquals(
            "${Store253Snapshots.linkHashA}, ${Store253Snapshots.ownHash},${Store253Snapshots.linkHashB}",
            prefs[stringPreferencesKey("vk_hashes_3")],
        )
        val noHashes = load(Store253Snapshots.participant)
        noHashes[stringPreferencesKey("csqtt_link_3")] = "csqtt://connect?v=2&host=198.51.100.9&peer=46000&password=p"
        val before = asMap(noHashes)
        SettingsStore.migrateStore(noHashes)
        assertEquals(setOf("workers_per_hash_3", "store_schema_version", "ui_owner_mode"), changedKeys(before, asMap(noHashes)))
    }

    @Test
    fun `a subscription slot and a token account keep everything but the modes' and workers' spelling`() {
        val (before, after) = migrate(Store253Snapshots.subscriptionAndToken)
        // step 3 (B5c1, M-170): the subscription slot becomes the participant link it came from
        assertEquals(
            setOf(
                "vk_auth_mode_2", "workers_per_hash_2", "store_schema_version", "ui_owner_mode",
                "peer_5", "connection_password_local_5", "csqtt_link_5", "csqtt_link_mode_5",
            ),
            changedKeys(before, after),
        )
        assertEquals(CsqttConstants.VkAuth.MODE_CALLS, after["vk_auth_mode_2"])
        assertEquals(70, after["workers_per_hash_2"])
        assertEquals(40, after["workers_per_hash_5"])
        assertEquals("c3", after["vk_access_token_encrypted_2"])
        assertEquals(true, after["csqtt_link_mode_5"])
        val link = com.csqtt.client.ui.legacy.utils.parseCsqttLink(after["csqtt_link_5"] as String)!!
        assertEquals("192.0.2.10", link.host)
        assertEquals(CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT, link.port)
        assertEquals("s", link.password)
        assertEquals(40, link.power)
        assertEquals("https://sub.example.invalid/s/abc", after["subscription_url_5"])
    }

    @Test
    fun `all 32 slots are migrated, and nothing else in them changes`() {
        val (before, after) = migrate(Store253Snapshots.allSlots)
        val expected = (CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX)
            .map { if (it == 0) "workers_per_hash" else "workers_per_hash_$it" }
            .toSet() + "store_schema_version" + "ui_owner_mode"
        assertEquals(expected, changedKeys(before, after))
        assertEquals(setOf(10), expected.minus(setOf("store_schema_version", "ui_owner_mode")).map { after[it] }.toSet())
    }

    @Test
    fun `a value of an unexpected type skips its step and never stops the migration`() {
        val prefs = load(Store253Snapshots.owner)
        prefs[stringPreferencesKey("workers_per_hash_4")] = "18"
        prefs[intPreferencesKey("vk_hash_mode_4")] = 7
        SettingsStore.migrateStore(prefs)
        assertEquals("18", prefs[stringPreferencesKey("workers_per_hash_4")])
        assertEquals(10, prefs[intPreferencesKey("workers_per_hash")])
        assertEquals(SettingsStore.STORE_SCHEMA, prefs[intPreferencesKey("store_schema_version")])
    }

    @Test
    fun `workers become what the core runs`() {
        for ((stored, runs) in listOf(0 to 10, 5 to 10, 18 to 10, 20 to 20, 75 to 70, 100 to 100, 250 to 100)) {
            val prefs = load("""{"ints": {"workers_per_hash_9": $stored}, "booleans": {"default_role_decided": true}}""")
            SettingsStore.migrateStore(prefs)
            assertEquals("stored $stored", runs, prefs[intPreferencesKey("workers_per_hash_9")])
        }
    }

    @Test
    fun `step 4 keeps the role the switch showed, from the active profile, once`() {
        assertEquals(true, migrate(Store253Snapshots.owner).second["ui_owner_mode"])
        assertEquals(false, migrate(Store253Snapshots.participant).second["ui_owner_mode"])
        val chosen = Store253Snapshots.participant.replace(
            "\"booleans\": {", "\"booleans\": {\"ui_owner_mode\": true, ",
        )
        assertEquals(true, migrate(chosen).second["ui_owner_mode"])
    }
}
