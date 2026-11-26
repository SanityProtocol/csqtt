package com.csqtt.client

import com.csqtt.client.Store253Snapshots.load
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The canvas, F-SubCertChanged: a marked subscription whose server shows another certificate than the trusted one is
 * not refreshed; the certificate it showed is kept for the question, and «Доверять новому» makes exactly that one the
 * trusted one. On P1's SubscriptionTlsTest servers.
 */
class SubCertRetrustTest {
    private val body = """{"ok": true, "group": {"name": "Pinned", "items": [
        {"name": "N", "link": "csqtt://connect?v=2&host=203.0.113.40&peer=46000&password=secret"}]}}"""
    private val future = System.currentTimeMillis() + 86_400_000L
    private val old = SubscriptionCertRecord("ab".repeat(32), future)

    private fun <T> withHttps(block: suspend (url: String) -> T): T = runBlocking {
        val server = SubscriptionTestServers.https()
        server.createContext("/sub") { exchange ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("https://127.0.0.1:${server.address.port}/sub")
        } finally {
            server.stop(0)
        }
    }

    private fun meta(prefs: androidx.datastore.preferences.core.Preferences, url: String) =
        SettingsStore.subscriptionMetaOf(prefs).getValue(url)

    @Test
    fun `the certificate that was turned away is the one the user can trust, and then the refresh works`() = withHttps { url ->
        val refusal = SubscriptionImporter.fetch(url, SubscriptionTrust(true, old))
        val refused = (refusal as SubscriptionFetchResult.Failure).refused!!
        assertEquals(SubscriptionTestServers.fixtureSha256, refused.sha256)

        val prefs = load("{}")
        SettingsStore.writeSubscriptionCertificate(prefs, url, old)
        assertTrue(SettingsStore.writeSubscriptionOutcome(prefs, url, refusal.message, "", refused))
        assertEquals(old.sha256, meta(prefs, url).trusted!!.sha256)
        assertEquals(refused, meta(prefs, url).refused)

        // another certificate than the one shown is never trusted by the answer
        assertFalse(SettingsStore.trustRefusedCertificateKeys(prefs, url, "cd".repeat(32)))
        assertEquals(old.sha256, meta(prefs, url).trusted!!.sha256)

        assertTrue(SettingsStore.trustRefusedCertificateKeys(prefs, url, refused.sha256.uppercase()))
        assertEquals(refused, meta(prefs, url).trusted)
        assertNull(meta(prefs, url).refused)
        assertTrue(SubscriptionImporter.fetch(url, SubscriptionTrust(true, meta(prefs, url).trusted)) is SubscriptionFetchResult.Success)
        // answered once: there is nothing left to trust
        assertFalse(SettingsStore.trustRefusedCertificateKeys(prefs, url, refused.sha256))
    }

    @Test
    fun `a refresh that fails for another reason, or works, leaves no certificate to ask about`() = withHttps { url ->
        val prefs = load("{}")
        val refused = (SubscriptionImporter.fetch(url, SubscriptionTrust(true, old)) as SubscriptionFetchResult.Failure).refused
        SettingsStore.writeSubscriptionOutcome(prefs, url, "Сертификат сервера подписки не совпадает", "", refused)
        SettingsStore.writeSubscriptionOutcome(prefs, url, "Ошибка сети: нет соединения", "")
        assertNull(meta(prefs, url).refused)
        SettingsStore.writeSubscriptionOutcome(prefs, url, "Сертификат сервера подписки не совпадает", "", refused)
        SettingsStore.writeSubscriptionOutcome(prefs, url, "", "")
        assertNull(meta(prefs, url).refused)
        // a failure that is not about the certificate carries none
        val plain = SubscriptionImporter.fetch(url.replace("/sub", "/nothing"), SubscriptionTrust(true, null))
        assertNull((plain as SubscriptionFetchResult.Failure).refused)
    }
}
