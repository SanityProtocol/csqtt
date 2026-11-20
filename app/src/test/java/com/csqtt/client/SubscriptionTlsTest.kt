package com.csqtt.client

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.CertificateException

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

    @Test
    fun `a marked subscription pins its server's certificate on the first fetch`() = withHttps { port ->
        val result = SubscriptionImporter.fetch("https://127.0.0.1:$port/sub", SubscriptionTestServers.pinnedOnFirstUse)
        assertTrue(result is SubscriptionFetchResult.Success)
        assertEquals(SubscriptionTestServers.fixtureSha256, (result as SubscriptionFetchResult.Success).certSha256)
    }

    @Test
    fun `the pinned certificate is accepted, any other is refused`() = withHttps { port ->
        val url = "https://127.0.0.1:$port/sub"
        val pinned = SubscriptionImporter.fetch(url, SubscriptionTrust(true, SubscriptionTestServers.fixtureSha256.uppercase()))
        assertTrue(pinned is SubscriptionFetchResult.Success)

        val changed = SubscriptionImporter.fetch(url, SubscriptionTrust(true, "ab".repeat(32)))
        assertTrue(changed is SubscriptionFetchResult.Failure)
        assertTrue((changed as SubscriptionFetchResult.Failure).message.startsWith("Сертификат сервера подписки изменился"))
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
                SubscriptionTrust(true, SubscriptionTestServers.fixtureSha256),
            )
            assertTrue(result is SubscriptionFetchResult.Failure)
            assertTrue((result as SubscriptionFetchResult.Failure).message.startsWith("Ошибка TLS"))
        } finally {
            origin.stop(0)
            target.stop(0)
        }
    }

    @Test
    fun `a certificate the platform accepts is checked as usual and never pinned`() = withHttps { port ->
        // a stand-in platform that accepts every chain: a CA certificate that renews must not be pinned
        val acceptAll = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        }
        val pin = SubscriptionCertPin("127.0.0.1", port, expected = "ab".repeat(32), platform = acceptAll)
        val connection = java.net.URL("https://127.0.0.1:$port/sub").openConnection() as javax.net.ssl.HttpsURLConnection
        connection.sslSocketFactory = pin.socketFactory
        connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        try {
            assertEquals(200, connection.responseCode)
        } finally {
            connection.disconnect()
        }
        assertNull(pin.seen)
        assertFalse(pin.byPin)
    }

    @Test
    fun `the pin check itself`() {
        val der = byteArrayOf(1, 2, 3)
        val sha = MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }
        assertEquals(sha, acceptPinnedLeaf(null, der))
        assertEquals(sha, acceptPinnedLeaf(sha.uppercase(), der))
        try {
            acceptPinnedLeaf("00".repeat(32), der)
            fail("a different certificate must be refused")
        } catch (e: CertificateException) {
            assertEquals(SubscriptionCertPin.MISMATCH, e.message)
        }
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
