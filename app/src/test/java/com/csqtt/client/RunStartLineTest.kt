package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Card p3-apply-bar: «Логи» say what each connection started with, so that a log can show which settings a connection
 * ran on (the operator changed TCP to UDP without reconnecting, travelled, and the log could not say the trip was still
 * on TCP). And the two rules the service and the picker ask: when a change of the VPN settings is the running tunnel's
 * to take, and what the notification says of a reconnect.
 */
class RunStartLineTest {
    private val tcp = CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS
    private val hash = "h".repeat(20)

    private fun params(
        transport: String = CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT,
        dpi: DpiSplitMode = DpiSplitMode.OFF,
        srtp: String = CsqttConstants.SrtpMode.AUTO,
        authMode: String = CsqttConstants.VkAuth.MODE_CALLS,
        hashMode: String = CsqttConstants.VkAutoHash.MODE_MANUAL,
        hashes: String = hash,
        streams: Int = 20,
    ) = TunnelParams(
        peer = "203.0.113.5:46000", vkHashes = hashes, workersPerHash = streams, port = 0, turnTransport = transport,
        dpiSplit = dpi, srtpMode = srtp, vkAuthMode = authMode, vkHashMode = hashMode,
    )

    @Test
    fun `a UDP connection says its transport, streams, SRTP and creds mode`() {
        assertEquals("[ЗАПУСК] UDP · потоки: 20 · SRTP: авто · креды: авто", runStartLine(params(), 20))
    }

    @Test
    fun `a TCP connection says its DPI level too, whichever it is`() {
        assertEquals(
            "[ЗАПУСК] TCP · обход DPI: средняя · потоки: 40 · SRTP: авто · креды: авто",
            runStartLine(params(transport = tcp, dpi = DpiSplitMode.MEDIUM), 40),
        )
        assertTrue(runStartLine(params(transport = tcp), 20).contains("обход DPI: выкл"))
        assertTrue(runStartLine(params(transport = tcp, dpi = DpiSplitMode.LIGHT), 20).contains("обход DPI: лёгкая"))
        assertTrue(runStartLine(params(transport = tcp, dpi = DpiSplitMode.HEAVY), 20).contains("обход DPI: тяжёлая"))
        // over UDP nothing is split, whatever level the profile holds: the line does not name one
        assertFalse(runStartLine(params(dpi = DpiSplitMode.HEAVY), 20).contains("DPI"))
    }

    @Test
    fun `SRTP and the creds mode are named as the screen names them`() {
        assertTrue(runStartLine(params(srtp = CsqttConstants.SrtpMode.WRAP), 20).contains("SRTP: WRAP-SRTP"))
        assertTrue(runStartLine(params(srtp = CsqttConstants.SrtpMode.DTLS), 20).contains("SRTP: DTLS-SRTP"))
        assertTrue(runStartLine(params(authMode = CsqttConstants.VkAuth.MODE_CAPTCHA), 20).endsWith("креды: капча"))
        val autoVk = params(authMode = CsqttConstants.VkAuth.MODE_AUTO_JS, hashMode = CsqttConstants.VkAutoHash.MODE_AUTO_JS)
        assertTrue(runStartLine(autoVk, 20).endsWith("креды: Авто ВК"))
        // «Авто ВК» as a creds mode over hashes of another kind is started as the plain calls, and said so
        assertTrue(runStartLine(params(authMode = CsqttConstants.VkAuth.MODE_AUTO_JS), 20).endsWith("креды: авто"))
    }

    @Test
    fun `the line carries no address, password or hash`() {
        val line = runStartLine(params(), 20)
        assertFalse(line.contains("203.0.113.5"))
        assertFalse(line.contains(hash))
        // and nothing the log's redaction would cut out of it
        assertEquals(line, LogRedaction.redact(line))
    }

    @Test
    fun `the streams said are the ones the core is asked for`() {
        // the count on the command line and the count in the log are one reading
        val two = params(hashes = "$hash,${"g".repeat(20)}", streams = 40)
        assertEquals(listOf(hash, "g".repeat(20)), coreHashes(two))
        assertEquals(
            WorkerCountPolicy.normalizeForHashValues(40, coreHashes(two), false),
            coreStreams(two),
        )
        // «Авто ВК» has one call of its own, whatever the list holds
        val autoVk = params(hashes = "", hashMode = CsqttConstants.VkAutoHash.MODE_AUTO_JS, streams = 30)
        assertEquals(WorkerCountPolicy.normalizeForHashValues(30, emptyList(), true), coreStreams(autoVk))
        assertEquals(CsqttConstants.Tunnel.MAX_VK_HASHES, coreHashes(params(hashes = (1..12).joinToString(",") { "hash$it".padEnd(20, 'x') })).size)
    }

    // ---- «Переподключить»

    @Test
    fun `the notification names a reconnect onto the run's own profile, and a switch onto another`() {
        assertEquals("Переподключение…", ProfileSwitch.notice(target = 2, runProfile = 2))
        assertEquals("Переключение профиля...", ProfileSwitch.notice(target = 3, runProfile = 2))
        assertEquals("Переключение профиля...", ProfileSwitch.notice(target = null, runProfile = 2))
    }
}
