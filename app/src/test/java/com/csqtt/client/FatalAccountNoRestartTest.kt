package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FatalAccountNoRestartTest {
    @Test
    fun theFirstFatalCauseIsKept() {
        val fatal = FirstFatal()
        assertTrue(fatal.record("VK: аккаунт удалён или заблокирован (API 18)"))
        // retry chatter and a second error never replace it (#19's isCritical problem)
        assertFalse(fatal.record("[TURN] timeout"))
        assertEquals("VK: аккаунт удалён или заблокирован (API 18)", fatal.cause)
    }

    @Test
    fun aProcessWithAFatalCauseIsNeverRestarted() {
        // the reader's exit sees the run still wanted when it takes the lock before the stop: the cause decides
        val fatal = FirstFatal()
        assertTrue(shouldRestartProcess(lifecycleWantsRestart = true, fatal = fatal))
        fatal.record("API 18")
        assertFalse(shouldRestartProcess(lifecycleWantsRestart = true, fatal = fatal))
        // the stop came first: no restart either way
        assertFalse(shouldRestartProcess(lifecycleWantsRestart = false, fatal = FirstFatal()))
    }

    @Test
    fun aBlankCauseStillCounts() {
        val fatal = FirstFatal()
        assertTrue(fatal.record("  "))
        assertFalse(shouldRestartProcess(true, fatal))
    }
}
