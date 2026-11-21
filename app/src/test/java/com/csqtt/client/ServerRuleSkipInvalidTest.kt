package com.csqtt.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M-56 / D23: one bad entry in a server rule is skipped and named; only a rule broken as a whole stops the tunnel. */
class ServerRuleSkipInvalidTest {
    private fun rule(packages: List<String> = emptyList(), domains: List<String> = emptyList(), ips: List<String> = emptyList()) =
        JSONObject().put("mode", "blacklist")
            .put("packages", JSONArray(packages)).put("domains", JSONArray(domains)).put("ips", JSONArray(ips)).toString()

    @Test fun aTypoInOneEntryKeepsTheRestOfTheRule() {
        val parsed = requireNotNull(
            parseServerRoutingRuleChecked(
                rule(
                    packages = listOf("com.good.app", "com.example.", "ru.bank.app"),
                    domains = listOf("ok.ru", "bad;ip_enabled=0", "yandex.ru"),
                    ips = listOf("10.0.0.0/8", "300.1.1.1", "1.2.3.4-1.2.3.9", "fe80::1"),
                ),
            ),
        )
        assertEquals(listOf("com.good.app", "ru.bank.app"), parsed.rule.packages)
        assertEquals(listOf("ok.ru", "yandex.ru"), parsed.rule.domains)
        assertEquals(listOf("10.0.0.0/8", "1.2.3.4-1.2.3.9"), parsed.rule.ips)
        assertEquals(listOf("com.example.", "bad;ip_enabled=0", "300.1.1.1", "fe80::1"), parsed.skipped)
    }

    @Test fun noServerEntryCanBreakTheCoresCommandLine() {
        val injected = listOf("a.ru;whitelist=0", "a.ru\nRESUME", "a.ru|x", "a.ru=1", "a.ru,b.ru", "a .ru", "a.ru\u0000")
        val parsed = requireNotNull(parseServerRoutingRuleChecked(rule(domains = injected + "!forced.example.com")))
        assertEquals(listOf("!forced.example.com"), parsed.rule.domains)
        val command = parsed.rule.toUpdate().toCommand()
        assertEquals(1, command.lines().size)
        assertEquals(8, command.substringAfter('|').split(';').size)
    }

    @Test fun structuralBreaksStillFailClosed() {
        assertNull(parseServerRoutingRuleChecked("not json"))
        assertNull(parseServerRoutingRuleChecked("""{"domains":"ok.ru"}"""))
        assertNull(parseServerRoutingRuleChecked("""{"ips":[1]}"""))
        assertNull(parseServerRoutingRuleChecked("""{"packages":null}"""))
        assertNull(parseServerRoutingRuleChecked(rule(domains = List(4097) { "d$it.example.com" })))
        assertTrue(parseServerRoutingRuleChecked(rule(domains = List(4096) { "d$it.example.com" })) != null)
    }

    @Test fun aBlacklistWithEverythingSkippedExcludesNothingAndAWhitelistStillRefuses() {
        val black = requireNotNull(parseServerRoutingRuleChecked(rule(packages = listOf("..")))).rule
        assertTrue(black.packages.isEmpty())
        val white = requireNotNull(parseServerRoutingRule("""{"mode":"whitelist","packages":[".."]}"""))
        assertTrue(white.packagesListed)
        val none = requireNotNull(parseServerRoutingRule("""{"mode":"whitelist","packages":[]}"""))
        assertFalse(none.packagesListed)
    }

    @Test fun theLogLineIsShortAndShowsNoControlCharacters() {
        val many = List(15) { "bad;$it" } + "x\ny"
        val line = describeSkippedEntries(many)
        assertTrue(line.endsWith("(всего 16)"))
        assertFalse(line.contains('\n'))
        assertEquals("x?y", describeSkippedEntries(listOf("x\ny")))
    }

    @Test fun theEffectiveRuleNamesEachModeOnItsOwn() {
        val line = describeEffectiveRouting(
            source = "сервер", appsWhite = true, apps = 3, domainsWhite = false, domains = 12,
            ruZones = false, template = true, ipsWhite = false, ips = 0,
        )
        assertEquals(
            "[СЕТЬ] Маршрутизация (сервер): приложения — белый список, 3; домены — чёрный список, 12 + шаблон; IP — нет",
            line,
        )
        val all = describeEffectiveRouting("устройство", false, 0, true, 0, true, true, true, 2)
        assertEquals("[СЕТЬ] Маршрутизация (устройство): приложения — все с VPN; домены — нет; IP — белый список, 2", all)
    }
}
