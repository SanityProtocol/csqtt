package com.csqtt.client

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
