package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val OWN = "net.sanityprotocol.csqtt"

private fun plan(
    settings: VpnSettings = VpnSettings(),
    rule: ServerRoutingRule? = null,
    sdk: Int = 34,
    dns: String = "77.88.8.8",
    installed: Set<String> = setOf("org.telegram.messenger", "com.android.chrome", "com.vkontakte.android", "com.vk.im"),
) = VpnPlan.of(sdk, OWN, "10.16.0.3", dns, rule, settings) { it in installed }

/** M-62 / D24: every VK app bypasses the tunnel in every mode, and is never let into a whitelist. */
class VkPackagesDirectTest {
    @Test fun everyVkPackageIsCovered() {
        assertEquals(
            setOf("com.vkontakte.android", "com.vk.im", "com.vk.vkvideo", "com.vk.music", "com.vk.calls"),
            VpnPlan.VK_PACKAGES.toSet(),
        )
    }

    @Test fun blacklistAndAllAppsDisallowTheInstalledVkAppsAndCsqttFirst() {
        for (settings in listOf(VpnSettings(), VpnSettings(allApps = true, excludedApps = setOf("org.telegram.messenger")))) {
            val p = plan(settings)
            assertEquals(OWN, p.disallowedApps.first())
            assertEquals(listOf(OWN, "com.vk.im", "com.vkontakte.android"), p.disallowedApps)
            assertTrue(p.allowedApps.isEmpty())
        }
    }

    @Test fun aWhitelistNeverLetsVkIn() {
        val p = plan(VpnSettings(appsWhitelist = true, whitelistApps = setOf("com.vk.im", "com.android.chrome")))
        assertEquals(listOf("com.android.chrome"), p.allowedApps)
        val onlyVk = plan(VpnSettings(appsWhitelist = true, whitelistApps = setOf("com.vk.im")))
        assertEquals("Whitelist пуст: выберите хотя бы одно установленное приложение", onlyVk.failure)
    }

    @Test fun aServerBlacklistAlsoKeepsVkDirect() {
        val rule = ServerRoutingRule(isWhite = false, packages = listOf("org.telegram.messenger"), domains = emptyList(), templateDomains = false)
        assertEquals(listOf(OWN, "com.vk.im", "com.vkontakte.android", "org.telegram.messenger"), plan(rule = rule).disallowedApps)
    }
}

/** M-59: «Все с VPN» carries every app but the transport ones, and both lists stay stored. */
class AllAppsFlagKeepsListsTest {
    @Test fun allAppsIgnoresBothListsWithoutTouchingThem() {
        val settings = VpnSettings(
            appsWhitelist = true,
            whitelistApps = setOf("com.android.chrome"),
            excludedApps = setOf("org.telegram.messenger"),
            allApps = true,
        )
        val p = plan(settings)
        assertTrue(p.allowedApps.isEmpty())
        assertFalse("org.telegram.messenger" in p.disallowedApps)
        assertNull(p.failure)
        // the lists are inputs only: switching the flag off brings the stored whitelist straight back
        assertEquals(listOf("com.android.chrome"), plan(settings.copy(allApps = false)).allowedApps)
    }

    @Test fun aServerRuleStillWins() {
        val rule = ServerRoutingRule(isWhite = true, packages = listOf("com.android.chrome"), domains = emptyList(), templateDomains = false)
        assertEquals(listOf("com.android.chrome"), plan(VpnSettings(allApps = true), rule).allowedApps)
    }

    @Test fun aServerWhitelistWhoseEntriesWereAllSkippedRefusesAndAnEmptyOneIsDeviceWide() {
        val skipped = ServerRoutingRule(isWhite = true, packages = emptyList(), domains = emptyList(), templateDomains = false, packagesListed = true)
        assertEquals("Whitelist пуст: выберите хотя бы одно установленное приложение", plan(rule = skipped).failure)
        val empty = skipped.copy(packagesListed = false)
        assertNull(plan(rule = empty).failure)
        assertEquals(OWN, plan(rule = empty).disallowedApps.first())
    }
}

/** M-64: LAN bypass by excluded routes on API 33+, by the route list itself below. */
class LanBypassRoutesTest {
    private fun covers(prefixes: List<Ipv4Prefix>, ip: String): Boolean {
        val v = ipv4ToLong(ip)
        return prefixes.any { v >= it.start && v < it.start + it.size }
    }

