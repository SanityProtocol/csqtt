// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.csqtt.deploy.inspect

import com.csqtt.deploy.bundle.Bundle
import com.csqtt.deploy.wire.LineCodec
import com.csqtt.deploy.wire.Report
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class PreflightTest {
    private val now = 1_795_497_915L

    /** A root Ubuntu 24.04 KVM VPS with a public address and nothing in the way: no finding above OK. */
    private val clean = mapOf(
        "priv" to "uid=0", "running" to "lock=free|unit=",
        "os" to "id=ubuntu|version=24.04|like=debian|name=Ubuntu 24.04.3 LTS|kernel=6.8.0-88-generic",
        "init" to "systemd=1|version=255|pid1=systemd|systemd_run=1", "declarative" to "kind=|etc_ro=0", "arch" to "machine=x86_64",
        "virt" to "virt=kvm|wsl=0|container=", "tun" to "dev=1|probe=ok|error=|module=loaded",
        "nat" to "probe=ok|error=|iptables=1", "forwarding" to "ipv4=0|writable=1",
        "ipv4" to "dev=eth0|via=203.0.113.1|src=203.0.113.20|addrs=203.0.113.20/24 ", "port80" to "holder=|ss=1",
        "firewall" to "firewalld=inactive|ufw=inactive|backend=nft|docker_forward=0|nft_drop=|mixed=0|nftables_service=",
        "pkg" to "manager=apt|lock=|audit_lines=0|missing=", "disk" to "root_kb=20000000|varlib_kb=20000000|opt_kb=20000000|docker_kb=",
        "ram" to "total_kb=2000000|available_kb=1500000|swap_kb=0", "clock" to "epoch=$now|ntp=yes", "rundir" to "noexec=",
        "docker" to "bin=|version=|flavour=|enabled=|rootless=0",
    )

    private fun report(checks: Map<String, String>, vararg reach: String): String =
        (checks.map { (check, facts) -> "CSQTT_PRE|$check|$facts" } + reach.map { "CSQTT_PRE|reach|$it" })
            .let { it.joinToString("\n") + "\nCSQTT_END|${it.size}\n" }

    private fun findings(checks: Map<String, String>, vararg reach: String): Set<Finding> {
        val parsed = LineCodec.parse(report(checks, *reach)) as Report.Complete
        return PreflightRules.verdicts(Preflight.from(parsed.lines), now).map { it.finding }.toSet()
    }

    private fun with(check: String, facts: String) = clean + (check to facts)

    @Test
    fun aCleanVpsHasNothingToSay() {
        assertEquals(emptySet<Finding>(), findings(clean))
    }

    @Test
    fun eachCheckSaysWhatItSaw() {
        val cases = listOf(
            with("priv", "uid=1000") to Finding.NOT_ROOT,
            with("running", "lock=held|unit=sanity-csqtt-deploy-r1.service") to Finding.DEPLOY_RUNNING,
            with("os", "id=|version=|like=|name=") to Finding.OS_UNKNOWN,
            with("os", "id=debian|version=10|like=|name=Debian 10") to Finding.OS_UNSUPPORTED,
            with("os", "id=ubuntu|version=18.04|like=debian|name=Ubuntu 18.04") to Finding.OS_UNSUPPORTED,
            with("os", "id=centos|version=7|like=rhel|name=CentOS 7") to Finding.OS_UNSUPPORTED,
            with("os", "id=alpine|version=3.21.0|like=|name=Alpine") to Finding.OS_UNSUPPORTED,
            with("os", "id=arch|version=|like=|name=Arch Linux") to Finding.OS_ROLLING,
            with("os", "id=raspbian|version=10|like=debian|name=Raspbian 10") to Finding.OS_UNSUPPORTED,
            with("init", "systemd=0|version=|pid1=init|systemd_run=0") to Finding.NO_SYSTEMD,
            with("init", "systemd=1|version=219|pid1=systemd|systemd_run=0") to Finding.NO_SYSTEMD_RUN,
            with("declarative", "kind=nixos|etc_ro=0") to Finding.DECLARATIVE,
            with("declarative", "kind=|etc_ro=1") to Finding.DECLARATIVE,
            with("arch", "machine=mips") to Finding.ARCH_UNSUPPORTED,
            with("virt", "virt=wsl|wsl=1|container=") to Finding.NOT_A_VPS,
            with("virt", "virt=openvz|wsl=0|container=") to Finding.CONTAINER,
            with("tun", "dev=0|probe=failed|error=open /dev/net/tun: No such file|module=absent") to Finding.TUN_DEVICE_MISSING,
            with("tun", "dev=1|probe=failed|error=ioctl(TUNSETIFF): Operation not permitted|module=absent") to Finding.NO_TUN,
            with("tun", "dev=1|probe=unsupported|error=|module=absent") to Finding.TUN_UNTESTED,
            with("nat", "probe=failed|error=can't initialize iptables table `nat'|iptables=1") to Finding.NO_NAT,
            with("nat", "probe=skipped|error=|iptables=0") to Finding.NAT_UNTESTED,
            with("forwarding", "ipv4=0|writable=0") to Finding.FORWARDING_LOCKED,
            with("ipv4", "dev=|via=|src=|addrs=") to Finding.NO_IPV4,
            with("ipv4", "dev=eth0|via=10.0.0.1|src=10.0.0.5|addrs=10.0.0.5/24 ") to Finding.BEHIND_NAT,
            with("ipv4", "dev=eth0|via=100.64.0.1|src=100.70.1.2|addrs=") to Finding.BEHIND_NAT,
            with("ipv4", "dev=br-wan|via=203.0.113.1|src=203.0.113.20|addrs=") to Finding.UPLINK_LIKE_DOCKER_BRIDGE,
            with("port80", "holder=nginx|ss=1") to Finding.PORT80_TAKEN,
            with("firewall", "firewalld=inactive|ufw=absent|backend=nft|docker_forward=0|nft_drop=inet swg_fwd,|mixed=0|nftables_service=")
                to Finding.FORWARD_DROPPED_BY_SWG,
            with("firewall", "firewalld=inactive|ufw=absent|backend=nft|docker_forward=0|nft_drop=inet filter,|mixed=0|nftables_service=")
                to Finding.FORWARD_DROPPED_BY_NFT,
            with("firewall", "firewalld=inactive|ufw=absent|backend=nft|docker_forward=1|nft_drop=|mixed=1|nftables_service=")
                to Finding.MIXED_IPTABLES,
            with("firewall", "firewalld=inactive|ufw=absent|backend=nft|docker_forward=0|nft_drop=|mixed=0|nftables_service=enabled-flushes")
                to Finding.NFTABLES_FLUSHES,
            with("firewall", "firewalld=active|ufw=absent|backend=nft|docker_forward=0|nft_drop=|mixed=0|nftables_service=")
                to Finding.FIREWALLD,
            with("firewall", "firewalld=inactive|ufw=active|backend=nft|docker_forward=0|nft_drop=|mixed=0|nftables_service=")
                to Finding.UFW,
            with("pkg", "manager=apt|lock=1234:unattended-upgr|audit_lines=0|missing=") to Finding.PKG_BUSY,
            with("pkg", "manager=apt|lock=|audit_lines=3|missing=") to Finding.PKG_HALF_CONFIGURED,
            with("pkg", "manager=dnf|lock=|audit_lines=0|missing=iptables,") to Finding.TOOLS_MISSING,
            with("disk", "root_kb=200000|varlib_kb=200000|opt_kb=200000|docker_kb=") to Finding.DISK_LOW,
            with("disk", "root_kb=20000000|varlib_kb=20000000|opt_kb=100000|docker_kb=") to Finding.DISK_LOW,
            with("ram", "total_kb=400000|available_kb=100000|swap_kb=0") to Finding.RAM_LOW,
            with("clock", "epoch=${now + 900}|ntp=no") to Finding.CLOCK_SKEW,
            with("rundir", "noexec=/opt,") to Finding.RUNDIR_NOEXEC,
            with("docker", "bin=/snap/docker/current/bin/docker|version=24.0.5|flavour=snap|enabled=|rootless=0")
                to Finding.DOCKER_UNSUPPORTED,
            with("docker", "bin=/usr/bin/docker|version=27.1|flavour=docker|enabled=enabled|rootless=1") to Finding.DOCKER_UNSUPPORTED,
        )
        for ((checks, finding) in cases) assertEquals("$finding", setOf(finding), findings(checks))
        assertEquals(setOf(Finding.HOST_UNREACHABLE), findings(clean, "host=github.com|dns=0|https=skipped"))
        assertEquals(setOf(Finding.HOST_UNREACHABLE), findings(clean, "host=acme-v02.api.letsencrypt.org|dns=1|https=failed"))
        assertEquals(emptySet<Finding>(), findings(clean, "host=github.com|dns=1|https=ok"))
        // Raspberry Pi OS (32-bit) is Debian under another name
        assertEquals(emptySet<Finding>(), findings(with("os", "id=raspbian|version=12|like=debian|name=Raspbian 12")))
    }

    @Test
    fun theStopsAreTheFails() {
        val parsed = LineCodec.parse(report(with("tun", "dev=1|probe=failed|error=x|module=absent") + with("port80", "holder=nginx|ss=1")
            .filterKeys { it == "port80" })) as Report.Complete
        val verdicts = PreflightRules.verdicts(Preflight.from(parsed.lines), now)
        assertEquals(listOf(Finding.NO_TUN), PreflightRules.stops(verdicts).map { it.finding })
        assertTrue(verdicts.any { it.finding == Finding.PORT80_TAKEN })
    }

    @Test
    fun inspectShRunsWhereverItIsAndAlwaysEnds() {
        // the collector itself on this machine's sh (dash in CI), unprivileged: every check reports, the end line counts
        val dir = kotlin.io.path.createTempDirectory("csqtt-inspect").toFile()
        try {
            val script = File(dir, "inspect.sh").apply { writeBytes(Bundle.bytes("lib/marker.sh") + Bundle.bytes("inspect.sh")) }
            val out = File(dir, "out")
            val process = ProcessBuilder("sh", script.path).directory(dir).redirectOutput(out)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            process.outputStream.close()
            assertTrue("inspect.sh did not finish in 60 s", process.waitFor(60, TimeUnit.SECONDS))
            val report = LineCodec.parse(out.readText()) as Report.Complete
            val preflight = Preflight.from(report.lines)
            for (check in listOf("priv", "running", "os", "init", "declarative", "arch", "virt", "tun", "nat", "forwarding", "ipv4",
                "port80", "firewall", "pkg", "disk", "ram", "clock", "rundir", "docker")) {
                assertTrue("no $check line", preflight.has(check))
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
