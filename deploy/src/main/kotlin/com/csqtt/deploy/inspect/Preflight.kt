// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
//
// The environment, judged (CSQTT-APP-PLAN §6.4a, §6.8 "Where stopping is correct"; deploy-environments.md "Minimum preflight
// set"; P2 B3). inspect.sh reports facts as `CSQTT_PRE|<check>|key=value…`; every verdict is made here, so the rules are
// tested on fixtures and the shell stays a collector. A FAIL stops before anything changes; a WARN is shown before any run;
// an OK finding is information the run uses (the firewall tool to open ports through, the tools it installs).
package com.csqtt.deploy.inspect

import com.csqtt.deploy.wire.Line

/** One check's facts: inspect.sh's `key=value` fields, a missing key read as empty. */
class Facts(private val values: Map<String, String>) {
    operator fun get(key: String): String = values[key].orEmpty()

    fun long(key: String): Long? = values[key]?.trim()?.toLongOrNull()

    override fun toString() = values.toString()

    companion object {
        /** inspect.sh's fields, each `key=value`; a field with no `=` is not a fact and is skipped. */
        fun of(fields: List<String>): Facts = Facts(fields.mapNotNull { field ->
            val at = field.indexOf('=')
            if (at > 0) field.substring(0, at) to field.substring(at + 1) else null
        }.toMap())
    }
}

class Preflight(private val checks: Map<String, Facts>, val reach: List<Facts>) {
    fun facts(check: String): Facts = checks[check] ?: Facts(emptyMap())

    fun has(check: String): Boolean = check in checks

    companion object {
        fun from(lines: List<Line>): Preflight {
            val checks = mutableMapOf<String, Facts>()
            val reach = mutableListOf<Facts>()
            for (line in lines) {
                if (line.kind != "PRE" || line.fields.isEmpty()) continue
                val facts = Facts.of(line.fields.drop(1))
                if (line.fields[0] == "reach") reach += facts else checks[line.fields[0]] = facts
            }
            return Preflight(checks, reach)
        }
    }
}

enum class Level { OK, WARN, FAIL }

/** Every finding the environment can give. The app words them (P3); the zoo asserts them. */
enum class Finding(val level: Level) {
    NOT_ROOT(Level.FAIL),
    DEPLOY_RUNNING(Level.WARN),
    OS_UNKNOWN(Level.FAIL),
    OS_UNSUPPORTED(Level.FAIL),
    OS_ROLLING(Level.WARN),
    NO_SYSTEMD(Level.FAIL),
    NO_SYSTEMD_RUN(Level.FAIL),
    DECLARATIVE(Level.FAIL),
    ARCH_UNSUPPORTED(Level.FAIL),
    NOT_A_VPS(Level.FAIL),
    CONTAINER(Level.WARN),
    /** /dev/net/tun is missing: the app offers `modprobe tun`, or `mknod /dev/net/tun c 10 200` where the module is built in. */
    TUN_DEVICE_MISSING(Level.FAIL),
    /** The device is there and the kernel still refuses a TUN (OpenVZ/LXC without TUN): only the provider can turn it on. */
    NO_TUN(Level.FAIL),
    TUN_UNTESTED(Level.WARN),
    NO_NAT(Level.FAIL),
    NAT_UNTESTED(Level.WARN),
    FORWARDING_LOCKED(Level.FAIL),
    NO_IPV4(Level.FAIL),
    /** The uplink is named like a Docker network's bridge (br-…): the server drops new VPN flows bound for br-+ (Bsrv). */
    UPLINK_LIKE_DOCKER_BRIDGE(Level.FAIL),
    BEHIND_NAT(Level.WARN),
    PORT80_TAKEN(Level.WARN),
    FORWARD_DROPPED_BY_NFT(Level.FAIL),
    MIXED_IPTABLES(Level.FAIL),
    NFTABLES_FLUSHES(Level.WARN),
    FIREWALLD(Level.OK),
    UFW(Level.OK),
    PKG_BUSY(Level.WARN),
    PKG_HALF_CONFIGURED(Level.WARN),
    TOOLS_MISSING(Level.OK),
    DISK_LOW(Level.FAIL),
    RAM_LOW(Level.WARN),
    CLOCK_SKEW(Level.WARN),
    HOST_UNREACHABLE(Level.WARN),
    RUNDIR_NOEXEC(Level.FAIL),
    DOCKER_UNSUPPORTED(Level.WARN),
}

