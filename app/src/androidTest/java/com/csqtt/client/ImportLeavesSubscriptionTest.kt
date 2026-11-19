package com.csqtt.client

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A csqtt:// link imported into a slot that came from a subscription replaces that configuration: the slot leaves the
 * subscription, so the next refresh cannot match it again and put the subscription's node back (P1 B1, review F1).
 */
@RunWith(AndroidJUnit4::class)
class ImportLeavesSubscriptionTest {
    @Test
    fun importedLinkDetachesTheSlotFromItsSubscription() = runBlocking {
        val store = SettingsStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val slot = 7
        store.saveActiveProfile(slot)
        store.applySubscriptionImport(
            listOf(
                SubscriptionProfileWrite(
                    profile = slot, host = "203.0.113.7", port = 46000, password = "sub-password", hashes = emptyList(),
                    power = null, nodeName = "node A", title = "test subscription",
                    url = "https://sub.example.invalid/s/abc", subUuid = "uuid-abc",
                ),
            ),
        )
        assertTrue(slot in store.profileSubscriptions.first())

        store.saveCsqttParticipantLink("csqtt://connect?host=198.51.100.9&port=46000&password=link-password")

        assertFalse(slot in store.profileSubscriptions.first())
        assertEquals(slot, store.activeProfile.first())
    }
}
