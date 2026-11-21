package com.csqtt.client

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.csqtt.client.Store253Snapshots.asMap
import com.csqtt.client.Store253Snapshots.load
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** B5b1: each numbered step runs once, and the role comes from the data, never from another migration's flag. */
class MigrationRunsOnceTest {
    @Test
    fun `a second run changes nothing`() {
        for (json in listOf(Store253Snapshots.owner, Store253Snapshots.participant, Store253Snapshots.allSlots)) {
            val prefs = load(json)
            SettingsStore.migrateStore(prefs)
            val once = asMap(prefs)
            SettingsStore.migrateStore(prefs)
            assertEquals(once, asMap(prefs))
        }
    }

    @Test
    fun `a migrated store is left alone even if old keys come back (a downgrade and an upgrade)`() {
        val prefs = load(Store253Snapshots.owner)
        SettingsStore.migrateStore(prefs)
        prefs[stringPreferencesKey("ui_design")] = "v2"
        prefs[intPreferencesKey("workers_per_hash")] = 18
        val before = asMap(prefs)
        SettingsStore.migrateStore(prefs)
        assertEquals(before, asMap(prefs))
    }

    @Test
    fun `a fresh install starts as a participant, with the version and the role flags only`() {
        val prefs = mutablePreferencesOf()
        SettingsStore.migrateStore(prefs)
        assertEquals(
            // ui_owner_mode: step 4 (B5e1, M-75) keeps the participant role as the user's
            mapOf(
                "csqtt_link_mode" to true, "default_role_decided" to true, "ui_owner_mode" to false,
                "store_schema_version" to SettingsStore.STORE_SCHEMA,
            ),
            asMap(prefs),
        )
    }

    @Test
    fun `an owner keeps its role, with the flag (2_5_3) or without it (older)`() {
        val withFlag = load(Store253Snapshots.owner)
        SettingsStore.migrateStore(withFlag)
        assertNull(withFlag[booleanPreferencesKey("csqtt_link_mode")])

        val withoutFlag = load(Store253Snapshots.owner)
        withoutFlag.remove(booleanPreferencesKey("default_role_decided"))
        SettingsStore.migrateStore(withoutFlag)
        assertNull(withoutFlag[booleanPreferencesKey("csqtt_link_mode")])
        assertEquals(true, withoutFlag[booleanPreferencesKey("default_role_decided")])
    }

    @Test
    fun `the flag alone never makes anyone a participant, nor an owner`() {
        // an empty store that only carries the flag (2.5.3 fresh, nothing configured) stays as it was: no role is guessed
        val prefs = mutablePreferencesOf()
        prefs[booleanPreferencesKey("default_role_decided")] = true
        SettingsStore.migrateStore(prefs)
        assertNull(prefs[booleanPreferencesKey("csqtt_link_mode")])
    }
}
