package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P3 B3 (the operator, 2026-11-27): the corner control of «Одно подключение» is the switch with two or more
 * connections and «+» with one.
 */
class HomePickTest {
    private fun entry(index: Int, hidden: Boolean = false) = ProfileEntry(index = index, name = "Профиль ${index + 1}", hidden = hidden)

    @Test
    fun `one connection is the plus, two or more are the switch`() {
        assertEquals(HomePick.Add, homePick(listOf(entry(0))))
        assertEquals(HomePick.Switch, homePick(listOf(entry(0), entry(1))))
        assertEquals(HomePick.Switch, homePick((0..31).map { entry(it) }))
    }

    @Test
    fun `a hidden connection counts, since the sheet is where it is found`() {
        assertEquals(HomePick.Switch, homePick(listOf(entry(0), entry(4, hidden = true))))
    }

    @Test
    fun `with no connection at all it is the plus, never a switch to nothing`() {
        assertEquals(HomePick.Add, homePick(emptyList()))
    }
}