    @Test fun offByDefaultTheWholeIpv4SpaceGoesIn() {
        val p = plan()
        assertEquals(listOf(Ipv4Prefix("0.0.0.0", 0)), p.routes)
        assertTrue(p.excludedRoutes.isEmpty())
    }

    @Test fun api33AndLaterExcludeTheLocalRanges() {
        val p = plan(VpnSettings(lanBypass = true), sdk = 33)
        assertEquals(listOf(Ipv4Prefix("0.0.0.0", 0)), p.routes)
        assertEquals(VpnPlan.LAN_PREFIXES, p.excludedRoutes)
    }

    @Test fun belowApi33TheRoutesAreTheComplement() {
        val p = plan(VpnSettings(lanBypass = true), sdk = 26)
        assertTrue(p.excludedRoutes.isEmpty())
        for (lan in listOf("10.1.2.3", "192.168.1.1", "172.16.0.1", "172.31.255.255", "169.254.1.1", "100.64.0.1", "224.0.0.251", "255.255.255.255")) {
            assertFalse(lan, covers(p.routes, lan))
        }
        for (public in listOf("0.0.0.1", "1.1.1.1", "8.8.8.8", "100.63.255.255", "100.128.0.0", "172.15.255.255", "172.32.0.0", "192.167.255.255", "223.255.255.255")) {
            assertTrue(public, covers(p.routes, public))
        }
        // disjoint, and together with the local ranges the whole space
        val total = p.routes.sumOf { it.size } + VpnPlan.LAN_PREFIXES.sumOf { it.size }
        assertEquals(1L shl 32, total)
    }

    @Test fun theIpBlacklistStillExcludesOnApi33AndIsMergedWithTheLan() {
        val p = plan(VpnSettings(lanBypass = true, ips = "8.8.8.8, 1.2.3.4/24, 10.0.0.0/8, 1.2.3.4-1.2.3.9"))
        assertEquals(VpnPlan.LAN_PREFIXES + listOf(Ipv4Prefix("8.8.8.8", 32), Ipv4Prefix("1.2.3.0", 24)), p.excludedRoutes)
    }
}

/** M-66 / D25: the interface is IPv4 only, so Android blocks IPv6 for the tunnelled apps. */
class Ipv6BlockedTest {
    @Test fun noIpv6AddressRouteOrResolverEverEntersThePlan() {
        val p = plan(
            VpnSettings(lanBypass = true, ips = "2001:db8::/32,8.8.8.8"),
            dns = "2001:4860:4860::8888, 77.88.8.8",
        )
        val all = p.routes + p.excludedRoutes
        assertTrue(all.all { it.address.count { c -> c == '.' } == 3 && ':' !in it.address })
        assertEquals(listOf("77.88.8.8"), p.dnsServers)
        assertFalse(p.clientIp.contains(':'))
    }

    @Test fun anIpv6OnlyServerDnsFallsBackToAnIpv4One() {
        assertEquals(listOf("77.88.8.8"), plan(dns = "2001:4860:4860::8888").dnsServers)
    }

    @Test fun aLoopbackOrAnyAddressServerDnsFallsBackLikeBefore() {
        // Builder.addDnsServer refuses them; 9a07eb9 fell back to 77.88.8.8 (review F1)
        assertEquals(listOf("77.88.8.8"), plan(dns = "127.0.0.53").dnsServers)
        assertEquals(listOf("77.88.8.8"), plan(dns = "0.0.0.0").dnsServers)
        assertEquals(listOf("8.8.8.8"), plan(dns = "127.0.0.1, 8.8.8.8").dnsServers)
    }
}

/** M-67 / D26: the device's DNS replaces the server's; a private one only with LAN bypass, reached directly. */
class DnsOverrideTest {
    @Test fun aPublicOverrideReplacesTheServersAndGoesThroughTheTunnel() {
        val p = plan(VpnSettings(dnsOverride = "1.1.1.1, 9.9.9.9"))
        assertEquals(listOf("1.1.1.1", "9.9.9.9"), p.dnsServers)
        assertEquals(listOf("1.1.1.1", "9.9.9.9"), p.tunnelledDns)
        assertTrue(p.dnsOverridden)
        assertFalse(p.dnsOverrideRefused)
        assertNull(p.serverDnsForLog("77.88.8.8"))
        assertEquals("77.88.8.8", plan().serverDnsForLog("77.88.8.8"))
    }

