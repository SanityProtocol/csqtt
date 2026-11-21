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
