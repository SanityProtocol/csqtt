package com.csqtt.client

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress

/**
 * B5c1, M-171: a subscription is fetched over HTTPS only. The platform checks an unmarked one; one the user marked (V2's
 * «Скачивать по HTTP») is trusted by its pinned certificate, pinned on the first fetch. No cleartext anywhere in the app.
 */
class SubscriptionTlsTest {
    private val body = """{"ok": true, "group": {"name": "Pinned", "items": [
        {"name": "N", "link": "csqtt://connect?v=2&host=203.0.113.40&peer=46000&password=secret"}]}}"""

    private fun <T> withHttps(block: suspend (port: Int) -> T): T = runBlocking {
        val server = SubscriptionTestServers.https()
        server.createContext("/sub") { exchange ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block(server.address.port)
        } finally {
            server.stop(0)
        }
    }

    private val future = System.currentTimeMillis() + 86_400_000L
    private val past = System.currentTimeMillis() - 86_400_000L
    private fun other(notAfter: Long) = SubscriptionCertRecord("ab".repeat(32), notAfter)

    @Test
    fun `a marked subscription records its server's certificate on the first fetch`() = withHttps { port ->
        val result = SubscriptionImporter.fetch("https://127.0.0.1:$port/sub", SubscriptionTestServers.pinnedOnFirstUse)
        assertTrue(result is SubscriptionFetchResult.Success)
        val cert = (result as SubscriptionFetchResult.Success).cert!!
        assertEquals(SubscriptionTestServers.fixtureSha256, cert.sha256)
        assertTrue(cert.notAfterMs > System.currentTimeMillis())
    }

    @Test
    fun `the recorded certificate is accepted, another one only once the recorded one has expired`() = withHttps { port ->
        val url = "https://127.0.0.1:$port/sub"
        val same = SubscriptionCertRecord(SubscriptionTestServers.fixtureSha256.uppercase(), future)
        assertTrue(SubscriptionImporter.fetch(url, SubscriptionTrust(true, same)) is SubscriptionFetchResult.Success)

        val changed = SubscriptionImporter.fetch(url, SubscriptionTrust(true, other(future)))
        assertTrue(changed is SubscriptionFetchResult.Failure)
        assertTrue((changed as SubscriptionFetchResult.Failure).message.startsWith("Сертификат сервера подписки не совпадает"))

        // the panels the app deploys replace an expired certificate (a 365-day fallback, a lapsed Let's Encrypt one)
        val renewed = SubscriptionImporter.fetch(url, SubscriptionTrust(true, other(past)))
        assertTrue(renewed is SubscriptionFetchResult.Success)
        assertEquals(SubscriptionTestServers.fixtureSha256, (renewed as SubscriptionFetchResult.Success).cert!!.sha256)
        assertFalse(renewed.cert!!.fromCa)
    }

    @Test
    fun `a server that showed a CA chain is never displaced by a self-signed certificate, even after expiry`() = withHttps { port ->
        val caRecord = SubscriptionCertRecord("ab".repeat(32), past, fromCa = true)
        val result = SubscriptionImporter.fetch("https://127.0.0.1:$port/sub", SubscriptionTrust(true, caRecord))
        assertTrue(result is SubscriptionFetchResult.Failure)
        assertTrue((result as SubscriptionFetchResult.Failure).message.startsWith("Сертификат сервера подписки не совпадает"))
    }

    @Test
    fun `an unmarked subscription gets the platform's full checks, so a self-signed server is refused`() = withHttps { port ->
        val result = SubscriptionImporter.fetch("https://127.0.0.1:$port/sub")
        assertTrue(result is SubscriptionFetchResult.Failure)
        assertTrue((result as SubscriptionFetchResult.Failure).message.startsWith("Ошибка TLS"))
    }

    @Test
    fun `an http URL is fetched over https at the same host and port`() = withHttps { port ->
        val result = SubscriptionImporter.fetch("http://127.0.0.1:$port/sub", SubscriptionTestServers.pinnedOnFirstUse)
        assertTrue(result is SubscriptionFetchResult.Success)
        assertEquals("Pinned", (result as SubscriptionFetchResult.Success).preview.title)
        assertEquals("https://127.0.0.1:1/x", subscriptionTransportUrl("http://127.0.0.1:1/x"))
        assertEquals("https://h/x", subscriptionTransportUrl("https://h/x"))
    }

