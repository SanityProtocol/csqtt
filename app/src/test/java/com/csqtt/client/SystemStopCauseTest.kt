package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * B5f (rule 10): a run Android ended and never restarted says why on the next open — a battery saver's kill told from a
 * reboot by the boot count — and nothing is said while a run or a start is on, after a stop, or over a cause already shown.
 */
class SystemStopCauseTest {
    @Test
    fun aKillTheSystemNeverRestartedIsNamed() {
        assertEquals(StopCause.SYSTEM_KILLED, StopCause.systemStop(true, savedBoot = 7, bootNow = 7, runOn = false, failure = null))
        // no boot count where Android gives none: a kill
        assertEquals(StopCause.SYSTEM_KILLED, StopCause.systemStop(true, savedBoot = null, bootNow = null, runOn = false, failure = null))
    }

    @Test
    fun aRebootIsToldFromAKill() {
        assertEquals(StopCause.REBOOTED, StopCause.systemStop(true, savedBoot = 7, bootNow = 8, runOn = false, failure = null))
    }

    @Test
    fun nothingToSayOtherwise() {
        // the user's stop cleared the flag
        assertNull(StopCause.systemStop(false, savedBoot = 7, bootNow = 8, runOn = false, failure = null))
        // a run, or the sticky restart's start, is on
        assertNull(StopCause.systemStop(true, savedBoot = 7, bootNow = 7, runOn = true, failure = null))
        // a cause is shown already (B5h1's stored one, or a failed restore's)
        assertNull(StopCause.systemStop(true, savedBoot = 7, bootNow = 7, runOn = false, failure = "Не удалось создать звонки VK"))
    }
}
