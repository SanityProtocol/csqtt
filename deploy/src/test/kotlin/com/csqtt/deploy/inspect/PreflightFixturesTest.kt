// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.csqtt.deploy.inspect

import com.csqtt.deploy.wire.LineCodec
import com.csqtt.deploy.wire.Report
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * inspect.sh's real reports, captured by the deploy zoo (P2-PLAN §2.2: `.campaign/zoo/cell-engine.sh … inspect`, deploy-cli
 * run as root over SSH into a fresh cloud image under QEMU, 2026-11-24), through the same parse and rules the app uses. CI's
 * image has none of ip, ss, iptables, nft or systemctl, so these are the only tests in which the shell's real output meets
 * the rules: a key the shell renames would otherwise read as missing and stop every box while CI stays green (review F7).
 */
class PreflightFixturesTest {
    /** The keys PreflightRules reads, check by check: every real report carries each one. */
    private val read = mapOf(
        "priv" to setOf("uid"),
        "running" to setOf("lock", "unit"),
        "os" to setOf("id", "version", "like", "name"),
        "init" to setOf("systemd", "pid1", "systemd_run"),
        "declarative" to setOf("kind", "etc_ro"),
        "arch" to setOf("machine"),
        "virt" to setOf("virt", "wsl", "container"),
        "tun" to setOf("dev", "probe", "error", "module"),
        "nat" to setOf("probe", "error", "iptables"),
        "forwarding" to setOf("ipv4", "writable"),
        "ipv4" to setOf("dev", "src"),
        "port80" to setOf("holder"),
        "firewall" to setOf("firewalld", "ufw", "nft_drop", "mixed", "nftables_service"),
        "pkg" to setOf("lock", "audit_lines", "missing"),
        "disk" to setOf("varlib_kb", "opt_kb"),
        "ram" to setOf("total_kb"),
        "clock" to setOf("epoch"),
        "rundir" to setOf("noexec"),
        "docker" to setOf("flavour", "rootless"),
    )

    /** Each fresh image and what it rightly warns about beyond NAT (every zoo guest sits behind QEMU's slirp). */
    private val images = mapOf(
        "debian-11-genericcloud-amd64" to emptySet(),
        "debian-12-genericcloud-amd64" to emptySet(),
        "debian-13-genericcloud-amd64" to emptySet(),
        "focal-server-cloudimg-amd64" to emptySet(),
        "jammy-server-cloudimg-amd64" to emptySet(),
        "noble" to emptySet(),
        "resolute-server-cloudimg-amd64" to emptySet(),
        // no iptables in Fedora's or Alma's cloud image: the probe cannot run, the run installs it
        "fedora-45-cloud-base" to setOf(Finding.NAT_UNTESTED, Finding.TOOLS_MISSING),
        "alma-9-nft" to setOf(Finding.NAT_UNTESTED, Finding.TOOLS_MISSING),
    )

    private fun report(name: String): Report.Complete {
        val text = checkNotNull(javaClass.getResource("fixtures/$name.report")) { "no fixture $name.report" }.readText()
        val report = LineCodec.parse(text)
        assertTrue("$name: ${(report as? Report.Broken)?.reason}", report is Report.Complete)
        return report as Report.Complete
    }

    @Test
    fun everyRealReportCarriesTheKeysTheRulesRead() {
        for (name in images.keys) {
            val pre = Preflight.from(report(name).lines)
            for ((check, keys) in read) {
                assertTrue("$name: no $check line", pre.has(check))
                // a key the shell sent empty is present; a key it never sent is missing
                val line = report(name).lines.first { it.kind == "PRE" && it.fields.firstOrNull() == check }
                val sent = line.fields.drop(1).map { it.substringBefore('=') }.toSet()
                assertTrue("$name: $check lacks ${keys - sent}", sent.containsAll(keys))
            }
        }
    }

    @Test
    fun aFreshSupportedImageIsNeverStopped() {
        for ((name, expected) in images) {
            val pre = Preflight.from(report(name).lines)
            val verdicts = PreflightRules.verdicts(pre, pre.facts("clock").long("epoch") ?: 0)
            assertEquals("$name stops: ${PreflightRules.stops(verdicts)}", emptyList<Verdict>(), PreflightRules.stops(verdicts))
            assertEquals(name, expected + Finding.BEHIND_NAT, verdicts.map { it.finding }.toSet())
            // the probes really ran as root on each, in a namespace of their own
            val tun = pre.facts("tun")
            assertEquals("$name: tun", "ok", tun["probe"])
            assertEquals("$name: probes in their own namespace", "1", tun["ns"])
            if (Finding.NAT_UNTESTED !in expected) assertEquals("$name: nat", "ok", pre.facts("nat")["probe"])
            assertNotNull(pre.facts("ram").long("total_kb"))
        }
    }
}
