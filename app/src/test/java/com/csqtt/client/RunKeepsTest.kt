package com.csqtt.client

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Card p3-vk-run-account: a run keeps what it started with. A call recovery takes the VK account, the hash mode, the
 * address, the password and the stream count from the run, not from what was stored since; VK refusing the run's own
 * account, the run goes on with the account stored by now when that is another (the operator, 2026-11-28) — in «Авто
 * API» within the recovery, in «Авто ВК» at the exit of the core that said so.
 */
class RunKeepsTest {
    private val own = "vk1.a." + "A".repeat(40)
    private val other = "vk1.a." + "B".repeat(40)
    private val third = "vk1.a." + "C".repeat(40)
    private val api = CsqttConstants.VkAutoHash.MODE_AUTO_API
    private val js = CsqttConstants.VkAutoHash.MODE_AUTO_JS
    private val manual = CsqttConstants.VkAutoHash.MODE_MANUAL

    private fun run(mode: String = api, account: String = own, fromLink: Boolean = false) = TunnelParams(
        peer = "203.0.113.5:46000", vkHashes = "h".repeat(20), workersPerHash = 20, port = 0, connectionPassword = "run-password",
        vkHashMode = mode, vkAccessToken = account, vkTokenFromLink = fromLink,
    )

    private fun source(token: String = "", peer: String = "198.51.100.7:56000") = ConnectionSource(
        peer = peer, password = "profile-password", hashes = "s".repeat(20), hashesFromLink = token.isEmpty(),
        vkAccessToken = token, tokenFromLink = token.isNotEmpty(),
    )

    // ---- what a recovery works from

    @Test
    fun `a recovery of a run on the user's own account works from the run's own values`() {
        val basis = RunKeeps.recoveryBasis(run(), profile = null)!!
        assertTrue(basis.own)
        assertEquals("203.0.113.5:46000", basis.source.peer)
        assertEquals("run-password", basis.source.password)
        assertFalse("its link names no token: the account is the run's", basis.source.tokenFromLink)
        assertEquals(api, basis.hashMode)
        assertEquals(20, basis.workers)
        assertEquals(own, basis.account)
        assertTrue("the mode is told to the resolver, not read from the profile once more", basis.forceApi)
        // «Авто ВК» the same
        assertEquals(js, RunKeeps.recoveryBasis(run(mode = js), profile = null)!!.hashMode)
    }

    @Test
    fun `what the profile holds by now is not the recovery's, whatever it is`() {
        // another address and password, another mode, more streams, a link with a token: all for the next connection
        val reading = ProfileReading(source(token = other), hashMode = manual, workers = 60)
        assertEquals(RunKeeps.recoveryBasis(run(), profile = null), RunKeeps.recoveryBasis(run(), reading))
        // a profile that resolves to nothing by now — its address or link emptied under the tunnel — does not end it
        assertEquals(RunKeeps.recoveryBasis(run(), profile = null), RunKeeps.recoveryBasis(run(), ProfileReading(null, manual, 60)))
    }

    @Test
    fun `a pool that served with no account leaves the account to the profile`() {
        val basis = RunKeeps.recoveryBasis(run(account = ""), profile = null)!!
        assertNull(basis.account)
        assertNull(RunKeeps.recoveryBasis(run(account = "  "), profile = null)!!.account)
        assertTrue(basis.own)
    }

    @Test
    fun `a recovery of a run on a link's token reads the link and the stored mode, as before the card`() {
        val link = source(token = other)
        val basis = RunKeeps.recoveryBasis(run(account = third, fromLink = true), ProfileReading(link, hashMode = js, workers = 30))!!
        assertFalse(basis.own)
        assertEquals(link, basis.source)
        assertEquals(js, basis.hashMode)
        assertEquals(30, basis.workers)
        assertEquals("the link's token, not the one the run started on", other, basis.account)
        assertTrue(basis.forceApi)
        // the server sent hashes meanwhile, so the link names no token now: the stored account, and the stored mode read
        val hashes = RunKeeps.recoveryBasis(run(account = third, fromLink = true), ProfileReading(source(), hashMode = api, workers = 30))!!
        assertNull(hashes.account)
        assertFalse(hashes.forceApi)
        assertFalse(hashes.own)
        // a link that no longer resolves: the recovery gives up, as it did
        assertNull(RunKeeps.recoveryBasis(run(account = third, fromLink = true), ProfileReading(null, api, 30)))
        assertNull(RunKeeps.recoveryBasis(run(account = third, fromLink = true), profile = null))
    }

    // ---- VK refuses the run's own account

    @Test
    fun `VK refusing the run's own account, the run goes on with another one stored by now`() {
        assertEquals(other, RunKeeps.next(refused = own, stored = other))
        // nothing stored: the refused token was the profile's own and went with the refusal
        assertNull(RunKeeps.next(refused = own, stored = ""))
        assertNull(RunKeeps.next(refused = own, stored = " "))
        // the same account: no other to go on with, the run ends as it always did, and no second try is made
        assertNull(RunKeeps.next(refused = own, stored = own))
    }

