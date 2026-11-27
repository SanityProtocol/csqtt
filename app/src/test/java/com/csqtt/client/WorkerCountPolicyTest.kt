package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerCountPolicyTest {

    @Test
    fun everySupportedTotalRemainsAnExactMultipleOfFive() {
        val supported = (1..CsqttConstants.Tunnel.MAX_WORKERS / CsqttConstants.Tunnel.WORKERS_PER_GROUP)
            .map { it * CsqttConstants.Tunnel.WORKERS_PER_GROUP }
        for ((groups, workers) in supported.withIndex()) {
            assertEquals(workers, WorkerCountPolicy.normalize(workers))
            assertEquals(groups + 1, workers / CsqttConstants.Tunnel.WORKERS_PER_GROUP)
        }
        assertEquals(100, supported.last())
        assertEquals(100, WorkerCountPolicy.normalize(CsqttConstants.Tunnel.MAX_WORKERS))
    }

    @Test
    fun aCountStoredBeforeFivesIsWhatItRanAs() {
        // 2.5.3 ran fewer than ten as five and the rest in whole tens, so its stored default of 18 ran as 10: the
        // store's first step, step 9, an older backup and an older settings file keep to that
        for (requested in listOf(Int.MIN_VALUE, 0, 3, 5, 9)) {
            assertEquals("requested $requested", 5, WorkerCountPolicy.wholeGroups(requested))
        }
        for (requested in listOf(10, 18, 19)) {
            assertEquals("requested $requested", 10, WorkerCountPolicy.wholeGroups(requested))
        }
        assertEquals(70, WorkerCountPolicy.wholeGroups(75))
        assertEquals(100, WorkerCountPolicy.wholeGroups(250))
        // and with «Экстра потоки» off, or never touched, no more than 70
        assertEquals("a settings file of 2.5.3 holds 18", 10, SettingsStore.countAsItRan(18, extra = false))
        assertEquals(5, SettingsStore.countAsItRan(5, extra = null))
        assertEquals(70, SettingsStore.countAsItRan(100, extra = null))
        assertEquals(100, SettingsStore.countAsItRan(100, extra = true))
        assertEquals(90, SettingsStore.countAsItRan(95, extra = true))
    }

    @Test
    fun aPoolIsFilledToWhatTheStreamsNeedOrToOneMoreThanIsAlive() {
        // «Пул звонков VK»: a dead call made again, «Создать звонок»
        assertEquals(2, VkAutoCallsManager.poolTarget(workers = 20, alive = 2, extra = 0))
        assertEquals("one call beyond the three that live", 4, VkAutoCallsManager.poolTarget(workers = 20, alive = 3, extra = 1))
        assertEquals("never fewer than the streams need", 5, VkAutoCallsManager.poolTarget(workers = 50, alive = 1, extra = 1))
    }

    @Test
    fun aCountIsHeldInFivesFromFiveUp() {
        // the operator, 2026-11-27: the slider moves in fives, and the count held is the count that runs
        for ((requested, held) in listOf(Int.MIN_VALUE to 5, 0 to 5, 3 to 5, 5 to 5, 9 to 5, 10 to 10, 14 to 10, 15 to 15, 18 to 15, 99 to 95, 100 to 100, 250 to 100)) {
            assertEquals("requested $requested", held, WorkerCountPolicy.normalize(requested))
        }
        assertEquals("a subscription's node capped at 37", 35, WorkerCountPolicy.normalize(100, maximum = 37))
        assertEquals("a cap below the first stop", 5, WorkerCountPolicy.normalize(100, maximum = 3))
        assertEquals(5, WorkerCountPolicy.STEP)
        assertEquals("a new profile starts at the top of the green zone", StreamsPlan.GREEN_PER_HASH, CsqttConstants.Tunnel.DEFAULT_WORKERS)
    }

    @Test
    fun theMostAProfileRunsIsTheCoresAndInAutoVkThatModesOwn() {
        // «Экстра потоки» is gone (P3 B3): nothing lifts a maximum, and what is recommended is a count per hash
        assertEquals(CsqttConstants.Tunnel.MAX_WORKERS, WorkerCountPolicy.maximum(accountAutoVk = false))
        assertEquals(CsqttConstants.Tunnel.AUTO_VK_MAX_WORKERS, WorkerCountPolicy.maximum(accountAutoVk = true))
        assertEquals(100, WorkerCountPolicy.normalize(Int.MAX_VALUE, maximum = WorkerCountPolicy.maximum(accountAutoVk = false)))
        assertEquals(70, WorkerCountPolicy.normalize(Int.MAX_VALUE, maximum = WorkerCountPolicy.maximum(accountAutoVk = true)))
    }

    @Test
    fun hashCapacityHasNoHiddenExtraWorkers() {
        assertEquals(10, WorkerCountPolicy.maximumForHashes(1))
        assertEquals(20, WorkerCountPolicy.maximumForHashes(2))
        assertEquals(30, WorkerCountPolicy.maximumForHashes(3))
        assertEquals(40, WorkerCountPolicy.maximumForHashes(4))
        assertEquals(50, WorkerCountPolicy.maximumForHashes(5))
        assertEquals(60, WorkerCountPolicy.maximumForHashes(6))
        assertEquals(70, WorkerCountPolicy.maximumForHashes(7))
    }

    @Test
    fun participantCapacityComesFromLinkBeforeLocalProfileState() {
        assertEquals(
            100,
            WorkerCountPolicy.maximumForSources(
                linkMode = true,
                linkHashCount = 4,
                autoHashMode = false,
                manualHashCount = 0,
            ),
        )
        assertEquals(
            100,
            WorkerCountPolicy.maximumForSources(
                linkMode = false,
                linkHashCount = 4,
                autoHashMode = false,
                manualHashCount = 1,
            ),
        )
        assertEquals(
            100,
            WorkerCountPolicy.maximumForSources(
                linkMode = false,
                linkHashCount = 0,
                autoHashMode = true,
                manualHashCount = 1,
            ),
        )
    }

    @Test
    fun invalidPersistedCountsAreBoundedAndInFives() {
        for (requested in -1_000..1_000) {
            val normalized = WorkerCountPolicy.normalize(requested)
            assertTrue(normalized in WorkerCountPolicy.STEP..CsqttConstants.Tunnel.MAX_WORKERS)
            assertEquals(0, normalized % WorkerCountPolicy.STEP)
            assertTrue(normalized <= requested.coerceAtLeast(WorkerCountPolicy.STEP))
        }
    }

    @Test
    fun stalePersistedMaximumIsRecappedByCurrentHashCountAtRuntime() {
        assertEquals(10, WorkerCountPolicy.normalizeForHashes(Int.MAX_VALUE, 1))
        assertEquals(20, WorkerCountPolicy.normalizeForHashes(Int.MAX_VALUE, 2))
        assertEquals(30, WorkerCountPolicy.normalizeForHashes(Int.MAX_VALUE, 3))
        assertEquals(40, WorkerCountPolicy.normalizeForHashes(Int.MAX_VALUE, 4))
        assertEquals(50, WorkerCountPolicy.normalizeForHashes(Int.MAX_VALUE, 5))
        assertEquals(60, WorkerCountPolicy.normalizeForHashes(Int.MAX_VALUE, 6))
        assertEquals(70, WorkerCountPolicy.normalizeForHashes(Int.MAX_VALUE, 7))
    }

    @Test
    fun automaticHashModeAllowsTheFullWorkerRange() {
        for (requested in -100..300) {
            val admitted = WorkerCountPolicy.normalize(requested)
            assertTrue(admitted in 5..CsqttConstants.Tunnel.MAX_WORKERS)
            assertEquals(0, admitted % 5)
        }
        assertEquals(100, WorkerCountPolicy.normalize(Int.MAX_VALUE))
    }

    @Test
    fun craftedIntentCountsCannotBypassHashAwareRuntimeAdmission() {
        for (requested in listOf(Int.MIN_VALUE, -1, 0, 4, 28, 109, Int.MAX_VALUE)) {
            for (hashCount in 1..CsqttConstants.Tunnel.MAX_VK_HASHES) {
                val admitted = WorkerCountPolicy.normalizeForHashes(requested, hashCount)
                assertTrue(admitted in 5..WorkerCountPolicy.maximumForHashes(hashCount))
                assertEquals(0, admitted % WorkerCountPolicy.STEP)
            }
        }
        assertEquals(
            100,
            WorkerCountPolicy.normalizeForHashValues(
                Int.MAX_VALUE,
                listOf("same", " same ", "same", ""),
            ),
        )
        assertEquals(
            100,
            WorkerCountPolicy.normalizeForHashValues(
                Int.MAX_VALUE,
                listOf("one", "two", "three", "four", "five", "six", "ignored-seventh"),
            ),
        )
    }

    @Test
    fun automaticCallCountScalesToSevenHashes() {
        assertEquals(1, VkAutoCallsManager.callCountForWorkers(10))
        assertEquals(2, VkAutoCallsManager.callCountForWorkers(20))
        assertEquals(3, VkAutoCallsManager.callCountForWorkers(30))
        assertEquals(5, VkAutoCallsManager.callCountForWorkers(45))
        assertEquals(6, VkAutoCallsManager.callCountForWorkers(60))
        assertEquals(7, VkAutoCallsManager.callCountForWorkers(100))
        assertEquals(CsqttConstants.VkAutoHash.AUTO_CALL_SMALL_DELAY_MS, VkAutoCallsManager.requestDelayForCallCount(1))
        assertEquals(CsqttConstants.VkAutoHash.AUTO_CALL_SMALL_DELAY_MS, VkAutoCallsManager.requestDelayForCallCount(4))
        assertEquals(CsqttConstants.VkAutoHash.AUTO_CALL_LARGE_DELAY_MS, VkAutoCallsManager.requestDelayForCallCount(5))
        assertEquals(CsqttConstants.VkAutoHash.AUTO_CALL_LARGE_DELAY_MS, VkAutoCallsManager.requestDelayForCallCount(6))
    }

    @Test
    fun automaticCallFailureCanRedistributeWholeGroupsAcrossCreatedHashes() {
        assertEquals(
            100,
            WorkerCountPolicy.normalizeForHashValues(
                Int.MAX_VALUE,
                listOf("one", "two", "three", "four", "five"),
                allowRedistribution = true,
            ),
        )
    }

    @Test
    fun manualHashesSupportFullWorkerRangeUpToMaxWorkers() {
        assertEquals(
            30,
            WorkerCountPolicy.normalizeForHashValues(
                30,
                listOf("hash1_at_least_16_chars", "hash2_at_least_16_chars"),
                allowRedistribution = false,
            ),
        )
        assertEquals(
            100,
            WorkerCountPolicy.normalizeForHashValues(
                100,
                listOf("hash1_at_least_16_chars", "hash2_at_least_16_chars"),
                allowRedistribution = false,
            ),
        )
        assertEquals(
            50,
            WorkerCountPolicy.normalizeForHashValues(
                50,
                listOf("single_hash_at_least_16_chars"),
                allowRedistribution = false,
            ),
        )
    }
}