data class Verdict(val finding: Finding, val detail: String = "") {
    val level: Level get() = finding.level
}

object PreflightRules {
    /** Below this, a run cannot hold a core, its store, a release beside it and a certbot venv (~200 MB together). */
    const val MIN_FREE_KB = 300_000L
    const val LOW_RAM_KB = 450_000L
    const val MAX_CLOCK_SKEW_S = 300L
    private val ARCHES = setOf("x86_64", "aarch64", "armv7l")
    private val CONTAINERS = setOf("lxc", "lxc-libvirt", "openvz", "docker", "podman", "systemd-nspawn", "rkt", "wsl")

    fun verdicts(p: Preflight, nowEpochSeconds: Long): List<Verdict> {
        val out = mutableListOf<Verdict>()
        fun add(finding: Finding, detail: String = "") = out.add(Verdict(finding, detail))

        if (p.facts("priv")["uid"] != "0") add(Finding.NOT_ROOT, "uid ${p.facts("priv")["uid"]}")
        p.facts("running").also { if (it["lock"] == "held" || it["unit"].isNotEmpty()) add(Finding.DEPLOY_RUNNING, it["unit"]) }
        os(p.facts("os"))?.also { add(it.first, it.second) }
        p.facts("init").also {
            if (it["systemd"] != "1") add(Finding.NO_SYSTEMD, it["pid1"])
            else if (it["systemd_run"] != "1") add(Finding.NO_SYSTEMD_RUN)
        }
        p.facts("declarative").also { if (it["kind"].isNotEmpty() || it["etc_ro"] == "1") add(Finding.DECLARATIVE, it["kind"]) }
        p.facts("arch")["machine"].also { if (it !in ARCHES) add(Finding.ARCH_UNSUPPORTED, it) }
        p.facts("virt").also {
            if (it["wsl"] == "1") add(Finding.NOT_A_VPS, "wsl")
            else if (it["virt"] in CONTAINERS || it["container"].isNotEmpty()) add(Finding.CONTAINER, it["virt"].ifEmpty { it["container"] })
        }
        p.facts("tun").also {
            when {
                it["dev"] != "1" -> add(Finding.TUN_DEVICE_MISSING, "module ${it["module"]}")
                it["probe"] == "failed" -> add(Finding.NO_TUN, it["error"])
                it["probe"] != "ok" -> add(Finding.TUN_UNTESTED, it["probe"])
            }
        }
        p.facts("nat").also {
            when (it["probe"]) {
                "ok" -> {}
                "failed" -> add(Finding.NO_NAT, it["error"])
                else -> add(Finding.NAT_UNTESTED, if (it["iptables"] == "0") "iptables is not installed" else it["probe"])
            }
        }
        p.facts("forwarding").also { if (it["ipv4"] != "1" && it["writable"] != "1") add(Finding.FORWARDING_LOCKED) }
        p.facts("ipv4").also {
            if (it["dev"].isEmpty()) add(Finding.NO_IPV4)
            else if (isPrivate(it["src"])) add(Finding.BEHIND_NAT, it["src"])
            if (it["dev"].startsWith("br-")) add(Finding.UPLINK_LIKE_DOCKER_BRIDGE, it["dev"])
        }
        p.facts("port80")["holder"].also { if (it.isNotEmpty()) add(Finding.PORT80_TAKEN, it) }
        p.facts("firewall").also { fw ->
            // named, swg's included: none of swg-panel's forward chains drops by policy, so a box it manages stops here only
            // if one ever does (B5's classifier recognises swg's installations by themselves)
            val dropping = fw["nft_drop"].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (dropping.isNotEmpty()) add(Finding.FORWARD_DROPPED_BY_NFT, dropping.joinToString(", "))
            if (fw["mixed"] == "1") add(Finding.MIXED_IPTABLES)
            if (fw["nftables_service"] == "enabled-flushes") add(Finding.NFTABLES_FLUSHES)
            if (fw["firewalld"] == "active") add(Finding.FIREWALLD)
            if (fw["ufw"] == "active") add(Finding.UFW)
        }
        p.facts("pkg").also {
            if (it["lock"].isNotEmpty()) add(Finding.PKG_BUSY, it["lock"])
            if ((it.long("audit_lines") ?: 0) > 0) add(Finding.PKG_HALF_CONFIGURED)
            if (it["missing"].isNotEmpty()) add(Finding.TOOLS_MISSING, it["missing"].trimEnd(','))
        }
        p.facts("disk").also { disk ->
            val free = listOfNotNull(disk.long("varlib_kb"), disk.long("opt_kb")).minOrNull()
            if (free != null && free < MIN_FREE_KB) add(Finding.DISK_LOW, "${free / 1024} MB free")
        }
        p.facts("ram").long("total_kb")?.also { if (it < LOW_RAM_KB) add(Finding.RAM_LOW, "${it / 1024} MB") }
        p.facts("clock").long("epoch")?.also {
            val skew = it - nowEpochSeconds
            if (kotlin.math.abs(skew) > MAX_CLOCK_SKEW_S) add(Finding.CLOCK_SKEW, "$skew s")
        }
        // a host left unchecked by the 12 s cap (dns empty) is no finding: the run meets it, or not, with its own message
        for (host in p.reach) if (host["dns"] == "0" || host["https"] == "failed") add(Finding.HOST_UNREACHABLE, host["host"])
        p.facts("rundir")["noexec"].trimEnd(',').also { if (it.isNotEmpty()) add(Finding.RUNDIR_NOEXEC, it) }
        p.facts("docker").also {
            if (it["flavour"] == "snap" || it["flavour"] == "podman" || it["rootless"] == "1") {
                add(Finding.DOCKER_UNSUPPORTED, if (it["rootless"] == "1") "rootless" else it["flavour"])
            }
        }
        return out
    }

