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

/**
 * P3-PLAN §1.7 (the operator, 2026-11-26): a hidden profile stays on the device and is in none of the lists; the open
 * one is never hidden; a hidden one is shown again when it is opened and when an import lands on it; a subscription's
 * refresh keeps the mark; a subscription's node has no delete among its actions.
 */
class ProfileHiddenTest {
    private val link = "csqtt://connect?v=2&host=198.51.100.77&peer=46000&password=pw&hashes=${"E".repeat(20)}"
    private val url = "https://sub.example.net/c/1"
    private fun node(profile: Int, name: String, host: String) = SubscriptionProfileWrite(
        profile, "csqtt://connect?v=2&host=$host&peer=56000&password=pw", 20, name, "Мой VPN", url, "uuid-$name",
    )

    /** The owner's profile 0, open; a link profile in 1; two nodes of one subscription in 5 and 6. */
    private fun store() = load(Store253Snapshots.owner).also { prefs ->
        SettingsStore.importLinkKeys(prefs, link, target = null, name = "Дом")
        SettingsStore.writeSubscriptionNode(prefs, node(5, "msk-1", "msk1.example.net"), now = 1L)
        SettingsStore.writeSubscriptionNode(prefs, node(6, "ams-1", "ams1.example.net"), now = 1L)
    }

    private fun shown(prefs: androidx.datastore.preferences.core.Preferences, sort: ProfileSort = ProfileSort.Custom) =
        ProfileOrder.shown(SettingsStore.profileEntries(prefs), sort, emptyList()).map { it.index }

    @Test fun aHiddenProfileIsOutOfEveryListAndStaysOnTheDevice() {
        val prefs = store()
        assertTrue(SettingsStore.setProfileHiddenKeys(prefs, 1, hidden = true))
        for (sort in ProfileSort.entries) assertEquals("$sort", setOf(0, 5, 6), shown(prefs, sort).toSet())
        assertFalse(1 in ProfileOrder.homeRows(ProfileOrder.shown(SettingsStore.profileEntries(prefs), ProfileSort.Custom, emptyList()), 0).map { it.index })
        // it is still a profile: its link is there, and the sheet lists it among the hidden ones
        assertEquals(link, prefs[stringPreferencesKey("csqtt_link_1")])
        assertEquals(listOf(1), SettingsStore.profileEntries(prefs).filter { it.hidden }.map { it.index })
        // classic's picker leaves it out too
        assertEquals(listOf(0, 5, 6), SettingsStore.profileList(prefs).map { it.first })
        assertTrue(SettingsStore.setProfileHiddenKeys(prefs, 1, hidden = false))
        assertEquals(listOf(0, 1, 5, 6), shown(prefs))
        assertEquals(listOf(0, 1, 5, 6), SettingsStore.profileList(prefs).map { it.first })
    }

    @Test fun theSheetsRowsHaveWhatTheirLinesAndGroupsAreMadeOf() {
        val entries = SettingsStore.profileEntries(store()).associateBy { it.index }
        // a node: its subscription (the sheet's group), the streams it is set to
        assertEquals(url, entries.getValue(5).subscriptionUrl)
        assertEquals(url, entries.getValue(6).subscriptionUrl)
        assertEquals(20, entries.getValue(5).workers)
        assertEquals("", entries.getValue(1).subscriptionUrl)
        // the owner's server: the port beside its address
        assertEquals(46100, entries.getValue(0).port)
    }

    @Test fun theOpenProfileIsNeverHidden() {
        val prefs = store()
        assertFalse(SettingsStore.setProfileHiddenKeys(prefs, 0, hidden = true))
        assertNull(prefs[booleanPreferencesKey("profile_hidden")])
        assertEquals(listOf(0, 1, 5, 6), shown(prefs))
    }

    @Test fun aHiddenProfileIsShownAgainWhenItIsOpenedAndWhenAnImportLandsOnIt() {
        val prefs = store()
        SettingsStore.setProfileHiddenKeys(prefs, 1, hidden = true)
        SettingsStore.setProfileHiddenKeys(prefs, 5, hidden = true)
        SettingsStore.openProfileKeys(prefs, 5)   // chosen for a connection
        assertEquals(5, prefs[intPreferencesKey("active_profile")])
        assertEquals(listOf(0, 5, 6), shown(prefs))
        SettingsStore.importLinkKeys(prefs, link, target = 1)   // «Обновить «Дом»»
        assertEquals(listOf(0, 1, 5, 6), shown(prefs))
    }

    @Test fun aRefreshKeepsANodeHidden() {
        val prefs = store()
        SettingsStore.setProfileHiddenKeys(prefs, 6, hidden = true)
        SettingsStore.writeSubscriptionNode(prefs, node(6, "ams-1", "ams1.example.net"), now = 2L)
        assertEquals(true, prefs[booleanPreferencesKey("profile_hidden_6")])
        assertEquals(listOf(0, 1, 5), shown(prefs))
    }

    @Test fun whenTheOpenProfileIsDeletedAShownOneIsOpenedBeforeAHiddenOne() {
        val prefs = store()
        SettingsStore.setProfileHiddenKeys(prefs, 1, hidden = true)
        SettingsStore.deleteProfileKeys(prefs, 0)
        assertEquals(5, prefs[intPreferencesKey("active_profile")])
        assertEquals(true, prefs[booleanPreferencesKey("profile_hidden_1")])
        // with only hidden ones left, the one that is opened is shown
        val only = store()
        listOf(1, 5, 6).forEach { SettingsStore.setProfileHiddenKeys(only, it, hidden = true) }
        SettingsStore.deleteProfileKeys(only, 0)
        assertEquals(1, only[intPreferencesKey("active_profile")])
        assertNull(only[booleanPreferencesKey("profile_hidden_1")])
    }

    @Test fun aSubscriptionsNodeHasNoDeleteAmongItsActions() {
        val entries = SettingsStore.profileEntries(store()).associateBy { it.index }
        val own = ProfileMenu.items(entries.getValue(1))
        assertEquals(
            listOf(ProfileAction.Rename, ProfileAction.Pin, ProfileAction.Hide, ProfileAction.OpenTunnel, ProfileAction.Delete), own,
        )
        assertEquals(own - ProfileAction.Delete, ProfileMenu.items(entries.getValue(5)))
        // a hidden one is shown first; pinning and opening wait until it is
        assertEquals(listOf(ProfileAction.Show, ProfileAction.Rename, ProfileAction.Delete), ProfileMenu.items(entries.getValue(1).copy(hidden = true)))
        assertEquals(listOf(ProfileAction.Show, ProfileAction.Rename), ProfileMenu.items(entries.getValue(5).copy(hidden = true)))
        // the selected one cannot be hidden, so that is not offered
        assertFalse(ProfileAction.Hide in ProfileMenu.items(entries.getValue(0), selected = true))
        // a pinned one is offered «Открепить»
        assertEquals(ProfileAction.Unpin, ProfileMenu.items(entries.getValue(1).copy(pinned = true))[1])
    }
}
