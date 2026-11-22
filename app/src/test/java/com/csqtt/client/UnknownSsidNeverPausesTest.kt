package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * B5f2 (M-54, F1): a list rule on a Wi-Fi whose name Android does not give (no precise location, location off, a start in
 * the background) never pauses — above all in «Кроме отмеченных», where #19 paused on the very network the user excluded.
 * The service's reconcile and both of its guards decide through [wifiPauseAction]; the names come from [WifiNameCache].
 */
class UnknownSsidNeverPausesTest {
    private val except = WifiRules(enabled = true, rule = WifiRule.EXCEPT_CHECKED, checked = setOf("Офис"), saved = setOf("Офис"))
    private val only = WifiRules(enabled = true, rule = WifiRule.ONLY_CHECKED, checked = setOf("Дом"), saved = setOf("Дом"))

    private fun action(rules: WifiRules, name: String?, paused: Boolean = false) =
        wifiPauseAction(rules, onWifi = true, name = name, desiredRunning = true, paused = paused)

    @Test
    fun `an unknown name never pauses in either list rule, and ends a pause in force`() {
        for (rules in listOf(except, only)) {
            assertEquals(rules.rule.name, WifiAutoPauseAction.NONE, action(rules, name = null))
            assertEquals(rules.rule.name, WifiAutoPauseAction.RESUME, action(rules, name = null, paused = true))
        }
        // a known name decides by its check
        assertEquals(WifiAutoPauseAction.PAUSE, action(except, "Кафе"))
        assertEquals(WifiAutoPauseAction.NONE, action(except, "Офис"))
        assertEquals(WifiAutoPauseAction.PAUSE, action(only, "Дом"))
        assertEquals(WifiAutoPauseAction.RESUME, action(only, "Кафе", paused = true))
    }

    @Test
    fun `«В любой» needs no name and pauses as before, off and a stopped run do nothing`() {
        val any = WifiRules(enabled = true, rule = WifiRule.ALL)
        assertEquals(WifiAutoPauseAction.PAUSE, action(any, name = null))
        assertEquals(WifiAutoPauseAction.RESUME, action(any.copy(enabled = false), name = null, paused = true))
        assertEquals(
            WifiAutoPauseAction.NONE,
            wifiPauseAction(any, onWifi = true, name = null, desiredRunning = false, paused = false),
        )
        assertEquals(WifiAutoPauseAction.RESUME, wifiPauseAction(any, onWifi = false, name = null, desiredRunning = true, paused = true))
    }

    @Test
    fun `a network keeps its last readable name through a redacted update, and loses it with the network`() {
        val cache = WifiNameCache<String>()
        cache.seen("net-1", "Дом")
        // Android redacts the next update (the app went to the background, location turned off): the name stays
        cache.seen("net-1", null)
        assertEquals("Дом", cache.current(listOf("net-1")))
        assertEquals(WifiAutoPauseAction.PAUSE, action(only, cache.current(listOf("net-1"))))
        // the network is lost: its name goes with it, and a new network whose name is not given is unknown
        cache.lost("net-1")
        assertNull(cache.current(listOf("net-2")))
        assertEquals(WifiAutoPauseAction.NONE, action(only, cache.current(listOf("net-2"))))
    }

    @Test
    fun `a hand-over keeps the old network's name until it is lost, and «В любой» drops every name`() {
        val cache = WifiNameCache<String>()
        cache.seen("net-1", "Дом")
        cache.seen("net-2", null)
        assertEquals("Дом", cache.current(listOf("net-1", "net-2")))
        cache.lost("net-1")
        assertNull(cache.current(listOf("net-2")))
        cache.seen("net-2", "Кафе")
        assertEquals("Кафе", cache.current(listOf("net-2")))
        cache.clear()
        assertNull(cache.current(listOf("net-2")))
    }

    @Test
    fun `an unknown name is never offered to remember`() {
        assertNull(wifiOffer(only, onWifi = true, name = null))
    }
}
