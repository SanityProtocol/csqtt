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

/** M-70: an import never writes a profile the user did not pick, and never the active one while the tunnel runs. */
class ImportNeverOverwritesTest {
    private val link = "csqtt://connect?v=2&host=198.51.100.77&peer=46000&password=pw&hashes=${"E".repeat(20)}"
    private val linkEndpoint = LinkImport.endpoint("198.51.100.77", 46000)

    private fun state(empty: Boolean = false, endpoint: String? = "203.0.113.5:46100", free: Boolean = true, running: Boolean = false) =
        LinkImportState("Профиль 1", empty, endpoint, free, running)

    @Test fun anotherServersProfileIsOfferedForReplacementByName() {
        val plan = LinkImport.plan(linkEndpoint, state())
        assertTrue(plan.intoActive)
        assertFalse(plan.activeIsSameOrEmpty)   // the dialog says «Заменить профиль «…»»
        assertTrue(plan.intoNew)
        assertNull(plan.note)
    }

    @Test fun anEmptyOrSameServerProfileTakesItWithoutReplacingAnything() {
        assertTrue(LinkImport.plan(linkEndpoint, state(empty = true, endpoint = null)).activeIsSameOrEmpty)
        assertTrue(LinkImport.plan(linkEndpoint, state(endpoint = linkEndpoint)).activeIsSameOrEmpty)
        assertEquals("2001:db8::1:46000", LinkImport.endpoint("[2001:DB8::1]", 46000))
    }

    @Test fun whileTheTunnelRunsOnlyANewProfileIsOffered() {
        val plan = LinkImport.plan(linkEndpoint, state(running = true))
        assertFalse(plan.intoActive)
        assertTrue(plan.intoNew)
        assertEquals("Туннель работает: ссылка добавится в новый профиль", plan.note)
        val stuck = LinkImport.plan(linkEndpoint, state(running = true, free = false))
        assertFalse(stuck.intoActive || stuck.intoNew)
        assertTrue(stuck.note!!.startsWith("Отключите туннель"))
    }

    @Test fun withEveryProfileTakenOnlyTheActiveOneIsOfferedAndTheDialogSaysWhy() {
        val plan = LinkImport.plan(linkEndpoint, state(free = false))
        assertTrue(plan.intoActive)
        assertFalse(plan.intoNew)
        assertEquals("Все 32 профиля заняты", plan.note)
    }

    @Test fun theStoreReadsTheActiveProfileAndFreeSlots() {
        val owner = load(Store253Snapshots.owner)
        val s = SettingsStore.linkImportState(owner, tunnelRunning = false)
        assertEquals("203.0.113.5:46100", s.activeEndpoint)
        assertFalse(s.activeEmpty)
        assertTrue(s.freeProfile)
        val participant = load(Store253Snapshots.participant)
        assertEquals("198.51.100.9:46000", SettingsStore.linkImportState(participant, false).activeEndpoint)
        assertFalse(SettingsStore.linkImportState(load(Store253Snapshots.allSlots), false).freeProfile)
    }

    @Test fun aNewProfileLeavesTheActiveOneUntouchedAndIsActivatedOnlyWhenAsked() {
        val prefs = load(Store253Snapshots.owner)
        val written = SettingsStore.importParticipantLinkKeys(prefs, link, intoNew = true, activate = false)
        assertEquals(1, written)
        assertEquals(0, prefs[intPreferencesKey("active_profile")])   // the running tunnel's profile stays active
        assertEquals("203.0.113.5", prefs[stringPreferencesKey("peer")])   // profile 0 untouched
        assertEquals(link, prefs[stringPreferencesKey("csqtt_link_1")])
        assertEquals(true, prefs[booleanPreferencesKey("csqtt_link_mode_1")])
        val again = load(Store253Snapshots.owner)
        assertEquals(1, SettingsStore.importParticipantLinkKeys(again, link, intoNew = true, activate = true))
        assertEquals(1, again[intPreferencesKey("active_profile")])
    }

    @Test fun replacingAnOwnerProfileClearsItsOwnServerFieldsAndAFullStoreRefusesANewOne() {
        val prefs = load(Store253Snapshots.owner)
        assertEquals(0, SettingsStore.importParticipantLinkKeys(prefs, link, intoNew = false, activate = true))
        assertNull(prefs[stringPreferencesKey("peer")])
        assertNull(prefs[stringPreferencesKey("connection_password_local")])
        assertNull(prefs[stringPreferencesKey("vk_hashes")])   // the link brings hashes: the owner's own go
        assertEquals(link, prefs[stringPreferencesKey("csqtt_link")])
        assertNull(SettingsStore.importParticipantLinkKeys(load(Store253Snapshots.allSlots), link, intoNew = true, activate = true))
    }
}