    fun stops(verdicts: List<Verdict>): List<Verdict> = verdicts.filter { it.level == Level.FAIL }

    /**
     * What stops a run that brings a core to an instance already there (an update, «Починить», «Восстановить»): the room for the
     * core, a release beside it and a copy of the store, and a run dir that executes (B10 review F3). Create's other stops are the
     * box's own, passed or lived with since its create, and stop none of these.
     */
    fun stopsACoreRun(verdicts: List<Verdict>): List<Verdict> =
        verdicts.filter { it.finding == Finding.DISK_LOW || it.finding == Finding.RUNDIR_NOEXEC }

    /** The support table (APP-PLAN §6.4a): Debian 11+, Ubuntu 20.04+, Alma/Rocky/RHEL/Oracle 8+, a current Fedora; Arch warned. */
    private fun os(f: Facts): Pair<Finding, String>? {
        val id = f["id"]
        val version = f["version"].split('.').map { it.toIntOrNull() ?: 0 }
        val major = version.getOrElse(0) { 0 }
        val minor = version.getOrElse(1) { 0 }
        val name = f["name"].ifEmpty { "$id ${f["version"]}" }
        return when (id) {
            "" -> Finding.OS_UNKNOWN to "no /etc/os-release"
            // testing and sid carry no VERSION_ID: rolling, like Arch
            "debian", "raspbian" -> if (f["version"].isEmpty()) Finding.OS_ROLLING to name else if (major >= 11) null
            else Finding.OS_UNSUPPORTED to name
            "ubuntu" -> if (major > 20 || (major == 20 && minor >= 4)) null else Finding.OS_UNSUPPORTED to name
            "almalinux", "rocky", "rhel", "ol", "centos" -> if (major >= 8) null else Finding.OS_UNSUPPORTED to name
            "fedora" -> if (major >= 40) null else Finding.OS_UNSUPPORTED to name
            "arch", "manjaro", "endeavouros" -> Finding.OS_ROLLING to name
            // Arch's derivatives by ID_LIKE (Arch Linux ARM's ID is archarm)
            else -> if ("arch" in f["like"].split(' ')) Finding.OS_ROLLING to name else Finding.OS_UNSUPPORTED to name
        }
    }

    internal fun isPrivate(address: String): Boolean {
        val o = address.split('.').mapNotNull { it.toIntOrNull() }
        if (o.size != 4) return false
        return o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168) || (o[0] == 100 && o[1] in 64..127)
    }
}
