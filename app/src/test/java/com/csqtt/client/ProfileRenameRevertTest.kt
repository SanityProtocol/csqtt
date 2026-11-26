package com.csqtt.client

import androidx.datastore.preferences.core.stringPreferencesKey
import com.csqtt.client.Store253Snapshots.load
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3-PLAN §1.7 (the canvas, F-ProfileRename): any profile can be renamed and given the name it came with back; a
 * subscription node's rename outlives its subscription's refresh.
 */
class ProfileRenameRevertTest {
    private val url = "https://sub.example.net/c/1"
    private fun node(name: String, profile: Int = 5) = SubscriptionProfileWrite(
        profile, "csqtt://connect?v=2&host=msk1.example.net&peer=56000&password=pw", 20, name, "Мой VPN", url, "uuid-1",
    )

    private fun entry(prefs: androidx.datastore.preferences.core.Preferences, profile: Int) =
        SettingsStore.profileEntries(prefs).first { it.index == profile }

    @Test fun aRenameIsShownEverywhereAndTheRevertGivesTheNameItCameWith() {
        val prefs = load(Store253Snapshots.owner)
        assertEquals("Профиль 1", SettingsStore.profileDisplayName(prefs, 0))
        SettingsStore.renameProfileKeys(prefs, 0, "  Хельсинки ")
        assertEquals("Хельсинки", SettingsStore.profileDisplayName(prefs, 0))
        assertEquals("Хельсинки", entry(prefs, 0).name)
        assertEquals("Профиль 1", entry(prefs, 0).originalName)
        assertEquals("Хельсинки", SettingsStore.profileList(prefs).first { it.first == 0 }.second)
        SettingsStore.revertProfileNameKeys(prefs, 0)
        assertEquals("Профиль 1", SettingsStore.profileDisplayName(prefs, 0))
        assertNull(prefs[stringPreferencesKey("profile_name_override")])
    }

    @Test fun anEmptyNameOrTheOriginalOneIsTheRevert() {
        val prefs = load(Store253Snapshots.owner)
        prefs[stringPreferencesKey("profile_name")] = "Дача"   // a name from before renames were kept apart
        SettingsStore.renameProfileKeys(prefs, 0, "Хельсинки")
        SettingsStore.renameProfileKeys(prefs, 0, "   ")
        assertEquals("Дача", SettingsStore.profileDisplayName(prefs, 0))
        SettingsStore.renameProfileKeys(prefs, 0, "Хельсинки")
        SettingsStore.renameProfileKeys(prefs, 0, "Дача")
        assertNull(prefs[stringPreferencesKey("profile_name_override")])
        assertEquals("Дача", prefs[stringPreferencesKey("profile_name")])
    }

    @Test fun aNodesRenameOutlivesARefreshAndTheRevertGivesTheNodesCurrentName() {
        val prefs = load(Store253Snapshots.owner)
        SettingsStore.writeSubscriptionNode(prefs, node("msk-1"), now = 1L)
        assertEquals("msk-1", SettingsStore.profileDisplayName(prefs, 5))
        SettingsStore.renameProfileKeys(prefs, 5, "Москва")
        // the subscription renames its node: the user's name stays, the name to go back to follows the subscription
        SettingsStore.writeSubscriptionNode(prefs, node("msk-1-new"), now = 2L)
        assertEquals("Москва", SettingsStore.profileDisplayName(prefs, 5))
        assertEquals("msk-1-new", entry(prefs, 5).originalName)
        SettingsStore.revertProfileNameKeys(prefs, 5)
        assertEquals("msk-1-new", SettingsStore.profileDisplayName(prefs, 5))
    }

    @Test fun aNodeRenamedBy253TakesItsNodesNameAgainOnTheRevert() {
        val prefs = load(Store253Snapshots.owner)
        SettingsStore.writeSubscriptionNode(prefs, node("msk-1"), now = 1L)
        // 2.5.3 kept a rename in the node's own name key, and a refresh then left it alone
        prefs[stringPreferencesKey("profile_name_5")] = "Моя Москва"
        SettingsStore.writeSubscriptionNode(prefs, node("msk-1"), now = 2L)
        assertEquals("Моя Москва", SettingsStore.profileDisplayName(prefs, 5))
        assertEquals("msk-1", entry(prefs, 5).originalName)
        SettingsStore.revertProfileNameKeys(prefs, 5)
        assertEquals("msk-1", SettingsStore.profileDisplayName(prefs, 5))
        // and typing the node's own name is the same revert
        prefs[stringPreferencesKey("profile_name_5")] = "Моя Москва"
        SettingsStore.renameProfileKeys(prefs, 5, "msk-1")
        assertEquals("msk-1", SettingsStore.profileDisplayName(prefs, 5))
    }

    @Test fun aRenamedProfileIsARealOneAndItsNameGoesWithIt() {
        // a profile that holds nothing but a name the user gave stays in the lists when another is opened
        val prefs = load("""{"booleans": {"csqtt_link_mode": true}}""")
        SettingsStore.renameProfileKeys(prefs, 0, "Дом")
        assertTrue(SettingsStore.profileInUse(prefs, 0))
        SettingsStore.deleteProfileKeys(prefs, 0)
        assertNull(prefs[stringPreferencesKey("profile_name_override")])
        assertEquals("Профиль 1", SettingsStore.profileDisplayName(prefs, 0))
    }
}
