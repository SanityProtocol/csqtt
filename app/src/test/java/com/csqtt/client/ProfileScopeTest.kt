package com.csqtt.client

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The run reads its own profile (the operator, 2026-11-21): a store's scope picks the profile its per-profile reads and
 * writes use — the screens the active one, the run-time code run_profile (the active one when no run has one), a switch
 * one fixed profile.
 */
class ProfileScopeTest {
    private fun prefs(active: Int?, run: Int?) = mutablePreferencesOf().apply {
        active?.let { this[intPreferencesKey("active_profile")] = it }
        run?.let { this[intPreferencesKey("run_profile")] = it }
    }

    @Test
    fun `the screens read the active profile, whatever the run is on`() {
        assertEquals(3, SettingsStore.selectedProfileOf(prefs(active = 3, run = 7), ProfileScope.Active))
        assertEquals(0, SettingsStore.selectedProfileOf(prefs(active = null, run = 7), ProfileScope.Active))
    }

    @Test
    fun `the run reads its own profile, and the active one when no run has one`() {
        assertEquals(7, SettingsStore.selectedProfileOf(prefs(active = 3, run = 7), ProfileScope.Run))
        assertEquals(3, SettingsStore.selectedProfileOf(prefs(active = 3, run = null), ProfileScope.Run))
        assertEquals(0, SettingsStore.selectedProfileOf(prefs(active = null, run = null), ProfileScope.Run))
    }

    @Test
    fun `deleting the run's profile leaves the run on the open one, deleting another keeps it`() {
        val prefs = prefs(active = 3, run = 7)
        prefs[androidx.datastore.preferences.core.stringPreferencesKey("peer_7")] = "192.0.2.7"
        SettingsStore.deleteProfileKeys(prefs, 7)
        assertEquals(null, prefs[intPreferencesKey("run_profile")])
        assertEquals(3, SettingsStore.selectedProfileOf(prefs, ProfileScope.Run))
        val other = prefs(active = 3, run = 7)
        SettingsStore.deleteProfileKeys(other, 5)
        assertEquals(7, SettingsStore.selectedProfileOf(other, ProfileScope.Run))
    }

    @Test
    fun `a fixed scope reads its profile`() {
        assertEquals(12, SettingsStore.selectedProfileOf(prefs(active = 3, run = 7), ProfileScope.Fixed(12)))
    }
}
