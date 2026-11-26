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
 * M-70 and the canvas's one rule for imports (I15): an import never writes a profile the user did not pick, never the
 * one the tunnel is on, and a new profile is never made the open one by itself.
 */
class ImportNeverOverwritesTest {
    private val link = "csqtt://connect?v=2&host=198.51.100.77&peer=46000&password=pw&hashes=${"E".repeat(20)}"
    private val linkEndpoint = LinkImport.endpoint("198.51.100.77", 46000)

    private fun state(same: Int? = null, emptyOpen: Int? = null, free: Boolean = true, run: Int? = null) =
        LinkImportState(same, if (same == null) "" else "Дом", emptyOpen, free, run, if (run == null) "" else "Хельсинки")

    @Test fun aLinkToANewServerIsAddedAndNothingElseIsOffered() {
        val plan = LinkImport.plan(state())
        assertNull(plan.same)
        assertFalse(plan.update)
        assertTrue(plan.add)
        assertNull(plan.addInto)   // a new profile
        // the tunnel being on changes nothing: the new profile is not the one it runs on
        assertEquals(plan, LinkImport.plan(state(run = 0)))
    }

    @Test fun anOpenProfileThatHoldsNothingTakesTheLinkAndNoSecondSlot() {
        val plan = LinkImport.plan(state(emptyOpen = 0, free = false))
        assertTrue(plan.add)
        assertEquals(0, plan.addInto)
    }

    @Test fun aProfileAlreadyOnTheServerIsOfferedForUpdateUnlessTheTunnelIsOnIt() {
        val plan = LinkImport.plan(state(same = 4))
        assertEquals(4, plan.same)
        assertTrue(plan.update)
        assertTrue(plan.add)
        // on another profile: still offered
        assertTrue(LinkImport.plan(state(same = 4, run = 0)).update)
        // on that very profile: not written under the run
        val under = LinkImport.plan(state(same = 4, run = 4))
        assertEquals(4, under.same)
        assertFalse(under.update)
        assertTrue(under.add)
    }

    @Test fun withEveryProfileTakenNothingCanBeAdded() {
        assertFalse(LinkImport.plan(state(free = false)).add)
        val onlyUpdate = LinkImport.plan(state(same = 4, free = false))
        assertTrue(onlyUpdate.update)
        assertFalse(onlyUpdate.add)
        assertEquals("2001:db8::1:46000", LinkImport.endpoint("[2001:DB8::1]", 46000))
    }

    @Test fun theStoreFindsTheProfileOnTheLinksServerTheEmptyOpenOneAndAFreeSlot() {
        val owner = load(Store253Snapshots.owner)
        val elsewhere = SettingsStore.linkImportState(owner, linkEndpoint, runProfile = null)
        assertNull(elsewhere.same)
        assertNull(elsewhere.emptyOpen)
        assertTrue(elsewhere.freeProfile)
        // the owner's own server, by its address and port
        val own = SettingsStore.linkImportState(owner, "203.0.113.5:46100", runProfile = 0)
        assertEquals(0, own.same)
        assertEquals(0, own.runProfile)
        assertFalse(LinkImport.plan(own).update)
        // a participant's link profile, by its link's server
        assertEquals(3, SettingsStore.linkImportState(load(Store253Snapshots.participant), "198.51.100.9:46000", null).same)
        assertFalse(SettingsStore.linkImportState(load(Store253Snapshots.allSlots), linkEndpoint, null).freeProfile)
        // a first start: the open profile waits for a link and holds nothing
        assertEquals(0, SettingsStore.linkImportState(load("""{"booleans": {"csqtt_link_mode": true}}"""), linkEndpoint, null).emptyOpen)
    }

    @Test fun aSubscriptionsNodeIsNeverTheProfileToUpdate() {
        val prefs = load(Store253Snapshots.owner)
        SettingsStore.writeSubscriptionNode(
            prefs, SubscriptionProfileWrite(5, link, 20, "msk-1", "Мой VPN", "https://sub.example.net/c/1", null), now = 1L,
        )
        assertNull(SettingsStore.linkImportState(prefs, linkEndpoint, null).same)
    }

    @Test fun aNewProfileLeavesTheOpenOneOpenAndTakesTheNameItWasGiven() {
        val prefs = load(Store253Snapshots.owner)
        val written = SettingsStore.importLinkKeys(prefs, link, target = null, name = " Дом ")
        assertEquals(1, written)
        assertEquals(0, prefs[intPreferencesKey("active_profile")])   // never opened by itself
        assertEquals("203.0.113.5", prefs[stringPreferencesKey("peer")])   // profile 0 untouched
        assertEquals(link, prefs[stringPreferencesKey("csqtt_link_1")])
        assertEquals(true, prefs[booleanPreferencesKey("csqtt_link_mode_1")])
        assertEquals("Дом", SettingsStore.profileDisplayName(prefs, 1))
        assertEquals("Дом", SettingsStore.profileOriginalName(prefs, 1))
        assertNull(SettingsStore.importLinkKeys(load(Store253Snapshots.allSlots), link, target = null))
    }

    @Test fun anUpdatedProfileKeepsItsNameLosesItsOwnServerFieldsAndIsShownAgain() {
        val prefs = load(Store253Snapshots.owner)
        SettingsStore.renameProfileKeys(prefs, 0, "Хельсинки")
        prefs[booleanPreferencesKey("profile_hidden")] = true
        assertEquals(0, SettingsStore.importLinkKeys(prefs, link, target = 0))
        assertNull(prefs[stringPreferencesKey("peer")])
        assertNull(prefs[stringPreferencesKey("connection_password_local")])
        assertNull(prefs[stringPreferencesKey("vk_hashes")])   // the link brings hashes: the owner's own go
        assertEquals(link, prefs[stringPreferencesKey("csqtt_link")])
        assertEquals("Хельсинки", SettingsStore.profileDisplayName(prefs, 0))
        assertNull(prefs[booleanPreferencesKey("profile_hidden")])
    }
}