    @Test fun aPrivateOverrideNeedsLanBypassAndThenGoesDirect() {
        val refused = plan(VpnSettings(dnsOverride = "192.168.1.1"))
        assertEquals(listOf("77.88.8.8"), refused.dnsServers)
        assertTrue(refused.dnsOverrideRefused)
        val direct = plan(VpnSettings(dnsOverride = "192.168.1.1", lanBypass = true))
        assertEquals(listOf("192.168.1.1"), direct.dnsServers)
        assertTrue(direct.tunnelledDns.isEmpty())
    }

    @Test fun aServersPrivateDnsStaysInTheTunnelEvenWithLanBypass() {
        val p = plan(VpnSettings(lanBypass = true), dns = "10.66.0.1")
        assertEquals(listOf("10.66.0.1"), p.tunnelledDns)
    }

    @Test fun unusableOverridesAreRefused() {
        for (text in listOf("1.1.1.1,8.8.8.8,9.9.9.9", "dns.google", "127.0.0.1", "0.0.0.0", "224.0.0.1", "2001:4860::8888", "1.1.1.256")) {
            assertNull(text, VpnPlan.dnsOverride(text, lanBypass = true))
        }
        assertEquals(listOf("8.8.8.8"), VpnPlan.dnsOverride(" 8.8.8.8 ", lanBypass = false))
    }
}

/** An interface is reused only for an equal plan (PR #52: the reuse check includes the switch). */
class VpnPlanReuseTest {
    @Test fun theSameInputsGiveAnEqualPlanAndAnyChangeADifferentOne() {
        val base = VpnSettings(excludedApps = setOf("org.telegram.messenger"))
        assertEquals(plan(base), plan(base.copy()))
        assertNotEquals(plan(base), plan(base.copy(lanBypass = true)))
        assertNotEquals(plan(base), plan(base.copy(dnsOverride = "1.1.1.1")))
        assertNotEquals(plan(base), plan(base.copy(allApps = true)))
        assertNotEquals(plan(base), plan(base, dns = "8.8.8.8"))
        // an app installed since the last build changes the plan
        assertNotEquals(plan(base), plan(base, installed = setOf("com.vk.im")))
        // P1 Bt part 2: so does the profile's MTU
        assertNotEquals(plan(base), plan(base.copy(mtu = 1280)))
    }
}

/** P1 Bt part 2: the profile's MTU reaches the interface; a value no interface should take is the default. */
class VpnMtuTest {
    @Test fun theInterfaceTakesTheProfilesMtu() {
        assertEquals(1500, plan().mtu)
        assertEquals(1280, plan(VpnSettings(mtu = 1280)).mtu)
        assertEquals(1420, plan(VpnSettings(mtu = 1420)).mtu)
        assertEquals(576, plan(VpnSettings(mtu = 576)).mtu)
        for (outside in listOf(575, 1501, 9000, 0, -1)) assertEquals("$outside", 1500, plan(VpnSettings(mtu = outside)).mtu)
    }

    @Test fun aStoredValueOutsideTheRangeIsTheDefault() {
        assertEquals(1500, VpnMtu.effective(null))
        assertEquals(1280, VpnMtu.effective(1280))
        assertEquals(1500, VpnMtu.effective(575))
        assertEquals(1500, VpnMtu.effective(1501))
        assertEquals(listOf(1500, 1280), VpnMtu.PRESETS)
    }

    @Test fun aCustomValueIsDigitsInTheRange() {
        assertEquals(1400, VpnMtu.parse("1400"))
        assertEquals(1280, VpnMtu.parse(" 1280 "))
        assertEquals(576, VpnMtu.parse("576"))
        assertEquals(1500, VpnMtu.parse("1500"))
        for (text in listOf("", "575", "1501", "12a", "-1", "+1400", "1 400", "99999999999", "14.0")) {
            assertNull(text, VpnMtu.parse(text))
        }
    }
}

/** M-61: presets are offered, never applied; the user's choices come first. */
class AppRoutingPresetsTest {
    @Test fun theListsAreExactPackagesAndHoldNoVkApp() {
        val parsed = AppRoutingPresets.parse("# header\ncom.android.chrome\n\nnot a package\ncom.vk.im\ncom.android.chrome # again\n")
        assertEquals(listOf("com.android.chrome"), parsed)
    }

