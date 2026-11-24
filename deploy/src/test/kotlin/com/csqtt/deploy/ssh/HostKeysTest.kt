// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.csqtt.deploy.ssh

import net.schmizz.sshj.common.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class HostKeysTest {
    private class MemoryPins : HostKeyPins {
        val map = mutableMapOf<String, String>()
        override fun pinned(host: String, port: Int) = map[HostKeys.entry(host, port)]
        override fun pin(host: String, port: Int, fingerprint: String) {
            map[HostKeys.entry(host, port)] = fingerprint
        }
    }

    private fun ecKey(): PublicKey =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public

    @Test
    fun theFirstKeyIsPinnedTheSameKeyPassesAnotherIsAQuestion() {
        val pins = MemoryPins()
        val first = ecKey()
        val verifier = TofuVerifier(pins)
        assertTrue(verifier.verify("VPS.example ", 2222, first))
        assertEquals(HostKeys.fingerprint(first), pins.pinned("vps.example", 2222))
        assertTrue(TofuVerifier(pins).verify("vps.example", 2222, first))

        val other = ecKey()
        val again = TofuVerifier(pins)
        assertFalse(again.verify("vps.example", 2222, other))
        val change = again.change!!
        assertEquals(HostKeys.fingerprint(first), change.pinned)
        assertEquals(HostKeys.fingerprint(other), change.presented)
        // the pin stays the old key until the user trusts the new one: nothing is trusted automatically (D1)
        assertEquals(HostKeys.fingerprint(first), pins.pinned("vps.example", 2222))
        // another port of the same host is another server
        assertTrue(TofuVerifier(pins).verify("vps.example", 22, other))
        assertNull(TofuVerifier(pins).change)
    }

    @Test
    fun theFingerprintIsOpenSshsSha256() {
        // the app's SshHostKeyStore format (P3 carries its pins over): SHA256 of the key's wire blob, unpadded base64,
        // which is what `ssh-keygen -l` prints
        val key = ecKey()
        val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
        val line = "ecdsa-sha2-nistp256 " + Base64.getEncoder().encodeToString(blob)
        val dir = kotlin.io.path.createTempDirectory("csqtt-fp").toFile()
        try {
            val pub = File(dir, "k.pub").apply { writeText(line + "\n") }
            val out = KeyTool.run(dir, "ssh-keygen", "-l", "-E", "sha256", "-f", pub.path)
            val expected = out.split(" ")[1]
            assertEquals(expected, HostKeys.fingerprint(key))
            assertTrue(HostKeys.fingerprint(key).startsWith("SHA256:") && !HostKeys.fingerprint(key).endsWith("="))
        } finally {
            dir.deleteRecursively()
        }
    }
}
