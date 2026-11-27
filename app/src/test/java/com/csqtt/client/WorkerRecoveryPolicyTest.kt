package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerRecoveryPolicyTest {
    @Test
    fun startupZeroDoesNotArmRecovery() {
        val policy = WorkerRecoveryPolicy()
        assertNull(policy.armAtZero())
        assertFalse(policy.shouldRecover(0, 9))
    }

    @Test
    fun zeroAfterHealthyCohortArmsOneRecovery() {
        val policy = WorkerRecoveryPolicy()
        policy.observe(27)
        assertEquals(27, policy.armAtZero())
        assertEquals(27, policy.armAtZero())
        assertTrue(policy.shouldRecover(0, 27))
    }

    @Test
    fun partialRecoveryDoesNotHideFullOutage() {
        val policy = WorkerRecoveryPolicy()
        policy.observe(27)
        val target = policy.armAtZero()!!
        policy.observe(6)
        assertTrue(policy.shouldRecover(6, target))
    }

    @Test
    fun completeRecoveryDisarmsRestart() {
        val policy = WorkerRecoveryPolicy()
        policy.observe(27)
        val target = policy.armAtZero()!!
        assertTrue(policy.observe(27))
        assertFalse(policy.shouldRecover(27, target))
    }

    @Test
    fun resetRequiresAHealthyCohortAgain() {
        val policy = WorkerRecoveryPolicy()
        policy.observe(54)
        assertEquals(54, policy.armAtZero())
        policy.reset()
        assertNull(policy.armAtZero())
    }

    @Test
    fun aRunOfFewerThanTenArmsOnceAllOfItWasUp() {
        // P3 B3: the slider's first stop is five (and a server may allow as few as three): such a run never has a
        // whole group up, and was never brought back from zero
        val policy = WorkerRecoveryPolicy()
        policy.observe(3)
        assertNull("not all five were up yet", policy.armAtZero(runCount = 5))
        policy.observe(5)
        assertEquals(5, policy.armAtZero(runCount = 5))
        assertTrue(policy.shouldRecover(0, 5))
        // a run of ten and more still wants its first whole group
        val full = WorkerRecoveryPolicy()
        full.observe(9)
        assertNull(full.armAtZero(runCount = 40))
        full.observe(10)
        assertEquals(10, full.armAtZero(runCount = 40))
    }
}
