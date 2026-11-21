package com.csqtt.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ServerRoutingRuleTest {
    @Test fun allEightModeCombinationsAreIndependent() {
        for (apps in listOf(false, true)) for (domains in listOf(false, true)) for (ips in listOf(false, true)) {
            val json = JSONObject().put("is_whitelist", apps).put("domains_is_whitelist", domains)
                .put("ip_is_whitelist", ips).put("packages", org.json.JSONArray(listOf("com.example.app")))
            val rule = requireNotNull(parseServerRoutingRule(json.toString()))
            assertEquals(apps, rule.isWhite)
            assertEquals(domains, rule.domainsIsWhite)
            assertEquals(ips, rule.ipIsWhite)
        }
    }

    @Test fun stringModesAndLegacyFallbackRemainSupported() {
        val modern = requireNotNull(parseServerRoutingRule("""{"mode":"whitelist","domains_mode":"blacklist","ip_mode":"whitelist"}"""))
        assertTrue(modern.isWhite)
        assertFalse(modern.domainsIsWhite)
        assertTrue(modern.ipIsWhite)
        val legacy = requireNotNull(parseServerRoutingRule("""{"mode":"whitelist"}"""))
        assertTrue(legacy.isWhite)
        assertTrue(legacy.domainsIsWhite)
    }

    @Test fun invalidPackagesAreSkippedButNeverBecomeAnEmptyDeviceWideRule() {
        for (entry in listOf("com.example app", "../bad", "com..example", "a".repeat(256))) {
            val json = JSONObject().put("mode", "whitelist").put("packages", org.json.JSONArray(listOf(entry)))
            val parsed = requireNotNull(parseServerRoutingRuleChecked(json.toString()))
            assertTrue(parsed.rule.packages.isEmpty())
            // the server named packages: the VPN refuses («Whitelist пуст») instead of tunnelling every app
            assertTrue(parsed.rule.packagesListed)
            assertEquals(listOf(entry), parsed.skipped)
        }
        assertNull(parseServerRoutingRule("""{"packages":[42]}"""))
        assertNull(parseServerRoutingRule("""{"packages":"com.example.app"}"""))
        assertTrue(isRoutingPackageName("android"))
        assertTrue(isRoutingPackageName("com.example_app.client2"))
    }

    @Test fun emptyListsAndDuplicatesAreHandledWithoutChangingMode() {
        val rule = requireNotNull(parseServerRoutingRule("""{"mode":"whitelist","packages":[" com.example.app ","com.example.app"]}"""))
        assertTrue(rule.isWhite)
        assertEquals(listOf("com.example.app"), rule.packages)
        assertTrue(requireNotNull(parseServerRoutingRule("{}" )).packages.isEmpty())
    }
}
