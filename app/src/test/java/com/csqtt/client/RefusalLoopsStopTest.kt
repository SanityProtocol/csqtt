package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** B5e4: the VK refusal loop backs off and then stops with a cause; a core that never gets up stops instead of looping. */
class RefusalLoopsStopTest {
    @Test
    fun theRefusalBackoffThenTheStop() {
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L), (0..4).map { RefusalBackoff.delayFor(it) })
        assertNull(RefusalBackoff.delayFor(5))
        assertEquals("VK не создаёт звонки: код 9 Flood control", StopCause.vkRefusesCalls("код 9 Flood control"))
        assertEquals("VK не создаёт звонки", StopCause.vkRefusesCalls(null))
        assertEquals("VK не создаёт звонки", StopCause.vkRefusesCalls("  "))
    }

    @Test
    fun aCrashLoopCountsOnlyExitsBeforeTheProcessWasUp() {
        val stable = 30_000L
        var streak = 0
        repeat(CRASH_LOOP_STOP_STREAK - 1) { streak = nextCrashStreak(uptimeMs = 2_000L, streak = streak, stableMs = stable) }
        assertEquals(CRASH_LOOP_STOP_STREAK - 1, streak)
        // a process that stayed up starts the count again
        assertEquals(0, nextCrashStreak(uptimeMs = 31_000L, streak = streak, stableMs = stable))
        // the next early exit reaches the stop
        assertEquals(CRASH_LOOP_STOP_STREAK, nextCrashStreak(uptimeMs = 2_000L, streak = streak, stableMs = stable))
        assertEquals("[VK JS] аккаунт отказал", StopCause.crashLoop("[VK JS] аккаунт отказал", 1))
        assertEquals("Клиент не запускается (код 1)", StopCause.crashLoop(null, 1))
        assertEquals("Клиент не запускается (код ?)", StopCause.crashLoop(" ", null))
    }
}
