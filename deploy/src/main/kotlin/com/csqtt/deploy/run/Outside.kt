// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
//
// The engine's check from outside (P2-PLAN C4; P2 B8): after a run says ok on the box, the engine itself, from where the
// phone is, sends the server a DTLS ClientHello on its UDP port and opens its panel over HTTPS with the certificate pinned.
// A box's own check cannot see a hoster's firewall; this one can, and a run that passed only on the box is reported as
// ok-unreachable-from-outside with what did not answer. The through-TURN check is not here: one scripted run at P2's end,
// and the app's own TURN probe with P3.
package com.csqtt.deploy.run

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

sealed interface PanelAnswer {
    /** The panel answered with this HTTP status, over the pinned certificate. */
    data class Status(val code: Int) : PanelAnswer {
        val ok: Boolean get() = code in setOf(200, 301, 302, 401)
    }

    /** Something answered with another certificate: not this server's panel, or something in the way. */
    data object WrongCertificate : PanelAnswer

    data class NoAnswer(val reason: String) : PanelAnswer
}

/** What the outside check found; [panel] is null when the panel is local only (behind the user's own proxy). */
data class OutsideCheck(val udp: Boolean, val panel: PanelAnswer?) {
    val ok: Boolean get() = udp && (panel == null || (panel is PanelAnswer.Status && panel.ok))

    /** Why the instance is not reachable from outside, in the user's words; null when it is. */
    fun cause(connection: Connection): String? = when {
        !udp -> "UDP ${connection.udpPort} не отвечает снаружи: откройте его в файрволе хостера"
        panel is PanelAnswer.WrongCertificate -> "панель отвечает с другим сертификатом"
        panel is PanelAnswer.NoAnswer -> "панель (TCP ${connection.webPort}) не отвечает снаружи: откройте порт в файрволе хостера"
        panel is PanelAnswer.Status && !panel.ok -> "панель ответила HTTP ${panel.code}"
        else -> null
    }
}

object Outside {
    fun check(connection: Connection): OutsideCheck = OutsideCheck(
        udp = udp(connection.host, connection.udpPort),
        panel = if (connection.panelLocalOnly) null
        else panel(connection.host, connection.webPort, connection.panelPath, connection.certSha256),
    )

