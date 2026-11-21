package com.csqtt.client

import androidx.datastore.preferences.core.stringPreferencesKey
import com.csqtt.client.Store253Snapshots.load
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D6 (a1), B5e3b: the owner's pool becomes the server's default hashes through the panel's own API, over its HTTPS with
 * B5c1's trust rule (a self-signed panel on first use, then only that leaf).
 */
class PanelHashesHandOverTest {
    private class Seen(var path: String = "", var key: String? = null, var type: String? = null, var body: String = "")

    /** A panel at the fixture's self-signed leaf answering [code]; what it was sent lands in the returned [Seen]. */
    private fun <T> withPanel(code: Int = 200, block: (port: Int, seen: Seen) -> T): T {
        val seen = Seen()
        val server = SubscriptionTestServers.https()
        server.createContext("/") { exchange ->
            seen.path = exchange.requestURI.path
            seen.key = exchange.requestHeaders.getFirst("x-api-key")
            seen.type = exchange.requestHeaders.getFirst("Content-Type")
            seen.body = exchange.requestBody.bufferedReader().readText()
            val reply = (if (code == 200) """{"restart_required":false}""" else "provide 1 to 6 valid VK hashes").toByteArray()
            exchange.sendResponseHeaders(code, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
        return try {
            block(server.address.port, seen)
        } finally {
            server.stop(0)
        }
    }

    private fun panel(port: Int) = PoolPanel(2, "127.0.0.1", port, "0f0e-uuid", "web-pass")
    private val pool = ('A'..'G').map { it.toString().repeat(20) }   // 7: an owner at 70 workers

    @Test fun theFirstSixGoAsJsonWithTheWebPasswordAndTheLeafIsRecorded() = withPanel { port, seen ->
        val result = PanelApi.setDefaultHashes(panel(port), pool, cert = null)
        assertEquals(PanelApi.Outcome.Ok, result.outcome)
        assertEquals("/0f0e-uuid/api/settings", seen.path)
        assertEquals("web-pass", seen.key)
        assertEquals("application/json", seen.type)
        assertEquals(pool.take(6).joinToString(","), JSONObject(seen.body).getString("vk_hashes"))
        assertEquals("only the hashes are sent: a partial update keeps every other setting", setOf("vk_hashes"),
            JSONObject(seen.body).keys().asSequence().toSet())
        assertEquals(SubscriptionTestServers.fixtureSha256, result.observed?.sha256)
        assertEquals(CertKind.SELF, result.observed?.kind)
    }

    @Test fun theRecordedLeafIsTrustedAgainAndAnotherIsRefused() = withPanel { port, _ ->
        val recorded = SubscriptionCertRecord(SubscriptionTestServers.fixtureSha256, System.currentTimeMillis() + 86_400_000L)
        assertEquals(PanelApi.Outcome.Ok, PanelApi.setDefaultHashes(panel(port), pool, recorded).outcome)
        val other = SubscriptionCertRecord("ab".repeat(32), System.currentTimeMillis() + 86_400_000L)
        assertEquals(PanelApi.Outcome.CertificateChanged, PanelApi.setDefaultHashes(panel(port), pool, other).outcome)
    }

    @Test fun aRefusalCarriesItsCodeAndNoLeafIsRecorded() = withPanel(code = 401) { port, _ ->
        val result = PanelApi.setDefaultHashes(panel(port), pool, cert = null)
        assertEquals(401, (result.outcome as PanelApi.Outcome.Refused).code)
        assertNull(result.observed)
    }

    @Test fun aPasswordTheHeaderCannotCarryIsNeverSent() = withPanel { port, seen ->
        // the panel's x-api-key is visible ASCII only (HeaderValue::to_str); Android may print the value it refuses
        val result = PanelApi.setDefaultHashes(panel(port).copy(webPassword = "пароль-панели"), pool, cert = null)
        assertEquals(PanelApi.Outcome.PasswordNotAscii, result.outcome)
        assertEquals("nothing reached the panel", "", seen.path)
    }

    @Test fun anUnreachablePanelFails() {
        val port = java.net.ServerSocket(0).use { it.localPort }   // closed again: nothing listens there
        assertTrue(PanelApi.setDefaultHashes(panel(port), pool, cert = null).outcome is PanelApi.Outcome.Failed)
    }

    @Test fun anIpv6HostIsBracketed() {
        assertEquals("https://[2001:db8::1]:46002/p/api/settings",
            PanelApi.settingsUrl(PoolPanel(0, "2001:db8::1", 46002, "p", "x")))
    }

    @Test fun theStateIsKeptPerRecordAndADeployClearsIt() {
        val prefs = load("""{"strings": {"deploy_ip_2": "203.0.113.2"}}""")
        val cert = SubscriptionCertRecord("cd".repeat(32), 1_900_000_000_000L, CertKind.CA, setOf("203.0.113.2"))
        SettingsStore.writeHandOver(prefs, 2, pool.take(2), cert)
        assertEquals(pool.take(2) to cert, SettingsStore.handOverStateOf(prefs, 2))
        // a later success by the same leaf keeps it
        SettingsStore.writeHandOver(prefs, 2, pool.take(3), null)
        assertEquals(pool.take(3) to cert, SettingsStore.handOverStateOf(prefs, 2))
        SettingsStore.clearHandOver(prefs, 2)
        assertEquals(emptyList<String>() to null, SettingsStore.handOverStateOf(prefs, 2))
        // a record without its server is not revived
        SettingsStore.writeHandOver(prefs, 3, pool.take(1), cert)
        assertNull(prefs[stringPreferencesKey("deploy_vk_pool_sent_3")])
        assertNull("a broken record is no record", SettingsStore.decodePanelCert("{"))
    }
}
