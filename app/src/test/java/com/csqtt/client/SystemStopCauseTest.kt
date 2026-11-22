package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * B5f (rule 10): a run Android ended and never restarted says why on the next open — a kill, a reboot (the boot count),
 * an update (the install time) or the user's own «Остановить» (Android's exit reason) — and nothing is said while a run
 * or a start is on, after a stop, or over a cause already shown.
 */
class SystemStopCauseTest {
    private fun cause(
        wasRunning: Boolean = true,
        savedBoot: Int? = 7,
        bootNow: Int? = 7,
        savedUpdate: Long? = 1_000L,
        updateNow: Long? = 1_000L,
        userStopped: Boolean = false,
        runOn: Boolean = false,
        failure: String? = null,
    ) = StopCause.systemStop(wasRunning, savedBoot, bootNow, savedUpdate, updateNow, userStopped, runOn, failure)

    @Test
    fun aKillTheSystemNeverRestartedIsNamed() {
        assertEquals(StopCause.SYSTEM_KILLED, cause())
        // no boot count where Android gives none: a kill
        assertEquals(StopCause.SYSTEM_KILLED, cause(savedBoot = null, bootNow = null))
    }

    @Test
    fun aRebootAnUpdateAndTheUsersOwnStopAreToldFromAKill() {
        assertEquals(StopCause.REBOOTED, cause(bootNow = 8))
        assertEquals(StopCause.UPDATED, cause(updateNow = 2_000L))
        // the flag set by a build before this one: the update that brought this build stopped it (review F1)
        assertEquals(StopCause.UPDATED, cause(savedUpdate = null))
        // Android's «Остановить»: the user's act, no battery advice (review F2)
        assertEquals(StopCause.STOPPED_OUTSIDE, cause(userStopped = true))
    }

    @Test
    fun nothingToSayOtherwise() {
        // the user's stop in the app cleared the flag
        assertNull(cause(wasRunning = false, bootNow = 8))
        // a run, or the sticky restart's start, is on
        assertNull(cause(runOn = true))
        // a cause is shown already (B5h1's stored one, or a failed restore's)
        assertNull(cause(failure = "Не удалось создать звонки VK"))
    }
}
