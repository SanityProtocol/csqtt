package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Card p3-vk-run-account: a run keeps what it started with. A call recovery takes the VK account, the hash mode, the
 * address, the password and the stream count from the run, not from what was stored since; VK refusing the run's own
 * account, the run goes on with the account stored by now when that is another (the operator, 2026-11-28).
 */
class RunKeepsTest {
    private val own = "vk1.a." + "A".repeat(40)
    private val other = "vk1.a." + "B".repeat(40)
    private val api = CsqttConstants.VkAutoHash.MODE_AUTO_API
    private val js = CsqttConstants.VkAutoHash.MODE_AUTO_JS
    private val manual = CsqttConstants.VkAutoHash.MODE_MANUAL

    @Test
    fun `a recovery makes the calls with the run's own account, whatever is stored`() {
        assertEquals(own, RunKeeps.account(runToken = own, runTokenFromLink = false))
        // a pool that served with no account: the stored one is all there is
        assertNull(RunKeeps.account(runToken = "", runTokenFromLink = false))
        assertNull(RunKeeps.account(runToken = "  ", runTokenFromLink = false))
        // a run on a link's token: the link names it, as before the card
        assertNull(RunKeeps.account(runToken = own, runTokenFromLink = true))
    }

    @Test
    fun `VK refusing the run's own account, the run goes on with another one stored by now`() {
        assertEquals(other, RunKeeps.next(refused = own, stored = other))
        // nothing stored: the refused token was the profile's own and went with the refusal
        assertNull(RunKeeps.next(refused = own, stored = ""))
        assertNull(RunKeeps.next(refused = own, stored = " "))
        // the same account: no other to go on with, the run ends as it always did, and no second try is made
        assertNull(RunKeeps.next(refused = own, stored = own))
    }

    @Test
    fun `the core's refused account is held for the exit only on the user's own account in Auto VK`() {
        for (code in listOf("VK_ACCOUNT_5", "VK_ACCOUNT_17", "VK_ACCOUNT_18")) {
            assertTrue(code, RunKeeps.holdsAccountRefusal(code, hashMode = js, tokenFromLink = false))
            // a link's token has no other account behind it
            assertFalse(code, RunKeeps.holdsAccountRefusal(code, hashMode = js, tokenFromLink = true))
            // the app makes the calls itself in these modes: the core never says this word there
            assertFalse(code, RunKeeps.holdsAccountRefusal(code, hashMode = api, tokenFromLink = false))
            assertFalse(code, RunKeeps.holdsAccountRefusal(code, hashMode = manual, tokenFromLink = false))
        }
        // any other fatal word ends the run at once
        for (code in listOf("", "FATAL_AUTH", "DEVICE_LIMIT", "ACCOUNT_5", "XVK_ACCOUNT_5")) {
            assertFalse(code, RunKeeps.holdsAccountRefusal(code, hashMode = js, tokenFromLink = false))
        }
    }

    @Test
    fun `the exit of a core the run answers with another account does not count as a crash`() {
        fun plan(accountChange: Boolean, streak: Int) = exitPlan(
            wantsRestart = true, fatal = false, canRestart = true, uptimeMs = 400L, streak = streak,
            offline = false, stableMs = 30_000L, accountChange = accountChange,
        )
        // the core ended within half a second, as it does when VK refuses its account: with another account to go on
        // with, the count of early exits stays, and the run restarts and keeps its calls
        assertEquals(ExitPlan(nextStreak = 3, crashLoop = false, restarts = true, endCalls = false), plan(accountChange = true, streak = 3))
        // also the eighth early exit in a row, which otherwise ends the run
        val last = CRASH_LOOP_STOP_STREAK - 1
        assertEquals(ExitPlan(last, crashLoop = false, restarts = true, endCalls = false), plan(accountChange = true, streak = last))
        assertEquals(ExitPlan(CRASH_LOOP_STOP_STREAK, crashLoop = true, restarts = false, endCalls = true), plan(accountChange = false, streak = last))
    }

    @Test
    fun `the log names the change of account in one line for both modes`() {
        assertEquals("[VK] Аккаунт туннеля отклонён VK · продолжаем с выбранным аккаунтом", RunKeeps.NEXT_LOG)
    }
}
