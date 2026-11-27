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
            // vps_ref: step 2 (B5b2) links its deploy data as record 0; the battery saver's two: step 8 (P3 B0s);
            // «Экстра потоки»: step 9 (P3 B3)
            setOf("ui_design", "workers_per_hash", "vk_hash_mode", "store_schema_version", "vps_ref",
                "battery_saver_mode", "battery_saver_percent", "extra_workers"),
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
        // its count of five stays five: 2.5.3 ran fewer than ten as five, and five is a stop of the slider again
        assertEquals(setOf("vk_hashes_3", "store_schema_version"), changedKeys(before, after))
        assertEquals(Store253Snapshots.ownHash, after["vk_hashes_3"])
        assertEquals(5, after["workers_per_hash_3"])
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
        assertEquals(setOf("store_schema_version"), changedKeys(before, asMap(noHashes)))
    }

    @Test
    fun `a subscription slot and a token account keep everything but the modes' and workers' spelling`() {
        val (before, after) = migrate(Store253Snapshots.subscriptionAndToken)
        // step 3 (B5c1, M-170): the subscription slot becomes the participant link it came from
        assertEquals(
            setOf(
                "vk_auth_mode_2", "workers_per_hash_2", "store_schema_version",
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
            .toSet() + "store_schema_version"
        assertEquals(expected, changedKeys(before, after))
        assertEquals(setOf(10), expected.minus("store_schema_version").map { after[it] }.toSet())
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
        // 2.5.3 ran fewer than ten as five and the rest in whole tens: 18, its default, as 10. Above 70 only «Экстра
        // потоки» ran: with the switch off, or never touched, such a count ran as 70 (P3 B3, step 9)
        for ((stored, runs) in listOf(0 to 5, 5 to 5, 9 to 5, 10 to 10, 18 to 10, 20 to 20, 70 to 70, 75 to 70, 100 to 70, 250 to 70)) {
            val prefs = load("""{"ints": {"workers_per_hash_9": $stored}, "booleans": {"default_role_decided": true}}""")
            SettingsStore.migrateStore(prefs)
            assertEquals("stored $stored", runs, prefs[intPreferencesKey("workers_per_hash_9")])
        }
        for ((stored, runs) in listOf(75 to 70, 100 to 100, 250 to 100)) {
            val prefs = load("""{"ints": {"workers_per_hash_9": $stored}, "booleans": {"default_role_decided": true, "extra_workers_9": true}}""")
            SettingsStore.migrateStore(prefs)
            assertEquals("stored $stored with the switch on", runs, prefs[intPreferencesKey("workers_per_hash_9")])
        }
    }

    @Test
    fun `a profile that never held a count keeps the 10 it ran, and only a new one starts at 20`() {
        // P3 B3 (the operator, 2026-11-27): a new profile starts at 20 from now on; until then none stored meant 10
        val prefs = load(
            """{"strings": {"peer_4": "203.0.113.4", "csqtt_link_6": "${Store253Snapshots.participantLink}"},
                "ints": {"store_schema_version": 8, "active_profile": 9},
                "booleans": {"csqtt_link_mode_6": true, "default_role_decided": true}}""",
        )
        SettingsStore.migrateStore(prefs)
        assertEquals(10, prefs[intPreferencesKey("workers_per_hash_4")])
        assertEquals(10, prefs[intPreferencesKey("workers_per_hash_6")])
        // a slot nobody uses holds nothing: the profile made in it later is a new one
        assertNull(prefs[intPreferencesKey("workers_per_hash_9")])
        assertNull(prefs[intPreferencesKey("workers_per_hash_5")])
        assertEquals(20, CsqttConstants.Tunnel.DEFAULT_WORKERS)
        // a new install has no profile to keep anything for
        val fresh = load("""{"booleans": {"default_role_decided": true}}""")
        SettingsStore.migrateStore(fresh)
        assertEquals(emptySet<String>(), Store253Snapshots.asMap(fresh).keys.filter { it.startsWith("workers_per_hash") }.toSet())
    }

    @Test
    fun `a 2_5_3 store gets no role and no tab switch, and its owner has a server by what it holds`() {
        for (snapshot in listOf(Store253Snapshots.owner, Store253Snapshots.participant, Store253Snapshots.subscriptionAndToken)) {
            val after = migrate(snapshot).second
            assertNull(after["ui_owner_mode"])
            assertNull(after["show_deploy_tab"])
            assertNull(after["show_logs_tab"])
        }
        assertEquals(true, SettingsStore.userHasServer(load(Store253Snapshots.owner).also(SettingsStore::migrateStore)))
        assertEquals(false, SettingsStore.userHasServer(load(Store253Snapshots.participant).also(SettingsStore::migrateStore)))
    }

    @Test
    fun `a 2_5_3 subscription slot is no server of the user's, converted by step 3 or left for its refresh`() {
        // 2.5.3 kept a subscription node in owner mode with its address. Step 3 (M-170) makes a link profile of one that
        // never refreshes; one with auto-update stays as it is until its next refresh converts it
        val never = load(
            """{"strings": {"peer_5": "192.0.2.10", "subscription_url_5": "https://sub.example.invalid/s/abc",
                            "connection_password_local_5": "s"},
                "ints": {"active_profile": 5}, "booleans": {"default_role_decided": true}}""",
        )
        SettingsStore.migrateStore(never)
        assertEquals(true, asMap(never)["csqtt_link_mode_5"])
        assertEquals(false, SettingsStore.userHasServer(never))

        val refreshing = load(
            """{"strings": {"peer_5": "192.0.2.10", "subscription_url_5": "https://sub.example.invalid/s/abc",
                            "connection_password_local_5": "s",
                            "subscription_meta_v1": "{\"https://sub.example.invalid/s/abc\":{\"autoUpdateMinutes\":60}}"},
                "ints": {"active_profile": 5}, "booleans": {"default_role_decided": true}}""",
        )
        SettingsStore.migrateStore(refreshing)
        assertNull("left for its refresh", asMap(refreshing)["csqtt_link_mode_5"])
        assertEquals(false, SettingsStore.userHasServer(refreshing))
    }

    @Test
    fun `step 6 removes the logging switch and the never-read detailed flag`() {
        val prefs = load(
            """{"ints": {"store_schema_version": 5, "active_profile": 2},
               "booleans": {"logging_enabled": false, "detailed_logs": true, "show_logs_tab": true}}""",
        )
        val before = asMap(prefs)
        SettingsStore.migrateStore(prefs)
        val after = asMap(prefs)
        // show_logs_tab: step 7 (P3 B0) takes the tab switches out
        assertEquals(setOf("logging_enabled", "detailed_logs", "show_logs_tab", "store_schema_version"), changedKeys(before, after))
        assertNull(after["logging_enabled"])
        assertNull(after["detailed_logs"])
        assertEquals(SettingsStore.STORE_SCHEMA, after["store_schema_version"])
    }
}
