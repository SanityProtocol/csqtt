package com.csqtt.client

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B5a (B4c backlog B2): Home, the tile and the widget build START from the store at tap time, so a TCP + DPI split +
 * 30-worker profile starts with those values, never with a screen's not-yet-loaded defaults.
 */
@RunWith(AndroidJUnit4::class)
class StartIntentFromStoreTest {
    @Test
    fun theStartIntentCarriesTheStoredProfile() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SettingsStore(context)
        store.saveCsqttLinkMode(false)
        store.save(
            peer = "203.0.113.20",
            vkHashes = "H".repeat(20),
            secondaryVkHash = "",
            workersPerHash = 30,
            protocol = "udp",
            listenPort = 0,
        )
        store.saveConnectionPassword("secret-pass")
        store.saveTurnTransport(CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS)
        store.saveDpiSplit(DpiSplitMode.MEDIUM)

        val built = buildTunnelStartIntent(context, store, generationId = 77L, sessionSalt = "salt")
        assertNotNull(built)
        val intent = built!!
        assertEquals(QuickTunnelAction.START, intent.action)
        assertEquals(CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS, intent.getStringExtra("turn_transport"))
        assertEquals(DpiSplitMode.MEDIUM.storedValue, intent.getStringExtra(DpiSplitMode.EXTRA))
        assertEquals(30, intent.getIntExtra("workers_per_hash", 0))
        assertEquals("secret-pass", intent.getStringExtra("connection_password"))
        assertEquals(77L, intent.getLongExtra("generation_id", 0L))
        assertEquals("salt", intent.getStringExtra("session_salt"))
    }
}
