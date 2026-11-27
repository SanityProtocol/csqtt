package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3 B3, the canvas's F-TunnelMore: the «Потоки» slider stops at every five (the operator, 2026-11-27), the server's
 * limit is marked on its track (S10's hint), and its four zones are counts per hash.
 */
class StreamsMarkTest {
    @Test
    fun `the stops are the fives up to the profile's maximum`() {
        assertEquals((5..70 step 5).toList(), StreamsSlider.stops(70))
        assertEquals((5..100 step 5).toList(), StreamsSlider.stops(100))
        assertEquals("a subscription's node capped at 40", listOf(5, 10, 15, 20, 25, 30, 35, 40), StreamsSlider.stops(40))
        assertEquals("a cap between two stops", listOf(5, 10, 15), StreamsSlider.stops(18))
        assertEquals(listOf(5), StreamsSlider.stops(5))
        assertEquals("never fewer than one stop", listOf(5), StreamsSlider.stops(3))
    }

    @Test
    fun `a value stands where its share of the track is`() {
        assertEquals(0f, StreamsSlider.fraction(5, 65))
        assertEquals(1f, StreamsSlider.fraction(65, 65))
        assertEquals(0.5f, StreamsSlider.fraction(35, 65))
        assertEquals("a track of one stop has one place", 0f, StreamsSlider.fraction(5, 5))
    }

    @Test
    fun `a touch lands on the nearest stop`() {
        assertEquals(5, StreamsSlider.valueAt(0f, 65))
        assertEquals(65, StreamsSlider.valueAt(1f, 65))
        assertEquals(35, StreamsSlider.valueAt(0.5f, 65))
        assertEquals(15, StreamsSlider.valueAt(0.2f, 65))
        assertEquals(5, StreamsSlider.valueAt(-3f, 70))
        assertEquals(100, StreamsSlider.valueAt(7f, 100))
        assertEquals(5, StreamsSlider.valueAt(0.9f, 5))
        for (stop in StreamsSlider.stops(100)) assertEquals(stop, StreamsSlider.valueAt(StreamsSlider.fraction(stop, 100), 100))
    }

    @Test
    fun `the server's limit is marked where it lies, and at the end it lies beyond`() {
        assertNull("the server named none", StreamsSlider.mark(null, 65))
        assertEquals(0.5f, StreamsSlider.mark(35, 65))
        assertEquals("between two stops", 0.25f, StreamsSlider.mark(20, 65))
        assertEquals("fewer than the first stop", 0f, StreamsSlider.mark(3, 70))
        assertEquals("more than this profile runs", 1f, StreamsSlider.mark(100, 70))
    }

    // the operator, 2026-11-27: the count is said per hash, and the slider's zones are counts per hash

    @Test
    fun `the streams go over the hashes in tens, a ten to a hash in turn, the last ten a part of one`() {
        assertEquals(10, StreamsPlan.perHash(total = 10, hashes = 1))
        assertEquals(70, StreamsPlan.perHash(total = 70, hashes = 1))
        assertEquals("three tens over three hashes", 10, StreamsPlan.perHash(total = 30, hashes = 3))
        assertEquals("two tens use two of six hashes", 10, StreamsPlan.perHash(total = 20, hashes = 6))
        assertEquals("the fourth ten is the first hash's second", 20, StreamsPlan.perHash(total = 40, hashes = 3))
        assertEquals("the seventh and eighth tens are the first two hashes' second", 20, StreamsPlan.perHash(total = 80, hashes = 6))
        assertEquals("seven tens over six hashes: the first carries two", 20, StreamsPlan.perHash(total = 70, hashes = 6))
        assertEquals(30, StreamsPlan.perHash(total = 100, hashes = 4))
        assertEquals(40, StreamsPlan.perHash(total = 100, hashes = 3))
        // a count in fives: the core runs it as it is, and its last group is a half
        assertEquals(5, StreamsPlan.perHash(total = 5, hashes = 3))
        assertEquals(15, StreamsPlan.perHash(total = 15, hashes = 1))
        assertEquals("ten on the first hash, five on the second", 10, StreamsPlan.perHash(total = 15, hashes = 2))
        assertEquals("the half group is the first hash's third", 25, StreamsPlan.perHash(total = 45, hashes = 2))
        assertEquals("the half group is the second hash's second", 20, StreamsPlan.perHash(total = 35, hashes = 2))
        // however they are dealt, every stream is on some hash and none is counted twice
        for (hashes in 1..6) for (total in StreamsSlider.stops(100)) {
            val most = StreamsPlan.perHash(total, hashes)
            assertTrue("$total over $hashes", most * hashes >= total && most <= total)
        }
    }

    @Test
    fun `a zone is a count on one hash - green to 20, blue to 50, orange to 70, red above`() {
        assertEquals(listOf(20, 50, 70), listOf(StreamsPlan.GREEN_PER_HASH, StreamsPlan.RECOMMENDED_PER_HASH, StreamsPlan.ORANGE_PER_HASH))
        for ((perHash, zone) in listOf(
            5 to StreamZone.Green, 20 to StreamZone.Green, 25 to StreamZone.Blue, 50 to StreamZone.Blue,
            55 to StreamZone.Orange, 70 to StreamZone.Orange, 75 to StreamZone.Red, 100 to StreamZone.Red,
        )) {
            assertEquals("$perHash on a hash", zone, StreamsPlan.zone(perHash))
        }
        // two hashes with twenty each are no worse off than one hash with twenty
        assertEquals(StreamZone.Green, StreamsPlan.zone(total = 20, hashes = 1))
        assertEquals(StreamZone.Green, StreamsPlan.zone(total = 40, hashes = 2))
        assertEquals(StreamZone.Blue, StreamsPlan.zone(total = 45, hashes = 2))
        assertEquals(StreamZone.Blue, StreamsPlan.zone(total = 100, hashes = 2))
        assertEquals(StreamZone.Orange, StreamsPlan.zone(total = 60, hashes = 1))
        assertEquals(StreamZone.Green, StreamsPlan.zone(total = 60, hashes = 3))
        assertEquals(StreamZone.Red, StreamsPlan.zone(total = 75, hashes = 1))
        assertEquals("five hashes and more: the whole line is green", StreamZone.Green, StreamsPlan.zone(total = 100, hashes = 5))
        // the zones only ever rise along the line
        assertTrue(StreamZone.Green < StreamZone.Blue && StreamZone.Blue < StreamZone.Orange && StreamZone.Orange < StreamZone.Red)
    }

    @Test
    fun `on the line a zone ends at its count times the hashes, and never beyond the line`() {
        assertEquals(listOf(20, 50, 70), listOf(20, 50, 70).map { StreamsPlan.totalWithin(it, hashes = 1, maximum = 100) })
        assertEquals(listOf(40, 100, 100), listOf(20, 50, 70).map { StreamsPlan.totalWithin(it, hashes = 2, maximum = 100) })
        assertEquals(listOf(60, 100, 100), listOf(20, 50, 70).map { StreamsPlan.totalWithin(it, hashes = 3, maximum = 100) })
        assertEquals(listOf(80, 100, 100), listOf(20, 50, 70).map { StreamsPlan.totalWithin(it, hashes = 4, maximum = 100) })
        assertEquals(listOf(100, 100, 100), listOf(20, 50, 70).map { StreamsPlan.totalWithin(it, hashes = 5, maximum = 100) })
        assertEquals("«Авто ВК»: one call, up to its 70", listOf(20, 50, 70), listOf(20, 50, 70).map { StreamsPlan.totalWithin(it, hashes = 1, maximum = 70) })
        assertEquals("a subscription's node capped at 40", listOf(40, 40, 40), listOf(20, 50, 70).map { StreamsPlan.totalWithin(it, hashes = 3, maximum = 40) })
        // the line's zones are the counts' zones: every stop up to a zone's end is in it or below, the next is above
        for (hashes in 1..6) {
            for ((limit, zone) in listOf(20 to StreamZone.Green, 50 to StreamZone.Blue, 70 to StreamZone.Orange)) {
                val end = StreamsPlan.totalWithin(limit, hashes, 100)
                for (total in StreamsSlider.stops(100)) {
                    assertEquals("$total over $hashes, the end of $zone", total <= end, StreamsPlan.zone(total, hashes) <= zone)
                }
            }
        }
    }

    @Test
    fun `the calls are the hashes in use, the calls the app makes, or the account's one`() {
        assertEquals(3, StreamsPlan.calls(autoVk = false, autoApi = false, manual = 3))
        // the core takes six of the hashes it is handed (rust-client MAX_VK_HASHES): a seventh carries nothing
        assertEquals("no more than the core takes", 6, StreamsPlan.calls(autoVk = false, autoApi = false, manual = 9))
        assertEquals(CsqttConstants.Tunnel.CORE_VK_HASHES, StreamsPlan.calls(autoVk = false, autoApi = false, manual = 7))
        assertEquals("no hash yet: judged as with one", 1, StreamsPlan.calls(autoVk = false, autoApi = false, manual = 0))
        assertEquals("a call for every ten, of which the core takes six", 6, StreamsPlan.calls(autoVk = false, autoApi = true, manual = 0))
        assertEquals("the account's own call", 1, StreamsPlan.calls(autoVk = true, autoApi = false, manual = 2))
        // «N на хеш» is said where there are hashes to say it of
        assertTrue(StreamsPlan.saidPerHash(autoVk = false, autoApi = false, manual = 1))
        assertTrue(StreamsPlan.saidPerHash(autoVk = false, autoApi = true, manual = 0))
        assertFalse(StreamsPlan.saidPerHash(autoVk = false, autoApi = false, manual = 0))
        assertFalse(StreamsPlan.saidPerHash(autoVk = true, autoApi = false, manual = 3))
    }
}
