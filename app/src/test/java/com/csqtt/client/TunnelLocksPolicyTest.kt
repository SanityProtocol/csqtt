package com.csqtt.client

import com.csqtt.client.TunnelLockPolicy.Locks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3 §1.9: the CPU and the Wi-Fi radio are held only while that buys something. An idle tunnel behind a dark screen
 * holds neither, so the phone sleeps and its battery service has nothing to say about the app.
 */
class TunnelLocksPolicyTest {
    private val none = Locks(cpu = false, wifi = false)
    private val both = Locks(cpu = true, wifi = true)
    private val kb = 1024L

    private fun TunnelLockPolicy.at(
        seconds: Long,
        bytes: Long = 0L,
        run: Boolean = true,
        screenOn: Boolean = false,
        work: Boolean = false,
        deploying: Boolean = false,
    ) = decide(seconds * 1_000L, run, screenOn, work, deploying, bytes)

    @Test
    fun `an idle tunnel behind a dark screen holds nothing, and a lit screen only keeps the radio awake`() {
        val policy = TunnelLockPolicy()
        for (second in 0L..120L step 2) assertEquals("at $second s", none, policy.at(second))
        // the CPU is awake under a lit screen anyway: a lock there buys nothing and is counted against the app
        assertEquals(Locks(cpu = false, wifi = true), policy.at(122, screenOn = true))
        assertEquals(none, policy.at(124))
    }

    @Test
    fun `a transfer holds both locks until a whole window passed without one`() {
        val policy = TunnelLockPolicy()
        assertEquals(none, policy.at(0, bytes = 0))
        assertEquals(none, policy.at(2, bytes = 10 * kb))
        // 64 KB within the window: a transfer, seen at once
        assertEquals(both, policy.at(4, bytes = 80 * kb))
        // it goes on: every window carries
        var bytes = 80 * kb
        for (second in 6L..60L step 2) {
            bytes += 40 * kb
            assertEquals("at $second s", both, policy.at(second, bytes = bytes))
        }
        // it ends (the last byte at 60 s): let go after the first whole window that carried nothing
        val ended = (62L..90L step 2).map { second -> second to policy.at(second, bytes = bytes) }
        assertEquals(both, ended.first().second)
        val released = ended.first { it.second == none }.first
        assertTrue("let go at $released s, 10 to 20 s after the last byte", released in 70L..80L)
        assertTrue(ended.filter { it.first >= released }.all { it.second == none })
    }

    @Test
    fun `pings and pushes never hold the phone awake, however often they come`() {
        // any app's heartbeat through the tunnel: a few hundred bytes every 5 s, all night
        val policy = TunnelLockPolicy()
        var bytes = 0L
        for (second in 0L..3_600L step 5) {
            bytes += 400
            assertEquals("at $second s", none, policy.at(second, bytes = bytes))
        }
        // a push with a picture: 30 KB at once is not a transfer either
        assertEquals(none, policy.at(3_602, bytes = bytes + 30 * kb))
    }

    @Test
    fun `a voice call is a transfer`() {
        val policy = TunnelLockPolicy()
        var bytes = 0L
        var held = none
        for (second in 0L..30L step 2) {
            bytes += 2 * 10 * kb // about 10 KB/s
            held = policy.at(second, bytes = bytes)
        }
        assertEquals(both, held)
    }

    @Test
    fun `a window the phone slept through ends at the wake, judged by what it carried`() {
        val policy = TunnelLockPolicy()
        assertEquals(none, policy.at(0, bytes = 0))
        // an hour of sleep; a push of 2 KB woke the phone
        assertEquals(none, policy.at(3_600, bytes = 2 * kb))
        // another hour; a 300 KB picture came with the wake: a transfer, held for its window and let go after the next
        assertEquals(both, policy.at(7_200, bytes = 302 * kb))
        assertEquals(both, policy.at(7_202, bytes = 302 * kb))
        assertEquals(none, policy.at(7_210, bytes = 302 * kb))
    }

    @Test
    fun `a start or a repair holds both, but not for a night`() {
        val policy = TunnelLockPolicy()
        assertEquals(both, policy.at(0, work = true))
        assertEquals(both, policy.at(9 * 60, work = true))
        // a server that stays down: after ten minutes the phone may sleep
        assertEquals(none, policy.at(10 * 60, work = true))
        assertEquals(none, policy.at(60 * 60, work = true))
        // connected, then a new repair: its own ten minutes
        assertEquals(none, policy.at(60 * 60 + 2, work = false))
        assertEquals(both, policy.at(60 * 60 + 4, work = true))
    }

    @Test
    fun `a deploy holds the CPU alone, a paused or stopped run nothing`() {
        val policy = TunnelLockPolicy()
        assertEquals(Locks(cpu = true, wifi = false), policy.at(0, run = false, deploying = true))
        assertEquals(none, policy.at(2, run = false, screenOn = true, work = true, bytes = 500 * kb))
        // a run and a check of the server at once
        assertEquals(both, policy.at(4, deploying = true, screenOn = true))
    }

    @Test
    fun `a new run's counters start a new window`() {
        val policy = TunnelLockPolicy()
        policy.at(0, bytes = 900 * kb)
        assertEquals(both, policy.at(2, bytes = 1_000 * kb))
        // the counters went back: another run, nothing carried yet
        assertEquals(none, policy.at(4, bytes = 0))
        assertEquals(none, policy.at(6, bytes = 10 * kb))
    }
}
