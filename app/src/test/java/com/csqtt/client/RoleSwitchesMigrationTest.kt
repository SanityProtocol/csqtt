package com.csqtt.client

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.csqtt.client.Store253Snapshots.asMap
import com.csqtt.client.Store253Snapshots.changedKeys
import com.csqtt.client.Store253Snapshots.load
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The role and the tab switches are gone (the roles batch, then P3 B0): store step 5 removes the role and the per-profile
 * «Показывать логи», step 7 the two app-wide switches, and «Сервер» follows what the store holds (K4, userHasServer).
 */
class RoleSwitchesMigrationTest {
    /** A store the B5e1…B5e3b builds left (version 4): step 5 and the later ones run. */
    private fun atVersion(version: Int, json: String): MutablePreferences =
        load(json).also { it[intPreferencesKey("store_schema_version")] = version }

    private fun migrate(prefs: MutablePreferences): Pair<Map<String, Any>, Map<String, Any>> {
        val before = asMap(prefs)
        SettingsStore.migrateStore(prefs)
        return before to asMap(prefs)
    }

    @Test
    fun `the role goes and no switch is written, and an owner still has a server`() {
        val prefs = atVersion(4, """{"strings": {"peer": "203.0.113.5"}, "booleans": {"ui_owner_mode": true}}""")
        val (before, after) = migrate(prefs)
        assertEquals(setOf("ui_owner_mode", "store_schema_version"), changedKeys(before, after))
        assertNull(after["ui_owner_mode"])
        assertNull(after["show_deploy_tab"])
        assertNull(after["show_logs_tab"])
        assertTrue(SettingsStore.userHasServer(prefs))
    }

    @Test
    fun `every per-profile logs choice goes`() {
        val prefs = atVersion(
            4,
            """{"strings": {"csqtt_link_3": "${Store253Snapshots.participantLink}", "deploy_ip_4": " "},
                "ints": {"active_profile": 3},
                "booleans": {"csqtt_link_mode_3": true, "ui_owner_mode": false, "participant_show_logs": true,
                             "participant_show_logs_3": false, "participant_show_logs_31": true}}""",
        )
        val (before, after) = migrate(prefs)
        assertEquals(
            setOf("ui_owner_mode", "participant_show_logs", "participant_show_logs_3", "participant_show_logs_31",
                "store_schema_version"),
            changedKeys(before, after),
        )
        assertFalse("a link and a blank address are no server", SettingsStore.userHasServer(prefs))
    }

    @Test
    fun `step 7 removes the two switches an earlier 2_6_0 build stored, and nothing else`() {
        for (version in listOf(5, 6)) {
            val prefs = atVersion(
                version,
                """{"strings": {"peer": "203.0.113.5"}, "booleans": {"show_deploy_tab": true, "show_logs_tab": false}}""",
            )
            val (before, after) = migrate(prefs)
            assertEquals("from $version", setOf("show_deploy_tab", "show_logs_tab", "store_schema_version"), changedKeys(before, after))
            assertNull(after["show_deploy_tab"])
            assertNull(after["show_logs_tab"])
        }
    }

    @Test
    fun `step 8 removes the battery saver, its prompt's flags and nothing else`() {
        // P3 B0s (§1.9): the tunnel sleeps with the phone; nothing is set and nothing is granted
        for (version in listOf(6, 7)) {
            val prefs = atVersion(
                version,
                """{"strings": {"peer": "203.0.113.5", "battery_saver_mode": "level", "theme_mode": "dark"},
                    "ints": {"battery_saver_percent": 30},
                    "booleans": {"battery_optimization_prompt_handled": true, "permission_intro_shown": true,
                                 "auto_pause_on_wifi": true, "default_role_decided": true}}""",
            )
            val (before, after) = migrate(prefs)
            assertEquals(
                "from $version",
                setOf("battery_saver_mode", "battery_saver_percent", "battery_optimization_prompt_handled",
                    "permission_intro_shown", "store_schema_version"),
                changedKeys(before, after),
            )
            for (gone in listOf("battery_saver_mode", "battery_saver_percent", "battery_optimization_prompt_handled",
                "permission_intro_shown")) {
                assertNull(gone, after[gone])
            }
            assertEquals("dark", after["theme_mode"])
            assertEquals(true, after["auto_pause_on_wifi"])
        }
        // a 2.5.3 phone's «Экономия батареи» goes the same way
        val upgraded = migrate(load(Store253Snapshots.owner)).second
        assertNull(upgraded["battery_saver_mode"])
        assertNull(upgraded["battery_saver_percent"])
    }

    @Test
    fun `a fresh install has neither switch nor a server`() {
        val prefs = mutablePreferencesOf()
        SettingsStore.migrateStore(prefs)
        assertNull(asMap(prefs)["show_deploy_tab"])
        assertNull(asMap(prefs)["show_logs_tab"])
        assertNull(asMap(prefs)["ui_owner_mode"])
        assertFalse(SettingsStore.userHasServer(prefs))
    }

    @Test
    fun `the user has a server with a VPS record or a profile that dials an address, whichever profile is open`() {
        // an own-server profile that is not the open one
        assertTrue(SettingsStore.userHasServer(load("""{"strings": {"peer_2": "192.0.2.2"}, "ints": {"active_profile": 5}}""")))
        // a participant who also keeps a VPS record
        assertTrue(
            SettingsStore.userHasServer(
                load(
                    """{"strings": {"csqtt_link_3": "${Store253Snapshots.participantLink}", "deploy_ip_7": "198.51.100.7"},
                        "ints": {"active_profile": 3}, "booleans": {"csqtt_link_mode_3": true}}""",
                ),
            ),
        )
        // a link profile is the owner's server, not the user's: its address counts for nothing
        assertFalse(
            SettingsStore.userHasServer(
                load("""{"strings": {"peer_2": "192.0.2.2", "csqtt_link_2": "${Store253Snapshots.participantLink}"},
                         "booleans": {"csqtt_link_mode_2": true}}"""),
            ),
        )
        // a record with no address is no server: the secret path written by opening the tab, an address typed and
        // erased (its flags stay), «Docker» flipped
        assertFalse(SettingsStore.userHasServer(load("""{"strings": {"deploy_secret_path_uuid_3": "uuid"}}""")))
        assertFalse(
            SettingsStore.userHasServer(
                load("""{"strings": {"deploy_ip_3": " ", "deploy_login_3": "root"},
                         "booleans": {"deploy_server_configured_3": false, "docker_install_3": true}}"""),
            ),
        )
        // a 2.5.3 subscription slot its next refresh will make a link: owner mode and an address, but the owner's node
        assertFalse(
            SettingsStore.userHasServer(
                load("""{"strings": {"peer_5": "192.0.2.10", "subscription_url_5": "https://sub.example.invalid/s/abc"}}"""),
            ),
        )
    }

    @Test
    fun `the steps run once`() {
        val prefs = atVersion(4, """{"booleans": {"ui_owner_mode": true, "show_deploy_tab": true}}""")
        SettingsStore.migrateStore(prefs)
        val once = asMap(prefs)
        SettingsStore.migrateStore(prefs)
        assertEquals(once, asMap(prefs))
    }
}
