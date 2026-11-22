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
    fun aRecoveryThatGotNothingRetriesOnlyWhenNoCallIsLeft() {
        assertEquals(RefusalStep.None, refusalStep(keepRunning = false, runHasLiveHashes = false, retry = 0))
        // a live call carries the run, and its death will ask again: no timer
        assertEquals(RefusalStep.None, refusalStep(keepRunning = true, runHasLiveHashes = true, retry = 0))
        assertEquals(RefusalStep.Retry(30_000L), refusalStep(keepRunning = true, runHasLiveHashes = false, retry = 0))
        assertEquals(RefusalStep.Retry(480_000L), refusalStep(keepRunning = true, runHasLiveHashes = false, retry = 4))
        assertEquals(RefusalStep.Stop, refusalStep(keepRunning = true, runHasLiveHashes = false, retry = 5))
    }

    @Test
    fun theExitPlanKeepsTheCallsOfARestartAndEndsThemWithTheRun() {
        val stable = 30_000L
        fun plan(wants: Boolean = true, fatal: Boolean = false, can: Boolean = true, up: Long = 60_000L, streak: Int = 0, offline: Boolean = false) =
            exitPlan(wants, fatal, can, up, streak, offline, stable)
        // a crash restart reuses the calls
        assertEquals(ExitPlan(nextStreak = 0, crashLoop = false, restarts = true, endCalls = false), plan())
        // a fatal cause, or nothing to restart with, ends the run and its calls
        assertEquals(ExitPlan(0, false, false, true), plan(fatal = true))
        assertEquals(ExitPlan(0, false, false, true), plan(can = false))
        // a run nobody wants any more: its stop ends the calls, not the exit
        assertEquals(ExitPlan(0, false, false, false), plan(wants = false))
        // the eighth early exit in a row stops the run, and its calls end
        assertEquals(ExitPlan(CRASH_LOOP_STOP_STREAK, true, false, true), plan(up = 2_000L, streak = CRASH_LOOP_STOP_STREAK - 1))
        // offline, an early exit says nothing about the core and does not count (review F4)
        assertEquals(ExitPlan(CRASH_LOOP_STOP_STREAK - 1, false, true, false), plan(up = 2_000L, streak = CRASH_LOOP_STOP_STREAK - 1, offline = true))
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
