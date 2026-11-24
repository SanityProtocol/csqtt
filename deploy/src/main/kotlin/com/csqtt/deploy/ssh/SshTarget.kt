// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
//
// Who deploy v3 signs in as, and the host keys it trusts (CSQTT-APP-PLAN §6.3, P2 B1).
package com.csqtt.deploy.ssh

import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

/** How the deploy signs in. A key's text is what the user pasted or what the caller read from a file (.ppk included). */
sealed interface SshAuth {
    /** Password authentication, then keyboard-interactive answered with the same password if the server refuses it. */
    data class Password(val password: String) : SshAuth {
        override fun toString() = "Password(…)"
    }

    /** A private key (OpenSSH, PEM, PKCS8 or PuTTY), its passphrase if encrypted, and an OpenSSH certificate for it if any. */
    data class Key(val privateKey: String, val passphrase: String? = null, val certificate: String? = null) : SshAuth {
        override fun toString() = "Key(…${if (certificate != null) ", certificate" else ""})"
    }
}

/** A VPS as the deploy reaches it: the port always comes from here, never a default (M-143). */
data class SshTarget(val host: String, val port: Int, val user: String, val auth: SshAuth) {
    init {
        require(host.isNotBlank()) { "SSH host is empty" }
        require(user.isNotBlank()) { "SSH login is empty" }
        require(port in 1..65535) { "invalid SSH port: $port" }
    }
}

/**
 * The host keys trusted on first use (TOFU), keyed by host and port. The app keeps them in its store, deploy-cli in a file.
 * Fingerprints are the app's own SshHostKeyStore format, `SHA256:` + unpadded base64 of the key's wire blob (OpenSSH's), so
 * the keys an earlier app pinned carry over unchanged.
 */
interface HostKeyPins {
    fun pinned(host: String, port: Int): String?

    fun pin(host: String, port: Int, fingerprint: String)
}

/**
 * The server presented another key than the one pinned for it (D1, M-142). Never answered automatically: it becomes the
 * deploy's question with both fingerprints, and only the user's «Доверять» pins [presented].
 */
class HostKeyChanged(val host: String, val port: Int, val pinned: String, val presented: String) :
    Exception("the SSH host key of $host:$port changed: pinned $pinned, presented $presented")

object HostKeys {
    fun entry(host: String, port: Int): String = "${host.trim().lowercase()}:$port"

    fun fingerprint(key: PublicKey): String {
        val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }
}

/** sshj's verifier over [HostKeyPins]: pins on first use, accepts the pinned key, and remembers a different one. */
internal class TofuVerifier(private val pins: HostKeyPins) : HostKeyVerifier {
    @Volatile
    var change: HostKeyChanged? = null
        private set

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val presented = HostKeys.fingerprint(key)
        val pinned = pins.pinned(hostname, port)
        if (pinned == null) {
            pins.pin(hostname, port, presented)
            return true
        }
        if (pinned == presented) return true
        change = HostKeyChanged(hostname, port, pinned, presented)
        return false
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
}
