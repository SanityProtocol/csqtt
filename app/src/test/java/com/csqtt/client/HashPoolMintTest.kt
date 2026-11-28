package com.csqtt.client

import androidx.datastore.preferences.core.stringPreferencesKey
import com.csqtt.client.Store253Snapshots.load
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D6 (a1), B5e3b: the owner's pool is filled with as few `calls.start` as it lacks, and kept per VPS record. */
class HashPoolMintTest {
    private fun hash(c: Char) = c.toString().repeat(20)

    /** A fake VK: hands out [results] in order and counts the calls made. */
    private class FakeVk(vararg results: VkApiResult<VkApi.StartedCall>) {
        private val queue = ArrayDeque(results.toList())
        var calls = 0
        suspend fun mint(): VkApiResult<VkApi.StartedCall> {
            calls++
            return queue.removeFirstOrNull() ?: VkApiResult.Failed("no more")
        }
    }

    private fun ok(hash: String) = VkApiResult.Ok(VkApi.StartedCall(callId = "c-$hash", hash = hash, joinLink = ""))

    @Test fun aFullPoolMintsNothing() = runTest {
        val vk = FakeVk()
        val fill = VkHashPool.fill(listOf(hash('A'), hash('B')), dead = null, target = 2, mint = vk::mint)
        assertEquals(listOf(hash('A'), hash('B')), fill.hashes)
        assertEquals(0, vk.calls)
        assertEquals(0, fill.minted)
    }

    @Test fun onlyTheMissingOnesAreMintedAfterTheKeptOnes() = runTest {
        val vk = FakeVk(ok(hash('C')), ok(hash('D')))
        val fill = VkHashPool.fill(listOf(hash('A')), dead = null, target = 3, mint = vk::mint)
        assertEquals(listOf(hash('A'), hash('C'), hash('D')), fill.hashes)
        assertEquals(2, vk.calls)
        assertEquals(2, fill.minted)
    }

    @Test fun aDeadHashIsReplacedAndTheOthersKeepTheirPlace() = runTest {
        val vk = FakeVk(ok(hash('Z')))
        val fill = VkHashPool.fill(listOf(hash('A'), hash('B'), hash('C')), dead = hash('B'), target = 3, mint = vk::mint)
        assertEquals(listOf(hash('A'), hash('C'), hash('Z')), fill.hashes)
        assertEquals(1, vk.calls)
    }

    @Test fun aLargerPoolNeverShrinks() = runTest {
        val vk = FakeVk()
        val pool = listOf(hash('A'), hash('B'), hash('C'))
        assertEquals(pool, VkHashPool.fill(pool, dead = null, target = 1, mint = vk::mint).hashes)
        assertEquals(0, vk.calls)
    }

    @Test fun aDeadHashInAPoolAboveTheTargetIsReplacedToo() = runTest {
        // the owner ran at 30 workers, then lowered to 20: the pool keeps 3, and a dead one is still replaced (review F1)
        val vk = FakeVk(ok(hash('Z')))
        val fill = VkHashPool.fill(listOf(hash('A'), hash('B'), hash('C')), dead = hash('B'), target = 2, mint = vk::mint)
        assertEquals(listOf(hash('A'), hash('C'), hash('Z')), fill.hashes)
        assertEquals(1, vk.calls)
    }

    @Test fun anInvalidTokenStopsMintingAndKeepsTheLivePool() = runTest {
        val vk = FakeVk(VkApiResult.ApiError(5, "User authorization failed"), ok(hash('C')))
        val fill = VkHashPool.fill(listOf(hash('A')), dead = null, target = 3, mint = vk::mint)
        assertTrue(fill.tokenInvalid)
        assertEquals(listOf(hash('A')), fill.hashes)
        assertEquals(1, vk.calls)
    }

    @Test fun otherErrorsAreLoggedAndTheNextSlotIsTried() = runTest {
        val vk = FakeVk(VkApiResult.Failed("timeout"), VkApiResult.ApiError(9, "Flood control"), ok(hash('C')))
        val fill = VkHashPool.fill(emptyList(), dead = null, target = 3, mint = vk::mint)
        assertFalse(fill.tokenInvalid)
        assertEquals(listOf(hash('C')), fill.hashes)
        assertEquals(listOf("timeout", "код=9 Flood control"), fill.errors)
        assertTrue("no hash in a log line", fill.errors.none { hash('C') in it })
    }

    @Test fun aRepeatedOrBlankHashIsNotKeptTwice() = runTest {
        val vk = FakeVk(ok(hash('A')))
        val fill = VkHashPool.fill(listOf(hash('A'), " ", hash('A')), dead = null, target = 2, mint = vk::mint)
        assertEquals(listOf(hash('A')), fill.hashes)
    }

    /**
     * Card p3-vk-run-account: VK refuses the run's own account in the middle of a recovery's fill and the account
     * stored by now takes the fill over. Run through what the service runs (TunnelService.resolveAutoHashes):
     * RunKeeps.mint for the two attempts and VkHashPool.ask for what each is asked, with VK's answers given and the
     * fill counted as VkAutoCallsManager.fillPool counts it. The pool ends at the size an unbroken fill gives it.
     * [workers] at 20 need two calls.
     */
    private suspend fun takenOver(pool: List<String>, dead: String?, refused: FakeVk, next: FakeVk, workers: Int = 20): List<String> {
        val vk = mapOf("refused" to refused, "next" to next)
        val minted = RunKeeps.mint<VkHashPool.Fill>("refused", own = true, refused = { it.tokenInvalid }, stored = { "next" }) { account, left ->
            val ask = VkHashPool.ask(pool, dead, left)
            val target = VkAutoCallsManager.poolTarget(workers, VkHashPool.alive(ask.pool, ask.dead).size, ask.extra)
            VkHashPool.fill(ask.pool, ask.dead, target, vk.getValue(account)::mint)
        }
        assertEquals("the account that filled the pool is the run's from here", "next", minted.account)
        return minted.made.hashes
    }

