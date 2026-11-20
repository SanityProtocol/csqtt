package com.csqtt.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationThrottleTest {
    @Test
    fun screenOffPostsNothing() {
        val throttle = NotificationThrottle(5_000)
        assertFalse(throttle.shouldPost("Подключено", "a", 0, screenOn = false))
        assertFalse(throttle.shouldPost("Переподключение…", "b", 10_000, screenOn = false))
    }

    @Test
    fun textChangesAreAtLeastFiveSecondsApart() {
        val throttle = NotificationThrottle(5_000)
        assertTrue(throttle.shouldPost("S", "a", 0, screenOn = true))
        assertFalse(throttle.shouldPost("S", "b", 2_000, screenOn = true))
        assertFalse(throttle.shouldPost("S", "b", 4_999, screenOn = true))
        assertTrue(throttle.shouldPost("S", "b", 5_000, screenOn = true))
    }

    @Test
    fun anUnchangedTextIsNotPostedWhileShown() {
        val throttle = NotificationThrottle(5_000)
        assertTrue(throttle.shouldPost("S", "a", 0, screenOn = true))
        assertFalse(throttle.shouldPost("S", "a", 60_000, screenOn = true) { true })
        // swiped away or replaced: posted again
        assertTrue(throttle.shouldPost("S", "a", 60_000, screenOn = true) { false })
    }

    @Test
    fun aStateChangeIsPostedAtOnce() {
        val throttle = NotificationThrottle(5_000)
        assertTrue(throttle.shouldPost("Подключено", "a", 0, screenOn = true))
        assertTrue(throttle.shouldPost("Переподключение…", "a", 100, screenOn = true))
    }

    @Test
    fun invalidateLetsTheNextPostThrough() {
        val throttle = NotificationThrottle(5_000)
        assertTrue(throttle.shouldPost("S", "a", 0, screenOn = true))
        throttle.invalidate()
        assertTrue(throttle.shouldPost("S", "a", 1, screenOn = true))
    }
}
