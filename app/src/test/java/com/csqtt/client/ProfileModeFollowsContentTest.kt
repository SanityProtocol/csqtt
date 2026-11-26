package com.csqtt.client

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.csqtt.client.Store253Snapshots.asMap
import com.csqtt.client.Store253Snapshots.load
import com.csqtt.client.ui.legacy.ProfileModeAction
import com.csqtt.client.ui.legacy.profileModeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The roles batch: a profile's mode follows what it holds. Only an empty one changes it, by the Туннель tab's text action
 * or at a deploy's end; a new profile waits for a server with «Показывать деплой» on, else for a link.
 */
class ProfileModeFollowsContentTest {
    private fun mode(prefs: MutablePreferences, profile: Int) =
        prefs[booleanPreferencesKey(SettingsStore.slotKeyName("csqtt_link_mode", profile))] == true

    /** 1: a link profile with no link; 2: one holding a link; 3: own server, no address; 4: own server with one. */
    private fun profiles(): MutablePreferences = load(
        """{"strings": {"csqtt_link_2": "${Store253Snapshots.participantLink}", "peer_4": "203.0.113.4",
                        "peer_1": "192.0.2.1", "vk_hashes_3": "${"H".repeat(20)}"},
            "booleans": {"csqtt_link_mode_1": true, "csqtt_link_mode_2": true, "csqtt_link_mode_3": false}}""",
    )

    @Test
    fun `each text action shows only for an empty profile, an empty field and an idle tunnel`() {
        assertEquals(ProfileModeAction.ENTER_SERVER, profileModeAction(emptyProfileLinkMode = true, field = "", tunnelBusy = false))
        assertEquals(ProfileModeAction.USE_LINK, profileModeAction(emptyProfileLinkMode = false, field = " ", tunnelBusy = false))
        assertNull(profileModeAction(emptyProfileLinkMode = null, field = "", tunnelBusy = false))
        assertNull(profileModeAction(emptyProfileLinkMode = true, field = "csqtt://conn", tunnelBusy = false))
        assertNull(profileModeAction(emptyProfileLinkMode = false, field = "1.2.3.4", tunnelBusy = false))
        assertNull(profileModeAction(emptyProfileLinkMode = true, field = "", tunnelBusy = true))
        assertNull(profileModeAction(emptyProfileLinkMode = false, field = "", tunnelBusy = true))
    }

    @Test
    fun `only an empty profile changes its mode, and only to the other one`() {
        val prefs = profiles()
        assertTrue(SettingsStore.profileModeSwitchable(prefs, 1))   // a hidden address is not its link
        assertFalse(SettingsStore.profileModeSwitchable(prefs, 2))
        assertTrue(SettingsStore.profileModeSwitchable(prefs, 3))   // hashes are not a server
        assertFalse(SettingsStore.profileModeSwitchable(prefs, 4))

        val before = asMap(prefs)
        assertFalse(SettingsStore.switchEmptyProfileModeKeys(prefs, 2, linkMode = false))
        assertFalse(SettingsStore.switchEmptyProfileModeKeys(prefs, 4, linkMode = true))
        assertFalse(SettingsStore.switchEmptyProfileModeKeys(prefs, 1, linkMode = true))   // already waiting for a link
        assertEquals(before, asMap(prefs))

        assertTrue(SettingsStore.switchEmptyProfileModeKeys(prefs, 1, linkMode = false))
        assertFalse(mode(prefs, 1))
        assertFalse("its address now decides", SettingsStore.profileModeSwitchable(prefs, 1))
        assertTrue(SettingsStore.switchEmptyProfileModeKeys(prefs, 3, linkMode = true))
        assertTrue(mode(prefs, 3))
        assertEquals("${"H".repeat(20)}", prefs[stringPreferencesKey("vk_hashes_3")])

        // clearing the link makes the profile empty again
        prefs.remove(stringPreferencesKey("csqtt_link_2"))
        assertTrue(SettingsStore.profileModeSwitchable(prefs, 2))
    }

    @Test
    fun `a profile from a subscription is never empty, its link cleared or not`() {
        val prefs = load(
            """{"strings": {"subscription_url_5": "https://sub.example.invalid/s"},
                "booleans": {"csqtt_link_mode_5": true}}""",
        )
        assertFalse(SettingsStore.profileModeSwitchable(prefs, 5))
        assertFalse(SettingsStore.switchEmptyProfileModeKeys(prefs, 5, linkMode = false))
        assertTrue(mode(prefs, 5))
    }

    @Test
    fun `a deploy's end turns an empty link profile into one on the server just installed, a link stays`() {
        val prefs = load(
            """{"strings": {"peer_1": "192.0.2.1", "csqtt_link_2": "${Store253Snapshots.participantLink}",
                            "deploy_ip_1": "203.0.113.9", "deploy_ip_2": "203.0.113.9"},
                "ints": {"vps_ref_1": 1, "vps_ref_2": 2},
                "booleans": {"csqtt_link_mode_1": true, "csqtt_link_mode_2": true}}""",
        )
        assertTrue(SettingsStore.applyDeployedServer(prefs, 1, 1, "203.0.113.9", 46000, 46002, "new-pass"))
        assertFalse(mode(prefs, 1))
        assertEquals("the hidden older address is replaced", "203.0.113.9", prefs[stringPreferencesKey("peer_1")])
        assertEquals("new-pass", prefs[stringPreferencesKey("connection_password_local_1")])

        assertTrue(SettingsStore.applyDeployedServer(prefs, 2, 2, "203.0.113.9", 46000, 46002, "new-pass"))
        assertTrue(mode(prefs, 2))
        assertEquals(Store253Snapshots.participantLink, prefs[stringPreferencesKey("csqtt_link_2")])

        // an own-server profile keeps the address it dials (a domain, say) as before
        val own = load(
            """{"strings": {"peer_3": "vpn.example.invalid", "deploy_ip_3": "203.0.113.9"}, "ints": {"vps_ref_3": 3}}""",
        )
        assertTrue(SettingsStore.applyDeployedServer(own, 3, 3, "203.0.113.9", 46000, 46002, null))
        assertEquals("vpn.example.invalid", own[stringPreferencesKey("peer_3")])
        assertFalse(mode(own, 3))
    }

    @Test
    fun `a profile added by hand waits for the user's own server, and an empty open one is that profile`() {
        // a participant's profile holds a link: the manual one is a new profile, opened for editing
        val participant = load(
            """{"strings": {"csqtt_link": "${Store253Snapshots.participantLink}"}, "booleans": {"csqtt_link_mode": true}}""",
        )
        val added = SettingsStore.manualProfileKeys(participant)!!
        assertEquals(1, added)
        assertFalse(mode(participant, added))
        assertEquals(1, participant[intPreferencesKey("active_profile")])
        // a first start's profile waits for a link and holds nothing: it is the one that waits for a server now
        val first = load("""{"booleans": {"csqtt_link_mode": true}}""")
        assertEquals(0, SettingsStore.manualProfileKeys(first))
        assertFalse(mode(first, 0))
        assertEquals(setOf(0), SettingsStore.profileList(first).map { it.first }.toSet())
        // nothing free: nothing is written
        assertNull(SettingsStore.manualProfileKeys(load(Store253Snapshots.allSlots)))
    }
}