    @Test
    fun `a server that speaks only plain HTTP is refused, saying why`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/sub") { exchange ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val result = SubscriptionImporter.fetch(
                "http://127.0.0.1:${server.address.port}/sub",
                SubscriptionTestServers.pinnedOnFirstUse,
            )
            assertTrue(result is SubscriptionFetchResult.Failure)
            assertTrue((result as SubscriptionFetchResult.Failure).message.startsWith("Подписка только по HTTP не поддерживается"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `the pin covers the subscription's own host and port only`() = runBlocking {
        // the subscription at 127.0.0.1 redirects to another host name with the same certificate: the platform checks it
        val target = SubscriptionTestServers.https()
        target.createContext("/sub") { exchange ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        target.start()
        val origin = SubscriptionTestServers.https()
        origin.createContext("/sub") { exchange ->
            exchange.responseHeaders.add("Location", "https://localhost:${target.address.port}/sub")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        origin.start()
        try {
            val result = SubscriptionImporter.fetch(
                "https://127.0.0.1:${origin.address.port}/sub",
                SubscriptionTrust(true, SubscriptionCertRecord(SubscriptionTestServers.fixtureSha256, future)),
            )
            assertTrue(result is SubscriptionFetchResult.Failure)
            assertTrue((result as SubscriptionFetchResult.Failure).message.startsWith("Ошибка TLS"))
        } finally {
            origin.stop(0)
            target.stop(0)
        }
    }

    private val acceptAll = object : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
    }

    /** One request through [pin], as fetchBody makes it on the subscription's own server; the response code or null. */
    private fun through(pin: SubscriptionCertPin, url: String): Int? {
        val connection = java.net.URL(url).openConnection() as javax.net.ssl.HttpsURLConnection
        connection.sslSocketFactory = pin.socketFactory
        connection.hostnameVerifier = pin.hostnameVerifier
        return try {
            connection.responseCode
        } catch (_: java.io.IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `a certificate the platform accepts, name included, is used as usual and recorded`() = withHttps { port ->
        // a stand-in platform that accepts the chain (a CA certificate); the fixture is valid for 127.0.0.1
        val pin = SubscriptionCertPin("127.0.0.1", port, other(future), platform = acceptAll)
        assertEquals(200, through(pin, "https://127.0.0.1:$port/sub"))
        assertFalse(pin.byPin)
        assertEquals(SubscriptionTestServers.fixtureSha256, pin.observed?.sha256)
        assertTrue(pin.observed!!.fromCa)
    }

    @Test
    fun `a CA certificate for another name falls to the pin rule`() = withHttps { port ->
        // the URL's host ("localhost") is not in the certificate, as a domain pointed at a panel with an IP certificate
        val first = SubscriptionCertPin("localhost", port, null, platform = acceptAll)
        assertEquals(200, through(first, "https://localhost:$port/sub"))
        assertTrue(first.byPin)
        assertTrue("its chain is a CA's: it is recorded as such", first.observed!!.fromCa)

        // a CA chain for another name keeps the expiry rule, also after a CA record (the renewal of an IP certificate)
        val renewal = SubscriptionCertPin("localhost", port, other(past).copy(fromCa = true), platform = acceptAll)
        assertEquals(200, through(renewal, "https://localhost:$port/sub"))

        val refused = SubscriptionCertPin("localhost", port, other(future), platform = acceptAll)
        assertNull(through(refused, "https://localhost:$port/sub"))
        assertTrue(refused.refusedByPin)
    }

    @Test
    fun `the pin rule`() {
        val now = 1_000_000L
        val leaf = SubscriptionCertRecord("cd".repeat(32), now + 10)
        assertTrue("nothing recorded: trust on first use", pinAccepts(null, leaf, now))
        assertTrue(pinAccepts(SubscriptionCertRecord("CD".repeat(32), now + 5), leaf, now))
        assertFalse(pinAccepts(SubscriptionCertRecord("ab".repeat(32), now + 5), leaf, now))
        assertTrue(pinAccepts(SubscriptionCertRecord("ab".repeat(32), now - 5), leaf, now))
        assertFalse("an unknown expiry never expires", pinAccepts(SubscriptionCertRecord("ab".repeat(32), 0L), leaf, now))
        val ca = SubscriptionCertRecord("ab".repeat(32), now - 5, fromCa = true)
        assertFalse("after a CA chain, a refused chain never replaces it", pinAccepts(ca, leaf, now))
        assertTrue("a CA chain for another name keeps the expiry rule", pinAccepts(ca, leaf.copy(fromCa = true), now))
        assertNull(SubscriptionCertPin.forUrl("https://h/x", SubscriptionTrust()))
    }

    @Test
    fun `the app allows no cleartext traffic`() {
        val module = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val manifest = File(module, "AndroidManifest.xml").readText()
        assertFalse(manifest.contains("usesCleartextTraffic"))
        assertTrue(manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        val config = File(module, "res/xml/network_security_config.xml").readText()
        assertTrue(config.contains("cleartextTrafficPermitted=\"false\""))
        assertFalse(config.contains("cleartextTrafficPermitted=\"true\""))
        assertFalse(config.contains("src=\"user\""))
    }
}
