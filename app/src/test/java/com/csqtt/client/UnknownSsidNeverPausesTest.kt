package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * B5f2 (M-54, F1): a list rule on a Wi-Fi whose name Android does not give (no precise location, location off, a start in
 * the background) never pauses — above all in «Кроме отмеченных», where #19 paused on the very network the user excluded.
 */
class UnknownSsidNeverPausesTest {
    @Test
    fun `an unknown name never pauses in either list rule`() {
        for (rule in listOf(WifiRule.ONLY_CHECKED, WifiRule.EXCEPT_CHECKED)) {
            val rules = WifiRules(enabled = true, rule = rule, checked = setOf("Офис"), saved = setOf("Офис"))
            assertEquals(rule.name, WifiDecision.NAME_UNKNOWN, wifiDecision(rules, onWifi = true, name = null))
            // and the pause's action follows the decision, not «on Wi-Fi»
            assertEquals(
                rule.name,
                WifiAutoPauseAction.NONE,
                wifiAutoPauseAction(enabled = true, wifiConnected = false, desiredRunning = true, paused = false),
            )
        }
    }

    @Test
    fun `a pause on a network whose name is lost ends, the name of the paused network kept`() {
        val except = WifiRules(enabled = true, rule = WifiRule.EXCEPT_CHECKED, checked = setOf("Офис"), saved = setOf("Офис"))
        // the service keeps a network's last readable name, so a redacted update of the same network decides as before
        assertEquals(WifiDecision.PAUSE, wifiDecision(except, onWifi = true, name = "Кафе"))
        // a network it never could name decides «unknown»: a pause in force ends (the VPN back on)
        val decision = wifiDecision(except, onWifi = true, name = null)
        assertNotEquals(WifiDecision.PAUSE, decision)
        assertEquals(
            WifiAutoPauseAction.RESUME,
            wifiAutoPauseAction(enabled = true, wifiConnected = decision == WifiDecision.PAUSE, desiredRunning = true, paused = true),
        )
    }

    @Test
    fun `«В любой» needs no name and pauses as before`() {
        val any = WifiRules(enabled = true, rule = WifiRule.ALL)
        assertEquals(WifiDecision.PAUSE, wifiDecision(any, onWifi = true, name = null))
        assertEquals(
            WifiAutoPauseAction.PAUSE,
            wifiAutoPauseAction(enabled = true, wifiConnected = true, desiredRunning = true, paused = false),
        )
    }

    @Test
    fun `an unknown name is never offered to remember`() {
        val only = WifiRules(enabled = true, rule = WifiRule.ONLY_CHECKED)
        assertEquals(null, wifiOffer(only, onWifi = true, name = null))
    }
}
