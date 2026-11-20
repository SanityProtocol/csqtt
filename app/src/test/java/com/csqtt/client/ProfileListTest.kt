package com.csqtt.client

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.csqtt.client.Store253Snapshots.load
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B5b2, M-39 and M-97: the profiles in use, classic's list and names, «Добавить профиль», a clean slot for a new node. */
class ProfileListTest {
    @Test
    fun `a profile is in use with a peer, a link, a subscription, a VPS record or a name, and not with leftovers alone`() {
        val prefs = load(
            """{"strings": {"peer_1": "p", "csqtt_link_2": "l", "subscription_url_3": "u", "profile_name_4": "Дом",
                            "vk_hashes_6": "${"H".repeat(20)}", "excluded_apps_6": "a.b", "deploy_secret_path_uuid_6": "x"},
                "ints": {"vps_ref_5": 9, "active_profile": 1}}""",
        )
        assertEquals(listOf(1, 2, 3, 4, 5), (0..31).filter { SettingsStore.profileInUse(prefs, it) })
    }

    @Test
    fun `the dropdown lists the profiles in use and the active one, 1-based, a deploy-only profile by its server name`() {
        val prefs = load(
            """{"strings": {"peer": "p", "profile_name_2": "Работа", "deploy_ip_4": "h", "deploy_server_name_4": "Дача",
                            "peer_9": "q"},
                "ints": {"vps_ref_4": 4, "active_profile": 7}}""",
        )
        assertEquals(
            listOf(0 to "Профиль 1", 2 to "Работа", 4 to "Дача", 7 to "Профиль 8", 9 to "Профиль 10"),
            SettingsStore.profileList(prefs),
        )
    }

    @Test
    fun `adding a profile takes the first free slot, clean, with the current role, and makes it active`() {
        val prefs = load(
            """{"strings": {"peer": "p", "vk_hashes_2": "${"H".repeat(20)}", "excluded_apps_2": "a.b",
                            "vk_access_token_encrypted_2": "c"},
                "ints": {"vps_ref_1": 1, "active_profile": 0},
                "booleans": {"csqtt_link_mode_2": false}}""",
        )
        assertEquals(2, SettingsStore.addProfileKeys(prefs, linkMode = true))
        assertEquals(2, prefs[intPreferencesKey("active_profile")])
        assertEquals(true, prefs[booleanPreferencesKey("csqtt_link_mode_2")])
        for (name in listOf("vk_hashes_2", "excluded_apps_2", "vk_access_token_encrypted_2")) {
            assertNull(name, prefs[stringPreferencesKey(name)])
        }

        val full = load("""{"strings": {}, "ints": {"active_profile": 0}}""")
        for (i in 0..31) full[stringPreferencesKey(SettingsStore.slotKeyName("peer", i))] = "p$i"
        assertNull(SettingsStore.addProfileKeys(full, linkMode = false))
        assertEquals(0, full[intPreferencesKey("active_profile")])
    }

    @Test
    fun `a new node never lands on a VPS-only profile and starts without another profile's leftovers`() {
        val prefs = load(
            """{"strings": {"deploy_ip_1": "h", "vk_hashes_2": "${"H".repeat(20)}", "excluded_apps": "mine"},
                "ints": {"vps_ref_1": 1, "active_profile": 0}}""",
        )
        assertTrue(SettingsStore.profileInUse(prefs, 1))
        assertFalse(SettingsStore.profileInUse(prefs, 2))
        SettingsStore.startCleanIfFree(prefs, 2)
        assertNull(prefs[stringPreferencesKey("vk_hashes_2")])
        // the active slot's keys are the settings the user is looking at
        SettingsStore.startCleanIfFree(prefs, 0)
        assertEquals("mine", prefs[stringPreferencesKey("excluded_apps")])
        // a slot in use is never cleared
        SettingsStore.startCleanIfFree(prefs, 1)
        assertEquals(1, SettingsStore.linkedVps(prefs, 1))
    }
}
