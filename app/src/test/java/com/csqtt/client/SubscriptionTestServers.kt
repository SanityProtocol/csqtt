package com.csqtt.client

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.MessageDigest
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * HTTPS test servers for subscriptions (M-171): a subscription is only ever fetched over HTTPS, so the importer's tests run
 * over TLS. The server presents a self-signed certificate, as a csqtt panel or a swg-panel without a domain does. It is
 * made at run time by the JDK's keytool, so no key, even a test one, is kept in the repository.
 */
internal object SubscriptionTestServers {
    private val keyStore: KeyStore by lazy {
        val file = java.io.File.createTempFile("csqtt-subscription-tls", ".p12")
        file.delete() // keytool creates the keystore itself
        try {
            val keytool = java.io.File(System.getProperty("java.home"), "bin/keytool").path
            val process = ProcessBuilder(
                keytool, "-genkeypair", "-alias", "fixture", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=subscription.fixture.invalid", "-validity", "3650",
                "-storetype", "PKCS12", "-keystore", file.path, "-storepass", "fixture", "-keypass", "fixture",
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "keytool failed: $output" }
            KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, "fixture".toCharArray()) } }
        } finally {
            file.delete()
        }
    }

    /** The fixture's leaf certificate SHA-256, as the pin stores it. */
    val fixtureSha256: String by lazy {
        val der = keyStore.getCertificate(keyStore.aliases().nextElement()).encoded
        MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }
    }

    private val sslContext: SSLContext by lazy {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, "fixture".toCharArray())
        SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    fun https(): HttpsServer = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        httpsConfigurator = HttpsConfigurator(sslContext)
    }

    /** What a subscription the user marked (V2's «Скачивать по HTTP») fetches with before its first pin. */
    val pinnedOnFirstUse = SubscriptionTrust(pinned = true)
}
