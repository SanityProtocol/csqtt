// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.csqtt.deploy.ssh

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Runs a key tool (ssh-keygen) in a directory and returns its output; any failure fails the test. */
internal object KeyTool {
    fun run(dir: File, vararg command: String): String {
        val out = File(dir, ".tool-out")
        val process = ProcessBuilder(*command).directory(dir).redirectErrorStream(true).redirectOutput(out).start()
        process.outputStream.close()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("${command.first()} did not finish in 60 s")
        }
        val text = out.readText()
        if (process.exitValue() != 0) fail("${command.joinToString(" ")} failed (${process.exitValue()}): $text")
        return text.trim()
    }
}

/**
 * APP-PLAN §6.3: "P2 tests each format": ed25519, ECDSA and RSA keys in OpenSSH, PEM and PKCS8, encrypted and not, and a key
 * with an OpenSSH certificate, each made by ssh-keygen at test time and read by the engine with no network. PuTTY's .ppk v2
 * and v3 come as committed fixtures with B1's zoo cells (puttygen is not in the CI image).
 */
class SshKeysTest {
    private val dir = kotlin.io.path.createTempDirectory("csqtt-keys").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun keygen(name: String, type: String, format: String?, passphrase: String): Pair<String, String> {
        val args = mutableListOf("ssh-keygen", "-q", "-t", type, "-N", passphrase, "-C", "zoo", "-f", name)
        if (type == "rsa") args += listOf("-b", "2048")
        if (type == "ecdsa") args += listOf("-b", "256")
        if (format != null) args += listOf("-m", format)
        KeyTool.run(dir, *args.toTypedArray())
        val fingerprint = KeyTool.run(dir, "ssh-keygen", "-l", "-E", "sha256", "-f", "$name.pub").split(" ")[1]
        return File(dir, name).readText() to fingerprint
    }

    private fun publicFingerprint(key: SshAuth.Key): String = HostKeys.fingerprint(SshSession.loadKey(key).public)

    @Test
    fun everyFormatIsReadAndGivesItsOwnPublicKey() {
        val matrix = listOf(
            Triple("ed25519", null, ""),
            Triple("ed25519", null, "pass phrase"),
            Triple("ecdsa", null, "pass phrase"),
            Triple("ecdsa", "PEM", ""),
            Triple("ecdsa", "PKCS8", "pass phrase"),
            Triple("rsa", null, ""),
            Triple("rsa", "PEM", "pass phrase"),
            Triple("rsa", "PKCS8", ""),
        )
        for ((i, row) in matrix.withIndex()) {
            val (type, format, passphrase) = row
            val (text, fingerprint) = keygen("k$i", type, format, passphrase)
            val key = SshAuth.Key(text, passphrase.ifEmpty { null })
            assertEquals("$type ${format ?: "OpenSSH"} ${if (passphrase.isEmpty()) "plain" else "encrypted"}", fingerprint, publicFingerprint(key))
        }
    }

    @Test
    fun anEncryptedKeyWithoutItsPassphraseSaysSo() {
        for ((i, format) in listOf(null, "PEM", "PKCS8").withIndex()) {
            val (text, _) = keygen("e$i", if (format == null) "ed25519" else "rsa", format, "right phrase")
            for (given in listOf(null, "wrong phrase")) {
                try {
                    SshSession.loadKey(SshAuth.Key(text, given))
                    fail("${format ?: "OpenSSH"} with passphrase $given was read")
                } catch (error: SshError) {
                    assertEquals("${format ?: "OpenSSH"} with passphrase $given: ${error.detail}", SshFailure.KEY_PASSPHRASE, error.kind)
                }
            }
        }
    }

    @Test
    fun aWrongPassphraseOnALegacyPemKeyIsNamedSoWhateverItsBytesDid() {
        // CI run 45385215641: a wrong passphrase decrypts a legacy PEM key to bytes whose padding passes by chance about once in
        // 255, which then fail in the key's own parser; wrong passphrases are tried until that has happened (about 0.4 ms each)
        val (text, _) = keygen("lucky", "rsa", "PEM", "right phrase")
        var lucky = 0
        for (i in 1..5000) {
            try {
                SshSession.loadKey(SshAuth.Key(text, "wrong phrase $i"))
                fail("wrong phrase $i was taken")
            } catch (error: SshError) {
                assertEquals("wrong phrase $i: ${error.detail}", SshFailure.KEY_PASSPHRASE, error.kind)
                if ("problem creating" in error.detail) lucky++
            }
            if (lucky > 0) break
        }
        assertTrue("no wrong passphrase got past the padding in 5000 tries", lucky > 0)
    }

    @Test
    fun anEncryptedKeyOfAKindSshjCannotReadIsAFormatProblem() {
        // B1 fix review F1: openssl's PKCS#8 Ed25519, encrypted, with its right passphrase: sshj reads no such key, and the
        // passphrase is not the problem
        KeyTool.run(dir, "openssl", "genpkey", "-algorithm", "ed25519", "-aes256", "-pass", "pass:right phrase", "-out", "ossl.pem")
        try {
            SshSession.loadKey(SshAuth.Key(File(dir, "ossl.pem").readText(), "right phrase"))
            fail("an Ed25519 PKCS#8 key was read")
        } catch (error: SshError) {
            assertEquals(error.detail, SshFailure.KEY_FORMAT, error.kind)
        }
    }

    @Test
    fun textThatIsNoKeyIsAFormatProblem() {
        for (text in listOf("", "hello", "-----BEGIN OPENSSH PRIVATE KEY-----\nnot base64\n-----END OPENSSH PRIVATE KEY-----\n")) {
            try {
                SshSession.loadKey(SshAuth.Key(text))
                fail("\"$text\" was read as a key")
            } catch (error: SshError) {
                assertEquals(SshFailure.KEY_FORMAT, error.kind)
            }
        }
    }

    @Test
    fun aKeyWithItsCertificateIsReadAsTheCertificate() {
        // M-144: the certificate travels with the key into the login, the server check included. An OpenSSH-format key is
        // the case sshj 0.38 drops on its own (the shipped app's login never offered the certificate), PEM the one it reads.
        keygen("ca", "ed25519", null, "")
        for ((name, type, format) in listOf(Triple("user", "ed25519", null), Triple("user2", "rsa", "PEM"), Triple("user3", "ecdsa", "PKCS8"))) {
            val (text, fingerprint) = keygen(name, type, format, "")
            KeyTool.run(dir, "ssh-keygen", "-q", "-s", "ca", "-I", "zoo", "-n", "root", "-V", "+1h", "$name.pub")
            val certificate = File(dir, "$name-cert.pub").readText()
            val provider = SshSession.loadKey(SshAuth.Key(text, certificate = certificate))
            assertTrue("$type: ${provider.type}", provider.type.toString().endsWith("-cert-v01@openssh.com"))
            val cert = provider.public as com.hierynomus.sshj.userauth.certificate.Certificate<*>
            assertEquals(type, fingerprint, HostKeys.fingerprint(cert.key))
            assertEquals(listOf("root"), cert.validPrincipals)
        }
        // a certificate for another key, or a plain public key where the certificate goes, is a format problem
        for (wrong in listOf(File(dir, "user2-cert.pub").readText(), File(dir, "user.pub").readText())) {
            try {
                SshSession.loadKey(SshAuth.Key(File(dir, "user").readText(), certificate = wrong))
                fail("accepted $wrong")
            } catch (error: SshError) {
                assertEquals(SshFailure.KEY_FORMAT, error.kind)
            }
        }
    }
}
