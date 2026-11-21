package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M-172: no VK hash and no tunnel password on the core's command line; both travel in its environment. */
class CoreArgsCarryNoSecretTest {
    private val hashes = listOf("-WhashOne1234567890", "hashTwo1234567890ab")
    private val password = "tunnel-password-42"

    private fun assertNoSecret(command: List<String>) {
        val line = command.joinToString(" ")
        hashes.forEach { assertFalse("hash in argv: $line", line.contains(it)) }
        assertFalse("password in argv: $line", line.contains(password))
        assertFalse("-vk" in command)
        assertFalse("--vk" in command)
    }

    @Test
    fun tunnelCommandCarriesNoHashOrPassword() {
        val params = TunnelParams(
            peer = "203.0.113.7:56000",
            vkHashes = hashes.joinToString(","),
            workersPerHash = 10,
            port = 9000,
            connectionPassword = password,
            sessionSalt = "salt",
            generationId = 3,
        )
        val command = coreBaseCommand("/native/libclient.so", params, 10, "vkcalls")
        assertNoSecret(command)
        assertEquals("/native/libclient.so", command.first())
        assertTrue("-peer" in command && "-vk-hash-mode" in command && "-captcha-mode" in command)
    }

    @Test
    fun secretsTravelInTheEnvironment() {
        val env = coreSecretEnv(password, hashes)
        assertEquals(password, env["CSQTT_PASSWORD"])
        assertEquals(hashes.joinToString(","), env["CSQTT_VK_HASHES"])
        assertEquals(emptyMap<String, String>(), coreSecretEnv("", emptyList()))
        assertEquals(setOf("CSQTT_VK_HASHES"), coreSecretEnv("", hashes).keys)
    }

    @Test
    fun hashValidatorCommandCarriesNoHash() {
        assertNoSecret(VkHashValidator.buildCommand("/native/libclient.so", "firefox", "8202606,6287487"))
    }
}
