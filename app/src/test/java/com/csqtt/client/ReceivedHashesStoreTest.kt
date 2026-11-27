package com.csqtt.client

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.csqtt.client.Store253Snapshots.linkHashA
import com.csqtt.client.Store253Snapshots.linkHashB
import com.csqtt.client.Store253Snapshots.load
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S8 app half: the owner's hashes the server sends are checked, stored for the link profile, and used next connect. */
class ReceivedHashesStoreTest {
    private val fresh = listOf("F".repeat(20), "G".repeat(24))

    @Test fun onlyAWellFormedNonEmptySetIsTaken() {
        assertEquals(fresh, ReceivedHashes.parse("""{"version":1,"hashes":["${fresh[0]}","${fresh[1]}"]}"""))
        for (json in listOf(
            """{"version":1,"hashes":[]}""",
            """{"version":2,"hashes":["${fresh[0]}"]}""",
            """{"version":1,"hashes":["short"]}""",
            """{"version":1,"hashes":["${fresh[0]}","${fresh[0]}"]}""",
            """{"version":1,"hashes":["${"H".repeat(20)},x"]}""",
            """{"version":1,"hashes":"${fresh[0]}"}""",
            "not json",
        )) {
            assertNull(json, ReceivedHashes.parse(json))
        }
    }

    @Test fun theEventParserCarriesThemAndDropsABrokenSet() {
        val good = TunnelEventParser.parse(
            "__CSQTT_EVENT__|HASHES|" + org.json.JSONObject().put("hashes", """{"version":1,"hashes":["${fresh[0]}"]}"""),
        )
        assertEquals(TunnelEventParser.Event.Hashes(listOf(fresh[0])), good)
        assertNull(TunnelEventParser.parse("__CSQTT_EVENT__|HASHES|" + org.json.JSONObject().put("hashes", "{}")))
    }

    @Test fun storedOnlyForALinkProfileStillHoldingTheRunsLink() {
        val runLink = Store253Snapshots.participantLink
        val prefs = load(Store253Snapshots.participant)   // profile 3 is a link profile
        assertTrue(SettingsStore.receivedHashesKeys(prefs, 3, fresh, runLink))
        assertEquals(fresh.joinToString(","), prefs[stringPreferencesKey("received_hashes_3")])
        assertFalse("the same set again changes nothing", SettingsStore.receivedHashesKeys(prefs, 3, fresh, runLink))
        // the link's own set is nothing to replace: the stored one goes
        assertTrue(SettingsStore.receivedHashesKeys(prefs, 3, listOf(linkHashA, linkHashB), runLink))
        assertNull(prefs[stringPreferencesKey("received_hashes_3")])
        // the profile moved on to another link during the run (a paste, a subscription refresh): the old server's set
        // is not stored for the new link (review F2)
        assertFalse(SettingsStore.receivedHashesKeys(prefs, 3, fresh, runLink.replace("198.51.100.9", "198.51.100.10")))
        // an owner profile never stores any
        assertFalse(SettingsStore.receivedHashesKeys(load(Store253Snapshots.owner), 0, fresh, runLink))
    }

    @Test fun theSwitchOffDropsTheSetAndTakesNoneUntilItIsOnAgain() {
        // P3 B3, «Принимать хеши с сервера»
        val runLink = Store253Snapshots.participantLink
        val prefs = load(Store253Snapshots.participant)
        assertTrue(SettingsStore.receivedHashesKeys(prefs, 3, fresh, runLink, now = 5_000L))
        assertEquals(5_000L, prefs[longPreferencesKey("received_hashes_at_3")])

        SettingsStore.acceptServerHashesKeys(prefs, 3, accept = false)
        assertNull("the profile is back on its link's own hashes", prefs[stringPreferencesKey("received_hashes_3")])
        assertNull(prefs[longPreferencesKey("received_hashes_at_3")])
        assertFalse(SettingsStore.receivedHashesKeys(prefs, 3, fresh, runLink, now = 6_000L))
        assertNull(prefs[stringPreferencesKey("received_hashes_3")])

        SettingsStore.acceptServerHashesKeys(prefs, 3, accept = true)
        assertNull("on is the absence of the key", prefs[booleanPreferencesKey("accept_server_hashes_3")])
        assertTrue(SettingsStore.receivedHashesKeys(prefs, 3, fresh, runLink, now = 7_000L))
        assertEquals(7_000L, prefs[longPreferencesKey("received_hashes_at_3")])
    }

