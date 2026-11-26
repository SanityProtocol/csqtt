package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Потоки» is said only while it is news (P3-PLAN §1.3a; the operator, 2026-11-26). */
class StreamsLineTest {
    private val connected = ConnectionState.Connected(10)

    @Test
    fun shownWhileAStreamIsMissing() {
        assertTrue(StreamsLine.shown(ConnectionState.Connecting, false, active = 0, running = 10, fullSince = null, nowMs = 0))
        assertTrue(StreamsLine.shown(ConnectionState.Preparing, false, active = 0, running = 10, fullSince = null, nowMs = 0))
        assertTrue(StreamsLine.shown(connected, false, active = 9, running = 10, fullSince = null, nowMs = 0))
        assertTrue(StreamsLine.shown(ConnectionState.Reconnecting, false, active = 0, running = 10, fullSince = null, nowMs = 0))
        assertEquals(0L, StreamsLine.holdLeft(connected, false, active = 9, running = 10, fullSince = null, nowMs = 5_000))
    }

    @Test
    fun twoSecondsAfterTheLastOneThenGone() {
        val full = StreamsLine.fullSince(previous = null, active = 10, running = 10, nowMs = 1_000)
        assertEquals(1_000L, full)
        assertTrue(StreamsLine.shown(connected, false, 10, 10, full, nowMs = 1_000))
        assertEquals(500L, StreamsLine.holdLeft(connected, false, 10, 10, full, nowMs = 2_500))
        assertFalse(StreamsLine.shown(connected, false, 10, 10, full, nowMs = 3_000))
        assertFalse(StreamsLine.shown(connected, false, 10, 10, full, nowMs = 60_000))
        // the moment is kept while the count stays full: a recreation inside the two seconds shows the rest of them
        assertEquals(1_000L, StreamsLine.fullSince(previous = full, active = 10, running = 10, nowMs = 2_000))
    }

    @Test
    fun backWhenOneIsLost() {
        val lost = StreamsLine.fullSince(previous = 1_000, active = 9, running = 10, nowMs = 90_000)
        assertNull(lost)
        assertTrue(StreamsLine.shown(connected, false, 9, 10, lost, nowMs = 90_000))
        // and two seconds again once it is back
        val again = StreamsLine.fullSince(lost, active = 10, running = 10, nowMs = 95_000)
        assertEquals(95_000L, again)
        assertTrue(StreamsLine.shown(connected, false, 10, 10, again, nowMs = 96_000))
        assertFalse(StreamsLine.shown(connected, false, 10, 10, again, nowMs = 97_000))
    }

    @Test
    fun neverAtRestAndNeverWithoutARun() {
        // a tunnel at rest lost its streams to the phone's sleep: no news (§1.9)
        assertFalse(StreamsLine.shown(ConnectionState.Connected(0), dormant = true, active = 0, running = 10, fullSince = null, nowMs = 0))
        for (state in listOf(
            ConnectionState.Idle, ConnectionState.Failed("x"), ConnectionState.Stopping, ConnectionState.PausedOnWifi,
            ConnectionState.WaitingForNetwork,
        )) {
            assertFalse("$state", StreamsLine.shown(state, false, active = 0, running = 10, fullSince = null, nowMs = 0))
        }
        // no count to speak of yet
        assertFalse(StreamsLine.shown(ConnectionState.Preparing, false, active = 0, running = 0, fullSince = null, nowMs = 0))
        assertNull(StreamsLine.fullSince(null, active = 0, running = 0, nowMs = 0))
    }

    @Test
    fun theServersLowerLimitIsTheCount() {
        assertEquals(6, StreamsLine.running(target = 10, serverLimit = 6))
        assertEquals(10, StreamsLine.running(target = 10, serverLimit = null))
        // a limit at or above the profile's own changes nothing
        assertEquals(10, StreamsLine.running(target = 10, serverLimit = 10))
        assertEquals(10, StreamsLine.running(target = 10, serverLimit = 50))
        // six of six is every stream: the hold, then gone
        val running = StreamsLine.running(10, 6)
        val full = StreamsLine.fullSince(null, active = 6, running = running, nowMs = 0)
        assertTrue(StreamsLine.shown(connected, false, 6, running, full, nowMs = 1_999))
        assertFalse(StreamsLine.shown(connected, false, 6, running, full, nowMs = 2_000))
    }
}
