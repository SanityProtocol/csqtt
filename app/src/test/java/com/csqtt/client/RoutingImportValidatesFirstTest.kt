package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M-63: every selected section is checked before anything is written; a damaged one refuses the whole import. */
class RoutingImportValidatesFirstTest {
    private val current = RoutingImportCurrent(
        appsWhitelist = false,
        blackApps = setOf("com.old.app"),
        domainsWhitelist = false,
        blackDomains = "old.ru",
        ipsWhitelist = true,
        whiteIps = "1.1.1.1",
    )

    private fun parse(text: String, apps: Boolean = true, domains: Boolean = true, ips: Boolean = true) =
        RoutingRulesImport.parse(text, apps, domains, ips, current)

    private fun plan(result: RoutingImportResult) = (result as RoutingImportResult.Ready).plan

    @Test fun aDamagedSectionWritesNothing() {
        val good = """"apps":{"active_mode":"whitelist","whitelist":["com.a.app"]}"""
        for (bad in listOf(
            """{$good,"domains":"ok.ru"}""",
            """{$good,"domains":{"blacklist":"ok.ru"}}""",
            """{$good,"domains":{"blacklist":[1,2]}}""",
            """{$good,"ips":{"active_mode":"grey"}}""",
            """{$good,"domains":{"template_domains":"yes"}}""",
            """{"apps":{"whitelist":["com.a.app"]""",
        )) {
            val result = parse(bad)
            assertTrue(bad, result is RoutingImportResult.Refused)
        }
        // the same damage in a section that was not selected is never read
        assertTrue(parse("""{$good,"domains":"ok.ru"}""", domains = false) is RoutingImportResult.Ready)
    }

    @Test fun theSectionsThatPassAreWrittenTogether() {
        val p = plan(parse(
            """{"apps":{"active_mode":"whitelist","blacklist":["com.b.app","bad name"],"whitelist":["com.a.app"]},
               "domains":{"active_mode":"whitelist","whitelist":["https://www.Example.com/x","not a domain"],"template_domains":true},
               "ips":{"is_whitelist":false,"blacklist":["10.0.0.0/8","999.1.1.1"]}}""",
        ))
        assertEquals(AppsImport(black = listOf("com.b.app"), white = listOf("com.a.app"), isWhitelist = true), p.apps)
        assertEquals(listOf("example.com"), p.domains?.white)
        assertNull(p.domains?.black)   // an empty inactive list is left as it is
        assertEquals(true, p.domains?.isWhitelist)
        assertEquals(true, p.domains?.templateDomains)
        assertEquals(listOf("10.0.0.0/8"), p.ips?.black)
        assertEquals(false, p.ips?.isWhitelist)
    }

    @Test fun theLegacyFlatListFillsTheActiveMode() {
        val p = plan(parse("""{"ips":{"direct_ips":["8.8.8.8"]}}""", apps = false, domains = false))
        // no mode in the section: the stored one (whitelist) is kept, so the flat list fills the white list
        assertEquals(listOf("8.8.8.8"), p.ips?.white)
        assertEquals(true, p.ips?.isWhitelist)
    }

    @Test fun plainTextAddsToTheActiveListsAndKeepsTheModes() {
        val apps = plan(parse("Чёрный список:\ncom.new.app, com.other.app", domains = false, ips = false)).apps
        assertEquals(setOf("com.old.app", "com.new.app", "com.other.app"), apps?.black?.toSet())
        assertNull(apps?.white)
        assertNull(apps?.isWhitelist)
        val domains = plan(parse("new.ru\nold.ru", apps = false, ips = false)).domains
        assertEquals(listOf("old.ru", "new.ru"), domains?.black)
        assertNull(domains?.white)
        assertNull(domains?.isWhitelist)
        val ips = plan(parse("2.2.2.2 1.1.1.1", apps = false, domains = false)).ips
        assertEquals(listOf("1.1.1.1", "2.2.2.2"), ips?.white)
        assertNull(ips?.isWhitelist)
    }

    @Test fun nothingUsableIsSaidSo() {
        assertEquals(
            RoutingImportResult.Refused("В буфере обмена нет подходящих данных"),
            parse("hello", domains = false, ips = false),
        )
        assertEquals(RoutingImportResult.Refused("Буфер обмена пуст"), parse("  "))
        assertEquals(RoutingImportResult.Refused("Выберите хотя бы один пункт"), parse("x", false, false, false))
    }
}
