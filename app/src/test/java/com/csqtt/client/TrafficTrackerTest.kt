package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficTrackerTest {
    @Test
    fun speedFromTwoReadings() {
        val tracker = TrafficTracker()
        val first = tracker.onStats(1, bytesUp = 1_000, bytesDown = 10_000, nowMs = 0)
        assertFalse(first.hasSpeed)
        assertEquals(11_000, first.totalUp + first.totalDown)
        val second = tracker.onStats(1, bytesUp = 4_000, bytesDown = 40_000, nowMs = 3_000)
        assertTrue(second.hasSpeed)
        assertEquals(1_000, second.upBytesPerSecond)
        assertEquals(10_000, second.downBytesPerSecond)
        assertEquals(44_000, second.totalUp + second.totalDown)
    }

    @Test
    fun aNewProcessCarriesTheTotalsWithNoSpike() {
        val tracker = TrafficTracker()
        tracker.onStats(1, 1_000, 50_000_000, 0)
        tracker.onStats(1, 2_000, 60_000_000, 3_000)
        // the restart's process counts from zero: no negative or huge speed, the totals keep growing
        val restart = tracker.onStats(2, 100, 1_000, 6_000)
        assertFalse(restart.hasSpeed)
        assertEquals(2_100, restart.totalUp)
        assertEquals(60_001_000, restart.totalDown)
        val next = tracker.onStats(2, 3_100, 31_000, 9_000)
        assertEquals(1_000, next.upBytesPerSecond)
        assertEquals(10_000, next.downBytesPerSecond)
        assertEquals(60_031_000, next.totalDown)
    }

    @Test
    fun aCounterGoingDownIsANewProcess() {
        val tracker = TrafficTracker()
        tracker.onStats(1, 5_000, 5_000, 0)
        val down = tracker.onStats(1, 10, 10, 3_000)
        assertFalse(down.hasSpeed)
        assertEquals(5_010, down.totalUp)
    }

    @Test
    fun resetStartsARunFromZero() {
        val tracker = TrafficTracker()
        tracker.onStats(1, 5_000, 5_000, 0)
        tracker.reset()
        val fresh = tracker.onStats(1, 10, 20, 3_000)
        assertEquals(10, fresh.totalUp)
        assertEquals(20, fresh.totalDown)
    }

    @Test
    fun formatting() {
        assertEquals("", formatTraffic(null))
        assertEquals("всего 1.0 МБ", formatTraffic(TrafficSnapshot(totalDown = 1024 * 1024)))
        assertEquals(
            "↓ 2 КБ/с ↑ 512 Б/с · всего 3.00 ГБ",
            formatTraffic(
                TrafficSnapshot(
                    downBytesPerSecond = 2048, upBytesPerSecond = 512,
                    totalDown = 3L * 1024 * 1024 * 1024, hasSpeed = true,
                ),
            ),
        )
    }
}
