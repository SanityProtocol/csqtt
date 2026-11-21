package com.csqtt.client

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.csqtt.client.Store253Snapshots.asMap
import com.csqtt.client.Store253Snapshots.changedKeys
import com.csqtt.client.Store253Snapshots.load
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The roles batch, store step 5: the role becomes «Показывать деплой» (on for an owner or anyone with a server), and the
 * role and the per-profile «Показывать логи» go; a fresh install writes neither switch.
 */
class RoleSwitchesMigrationTest {
    /** A store the B5e1…B5e3b builds left (version 4): step 5 alone runs. */
    private fun atVersion4(json: String): MutablePreferences =
        load(json).also { it[intPreferencesKey("store_schema_version")] = 4 }

    private fun migrate(prefs: MutablePreferences): Pair<Map<String, Any>, Map<String, Any>> {
        val before = asMap(prefs)
        SettingsStore.migrateStore(prefs)
        return before to asMap(prefs)
    }

    @Test
    fun `an owner gets the deploy tab, and the role goes`() {
        val (before, after) = migrate(
            atVersion4("""{"strings": {"peer": "203.0.113.5"}, "booleans": {"ui_owner_mode": true}}"""),
        )
        assertEquals(setOf("ui_owner_mode", "show_deploy_tab", "store_schema_version"), changedKeys(before, after))
        assertEquals(true, after["show_deploy_tab"])
        assertNull(after["ui_owner_mode"])
        assertNull(after["show_logs_tab"])
    }

    @Test
    fun `a participant with a server gets the deploy tab`() {
        val (_, after) = migrate(
            atVersion4(
                """{"strings": {"csqtt_link_3": "${Store253Snapshots.participantLink}", "deploy_ip_7": "198.51.100.7"},
                    "ints": {"active_profile": 3},
                    "booleans": {"csqtt_link_mode_3": true, "ui_owner_mode": false}}""",
            ),
        )
        assertEquals(true, after["show_deploy_tab"])
        assertNull(after["ui_owner_mode"])
    }

    @Test
    fun `a participant without one gets neither switch, and every per-profile logs choice goes`() {
        val prefs = atVersion4(
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
        assertNull(after["show_deploy_tab"])
        assertNull(after["show_logs_tab"])
    }

    @Test
    fun `a fresh install has neither switch`() {
        val prefs = mutablePreferencesOf()
        SettingsStore.migrateStore(prefs)
        assertNull(asMap(prefs)["show_deploy_tab"])
        assertNull(asMap(prefs)["show_logs_tab"])
        assertNull(asMap(prefs)["ui_owner_mode"])
    }

    @Test
    fun `with no stored role the active profile's mode decides, and a switch already set is kept`() {
        val own = migrate(atVersion4("""{"strings": {"peer_2": "192.0.2.2"}, "ints": {"active_profile": 2}}""")).second
        assertEquals(true, own["show_deploy_tab"])
        val link = migrate(
            atVersion4("""{"ints": {"active_profile": 2}, "booleans": {"csqtt_link_mode_2": true}}"""),
        ).second
        assertNull(link["show_deploy_tab"])
        val chosen = migrate(
            atVersion4("""{"booleans": {"ui_owner_mode": true, "show_deploy_tab": false}}"""),
        ).second
        assertEquals(false, chosen["show_deploy_tab"])
        assertNull(chosen["ui_owner_mode"])
    }

    @Test
    fun `step 5 runs once`() {
        val prefs = atVersion4("""{"booleans": {"ui_owner_mode": true}}""")
        SettingsStore.migrateStore(prefs)
        prefs.remove(booleanPreferencesKey("show_deploy_tab"))
        val once = asMap(prefs)
        SettingsStore.migrateStore(prefs)
        assertEquals(once, asMap(prefs))
    }
}