    @Test fun aFillAsksForThePoolAsItIsAndATakeOverForWhatIsLeftAndOwed() {
        val pool = listOf(hash('A'), hash('B'), hash('C'))
        assertEquals(VkHashPool.Ask(pool, hash('B'), 0), VkHashPool.ask(pool, hash('B'), left = null))
        // the refused account dropped the dead call and made nothing: one call is owed, and nothing more is dropped
        val left = VkHashPool.Fill(listOf(hash('A'), hash('C')), minted = 0, tokenInvalid = true, errors = emptyList())
        assertEquals(VkHashPool.Ask(listOf(hash('A'), hash('C')), null, 1), VkHashPool.ask(pool, hash('B'), left))
    }

    @Test fun theAccountThatTakesAFillOverReplacesTheDeadCallOfAPoolAboveItsTarget() = runTest {
        // three calls where two are needed, one of them dead: the pool never shrinks, also across the two accounts
        val refused = FakeVk(VkApiResult.ApiError(5, "User authorization failed"))
        val next = FakeVk(ok(hash('Z')), ok(hash('Y')))
        val pool = listOf(hash('A'), hash('B'), hash('C'))
        assertEquals(listOf(hash('A'), hash('C'), hash('Z')), takenOver(pool, dead = hash('B'), refused, next))
        assertEquals("one call was owed, one is made", 1, next.calls)
        assertEquals(1, VkHashPool.owed(pool, listOf(hash('A'), hash('C'))))
    }

    @Test fun theAccountThatTakesAFillOverFillsAShortPoolToWhatTheStreamsNeed() = runTest {
        // the only call died: the streams need two
        val refused = FakeVk(VkApiResult.ApiError(5, "User authorization failed"))
        val next = FakeVk(ok(hash('Z')), ok(hash('Y')), ok(hash('X')))
        assertEquals(listOf(hash('Z'), hash('Y')), takenOver(listOf(hash('A')), dead = hash('A'), refused, next))
        assertEquals(2, next.calls)
    }

    @Test fun whatTheRefusedAccountMadeBeforeVkRefusedItIsKept() = runTest {
        // a token that died between two of its own calls: its first call stays in the pool, the next account adds one
        val refused = FakeVk(ok(hash('M')), VkApiResult.ApiError(5, "User authorization failed"))
        val next = FakeVk(ok(hash('Z')), ok(hash('Y')))
        assertEquals(listOf(hash('M'), hash('Z')), takenOver(listOf(hash('A')), dead = hash('A'), refused, next))
        assertEquals(1, next.calls)
        val pool = listOf(hash('A'), hash('B'))
        assertEquals("nothing is owed to a pool that lost nothing", 0, VkHashPool.owed(pool, pool + hash('M')))
        // a pool is counted as a fill counts it: without blanks and repeats
        assertEquals(0, VkHashPool.owed(listOf(hash('A'), " ", hash('A')), listOf(hash('A'))))
    }

    // An owner on profile 2 whose record 2 has the server's IP, web password and secret path (the panel button's fields).
    private fun ownerWithPanel(extra: String = "") = load(
        """{"strings": {"peer_2": "203.0.113.2", "deploy_ip_2": "203.0.113.2", "deploy_web_password_encrypted_2": "e",
                        "deploy_secret_path_uuid_2": "/0f0e-uuid/" $extra},
            "ints": {"server_web_port_2": 46102, "vps_ref_2": 2, "active_profile": 2}}""",
    )

    private val webPassword = { _: Int -> "web-pass" }

    @Test fun theOwnersRecordWithItsPanelFieldsGetsAPool() {
        val panel = SettingsStore.poolPanelOf(ownerWithPanel(), 2, webPassword)
        assertEquals(PoolPanel(2, "203.0.113.2", 46102, "0f0e-uuid", "web-pass"), panel)
        assertFalse("the password is never printed", "web-pass" in panel.toString())
    }

    @Test fun noPoolForALinkProfileOrARecordWithoutItsPanel() {
        val link = ownerWithPanel().apply { this[androidx.datastore.preferences.core.booleanPreferencesKey("csqtt_link_mode_2")] = true }
        assertNull(SettingsStore.poolPanelOf(link, 2, webPassword))
        assertNull(SettingsStore.poolPanelOf(ownerWithPanel(), 2) { "" })
        assertNull(SettingsStore.poolPanelOf(ownerWithPanel().apply { remove(stringPreferencesKey("deploy_secret_path_uuid_2")) }, 2, webPassword))
        assertNull("no linked record", SettingsStore.poolPanelOf(ownerWithPanel(), 3, webPassword))
    }

    @Test fun thePoolIsKeptPerRecordAndNeverRevivesADeletedOne() {
        val prefs = ownerWithPanel()
        assertTrue(SettingsStore.writeVkPool(prefs, 2, listOf(hash('A'), hash('B'))))
        assertEquals("${hash('A')},${hash('B')}", prefs[stringPreferencesKey("deploy_vk_pool_2")])
        assertEquals(listOf(hash('A'), hash('B')), SettingsStore.vkPoolOf(prefs, 2))
        assertTrue(SettingsStore.writeVkPool(prefs, 2, emptyList()))
        assertNull(prefs[stringPreferencesKey("deploy_vk_pool_2")])
        SettingsStore.deleteVpsKeys(prefs, 2)
        assertFalse(SettingsStore.writeVkPool(prefs, 2, listOf(hash('A'))))
        assertTrue(SettingsStore.vkPoolOf(prefs, 2).isEmpty())
    }
}
