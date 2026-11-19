package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    @Test
    fun comparesVersionsNumerically() {
        assertTrue(isNewerVersion("1.9.9", "2.0.5"))
        assertTrue(isNewerVersion("2.0", "2.0.1"))
        assertFalse(isNewerVersion("2.0.5", "2.0"))
        assertFalse(isNewerVersion("2.1.0", "2.0.9"))
        assertFalse(isNewerVersion("2.0.5", "invalid"))
    }

    @Test
    fun checksWhenDueAndNotBefore() {
        val hour = 60L * 60L * 1000L
        val now = 1_800_000_000_000L
        assertEquals(0L, updateCheckDelayMs(now, 0L, hour))                 // never checked
        assertEquals(0L, updateCheckDelayMs(now, now - hour, hour))         // exactly due
        assertEquals(0L, updateCheckDelayMs(now, now - 5 * hour, hour))     // overdue
        assertEquals(hour - 60_000L, updateCheckDelayMs(now, now - 60_000L, hour)) // checked a minute ago
        assertEquals(0L, updateCheckDelayMs(now, now + 3 * hour, hour))     // clock set back: due, not stuck
    }
}
