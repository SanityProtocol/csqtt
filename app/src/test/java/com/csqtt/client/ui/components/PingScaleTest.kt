package com.csqtt.client.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** The operator's scale (2026-11-25): 1–79 ms green with 3 bars, 80–139 blue with 2, 140–199 orange with 1, 200+ red with none. */
class PingScaleTest {
    @Test
    fun `each band starts and ends where the scale says`() {
        val expected = listOf(
            0 to PingGrade.Good, 1 to PingGrade.Good, 79 to PingGrade.Good,
            80 to PingGrade.Fair, 139 to PingGrade.Fair,
            140 to PingGrade.Slow, 199 to PingGrade.Slow,
            200 to PingGrade.Bad, 5_000 to PingGrade.Bad,
        )
        for ((ms, grade) in expected) assertEquals("$ms ms", grade, pingGrade(ms))
    }

    @Test
    fun `no answer is its own grade, never a fast one`() {
        assertEquals(PingGrade.NoAnswer, pingGrade(null))
        assertEquals(PingGrade.NoAnswer, pingGrade(-1))
    }

    @Test
    fun `the bars fall with the grade, so the colour is never the only cue`() {
        assertEquals(listOf(3, 2, 1, 0, 0), PingGrade.entries.map { it.bars })
    }
}
