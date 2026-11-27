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
    fun `a link typed again without its scheme is judged against the certificate it was trusted with`() {
        // imported with the switch on and no scheme: kept under http://, its server trusted by the certificate it showed
        val kept = "http://panel.example.net:8443/sub/abc"
        val metas = mapOf(kept to SubscriptionMeta(url = kept, allowHttp = true, certSha256 = old.sha256, certNotAfter = future))
        val (url, trust) = SubscriptionSync.trustFor(metas, " panel.example.net:8443/sub/abc ", allowHttp = true)
        assertEquals(kept, url)
        assertTrue(trust.pinned)
        assertEquals(old.sha256, trust.record!!.sha256)   // not a first use: another certificate is turned away
        // a refresh of a stored link: the record's own mark pins it, with no switch to say so
        val https = "https://sub.example.net/c/1"
        val marked = mapOf(https to SubscriptionMeta(url = https, allowHttp = true, certSha256 = old.sha256))
        assertTrue(SubscriptionSync.trustFor(marked, https, allowHttp = false).second.pinned)
        assertEquals(old.sha256, SubscriptionSync.trustFor(marked, https, allowHttp = false).second.record!!.sha256)
        // a link nobody marked gets the platform's checks and has no record; an http:// one is marked by itself
        val plain = SubscriptionSync.trustFor(emptyMap(), "sub.example.net/c/2", allowHttp = false)
        assertEquals("https://sub.example.net/c/2", plain.first)
        assertFalse(plain.second.pinned)
        assertNull(plain.second.record)
        assertTrue(SubscriptionSync.trustFor(emptyMap(), "http://sub.example.net/c/2", allowHttp = false).second.pinned)
    }

    @Test
    fun `another spelling of a subscription's link is the same subscription, under its stored key`() {
        // pasted with https:// and the switch on: kept under that key, with the certificate its server showed
        val kept = "https://panel.example.net:8443/sub/abc?t=Secret"
        val metas = mapOf(kept to SubscriptionMeta(url = kept, allowHttp = true, certSha256 = old.sha256, certNotAfter = future))
        // typed again without its scheme, the switch on: that would spell http:// — it is the stored one all the same
        for (typed in listOf("panel.example.net:8443/sub/abc?t=Secret", "http://panel.example.net:8443/sub/abc?t=Secret", "https://PANEL.example.NET:8443/sub/abc?t=Secret")) {
            val (url, trust) = SubscriptionSync.trustFor(metas, typed, allowHttp = true)
            assertEquals(typed, kept, url)
            assertEquals(typed, old.sha256, trust.record!!.sha256)   // judged against its certificate, not a first use
        }
        // and the reverse: kept under http://, pasted as https:// with the switch off — still the marked one
        val http = "http://panel.example.net/sub/abc"
        val marked = mapOf(http to SubscriptionMeta(url = http, allowHttp = true, certSha256 = old.sha256))
        val (url, trust) = SubscriptionSync.trustFor(marked, "https://panel.example.net/sub/abc", allowHttp = false)
        assertEquals(http, url)
        assertTrue(trust.pinned)
        assertEquals(old.sha256, trust.record!!.sha256)
        // another path, another port, another token is another subscription
        for (other in listOf("https://panel.example.net:8443/sub/abd?t=Secret", "https://panel.example.net:8444/sub/abc?t=Secret", "https://panel.example.net:8443/sub/abc?t=secret")) {
            val fresh = SubscriptionSync.trustFor(metas, other, allowHttp = true)
            assertEquals(other, fresh.first)
            assertNull(other, fresh.second.record)
        }
        // a store that already holds the subscription twice (an older build could): each spelling is its own record
        val twins = mapOf(kept to metas.getValue(kept), "http://panel.example.net:8443/sub/abc?t=Secret" to SubscriptionMeta(url = "http://panel.example.net:8443/sub/abc?t=Secret", allowHttp = true))
        for (spelling in twins.keys) assertEquals(spelling, SubscriptionSync.trustFor(twins, spelling, allowHttp = true).first)
        // the address two spellings share; a link without a host has none, and matches nothing
        assertEquals(subscriptionAddress("http://Panel.example.net/sub"), subscriptionAddress("https://panel.example.net:443/sub"))
        assertNull(subscriptionAddress("https:///sub"))
        assertEquals("https:///sub", SubscriptionSync.trustFor(mapOf("https:///other" to SubscriptionMeta(url = "https:///other")), "https:///sub", false).first)
    }

    @Test
    fun `a link left as it is always saves, and another subscription's link is refused in any spelling`() {
        val own = "https://panel.example.net/sub/abc"
        val twin = "http://panel.example.net/sub/abc"   // the same subscription, kept a second time by an older build
        val others = setOf(twin, "https://sub.example.net/c/1")
        // untouched: its name and its interval save, twin or no twin
        assertFalse(SubscriptionSync.linkIsAnothers(own, own, others))
        // changed to another subscription's link, in another spelling of it
        assertTrue(SubscriptionSync.linkIsAnothers("http://SUB.example.net/c/1", own, others))
        // changed onto its twin's spelling: the two would become one
        assertTrue(SubscriptionSync.linkIsAnothers(twin, own, others))
        // a link no subscription has; and one without a host, which matches nothing
        assertFalse(SubscriptionSync.linkIsAnothers("https://panel.example.net/sub/new", own, others))
        assertFalse(SubscriptionSync.linkIsAnothers("https:///x", own, setOf("https:///x2", "https:///x")))
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
