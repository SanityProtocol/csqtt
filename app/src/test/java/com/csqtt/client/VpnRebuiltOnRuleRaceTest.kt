package com.csqtt.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M-58: on vpnReady the rule the interface was built with is compared with the active one. */
class VpnRebuiltOnRuleRaceTest {
    private val base = ServerRoutingRule(
        isWhite = true, packages = listOf("a.app", "b.app"), domains = listOf("x.ru"), templateDomains = false,
        ipIsWhite = false, ips = listOf("10.0.0.0/8"),
    )

    @Test fun aRuleThatArrivedDuringTheBuildRebuilds() {
        // built on the local filter (null), the server rule landed before vpnReady
        assertTrue(vpnInputsDiffer(null, base))
        // built with a server rule that was then cleared
        assertTrue(vpnInputsDiffer(base, null))
        assertTrue(vpnInputsDiffer(base, base.copy(isWhite = false)))
        assertTrue(vpnInputsDiffer(base, base.copy(packages = listOf("a.app"))))
        assertTrue(vpnInputsDiffer(base, base.copy(ipIsWhite = true)))
        assertTrue(vpnInputsDiffer(base, base.copy(ips = listOf("10.0.0.0/8", "192.168.0.0/16"))))
        assertTrue(vpnInputsDiffer(base, base.copy(packagesListed = false, packages = emptyList())))
    }

    @Test fun whatTheInterfaceDoesNotCarryNeverRebuilds() {
        assertFalse(vpnInputsDiffer(null, null))
        assertFalse(vpnInputsDiffer(base, base.copy()))
        assertFalse(vpnInputsDiffer(base, base.copy(packages = listOf("b.app", "a.app"))))   // order
        assertFalse(vpnInputsDiffer(base, base.copy(domains = listOf("y.ru"), templateDomains = true)))   // the core's part
        val white = base.copy(ipIsWhite = true)
        assertFalse(vpnInputsDiffer(white, white.copy(ips = listOf("1.1.1.1"))))   // a whitelist's addresses make no route
    }
}
