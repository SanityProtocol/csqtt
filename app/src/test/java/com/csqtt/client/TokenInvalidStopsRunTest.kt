package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B5h1 (B3): a VK-call recovery that meets a token VK refused (or none at all) stops the run with what to do once no
 * call of the run is left, instead of leaving the core on a dead call that it reconnects to forever. While some calls
 * live they carry the run (review F1); a transient failure keeps it; a pool with live hashes serves on (B5e3b). (A
 * recovery runs only while the run's own profile is the active one.)
 */
class TokenInvalidStopsRunTest {
    private val hash = "H".repeat(20)

    private fun outcome(
        hashes: String?,
        tokenInvalid: Boolean,
        autoJs: Boolean = false,
        tokenFromLink: Boolean = false,
        runHasLiveHashes: Boolean = false,
    ) = recoveryOutcome(hashes, autoJs, tokenInvalid, tokenFromLink, runHasLiveHashes)

    @Test
    fun `an invalid token with no live call left stops the run with a sign-in`() {
        assertEquals(RecoveryOutcome.Stop("Войдите в VK заново"), outcome(hashes = null, tokenInvalid = true))
    }

    @Test
    fun `a link's invalid token asks the owner for a new link`() {
        assertEquals(
            RecoveryOutcome.Stop("Ссылка устарела: попросите у владельца новую"),
            outcome(hashes = null, tokenInvalid = true, tokenFromLink = true),
        )
    }

    @Test
    fun `while other calls of the run live, an invalid token keeps the run on them`() {
        assertEquals(RecoveryOutcome.KeepRunning, outcome(hashes = null, tokenInvalid = true, runHasLiveHashes = true))
    }

    @Test
    fun `a transient failure keeps the run for the next dead call to try again`() {
        assertEquals(RecoveryOutcome.KeepRunning, outcome(hashes = null, tokenInvalid = false))
    }

    @Test
    fun `new hashes restart the core, a pool's live ones included whatever the token`() {
        // the pool path returns its live hashes even when VK refused the token (VkHashPool, B5e3b)
        assertEquals(RecoveryOutcome.Restart, outcome(hashes = hash, tokenInvalid = false))
        assertEquals(RecoveryOutcome.Restart, outcome(hashes = "", tokenInvalid = false, autoJs = true))
    }

    @Test
    fun `an empty set stops the run with its cause`() {
        assertEquals(RecoveryOutcome.Stop("Не осталось рабочих VK-хешей"), outcome(hashes = " ", tokenInvalid = false))
    }
}