    @Test fun mergeAddsOnlyInstalledPresetAppsAfterTheUsersOwn() {
        val merged = AppRoutingPresets.merge(
            existing = linkedSetOf("b.mine", "a.mine"),
            preset = listOf("x.preset", "y.preset"),
            installed = setOf("y.preset", "a.mine"),
        )
        assertEquals(listOf("b.mine", "a.mine", "y.preset"), merged.toList())
        assertEquals(
            listOf("c.app", "a.app", "b.app", "d.app"),
            AppRoutingPresets.orderedPackages(listOf("a.app", "b.app", "c.app", "d.app"), linkedSetOf("c.app", "gone.app", "a.app")),
        )
    }
}

/**
 * Card p3-apply-bar: the IP list's dialog and a config file's import make a running tunnel's interface again when what
 * it is built from changed, and only then (the operator, 2026-11-28: «Two direct fixes, no watcher»).
 */
class VpnSettingsDifferTest {
    private val base = VpnSettings(excludedApps = setOf("org.telegram.messenger"), ips = "203.0.113.7,198.51.100.0/24")

    private fun differ(after: VpnSettings, before: VpnSettings = base, sdk: Int = 34) = VpnPlan.settingsDiffer(before, after, sdk)

    @Test fun nothingChangedBuildsTheSameInterface() {
        assertFalse(differ(base))
        assertFalse(differ(base.copy()))
    }

    @Test fun theInterfacesOwnSettingsEachMakeItAgain() {
        for (after in listOf(
            base.copy(appsWhitelist = true, whitelistApps = setOf("com.android.chrome")),
            base.copy(excludedApps = emptySet()),
            base.copy(excludedApps = setOf("org.telegram.messenger", "com.android.chrome")),
            base.copy(allApps = true),
            base.copy(lanBypass = true),
            base.copy(dnsOverride = "1.1.1.1"),
            base.copy(mtu = 1280),
        )) {
            assertTrue("$after", differ(after))
            assertTrue("on an older Android too: $after", differ(after, sdk = 30))
            assertNotEquals("the plans differ: $after", plan(base), plan(after))
        }
    }

    @Test fun anAppListTheModeDoesNotUseBuildsTheSameInterface() {
        val white = base.copy(appsWhitelist = true, whitelistApps = setOf("com.android.chrome"))
        val all = base.copy(allApps = true)
        for ((before, after) in listOf(
            // the other mode's list, edited
            base to base.copy(whitelistApps = setOf("com.android.chrome")),
            white to white.copy(excludedApps = emptySet()),
            // «Все с VPN» keeps both lists and uses neither
            all to all.copy(excludedApps = emptySet(), whitelistApps = setOf("com.android.chrome"), appsWhitelist = true),
            // and with nothing in the black list it is the black mode itself
            VpnSettings() to VpnSettings(allApps = true),
        )) {
            assertFalse("$before → $after", differ(after, before))
            assertEquals("$before → $after", plan(before), plan(after))
        }
    }

    @Test fun aBlackListEditMakesItAgainOnAndroid13AndLater() {
        val edited = base.copy(ips = "203.0.113.7")
        assertTrue(differ(edited))
        assertNotEquals(plan(base), plan(edited))
        // an older Android has no excluded routes: the interface takes nothing from the list, the core routes by it
        assertFalse(differ(edited, sdk = 32))
        assertEquals(plan(base, sdk = 32), plan(edited, sdk = 32))
    }

    @Test fun theSameAddressesWrittenAnotherWayBuildTheSameInterface() {
        for (same in listOf("198.51.100.0/24,203.0.113.7", "203.0.113.7/32 198.51.100.9/24", "203.0.113.7\n198.51.100.0/24\nnot-an-address")) {
            assertFalse(same, differ(base.copy(ips = same)))
            assertEquals(same, plan(base).excludedRoutes.toSet(), plan(base.copy(ips = same)).excludedRoutes.toSet())
        }
    }

    @Test fun aWhiteListIsTheCoresAlone() {
        val white = VpnSettings(ipsWhitelist = true, ips = "203.0.113.7")
        // a white list edited: the interface takes nothing from it
        assertFalse(differ(white.copy(ips = "203.0.113.7,203.0.113.8"), before = white))
        assertEquals(plan(white), plan(white.copy(ips = "203.0.113.7,203.0.113.8")))
        // the mode changed with a black list that held addresses: its routes go from the interface, or come to it
        assertTrue(differ(white, before = base))
        assertTrue(differ(base, before = white))
        assertNotEquals(plan(base), plan(white))
        // with nothing in the black list the mode alone changes nothing the interface takes
        assertFalse(differ(white, before = VpnSettings(ips = "")))
        assertEquals(plan(VpnSettings(ips = "")), plan(white))
    }
}
