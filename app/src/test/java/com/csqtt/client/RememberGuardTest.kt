package com.csqtt.client

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B5f2: «Запомнить» acts only under the rule the offer was made in, with the switch on (a notice from before a change
 * changes nothing); «Удалить» and «Не сейчас» are kept, so the network is not offered again; the networks stay on the phone.
 */
class RememberGuardTest {
    private fun store(rule: WifiRule?, enabled: Boolean = true): MutablePreferences = mutablePreferencesOf().also { prefs ->
        prefs[booleanPreferencesKey("auto_pause_on_wifi")] = enabled
        rule?.let { prefs[stringPreferencesKey("wifi_network_list_mode")] = it.stored }
    }

    @Test
    fun `remember saves and checks the network under the rule it was offered in`() {
        val prefs = store(WifiRule.EXCEPT_CHECKED)
        assertTrue(SettingsStore.rememberWifi(prefs, "\"Офис\"", WifiRule.EXCEPT_CHECKED))
        val rules = SettingsStore.readWifiRules(prefs)
        assertEquals(setOf("Офис"), rules.saved)
        assertEquals(setOf("Офис"), rules.checked)
        assertEquals(WifiDecision.STAY, wifiDecision(rules, onWifi = true, name = "Офис"))
    }

    @Test
    fun `a stale notice changes nothing — another rule, the switch off, or «В любой»`() {
        val changedRule = store(WifiRule.ONLY_CHECKED)
        assertFalse(SettingsStore.rememberWifi(changedRule, "Офис", WifiRule.EXCEPT_CHECKED))
        assertEquals(emptySet<String>(), SettingsStore.readWifiRules(changedRule).saved)

        val switchedOff = store(WifiRule.EXCEPT_CHECKED, enabled = false)
        assertFalse(SettingsStore.rememberWifi(switchedOff, "Офис", WifiRule.EXCEPT_CHECKED))
        assertEquals(emptySet<String>(), SettingsStore.readWifiRules(switchedOff).saved)

        val any = store(null)
        assertFalse(SettingsStore.rememberWifi(any, "Офис", WifiRule.ALL))
        assertFalse(SettingsStore.rememberWifi(any, "<unknown ssid>", WifiRule.ALL))
    }

    @Test
    fun `a check keeps the network saved, «Удалить» drops it and is never offered again`() {
        val prefs = store(WifiRule.ONLY_CHECKED)
        SettingsStore.rememberWifi(prefs, "Дом", WifiRule.ONLY_CHECKED)
        SettingsStore.checkWifi(prefs, "Дом", false)
        SettingsStore.readWifiRules(prefs).let { rules ->
            assertEquals(setOf("Дом"), rules.saved)
            assertEquals(emptySet<String>(), rules.checked)
            assertEquals(WifiDecision.STAY, wifiDecision(rules, onWifi = true, name = "Дом"))
        }
        SettingsStore.forgetWifi(prefs, "Дом")
        SettingsStore.readWifiRules(prefs).let { rules ->
            assertEquals(emptySet<String>(), rules.saved)
            assertEquals(null, wifiOffer(rules, onWifi = true, name = "Дом"))
        }
    }

    @Test
    fun `«Не сейчас» is kept`() {
        val prefs = store(WifiRule.EXCEPT_CHECKED)
        SettingsStore.dismissWifi(prefs, "Кафе")
        val rules = SettingsStore.readWifiRules(prefs)
        assertEquals(setOf("Кафе"), rules.dismissed)
        assertEquals(null, wifiOffer(rules, onWifi = true, name = "Кафе"))
        assertEquals("Метро", wifiOffer(rules, onWifi = true, name = "Метро"))
    }

    @Test
    fun `the rule and the networks are never in a backup, the switch is (F7)`() {
        val wifiKeys = setOf("wifi_network_list_mode", "wifi_network_list_ssids", "wifi_saved_ssids", "wifi_dismissed_ssids")
        assertEquals(wifiKeys, wifiKeys intersect SettingsStore.notBackedUpGlobalKeys)
        assertEquals(emptySet<String>(), wifiKeys intersect SettingsStore.backupGlobalKeys.keys)
        assertTrue("auto_pause_on_wifi" in SettingsStore.backupGlobalKeys.keys)
    }
}
