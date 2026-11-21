package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B5h1 (B3): a VK-call recovery that meets a token VK refused (or none at all) stops the run with what to do, instead of
 * leaving the core on a dead call that it reconnects to forever; a transient failure keeps the run, and a pool with live
 * hashes serves on (B5e3b).
 */
class TokenInvalidStopsRunTest {
    private val hash = "H".repeat(20)

    @Test
    fun `an invalid token with nothing left stops the run with a sign-in`() {
        assertEquals(
            RecoveryOutcome.Stop("Войдите в VK заново"),
            recoveryOutcome(hashes = null, autoJs = false, tokenInvalid = true, tokenFromLink = false),
        )
    }

    @Test
    fun `a link's invalid token asks the owner for a new link`() {
        assertEquals(
            RecoveryOutcome.Stop("Ссылка устарела: попросите у владельца новую"),
            recoveryOutcome(hashes = null, autoJs = false, tokenInvalid = true, tokenFromLink = true),
        )
    }

    @Test
    fun `a transient failure keeps the run for the next dead call to try again`() {
        assertEquals(
            RecoveryOutcome.KeepRunning,
            recoveryOutcome(hashes = null, autoJs = false, tokenInvalid = false, tokenFromLink = false),
        )
    }

    @Test
    fun `new hashes restart the core, a pool's live ones included whatever the token`() {
        // the pool path returns its live hashes even when VK refused the token (VkHashPool, B5e3b)
        assertEquals(RecoveryOutcome.Restart, recoveryOutcome(hashes = hash, autoJs = false, tokenInvalid = false, tokenFromLink = false))
        assertEquals(RecoveryOutcome.Restart, recoveryOutcome(hashes = "", autoJs = true, tokenInvalid = false, tokenFromLink = false))
    }

    @Test
    fun `an empty set stops the run with its cause`() {
        assertEquals(
            RecoveryOutcome.Stop("Не осталось рабочих VK-хешей"),
            recoveryOutcome(hashes = " ", autoJs = false, tokenInvalid = false, tokenFromLink = false),
        )
    }
}
