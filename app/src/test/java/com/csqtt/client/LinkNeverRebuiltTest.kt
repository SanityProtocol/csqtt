package com.csqtt.client

import androidx.datastore.preferences.core.stringPreferencesKey
import com.csqtt.client.Store253Snapshots.load
import org.junit.Assert.assertEquals
import org.junit.Test

/** M-72: a participant link is stored as the owner gave it, never rebuilt from its parsed fields. */
class LinkNeverRebuiltTest {
    @Test fun theStoredLinkIsTheGivenTextByteForByte() {
        // parameters out of order, an unknown one, and upper case in the scheme: a rebuild would change every one
        val given = "CSQTT://connect?peer=46000&v=2&x-extra=1&host=198.51.100.9&hashes=${"A".repeat(20)}"
        val prefs = load(Store253Snapshots.participant)
        SettingsStore.importLinkKeys(prefs, given, target = 3)
        assertEquals(given, prefs[stringPreferencesKey("csqtt_link_3")])
    }
}
