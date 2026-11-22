package com.csqtt.client

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B5f2 (M-54, F2, F4): the location foreground type only while a list rule is on and precise location is granted; on API
 * 29–33 an explicit type, since the two-argument start would take every type the manifest declares (location included).
 */
class LocationFgsOnlyWhenOnTest {
    private val special = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    private val location = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

    @Test
    fun `Android 14 and later — special use, with location only when wanted`() {
        for (sdk in listOf(34, 35, 36, 37)) {
            assertEquals(special, foregroundTypes(sdk, wantLocation = false))
            assertEquals(special or location, foregroundTypes(sdk, wantLocation = true))
        }
    }

    @Test
    fun `Android 10 to 13 — an explicit type, none without the rule`() {
        for (sdk in 29..33) {
            assertEquals(0, foregroundTypes(sdk, wantLocation = false))
            assertEquals(location, foregroundTypes(sdk, wantLocation = true))
        }
    }

    @Test
    fun `Android 8 and 9 — the two-argument start, no types`() {
        assertNull(foregroundTypes(26, wantLocation = true))
        assertNull(foregroundTypes(28, wantLocation = false))
    }

    @Test
    fun `wanted only by a list rule that is on, with precise location`() {
        val only = WifiRules(enabled = true, rule = WifiRule.ONLY_CHECKED)
        val except = WifiRules(enabled = true, rule = WifiRule.EXCEPT_CHECKED)
        assertTrue(wantsLocationType(only, fineGranted = true))
        assertTrue(wantsLocationType(except, fineGranted = true))
        assertFalse(wantsLocationType(only, fineGranted = false))
        // «В любой» and the switch off never hold location (F2)
        assertFalse(wantsLocationType(WifiRules(enabled = true, rule = WifiRule.ALL), fineGranted = true))
        assertFalse(wantsLocationType(only.copy(enabled = false), fineGranted = true))
    }
}