    /** A fake VK for [RunKeeps.mint]: [refuses] names the accounts it refuses; what is made says who made it and from what. */
    private class Vk(vararg val refuses: String) {
        val asked = ArrayList<String>()
        var storeReads = 0
        fun make(account: String, left: String?): String {
            asked += account
            return (if (account in refuses) "refused:" else "made:") + account + (left?.let { " after($it)" } ?: "")
        }
    }

    private suspend fun mint(vk: Vk, first: String, own: Boolean, stored: String): Minted<String> =
        RunKeeps.mint(first, own, refused = { it.startsWith("refused:") }, stored = { vk.storeReads++; stored }) { account, left -> vk.make(account, left) }

    @Test
    fun `an Auto API recovery makes its calls with the run's account and asks the store nothing`() = runTest {
        val vk = Vk()
        val minted = mint(vk, first = own, own = true, stored = other)
        assertEquals("made:$own", minted.made)
        assertEquals(own, minted.account)
        assertEquals(listOf(own), vk.asked)
        assertEquals("the stored account is not even read while the run's own works", 0, vk.storeReads)
    }

    @Test
    fun `VK refuses the run's account and the stored one makes the calls from what the first left`() = runTest {
        val vk = Vk(own)
        val minted = mint(vk, first = own, own = true, stored = other)
        assertEquals("made:$other after(refused:$own)", minted.made)
        assertEquals("the run's account from here on", other, minted.account)
        assertEquals(listOf(own, other), vk.asked)
        assertEquals(1, vk.storeReads)
    }

    @Test
    fun `with no other account stored the refusal stands and the run has no account`() = runTest {
        for (stored in listOf("", own)) {
            val vk = Vk(own)
            val minted = mint(vk, first = own, own = true, stored = stored)
            assertEquals("refused:$own", minted.made)
            assertEquals("", minted.account)
            assertEquals("never the same account twice", listOf(own), vk.asked)
        }
    }

    @Test
    fun `the stored account refused too ends it there`() = runTest {
        val vk = Vk(own, other)
        val minted = mint(vk, first = own, own = true, stored = other)
        assertEquals("refused:$other after(refused:$own)", minted.made)
        assertEquals("", minted.account)
        assertEquals("two accounts, two attempts, no third", listOf(own, other), vk.asked)
    }

    @Test
    fun `a link's token and a start's stored account have no other account behind them`() = runTest {
        val vk = Vk(own)
        val minted = mint(vk, first = own, own = false, stored = other)
        assertEquals("refused:$own", minted.made)
        assertEquals("", minted.account)
        assertEquals(listOf(own), vk.asked)
        assertEquals(0, vk.storeReads)
        // and a start that works keeps the account it was made with
        assertEquals(own, mint(Vk(), first = own, own = false, stored = other).account)
        // a pool that serves with no account has none after it either
        assertEquals("", mint(Vk(), first = "", own = false, stored = other).account)
    }

    // ---- «Авто ВК»: the core says its account is refused

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

    private val word = "Аккаунт VK недоступен: вход в аккаунт VK больше не действует — войдите в VK заново (API 5: User authorization failed)"

    private fun exit(refused: String? = word, wanted: Boolean = true, fatal: Boolean = false, runAccount: String = own, stored: String = other) =
        RunKeeps.atExit(refused, wanted, fatal, runAccount, stored)

    @Test
    fun `the exit of a core whose account VK refused goes on with the stored account`() {
        assertEquals(AccountExit.GoOn(other), exit())
    }

    @Test
    fun `with no other account the core's word is the cause the run stops with`() {
        assertEquals(AccountExit.Stop(word), exit(stored = own))
        assertEquals(AccountExit.Stop(word), exit(stored = ""))
        assertEquals(AccountExit.Stop(word), exit(stored = "  "))
    }

    @Test
    fun `the run goes on once - the stored account refused too stops it`() {
        // after a go-on the stored account is the run's own: the same exit again finds no other
        val first = exit(runAccount = own, stored = other) as AccountExit.GoOn
        assertEquals(AccountExit.Stop(word), exit(runAccount = first.account, stored = other))
    }

    @Test
    fun `an exit that is not the word's to decide decides nothing`() {
        // the core said no such word: an ordinary exit
        assertEquals(AccountExit.None, exit(refused = null))
        // the run is no longer wanted: a stop, a switch
        assertEquals(AccountExit.None, exit(wanted = false))
        // another cause ended it meanwhile: the first cause is the one that counts
        assertEquals(AccountExit.None, exit(fatal = true))
        assertEquals(AccountExit.None, exit(fatal = true, stored = own))
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
