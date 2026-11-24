// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
//
// «Вход без пароля» (CSQTT-APP-PLAN §6.3, the operator 2026-11-22; P2 B2): after a sign-in with a password, the app makes its
// own ed25519 key, saves it, puts its public half into the login user's authorized_keys, and signs in with it from then on.
// For a root login the root password then no longer has to be kept on the phone; a login that needs a sudo password keeps
// that one (APP-PLAN §4.7), and the app's summary says so.
// The order is the caller's duty and is said here once: SAVE the key, then install it. A key installed and then lost with the
// app leaves an orphan line that nobody can use, and a second key would follow it.
package com.csqtt.deploy.ssh

import com.csqtt.deploy.bundle.Bundle
import com.csqtt.deploy.wire.LineCodec
import com.csqtt.deploy.wire.Report
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** The app's login key: the private half as an OpenSSH key file (what sshj and SshAuth.Key read), the public half as a blob. */
class LoginKey(val privateKey: String, val publicBlob: String) {
    val type: String get() = "ssh-ed25519"

    /** The fingerprint OpenSSH prints for it (SHA256 of the blob), so the user can match it on the box. */
    val fingerprint: String
        get() = "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(publicBlob)))

    override fun toString() = "LoginKey($fingerprint)"

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): LoginKey {
            val pair = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(random)) }.generateKeyPair()
            val body = Base64.getMimeEncoder(70, "\n".toByteArray()).encodeToString(OpenSSHPrivateKeyUtil.encodePrivateKey(pair.private))
            val private = "-----BEGIN OPENSSH PRIVATE KEY-----\n$body\n-----END OPENSSH PRIVATE KEY-----\n"
            val public = Base64.getEncoder().encodeToString(OpenSSHPublicKeyUtil.encodePublicKey(pair.public))
            return LoginKey(private, public)
        }
    }
}

enum class LoginKeyState { ADDED, PRESENT, REMOVED, ABSENT }

object LoginKeys {
    private const val DEADLINE = 20_000L

    /** Adds the key's line for the login user (as that user: no sudo). Idempotent: a second add reports PRESENT. */
    fun install(remote: Remote, key: LoginKey): LoginKeyState = run(remote, "add", key.type, key.publicBlob)

    /** Removes exactly the lines carrying this key. */
    fun remove(remote: Remote, publicBlob: String): LoginKeyState = run(remote, "remove", publicBlob)

    private fun run(remote: Remote, vararg args: String): LoginKeyState {
        val script = Bundle.bytes("lib/marker.sh") + Bundle.bytes("lib/authkey.sh")
        val result = remote.exec(RemoteCommand.of("/bin/sh", "-s", *args), script, DEADLINE)
        val report = LineCodec.parse(result.stdoutText)
        val line = (report as? Report.Complete)?.lines?.singleOrNull { it.kind == "AK" }
            ?: throw SshError(SshFailure.OTHER, "authorized_keys: no report (${(report as? Report.Broken)?.reason ?: "exit ${result.exitStatus}"})")
        return when (line.fields.firstOrNull()) {
            "added" -> LoginKeyState.ADDED
            "present" -> LoginKeyState.PRESENT
            "removed" -> LoginKeyState.REMOVED
            "absent" -> LoginKeyState.ABSENT
            else -> throw SshError(SshFailure.OTHER, "authorized_keys: ${line.fields.joinToString(" ")}")
        }
    }
}
