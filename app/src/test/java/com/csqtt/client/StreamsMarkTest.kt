package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P3 B3, the canvas's F-TunnelMore: the «Потоки» slider stops at whole groups of ten, and the server's limit is marked
 * on its track (S10's hint).
 */
class StreamsMarkTest {
    @Test
    fun `the stops are the groups of ten up to the profile's maximum`() {
        assertEquals(listOf(10, 20, 30, 40, 50, 60, 70), StreamsSlider.stops(70))
        assertEquals((10..100 step 10).toList(), StreamsSlider.stops(100))
        assertEquals("a subscription's node capped at 40", listOf(10, 20, 30, 40), StreamsSlider.stops(40))
        assertEquals(listOf(10), StreamsSlider.stops(10))
        assertEquals("never fewer than one group", listOf(10), StreamsSlider.stops(3))
    }

    @Test
    fun `a value stands where its share of the track is`() {
        assertEquals(0f, StreamsSlider.fraction(10, 70))
        assertEquals(1f, StreamsSlider.fraction(70, 70))
        assertEquals(0.5f, StreamsSlider.fraction(40, 70))
        assertEquals("a track of one stop has one place", 0f, StreamsSlider.fraction(10, 10))
    }

    @Test
    fun `a touch lands on the nearest stop`() {
        assertEquals(10, StreamsSlider.valueAt(0f, 70))
        assertEquals(70, StreamsSlider.valueAt(1f, 70))
        assertEquals(40, StreamsSlider.valueAt(0.5f, 70))
        assertEquals(20, StreamsSlider.valueAt(0.2f, 70))
        assertEquals(10, StreamsSlider.valueAt(-3f, 70))
        assertEquals(100, StreamsSlider.valueAt(7f, 100))
        assertEquals(10, StreamsSlider.valueAt(0.9f, 10))
        for (stop in StreamsSlider.stops(100)) assertEquals(stop, StreamsSlider.valueAt(StreamsSlider.fraction(stop, 100), 100))
    }

    @Test
    fun `the server's limit is marked where it lies, and at the end it lies beyond`() {
        assertNull("the server named none", StreamsSlider.mark(null, 70))
        assertEquals(0.5f, StreamsSlider.mark(40, 70))
        assertEquals("between two stops", 0.25f, StreamsSlider.mark(25, 70))
        assertEquals("fewer than one group", 0f, StreamsSlider.mark(3, 70))
        assertEquals("more than this profile runs", 1f, StreamsSlider.mark(100, 70))
    }

    // the operator, 2026-11-27: the count is said per hash, and what is recommended is a count per hash

    @Test
    fun `the streams go over the hashes in tens, a ten to a hash in turn`() {
        assertEquals(10, StreamsPlan.perHash(total = 10, hashes = 1))
        assertEquals(70, StreamsPlan.perHash(total = 70, hashes = 1))
        assertEquals("three tens over three hashes", 10, StreamsPlan.perHash(total = 30, hashes = 3))
        assertEquals("two tens use two of seven hashes", 10, StreamsPlan.perHash(total = 20, hashes = 7))
        assertEquals("the fourth ten is the first hash's second", 20, StreamsPlan.perHash(total = 40, hashes = 3))
        assertEquals(20, StreamsPlan.perHash(total = 80, hashes = 7))
        assertEquals(30, StreamsPlan.perHash(total = 100, hashes = 4))
        assertEquals(40, StreamsPlan.perHash(total = 100, hashes = 3))
    }

    @Test
    fun `what is recommended is thirty on a hash, and never above what the profile may run`() {
        assertEquals(30, StreamsPlan.RECOMMENDED_PER_HASH)
        assertEquals(30, StreamsPlan.recommended(hashes = 1, maximum = 100))
        assertEquals(60, StreamsPlan.recommended(hashes = 2, maximum = 100))
        assertEquals(90, StreamsPlan.recommended(hashes = 3, maximum = 100))
        assertEquals("four hashes and more: the whole slider", 100, StreamsPlan.recommended(hashes = 4, maximum = 100))
        assertEquals(100, StreamsPlan.recommended(hashes = 7, maximum = 100))
        assertEquals("a subscription's node capped at 40", 40, StreamsPlan.recommended(hashes = 3, maximum = 40))
        // every count up to the recommended one keeps to thirty on a hash, and the next one does not
        for (hashes in 1..7) {
            val recommended = StreamsPlan.recommended(hashes, 100)
            for (total in StreamsSlider.stops(100)) {
                assertEquals("$total over $hashes", total <= recommended, StreamsPlan.perHash(total, hashes) <= StreamsPlan.RECOMMENDED_PER_HASH)
            }
        }
    }

    @Test
    fun `the hashes are the ones in use, the calls the app makes, or nothing the user counts`() {
        assertEquals(3, StreamsPlan.hashes(autoVk = false, autoApi = false, manual = 3))
        assertEquals("no more than the tunnel takes", 7, StreamsPlan.hashes(autoVk = false, autoApi = false, manual = 9))
        assertNull("no hash yet", StreamsPlan.hashes(autoVk = false, autoApi = false, manual = 0))
        assertEquals("a call for every ten, up to seven", 7, StreamsPlan.hashes(autoVk = false, autoApi = true, manual = 0))
        assertNull("the account's own call", StreamsPlan.hashes(autoVk = true, autoApi = false, manual = 2))
    }
}
