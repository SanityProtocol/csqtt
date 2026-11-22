package com.csqtt.client

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * B5e4: the calls a run is on are ended when the run ends or moves to a new set, and never before: a recovery's set is
 * the run's only once taken, a set no run takes is ended, and the set a run is on outlives a recovery that got nothing.
 */
class CallSetBookkeepingTest {
    private val ended = mutableListOf<VkAutoCallsManager.CallSet>()
    private val realEnder = VkAutoCallsManager.ender

    private fun set(vararg ids: String) = VkAutoCallsManager.StartResult(
        hashes = ids.joinToString(","),
        requestedCalls = ids.size,
        createdCalls = ids.size,
        set = VkAutoCallsManager.CallSet(ids.map { VkAutoCallsManager.ActiveCall(it, "key-$it") }, "token"),
    )

    @Before
    fun recordEnds() {
        VkAutoCallsManager.ender = { ended += it }
        VkAutoCallsManager.finishActiveCalls()
        ended.clear()
    }

    @After
    fun restore() {
        VkAutoCallsManager.ender = { }
        VkAutoCallsManager.finishActiveCalls()
        VkAutoCallsManager.ender = realEnder
    }

    @Test
    fun aStartTakesItsSetAndTheStopEndsIt() {
        val start = set("a1", "a2")
        VkAutoCallsManager.take(start)
        assertTrue(ended.isEmpty())
        assertTrue(VkAutoCallsManager.hasActiveCalls())
        VkAutoCallsManager.finishActiveCalls()
        assertEquals(listOf(start.set), ended)
        assertFalse(VkAutoCallsManager.hasActiveCalls())
        // a second stop has nothing left to end
        VkAutoCallsManager.finishActiveCalls()
        assertEquals(1, ended.size)
    }

    @Test
    fun aRecoveryThatGotNothingLeavesTheRunOnItsSet() {
        val start = set("a1", "a2")
        VkAutoCallsManager.take(start)
        // a failed mint returns no result: nothing is taken, nothing ended
        VkAutoCallsManager.take(null)
        assertTrue(ended.isEmpty())
        VkAutoCallsManager.finishActiveCalls()
        assertEquals("the stop still ends the run's set", listOf(start.set), ended)
    }

    @Test
    fun aTakenSetEndsThePreviousOne() {
        val start = set("a1", "a2")
        val recovery = set("b1", "b2")
        VkAutoCallsManager.take(start)
        VkAutoCallsManager.take(recovery)
        assertEquals(listOf(start.set), ended)
        VkAutoCallsManager.finishActiveCalls()
        assertEquals(listOf(start.set, recovery.set), ended)
    }

    @Test
    fun aSetNoRunTakesIsEndedAndTheRunKeepsItsOwn() {
        val start = set("a1")
        val abandoned = set("b1")
        VkAutoCallsManager.take(start)
        VkAutoCallsManager.discard(abandoned)
        assertEquals(listOf(abandoned.set), ended)
        assertTrue(VkAutoCallsManager.hasActiveCalls())
        VkAutoCallsManager.discard(null)
        assertEquals(1, ended.size)
    }
}
