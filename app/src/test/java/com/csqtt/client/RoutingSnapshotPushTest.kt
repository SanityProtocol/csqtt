package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M-57: every push carries the whole config from one snapshot; the list is the switch for domains as for IPs. */
class RoutingSnapshotPushTest {
    private fun fields(command: String): Map<String, String> =
        command.substringAfter("UPDATE_DOMAINS|").split(';').associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test fun aDomainEditWhileConnectedKeepsDomainRoutingOn() {
        // nothing in the app sets domain_routing_enabled, and the old push sent enabled=0 on every edit
        val edited = RoutingSnapshot(directDomainsBlack = "ok.ru,yandex.ru")
        val f = fields(edited.toUpdate().toCommand())
        assertEquals("1", f["enabled"])
        assertEquals("ok.ru,yandex.ru", f["domains"])
    }

    @Test fun aWhitelistStaysAWhitelistAndTheIpRulesSurviveADomainEdit() {
        val snapshot = RoutingSnapshot(
            domainIsWhitelist = true,
            directDomainsBlack = "black.example.com",
            directDomainsWhite = "white.example.com",
            excludeRuZonesEnabled = true,
            templateDomainsEnabled = true,
            ipIsWhitelist = false,
            directIpsBlack = "10.0.0.0/8",
            directIpsWhite = "1.1.1.1",
        )
        val f = fields(snapshot.toUpdate().toCommand())
        assertEquals("1", f["whitelist"])
        assertEquals("white.example.com", f["domains"])
        // RU zones and the template are blacklist-only
        assertEquals("0", f["ru"])
        assertEquals("0", f["template"])
        assertEquals("1", f["ip_enabled"])
        assertEquals("0", f["ip_whitelist"])
        assertEquals("10.0.0.0/8", f["ips"])
    }

    @Test fun ruZonesOrTheTemplateAloneSwitchDomainRoutingOnInBlacklistMode() {
        assertTrue(RoutingSnapshot(excludeRuZonesEnabled = true).hasDomainRules)
        assertTrue(RoutingSnapshot(templateDomainsEnabled = true).hasDomainRules)
        assertFalse(RoutingSnapshot(domainIsWhitelist = true, templateDomainsEnabled = true).hasDomainRules)
        assertFalse(RoutingSnapshot(directDomainsWhite = "a.ru").hasDomainRules)   // the white list is not the active one
    }

    @Test fun clearingTheListsSwitchesBothOff() {
        val f = fields(RoutingSnapshot(directDomainsBlack = " ", directIpsBlack = "").toUpdate().toCommand())
        assertEquals("0", f["enabled"])
        assertEquals("0", f["ip_enabled"])
    }

    @Test fun aServerRuleGivesTheSameShape() {
        val rule = ServerRoutingRule(
            isWhite = true, packages = listOf("a.b"), domains = listOf("x.ru", "y.ru"), templateDomains = false,
            ipIsWhite = true, ips = listOf("2.2.2.2"), domainsIsWhite = false,
        )
        val f = fields(rule.toUpdate().toCommand())
        assertEquals(setOf("enabled", "ru", "template", "whitelist", "domains", "ip_enabled", "ip_whitelist", "ips"), f.keys)
        assertEquals("1", f["enabled"])
        assertEquals("0", f["whitelist"])
        assertEquals("x.ru,y.ru", f["domains"])
        assertEquals("1", f["ip_whitelist"])
    }
}
