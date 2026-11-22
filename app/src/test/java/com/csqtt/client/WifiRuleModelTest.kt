package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** B5f2 (M-52): #19's three rules, its sanitiser, and the «Запомнить» offer. */
class WifiRuleModelTest {
    private fun rules(rule: WifiRule, checked: Set<String> = emptySet(), saved: Set<String> = checked, dismissed: Set<String> = emptySet()) =
        WifiRules(enabled = true, rule = rule, checked = checked, saved = saved, dismissed = dismissed)

    @Test
    fun `«В любой» pauses on any Wi-Fi and never needs a name`() {
        val any = rules(WifiRule.ALL)
        assertEquals(WifiDecision.PAUSE, wifiDecision(any, onWifi = true, name = "Дом"))
        assertEquals(WifiDecision.PAUSE, wifiDecision(any, onWifi = true, name = null))
        assertEquals(false, any.needsNames)
    }

    @Test
    fun `«В отмеченных» pauses only on a checked network`() {
        val only = rules(WifiRule.ONLY_CHECKED, checked = setOf("Дом"), saved = setOf("Дом", "Кафе"))
        assertEquals(WifiDecision.PAUSE, wifiDecision(only, onWifi = true, name = "Дом"))
        assertEquals(WifiDecision.STAY, wifiDecision(only, onWifi = true, name = "Кафе"))
        assertEquals(WifiDecision.STAY, wifiDecision(only, onWifi = true, name = "Офис"))
        // names are exact: Android's case is the network's
        assertEquals(WifiDecision.STAY, wifiDecision(only, onWifi = true, name = "дом"))
    }

    @Test
    fun `«Кроме отмеченных» pauses everywhere but on a checked network`() {
        val except = rules(WifiRule.EXCEPT_CHECKED, checked = setOf("Офис"), saved = setOf("Офис", "Кафе"))
        assertEquals(WifiDecision.STAY, wifiDecision(except, onWifi = true, name = "Офис"))
        assertEquals(WifiDecision.PAUSE, wifiDecision(except, onWifi = true, name = "Кафе"))
        assertEquals(WifiDecision.PAUSE, wifiDecision(except, onWifi = true, name = "Новая"))
    }

    @Test
    fun `off or off Wi-Fi decides nothing`() {
        assertEquals(WifiDecision.NONE, wifiDecision(rules(WifiRule.ALL).copy(enabled = false), onWifi = true, name = "Дом"))
        assertEquals(WifiDecision.NONE, wifiDecision(rules(WifiRule.ONLY_CHECKED, setOf("Дом")), onWifi = false, name = "Дом"))
        assertEquals(WifiDecision.NONE, wifiDecision(rules(WifiRule.EXCEPT_CHECKED), onWifi = false, name = null))
    }

    @Test
    fun `the offer is a known network the list does not have and the user did not dismiss, in a list rule only`() {
        val only = rules(WifiRule.ONLY_CHECKED, checked = setOf("Дом"), saved = setOf("Дом", "Кафе"), dismissed = setOf("Метро"))
        assertEquals("Новая", wifiOffer(only, onWifi = true, name = "Новая"))
        assertNull(wifiOffer(only, onWifi = true, name = "Дом"))
        assertNull(wifiOffer(only, onWifi = true, name = "Кафе"))
        assertNull(wifiOffer(only, onWifi = true, name = "Метро"))
        assertNull(wifiOffer(only, onWifi = false, name = "Новая"))
        assertNull(wifiOffer(only, onWifi = true, name = null))
        assertNull(wifiOffer(rules(WifiRule.ALL), onWifi = true, name = "Новая"))
        assertNull(wifiOffer(only.copy(enabled = false), onWifi = true, name = "Новая"))
    }

    @Test
    fun `the sanitiser takes Android's quotes off and refuses what is not a name`() {
        assertEquals("Дом", normalizeWifiSsid("\"Дом\""))
        assertEquals("Дом 5G", normalizeWifiSsid("  \"Дом 5G\"  "))
        // a name Android gives as hex (not UTF-8) is kept as it comes, so it matches itself next time
        assertEquals("d0b4d0bed0bc", normalizeWifiSsid("d0b4d0bed0bc"))
        assertNull(normalizeWifiSsid("<unknown ssid>"))
        assertNull(normalizeWifiSsid("\"\""))
        assertNull(normalizeWifiSsid("   "))
        assertNull(normalizeWifiSsid(null))
        assertNull(normalizeWifiSsid("Дом\nОфис"))
        assertNull(normalizeWifiSsid("Дом\u0000"))
    }

    @Test
    fun `a list is stored one name a line, sorted, without duplicates or junk`() {
        val stored = encodeWifiSsids(listOf("кафе", "\"Дом\"", "Дом", "<unknown ssid>", "Офис"))
        assertEquals("Дом\nкафе\nОфис", stored)
        assertEquals(setOf("Дом", "кафе", "Офис"), decodeWifiSsids(stored))
        assertEquals(emptySet<String>(), decodeWifiSsids(null))
        assertEquals(WifiRule.ALL, WifiRule.fromStored(null))
        assertEquals(WifiRule.ALL, WifiRule.fromStored("garbage"))
        // #19's stored values
        assertEquals(WifiRule.ONLY_CHECKED, WifiRule.fromStored("blacklist"))
        assertEquals(WifiRule.EXCEPT_CHECKED, WifiRule.fromStored("whitelist"))
    }
}
