package com.csqtt.client

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
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
        // as the «Туннель» form writes them, a field at a time
        store.saveFormAddress("203.0.113.20", null, store.profileEpoch.first())
        store.saveVkHashesOnly("H".repeat(20))
        store.saveWorkersPerHash(30)
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

        // card p3-apply-bar: the START carries what it read of the profile, whole — the service keeps it as the run's
        // record, and the screens compare the store with it. It is the store's own reading, and the settings the
        // START carries are the record's
        val read = intent.runRead()
        assertEquals(store.runRead(), read)
        assertEquals(read!!.settings, intent.tunnelSettings())
        assertEquals("203.0.113.20", read.source.address)
        assertEquals("secret-pass", read.source.password)
        assertEquals(listOf("H".repeat(20)), read.source.ownHashes)
        assertEquals(store.selectedProfileIndex.first(), read.profile)
        // a START that carries none (another build's) is read by the service from the store instead
        assertEquals(null, android.content.Intent().runRead())
    }
}
