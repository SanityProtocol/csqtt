package com.csqtt.client

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.csqtt.client.Store253Snapshots.asMap
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Home's two layouts (P3-PLAN §1.3a): what is stored. The screen's side is the instrumented HomeLayoutSurvivesTest. */
class HomeLayoutTest {
    private val key = stringPreferencesKey("home_layout")

    @Test
    fun nothingStoredIsOneConnection() {
        val prefs = mutablePreferencesOf()
        assertEquals(HomeLayout.One, SettingsStore.homeLayout(prefs))
        // a value another build wrote and this one does not know is the default too
        prefs[key] = "grid"
        assertEquals(HomeLayout.One, SettingsStore.homeLayout(prefs))
        assertEquals(HomeLayout.One, HomeLayout.fromStored(null))
        assertEquals(HomeLayout.List, HomeLayout.fromStored("list"))
    }

    @Test
    fun theSwitchFlipsAndTheChoiceIsKept() {
        val prefs = mutablePreferencesOf()
        SettingsStore.setHomeLayout(prefs, SettingsStore.homeLayout(prefs).other)
        assertEquals(HomeLayout.List, SettingsStore.homeLayout(prefs))
        assertEquals("list", prefs[key])
        SettingsStore.setHomeLayout(prefs, SettingsStore.homeLayout(prefs).other)
        assertEquals(HomeLayout.One, SettingsStore.homeLayout(prefs))
        // said, not left to the default: a later default would not change what the user chose
        assertEquals("one", prefs[key])
    }

    @Test
    fun theFullBackupCarriesIt() {
        val source = mutablePreferencesOf().also { SettingsStore.setHomeLayout(it, HomeLayout.List) }
        val json = StoreBackup.encode(source, serverSecrets = false, decrypt = { it }, exportedAt = 1L)
        val target = mutablePreferencesOf()
        StoreBackup.restore(target, json, { it }, { it })
        assertEquals(HomeLayout.List, SettingsStore.homeLayout(target))
        // and a backup of a phone that never chose restores to the default
        val plain = StoreBackup.encode(mutablePreferencesOf(), serverSecrets = false, decrypt = { it }, exportedAt = 1L)
        val chosen = mutablePreferencesOf().also { SettingsStore.setHomeLayout(it, HomeLayout.List) }
        StoreBackup.restore(chosen, plain, { it }, { it })
        assertEquals(HomeLayout.One, SettingsStore.homeLayout(chosen))
        assertFalse(asMap(chosen).containsKey("home_layout"))
    }

    @Test
    fun theSettingsExportCarriesIt() {
        val source = mutablePreferencesOf().also { SettingsStore.setHomeLayout(it, HomeLayout.List) }
        val settings = JSONObject()
        SettingsStore.exportHomeLayout(settings, source)
        assertEquals("list", settings.getString("home_layout"))
        val target = mutablePreferencesOf()
        SettingsStore.importHomeLayout(JSONObject(settings.toString()), target)
        assertEquals(HomeLayout.List, SettingsStore.homeLayout(target))
        // an export made before the layouts says nothing of it, and changes nothing
        SettingsStore.importHomeLayout(JSONObject("""{"theme_mode": "dark"}"""), target)
        assertEquals(HomeLayout.List, SettingsStore.homeLayout(target))
    }
}