    /**
     * Whether the server answers a ClientHello on its UDP port: any DTLS handshake record back (ServerHello, or a
     * HelloVerifyRequest) is an answer. Sent three times, 2 s apart; an ICMP port-unreachable ends it at once.
     */
    fun udp(host: String, port: Int, tries: Int = 3, waitMs: Int = 2_000): Boolean {
        DatagramSocket().use { socket ->
            try {
                socket.connect(InetSocketAddress(host, port))
                socket.soTimeout = waitMs
                val hello = clientHello(ByteArray(32).also { SecureRandom().nextBytes(it) })
                val buffer = ByteArray(4_096)
                repeat(tries) {
                    socket.send(DatagramPacket(hello, hello.size))
                    val deadline = System.nanoTime() + waitMs * 1_000_000L
                    while (System.nanoTime() < deadline) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(packet)
                        } catch (_: SocketTimeoutException) {
                            break
                        }
                        if (isHandshakeAnswer(buffer, packet.length)) return true
                    }
                }
            } catch (_: PortUnreachableException) {
                return false
            } catch (_: IOException) {
                return false
            }
        }
        return false
    }

    /** A DTLS 1.2 record from the server's first flight: a handshake record whose first message is 2 or 3. */
    internal fun isHandshakeAnswer(datagram: ByteArray, length: Int): Boolean =
        length >= 14 && datagram[0] == 22.toByte() && datagram[1] == 0xfe.toByte() && datagram[2] == 0xfd.toByte() &&
            (datagram[13] == 2.toByte() || datagram[13] == 3.toByte())

    /**
     * A ClientHello holding what the server's validate_client_hello requires (shared/dtls.rs): DTLS 1.2,
     * TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256, null compression, extended_master_secret, use_srtp with
     * SRTP_AES128_CM_HMAC_SHA1_80, secp256r1 among the groups, ecdsa_secp256r1_sha256 among the signature algorithms.
     */
    internal fun clientHello(random: ByteArray): ByteArray {
        require(random.size == 32)
        val extensions = Bytes()
            .u16(23).u16(0) // extended_master_secret
            .u16(10).u16(4).u16(2).u16(23) // supported_groups: secp256r1
            .u16(11).u16(2).u8(1).u8(0) // ec_point_formats: uncompressed
            .u16(13).u16(4).u16(2).u16(0x0403) // signature_algorithms: ecdsa_secp256r1_sha256
            .u16(14).u16(5).u16(2).u16(0x0001).u8(0) // use_srtp: SRTP_AES128_CM_HMAC_SHA1_80, no MKI
            .array()
        val body = Bytes()
            .u8(0xfe).u8(0xfd).raw(random)
            .u8(0) // session id
            .u8(0) // cookie
            .u16(2).u16(0xc02b) // cipher suites
            .u8(1).u8(0) // compression: null
            .u16(extensions.size).raw(extensions)
            .array()
        val handshake = Bytes().u8(1).u24(body.size).u16(0).u24(0).u24(body.size).raw(body).array()
        return Bytes().u8(22).u8(0xfe).u8(0xfd).u16(0).u16(0).u16(0).u16(0).u16(handshake.size).raw(handshake).array()
    }

    /**
     * GET of the panel's path over TLS, the certificate checked against [pinSha256] (the read-back's): the HTTP status, or
     * why there was none. No credential is sent; with no pin (the box's certificate was not readable) any certificate does,
     * since all this asks is whether the panel answers from outside.
     */
    fun panel(host: String, port: Int, path: String, pinSha256: String?, timeoutMs: Int = 8_000): PanelAnswer {
        var pinMismatch = false
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                throw CertificateException("no client certificates here")

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
                if (pinSha256 != null && sha256Hex(leaf.encoded) != pinSha256.lowercase()) {
                    pinMismatch = true
                    throw CertificateException("the panel's certificate is not the one read back")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return try {
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
            (context.socketFactory.createSocket() as SSLSocket).use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                socket.startHandshake()
                val hostHeader = if (':' in host) "[$host]:$port" else "$host:$port"
                socket.outputStream.write("GET $path HTTP/1.1\r\nHost: $hostHeader\r\nUser-Agent: csqtt-deploy\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII))
                socket.outputStream.flush()
                val line = socket.inputStream.bufferedReader(Charsets.ISO_8859_1).readLine()
                    ?: return PanelAnswer.NoAnswer("the connection closed with no answer")
                val code = Regex("^HTTP/\\d(?:\\.\\d)? (\\d{3})").find(line)?.groupValues?.get(1)?.toInt()
                    ?: return PanelAnswer.NoAnswer("not an HTTP answer")
                PanelAnswer.Status(code)
            }
        } catch (error: SSLHandshakeException) {
            if (pinMismatch) PanelAnswer.WrongCertificate else PanelAnswer.NoAnswer(error.message ?: "TLS failed")
        } catch (error: IOException) {
            if (pinMismatch) PanelAnswer.WrongCertificate else PanelAnswer.NoAnswer(error.message ?: error.javaClass.simpleName)
        }
    }

    private class Bytes {
        private val out = java.io.ByteArrayOutputStream()
        fun u8(v: Int) = apply { out.write(v and 0xff) }
        fun u16(v: Int) = apply { u8(v shr 8); u8(v) }
        fun u24(v: Int) = apply { u8(v shr 16); u8(v shr 8); u8(v) }
        fun raw(b: ByteArray) = apply { out.write(b) }
        fun array(): ByteArray = out.toByteArray()
    }
}
