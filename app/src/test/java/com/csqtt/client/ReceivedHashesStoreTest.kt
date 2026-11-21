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

    @Test fun storedForTheLinkProfileTheRunStartedFrom() {
        val prefs = load(Store253Snapshots.participant)   // profile 3 is a link profile
        assertTrue(SettingsStore.receivedHashesKeys(prefs, 3, fresh))
        assertEquals(fresh.joinToString(","), prefs[stringPreferencesKey("received_hashes_3")])
        assertFalse("the same set again changes nothing", SettingsStore.receivedHashesKeys(prefs, 3, fresh))
        // the link's own set is nothing to replace: the stored one goes
        assertTrue(SettingsStore.receivedHashesKeys(prefs, 3, listOf(linkHashA, linkHashB)))
        assertNull(prefs[stringPreferencesKey("received_hashes_3")])
        // an owner profile never stores any
        assertFalse(SettingsStore.receivedHashesKeys(load(Store253Snapshots.owner), 0, fresh))
    }

    @Test fun theyReplaceTheLinksSetAndANewLinkClearsThem() {
        assertEquals(fresh, ReceivedHashes.ownerSet(listOf(linkHashA), fresh))
        assertEquals(listOf(linkHashA), ReceivedHashes.ownerSet(listOf(linkHashA), emptyList()))
        val prefs = load(Store253Snapshots.participant)
        SettingsStore.receivedHashesKeys(prefs, 3, fresh)
        SettingsStore.importParticipantLinkKeys(prefs, Store253Snapshots.participantLink, intoNew = false, activate = true)
        assertNull(prefs[stringPreferencesKey("received_hashes_3")])
    }
}

/** M-74: the activity refreshes with the tunnel idle; the tunnel service's hourly call refreshes while it runs. */
class SubscriptionRefreshWhileConnectedTest {
    @Test fun theServiceMayRefreshWhileConnectedAndTheActivityMayNot() {
        assertTrue(SubscriptionSync.mayRefresh(tunnelActive = false, whileConnected = false))
        assertFalse(SubscriptionSync.mayRefresh(tunnelActive = true, whileConnected = false))
        assertTrue(SubscriptionSync.mayRefresh(tunnelActive = true, whileConnected = true))
    }
}
