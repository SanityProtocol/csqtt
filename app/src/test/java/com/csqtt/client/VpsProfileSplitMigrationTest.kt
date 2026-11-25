package com.csqtt.client

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.csqtt.client.Store253Snapshots.asMap
import com.csqtt.client.Store253Snapshots.changedKeys
import com.csqtt.client.Store253Snapshots.load
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B5b2, M-39 and M-104: store step 2 turns each slot's deploy data into VPS record n, linked from profile n. */
class VpsProfileSplitMigrationTest {
    /** A store B5b1 already migrated (version 1), so only step 2 runs. */
    private fun atVersion1(json: String): MutablePreferences =
        load(json).also { it[intPreferencesKey("store_schema_version")] = 1 }

    @Test
    fun `an owner's deploy data becomes record 0, linked from profile 0, and nothing else changes`() {
        val prefs = atVersion1(Store253Snapshots.owner)
        val before = asMap(prefs)
        SettingsStore.migrateStore(prefs)
        assertEquals(setOf("vps_ref", "store_schema_version"), changedKeys(before, asMap(prefs)))
        assertEquals(0, SettingsStore.linkedVps(prefs, 0))
        assertEquals(SettingsStore.STORE_SCHEMA, prefs[intPreferencesKey("store_schema_version")])
        assertTrue(SettingsStore.profileInUse(prefs, 0))
    }

    @Test
    fun `a VPS-only slot keeps a profile linked to it, in use and named by its server`() {
        val prefs = atVersion1(
            """{"strings": {"peer": "192.0.2.1", "deploy_ip_4": "203.0.113.40", "deploy_login_4": "root",
                            "deploy_server_name_4": "Дача"},
                "ints": {"active_profile": 0}}""",
        )
        assertFalse("before: the free-slot bug of M-39", SettingsStore.profileInUse(prefs, 4))
        SettingsStore.migrateStore(prefs)
        assertEquals(4, SettingsStore.linkedVps(prefs, 4))
        assertTrue(SettingsStore.profileInUse(prefs, 4))
        assertEquals("Дача", SettingsStore.profileDisplayName(prefs, 4))
        assertEquals(listOf(0 to "Профиль 1", 4 to "Дача"), SettingsStore.profileList(prefs))
    }

    @Test
    fun `a slot holding only the secret path written by opening the deploy tab stays unlinked`() {
        val prefs = atVersion1(Store253Snapshots.participant)
        prefs[stringPreferencesKey("deploy_secret_path_uuid_3")] = "uuid"
        prefs[stringPreferencesKey("deploy_ip_3")] = " "
        val before = asMap(prefs)
        SettingsStore.migrateStore(prefs)
        assertNull(SettingsStore.linkedVps(prefs, 3))
        assertEquals(setOf("store_schema_version"), changedKeys(before, asMap(prefs)))
    }

    @Test
    fun `32 connection-only slots get no link, and a second run changes nothing`() {
        val prefs = atVersion1(Store253Snapshots.allSlots)
        SettingsStore.migrateStore(prefs)
        assertTrue((0..31).all { SettingsStore.linkedVps(prefs, it) == null })
        val owner = load(Store253Snapshots.owner)
        SettingsStore.migrateStore(owner)
        val once = asMap(owner)
        SettingsStore.migrateStore(owner)
        assertEquals(once, asMap(owner))
        assertEquals(0, SettingsStore.linkedVps(owner, 0))
    }
}
