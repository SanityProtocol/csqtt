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
}
