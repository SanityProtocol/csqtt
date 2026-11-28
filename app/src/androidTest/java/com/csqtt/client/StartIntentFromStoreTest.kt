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

    /**
     * The record's other half, each value another than its default: one a START dropped on its way would come out as
     * the default and pass a comparison of two defaults. The hash mode among them — the service takes it from here now.
     */
    @Test
    fun theStartCarriesALinksRecordValueByValue() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val shared = SettingsStore(context)
        val slot = 23
        val store = SettingsStore(context, ProfileScope.Fixed(slot))
        val owner = "O".repeat(20)
        val taken = "T".repeat(20)
        val link = "csqtt://connect?v=2&host=home.example.net&peer=56000&password=pw&hashes=$owner,$taken"
        val subnet = TurnSubnetRegistry.subnets.first()
        try {
            shared.clearProfile(slot)
            // as an import writes it: the link and the profile's kind in one (the form's own write is for a profile in use)
            store.saveCsqttParticipantLink(link)
            store.saveVkHashesOnly("P".repeat(20))
            store.saveLinkHashesRemoved(setOf(taken))
            store.saveAcceptServerHashes(false)
            store.saveVkHashMode(CsqttConstants.VkAutoHash.MODE_AUTO_API)
            store.saveSelectedTurnSubnet(subnet.id, ipLimit = "3")
            store.saveSrtpMode(CsqttConstants.SrtpMode.DTLS)

            val intent = buildTunnelStartIntent(context, store, generationId = 78L, sessionSalt = "salt")!!
            val read = intent.runRead()!!
            assertEquals(store.runRead(), read)
            assertEquals(slot, read.profile)
            assertEquals(true, read.source.linkMode)
            assertEquals(link, read.source.link)
            assertEquals(listOf("P".repeat(20)), read.source.ownHashes)
            assertEquals(setOf(taken), read.source.removedOwner)
            assertEquals(false, read.source.acceptServerHashes)
            assertEquals(CsqttConstants.VkAutoHash.MODE_AUTO_API, read.hashMode)
            assertEquals(subnet.id, read.turnSubnet)
            assertEquals("3", read.turnLimit)
            assertEquals(CsqttConstants.SrtpMode.DTLS, read.settings.srtpMode)
            assertEquals(subnet.ips.take(3).joinToString(","), read.settings.customTurnIps)
        } finally {
            shared.deleteProfile(slot)
        }
    }
}