    @Test fun aSetThatChangesNothingIsStillTheServersWordAsOfNow() {
        val prefs = load(Store253Snapshots.participant)
        // the link's own set: nothing to store, and the time it was confirmed is kept
        assertFalse(SettingsStore.receivedHashesKeys(prefs, 3, listOf(linkHashA, linkHashB), Store253Snapshots.participantLink, now = 9_000L))
        assertNull(prefs[stringPreferencesKey("received_hashes_3")])
        assertEquals(9_000L, prefs[longPreferencesKey("received_hashes_at_3")])
        // the same word a moment later (a reconnect) is not written again; ten minutes on it is
        assertFalse(SettingsStore.receivedHashesKeys(prefs, 3, listOf(linkHashA, linkHashB), Store253Snapshots.participantLink, now = 9_500L))
        assertEquals(9_000L, prefs[longPreferencesKey("received_hashes_at_3")])
        assertFalse(SettingsStore.receivedHashesKeys(prefs, 3, listOf(linkHashA, linkHashB), Store253Snapshots.participantLink, now = 9_000L + 600_000L))
        assertEquals(609_000L, prefs[longPreferencesKey("received_hashes_at_3")])
        // a set that changes is dated at once
        assertTrue(SettingsStore.receivedHashesKeys(prefs, 3, fresh, Store253Snapshots.participantLink, now = 610_000L))
        assertEquals(610_000L, prefs[longPreferencesKey("received_hashes_at_3")])
        SettingsStore.receivedHashesKeys(prefs, 3, listOf(linkHashA, linkHashB), Store253Snapshots.participantLink, now = 9_000L)
        prefs[longPreferencesKey("received_hashes_at_3")] = 9_000L
        // another link's set says nothing of this one
        assertFalse(SettingsStore.receivedHashesKeys(prefs, 3, fresh, Store253Snapshots.participantLink.replace("46000", "46010"), now = 10_000L))
        assertEquals(9_000L, prefs[longPreferencesKey("received_hashes_at_3")])
        // a new link starts with no word from its server
        SettingsStore.writeLinkKeys(prefs, 3, Store253Snapshots.participantLink.replace("46000", "46010"))
        assertNull(prefs[longPreferencesKey("received_hashes_at_3")])
    }

    @Test fun theOwnerSetPrefersTheReceivedOneAndANewLinkClearsIt() {
        assertEquals(fresh, ReceivedHashes.ownerSet(listOf(linkHashA), fresh))
        assertEquals(listOf(linkHashA), ReceivedHashes.ownerSet(listOf(linkHashA), emptyList()))
        val prefs = load(Store253Snapshots.participant)
        SettingsStore.receivedHashesKeys(prefs, 3, fresh, Store253Snapshots.participantLink)
        SettingsStore.importLinkKeys(prefs, Store253Snapshots.participantLink, target = 3)
        assertNull(prefs[stringPreferencesKey("received_hashes_3")])
    }

    @Test fun everyLinkWriteDropsTheSetOfAnotherLink() {
        // verify V1: the profile-settings JSON import writes the link through writeLinkKeys too
        val prefs = load(Store253Snapshots.participant)
        SettingsStore.receivedHashesKeys(prefs, 3, fresh, Store253Snapshots.participantLink)
        SettingsStore.writeLinkKeys(prefs, 3, Store253Snapshots.participantLink)
        assertEquals(fresh.joinToString(","), prefs[stringPreferencesKey("received_hashes_3")])
        SettingsStore.writeLinkKeys(prefs, 3, Store253Snapshots.participantLink.replace("46000", "46010"))
        assertNull(prefs[stringPreferencesKey("received_hashes_3")])
    }

    @Test fun aSubscriptionRefreshWithANewLinkDropsTheOldSetAndTheSameLinkKeepsIt() {
        // review F1: the owner replaced dead hashes; the refresh writes the new link, the old received set must go
        val prefs = load(Store253Snapshots.participant)
        SettingsStore.receivedHashesKeys(prefs, 3, fresh, Store253Snapshots.participantLink)
        fun write(link: String) = SettingsStore.writeSubscriptionNode(
            prefs,
            SubscriptionProfileWrite(
                profile = 3, link = link, power = null, nodeName = "node", title = "sub",
                url = "https://sub.example.invalid/s/abc", subUuid = "uuid",
            ),
        )
        write(Store253Snapshots.participantLink)
        assertEquals(fresh.joinToString(","), prefs[stringPreferencesKey("received_hashes_3")])
        write("csqtt://connect?v=2&host=198.51.100.9&peer=46000&password=link-pass&hashes=${"Y".repeat(20)}")
        assertNull(prefs[stringPreferencesKey("received_hashes_3")])
    }
}

/** M-74, the gate only: the activity refreshes with the tunnel idle; the tunnel service's call refreshes while it runs. */
class SubscriptionRefreshWhileConnectedTest {
    @Test fun theServiceMayRefreshWhileConnectedAndTheActivityMayNot() {
        assertTrue(SubscriptionSync.mayRefresh(tunnelActive = false, whileConnected = false))
        assertFalse(SubscriptionSync.mayRefresh(tunnelActive = true, whileConnected = false))
        assertTrue(SubscriptionSync.mayRefresh(tunnelActive = true, whileConnected = true))
    }
}
