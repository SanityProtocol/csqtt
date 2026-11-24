// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
//
// The installations on a VPS, from inspect.sh's five views (CSQTT-APP-PLAN §6.4b; server-footprints.md §3–§4; P2 B5): the
// views joined into installations (config dir → binary → unit or container → interface → UDP port), each with its owner
// (first match wins), its build, its runtime and state, the broken states it shows and what it claims; then the problems of
// the box itself. Pure: everything comes from the Inventory, nothing from the box.
package com.csqtt.deploy.inspect

/** Who an installation belongs to, in the order the first match wins (APP-PLAN §6.4b step 3). */
enum class Owner { SWG_BARE, SWG_DOCKER, OURS, APP_SIDE, SIDE_TOOL, MRTT_BETA, APP_STANDARD, MRTT, HANDMADE, UNKNOWN }

/** The protocol line a build speaks, which decides what a 2.6 app can do with it (§1.3 "compatibility"). */
enum class ProtocolLine { V20, V21, V25, V26, MRTT, UNKNOWN }

enum class Runtime { SYSTEMD, DOCKER, PROCESS, NONE }

enum class InstallState { RUNNING, CRASH_LOOPING, STOPPED, FAILED, DORMANT, LEFTOVER }

/** A build, from the strings in its binary (never by running it), or failing that from its image tag or its store. */
data class Build(val version: String, val line: ProtocolLine, val sanityBuild: Boolean, val swgFlags: Boolean, val source: String)

/** What an installation holds, running or not: a stopped stock install still kills a port's holder at its next start. */
data class Claims(
    val udpPort: Int?,
    val webPort: Int?,
    val iface: String?,
    val subnet: String?,
    val configDir: String?,
    val names: Set<String>,
)

data class Installation(
    val owner: Owner,
    val build: Build?,
    val runtime: Runtime,
    val state: InstallState,
    /** Why the state is what it is, in a few words: «binary missing», «network-up exit 21», «7 restarts». */
    val detail: String,
    /** server-footprints.md §4's codes that this installation shows (B1, B3…). */
    val broken: Set<String>,
    val claims: Claims,
    val units: List<UnitView>,
    val containers: List<Ctr>,
    val procs: List<Proc>,
    val stores: List<Store>,
) {
    /** Nothing runs it that the inspection could see, and Docker, which may, was stopped (B5 review F1): B4, never a leftover. */
    val dockerUnseen: Boolean get() = "B4" in broken && containers.isEmpty() && units.isEmpty() && procs.isEmpty()

    /** What the list calls it: its unit or container, else its config dir. */
    val name: String
        get() = units.firstOrNull()?.id ?: containers.firstOrNull()?.name ?: stores.firstOrNull()?.dir ?: procs.firstOrNull()?.exe ?: "?"
}

/** A problem of the box rather than of one installation (B9 two servers on one port, B14 a prereq without its helper…). */
data class BoxProblem(val code: String, val detail: String)

class Classification(val installations: List<Installation>, val problems: List<BoxProblem>) {
    /** Docker is installed and was not asked (stopped): an installation it may hold looks like a leftover, and is not one. */
    val dockerUnseen: Boolean get() = installations.any { it.dockerUnseen }
}

object Classifier {
    private val FAMILY = Regex("csqtt|mrtt")
    private val HELPER_UNIT = Regex("-docker-prereq\\.service$|-letsencrypt\\.(service|timer)$|\\.timer$|-panel-restart-|^mrtt-exit-|-cascade\\.service$")

    /** MRTT's cascade exit client (cascade.sh): a TUN holder of the family's name, never a server (B5 review F9). */
    private fun isServerExe(exe: String): Boolean = exe.substringAfterLast('/').let { FAMILY.containsMatchIn(it) && !it.startsWith("mrtt-exit") }

    fun classify(inv: Inventory): Classification {
        val groups = Groups()
        val serverUnits = inv.units.filter { isServerUnit(it) }
        val serverProcs = inv.procs.filter { isServerExe(it.exe) }
        // a server's container; a foreign TUN holder's (Amnezia's OpenVPN, amneziawg-go, Tailscale) is no installation (F7)
        val containers = inv.containers.filter { FAMILY.containsMatchIn(it.name + " " + it.image) || it.name.startsWith("sanity-csqtt-") ||
            it.name == "swg-node" || serverProcs.any { p -> p.container.isNotEmpty() && it.id.startsWith(p.container) } }
        val stores = inv.stores

        stores.forEach { groups.add("store:${it.dir}") }
        serverUnits.forEach { unit ->
            groups.add("unit:${unit.id}")
            configDir(unit.flags, unit.exec)?.let { dir -> stores.find { it.dir == dir }?.let { groups.union("unit:${unit.id}", "store:${it.dir}") } }
        }
        containers.forEach { ctr ->
            groups.add("ctr:${ctr.id}")
            hostDir(ctr, configDir(ctr.flags, ctr.image))?.let { dir -> stores.find { it.dir == dir }?.let { groups.union("ctr:${ctr.id}", "store:${it.dir}") } }
            // a container's own host mounts that hold a store
            ctr.mounts.forEach { (source, _) -> stores.find { it.dir == source }?.let { groups.union("ctr:${ctr.id}", "store:${it.dir}") } }
        }
        serverProcs.forEach { proc ->
            groups.add("proc:${proc.pid}")
            val ctr = containers.find { proc.container.isNotEmpty() && it.id.startsWith(proc.container) }
            when {
                ctr != null -> groups.union("proc:${proc.pid}", "ctr:${ctr.id}")
                proc.unit.isNotEmpty() && serverUnits.any { it.id == proc.unit } -> groups.union("proc:${proc.pid}", "unit:${proc.unit}")
            }
            val dir = configDir(proc.flags, proc.exe).let { if (ctr != null) hostDir(ctr, it) else it }
            dir?.let { d -> stores.find { it.dir == d }?.let { groups.union("proc:${proc.pid}", "store:${it.dir}") } }
        }

        val installations = groups.sets().map { members ->
            build(inv, members.mapNotNull { m -> m.removePrefix("unit:").takeIf { m.startsWith("unit:") } }.mapNotNull { id -> serverUnits.find { it.id == id } },
                members.filter { it.startsWith("ctr:") }.mapNotNull { m -> containers.find { it.id == m.removePrefix("ctr:") } },
                members.filter { it.startsWith("proc:") }.mapNotNull { m -> serverProcs.find { it.pid.toString() == m.removePrefix("proc:") } },
                members.filter { it.startsWith("store:") }.mapNotNull { m -> stores.find { it.dir == m.removePrefix("store:") } })
        }.sortedBy { it.name }
        return Classification(installations, boxProblems(inv, installations))
    }

    private fun isServerUnit(unit: UnitView): Boolean {
        if (HELPER_UNIT.containsMatchIn(unit.id)) return false
        val exe = unit.exec.substringAfterLast('/')
        return FAMILY.containsMatchIn(exe) || unit.flags.configDir != null || unit.flags.has("--listen")
    }

    /** The config dir a server uses: its --config-dir, or its family's default (stock /etc/csqtt, MRTT /etc/mrtt). */
    private fun configDir(flags: ServerFlags, exeOrImage: String): String? = flags.configDir ?: when {
        exeOrImage.substringAfterLast('/').startsWith("mrtt") -> "/etc/mrtt"
        exeOrImage.substringAfterLast('/').startsWith("csqtt") -> "/etc/csqtt"
        else -> null
    }

    /** A container's in-container path on the host, through its bind mounts (the longest destination that contains it). */
    private fun hostDir(ctr: Ctr, inside: String?): String? {
        inside ?: return null
        val mount = ctr.mounts.filter { (_, dest) -> inside == dest || inside.startsWith("$dest/") }.maxByOrNull { it.second.length } ?: return null
        return mount.first + inside.removePrefix(mount.second)
    }

    private fun build(inv: Inventory, units: List<UnitView>, containers: List<Ctr>, procs: List<Proc>, stores: List<Store>): Installation {
        val dirs = stores.map { it.dir }.toSet()
        // a store of ours kept by «Удалить» runs nothing: the flags it ran with are its claims, so no new instance takes its ports,
        // interface, subnet or slot and «Восстановить» stays possible (B11a review F3)
        val flags = procs.firstOrNull()?.flags ?: units.firstOrNull()?.flags ?: containers.firstOrNull()?.flags
            ?: stores.firstNotNullOfOrNull { it.keptFlags } ?: ServerFlags(emptyMap())
        val exes = procs.mapNotNull { inv.exes[it.sha] } + units.mapNotNull { unit -> inv.exeAt(unit.exec) }
        val exe = exes.firstOrNull()
        val side = units.any { it.id == "csqtt25.service" } || containers.any { it.name == "csqtt25" } || "/etc/csqtt25" in dirs ||
            flags["--instance"] == "csqtt25"
        val stockMain = units.any { it.id == "csqtt.service" } || containers.any { it.name == "csqtt" } || "/etc/csqtt" in dirs
        val mrtt = units.any { it.id == "mrtt.service" } || containers.any { it.name == "mrtt" } || dirs.any { it.startsWith("/etc/mrtt") } ||
            exes.any { it.mrtt } || procs.any { it.exe.substringAfterLast('/').startsWith("mrtt") }
        val schema = stores.flatMap { it.schema }.toSet()
        // MRTT's beta under the stock names: its store is decisive alone (its binary may be gone, its container unseen); its
        // install log only with a label-less binary beside it (B5 review F14)
        val mrttBeta = stockMain && ("devices_json" in schema || (exe != null && exe.label.isEmpty() && !exe.mrtt &&
            inv.files.any { it.path == "/var/log/csqtt-install.log" && "v1.0-beta" in it.facts["last_run"] }))
        val owner = when {
            units.any { it.id.startsWith("swg-csqtt-") } || dirs.any { it.startsWith("/opt/swg-csqtt/") } ||
                (flags.has("--desired") && containers.isEmpty()) -> Owner.SWG_BARE
            containers.any { it.name == "swg-node" } ||
                dirs.any { it.startsWith("/var/lib/swg-noded/csqtt/") || it.startsWith("/opt/swg-panel-docker/data/node/csqtt/") } -> Owner.SWG_DOCKER
            units.any { it.id.startsWith("sanity-csqtt-") } || containers.any { it.name.startsWith("sanity-csqtt-") } ||
                dirs.any { it.startsWith("/var/lib/sanityprotocol/csqtt/") } ||
                (procs.map { it.exe } + units.map { it.exec }).any { it.startsWith("/opt/sanityprotocol/") } -> Owner.OURS
            side -> if (flags.has("--no-nat")) Owner.SIDE_TOOL else Owner.APP_SIDE
            mrttBeta -> Owner.MRTT_BETA
            stockMain -> Owner.APP_STANDARD
            mrtt -> Owner.MRTT
            exe != null && (exe.label.isNotEmpty() || exe.startup.isNotEmpty() || exe.swgFlags) -> Owner.HANDMADE
            else -> Owner.UNKNOWN
        }
        val build = buildOf(exe, containers, schema, owner == Owner.MRTT || owner == Owner.MRTT_BETA,
            stores.firstNotNullOfOrNull { it.keptRelease.ifEmpty { null } }.orEmpty())
        val runtime = when {
            units.isNotEmpty() -> Runtime.SYSTEMD
            containers.isNotEmpty() -> Runtime.DOCKER
            procs.isNotEmpty() -> Runtime.PROCESS
            else -> Runtime.NONE
        }
        val (unitState, unitDetail) = stateOf(runtime, units.firstOrNull(), containers.firstOrNull())
        // what serves wins over what its unit says: a running container beside a dead unit (B16), a server started by hand
        // (B5 review F13). Each process on its own (verify N2): one inside a container serves only while its container runs,
        // so a crash-looping container's server caught between two restarts is the crash loop it is
        val serving = containers.any { it.state == "running" } || procs.any { it.container.isEmpty() }
        val (state0, detail0) = when {
            unitState in setOf(InstallState.RUNNING, InstallState.CRASH_LOOPING) -> unitState to unitDetail
            serving -> InstallState.RUNNING to "serving outside its unit"
            containers.any { it.state == "restarting" } ->
                InstallState.CRASH_LOOPING to "container ${containers.first { it.state == "restarting" }.restarts ?: 0} restarts"
            else -> unitState to unitDetail
        }
        // Docker installed and stopped: a store nothing visible runs, with a sign of a Docker install, may be its container's
        // waiting for the daemon (F1); with none it is the leftover it looks like (verify N1)
        val unseen = runtime == Runtime.NONE && inv.dockerStopped && stores.isNotEmpty() && dockerMode(inv, stores)
        val (state, detail) = if (unseen) InstallState.STOPPED to "Docker остановлен" else state0 to detail0
        val broken = brokenOf(inv, owner, build, state, units, containers, stores, unseen)
        return Installation(owner, build, runtime, state, detail, broken, claimsOf(owner, flags, procs, units, containers, stores),
            units, containers, procs, stores)
    }

    /**
     * A sign that a store belongs to a Docker install: the family's prereq unit for it (or docker.service's link to one),
     * which a live Docker-mode 2.1.9+/2.5.x install keeps and its uninstall removes, or a 2.0.0 Docker install's docker dir.
     */
    private fun dockerMode(inv: Inventory, stores: List<Store>): Boolean = stores.any { store ->
        val prereq = "${store.dir.substringAfterLast('/')}-docker-prereq.service"
        "docker" in store.files || inv.units.any { it.id == prereq } || prereq in inv.dockerRequires || prereq in inv.dockerWants
    }

    private fun buildOf(exe: Exe?, containers: List<Ctr>, schema: Set<String>, mrtt: Boolean, keptRelease: String): Build? {
        if (mrtt) return Build(exe?.label?.removePrefix("v")?.ifEmpty { null } ?: exe?.startup?.substringBefore(' ').orEmpty(),
            ProtocolLine.MRTT, false, false, "MRTT")
        if (exe != null) {
            val sanity = exe.caption || "SanityProtocol build" in exe.startup
            val startup = exe.startup.substringBefore(" (").trim()
            val version = exe.label.removePrefix("v").ifEmpty { startup }
            if (version.isNotEmpty()) {
                return Build(version, lineOf(version), sanity, exe.swgFlags, if (exe.label.isNotEmpty()) "label" else "startup string")
            }
        }
        // a stopped container's binary is not on the host: its image's tag (csqtt:2.5.3, csqtt25:2.5.3)
        containers.map { it.image.substringAfterLast(':', "") }.firstOrNull { it.isNotEmpty() && it[0].isDigit() }?.let { tag ->
            val version = tag.substringBefore('-')
            return Build(version, lineOf(version), false, false, "image tag")
        }
        // a store of ours kept by «Удалить»: the release it ran (`<version>-<sha8>`), so no older core restores it (M-150, B11a
        // review F6)
        keptRelease.substringBefore('-').takeIf { it.isNotEmpty() && it[0].isDigit() }?.let { version ->
            return Build(version, lineOf(version), true, true, "kept release")
        }
        // the store's schema: client_groups is a 2.5 server's (a 2.1 store the app updated keeps 2.1's devices and device_id
        // beside it, B5 review F3), devices or device_id alone the 2.1 line's. A line, and no version: one made up here would
        // compare as older than ours and invite a «Починить» that downgrades (F4)
        return when {
            "table:client_groups" in schema -> Build("", ProtocolLine.V25, false, false, "store")
            "table:devices" in schema || "device_id" in schema -> Build("", ProtocolLine.V21, false, false, "store")
            else -> null
        }
    }

    private fun lineOf(version: String): ProtocolLine = when {
        version.startsWith("2.0") -> ProtocolLine.V20
        version.startsWith("2.1") -> ProtocolLine.V21
        version.startsWith("2.5") -> ProtocolLine.V25
        version.startsWith("2.6") -> ProtocolLine.V26
        else -> ProtocolLine.UNKNOWN
    }

    private fun stateOf(runtime: Runtime, unit: UnitView?, ctr: Ctr?): Pair<InstallState, String> = when (runtime) {
        Runtime.SYSTEMD -> unit!!.let { u ->
            when {
                u.active == "active" && u.sub == "running" -> InstallState.RUNNING to ""
                u.active == "activating" && (u.sub == "auto-restart" || (u.restarts ?: 0) > 0) ->
                    InstallState.CRASH_LOOPING to "${u.restarts ?: 0} restarts"
                u.active == "failed" || u.result !in setOf("", "success") -> InstallState.FAILED to failure(u)
                u.fileState == "disabled" -> InstallState.DORMANT to "disabled"
                else -> InstallState.STOPPED to ""
            }
        }
        Runtime.DOCKER -> ctr!!.let { c ->
            when (c.state) {
                "running" -> InstallState.RUNNING to ""
                "restarting" -> InstallState.CRASH_LOOPING to "${c.restarts ?: 0} restarts"
                "exited" -> if (c.exitCode == 0) InstallState.STOPPED to "" else InstallState.FAILED to "exit ${c.exitCode}"
                else -> InstallState.FAILED to c.state
            }
        }
        Runtime.PROCESS -> InstallState.RUNNING to "no unit or container"
        Runtime.NONE -> InstallState.LEFTOVER to ""
    }

    /** systemd's ExecMainStatus 203 is a binary that is not there; an ExecStartPre's 20–22 network-up's, 23–24 tun-recover's. */
    private fun failure(u: UnitView): String = when {
        u.mainStatus == 203 || u.execPresent == false -> "binary missing"
        u.preStatus in 20..22 -> "network-up exit ${u.preStatus}"
        u.preStatus in 23..24 -> "tun-recover exit ${u.preStatus}"
        else -> listOfNotNull(u.result.ifEmpty { null }, u.mainStatus?.let { "status $it" }).joinToString(", ")
    }

    private fun brokenOf(inv: Inventory, owner: Owner, build: Build?, state: InstallState, units: List<UnitView>,
        containers: List<Ctr>, stores: List<Store>, dockerUnseen: Boolean): Set<String> {
        val codes = linkedSetOf<String>()
        val unit = units.firstOrNull()
        if (unit != null && (unit.execPresent == false || unit.mainStatus == 203)) codes += "B1"
        // B3: a crash loop, or one that ran into systemd's start limit (failed after restarts); a missing binary (B1) is
        // its own cause and needs no second name
        val restarted = unit != null && (unit.restarts ?: 0) > 0
        if ("B1" !in codes && units.isNotEmpty() && (state == InstallState.CRASH_LOOPING || (state == InstallState.FAILED && restarted))) {
            codes += "B3"
        }
        // B2: the family's binary on disk with no unit or container to run it (an install cut off between the two); not while
        // Docker, which may run it, is stopped (a Docker install keeps its binary on the host too)
        if (!dockerUnseen && units.isEmpty() && containers.isEmpty() && inv.files.any { it.path == binaryOf(owner, stores) && it.type == "f" }) {
            codes += "B2"
        }
        if (dockerUnseen || containers.any { it.state != "running" }) codes += "B4"
        val stock = owner in setOf(Owner.APP_STANDARD, Owner.APP_SIDE, Owner.SIDE_TOOL)
        stores.forEach { store ->
            if (stock && "csqtt.db" in store.files && store.envKeys.none { it.endsWith("WEB_PASS") }) codes += "B5"
            if ("deploy-overrides.json" in store.files) codes += "B6"
            // B7: a 2.1 binary that will not run on a store a 2.5 server made or ran (client_groups is 2.5's). Its first start
            // adds 2.1's devices and device_id before it fails (the zoo, 2026-11-24), so the schema alone cannot tell, and a
            // 2.1-made store that went through 2.5 still runs under 2.1: hence a failure, never a clean stop (B5 review F3), and
            // a 2.1 binary, never a line read off the store itself
            if (build?.line == ProtocolLine.V21 && build.source != "store" && "table:client_groups" in store.schema &&
                state in setOf(InstallState.FAILED, InstallState.CRASH_LOOPING)) codes += "B7"
            if ("csqtt.db-wal" in store.files && "csqtt.db" !in store.files) codes += "B8"
        }
        if (units.any { it.id == "csqtt.service" } && containers.any { it.name == "csqtt" }) codes += "B16"
        if (unit?.preStatus == 21) codes += "B18"
        if (owner == Owner.MRTT && stores.any { it.dir.startsWith("/etc/mrtt") } && units.isEmpty() && containers.isEmpty() &&
            inv.files.none { it.path == "/usr/local/bin/mrtt" }) codes += "B22"
        return codes
    }

    /** Where the family's binary lives, by owner (server-footprints.md §1.6, §1.11). */
    private fun binaryOf(owner: Owner, stores: List<Store>): String? = when (owner) {
        Owner.APP_STANDARD, Owner.MRTT_BETA -> "/usr/local/bin/csqtt"
        Owner.APP_SIDE, Owner.SIDE_TOOL -> "/usr/local/lib/csqtt25/csqtt25"
        Owner.MRTT -> "/usr/local/bin/mrtt"
        else -> null
    }

    private fun claimsOf(owner: Owner, flags: ServerFlags, procs: List<Proc>, units: List<UnitView>, containers: List<Ctr>,
        stores: List<Store>): Claims {
        val side = owner == Owner.APP_SIDE || owner == Owner.SIDE_TOOL
        val defaultPort = if (side) 46100 else 46000
        val iface = flags["--iface"] ?: procs.flatMap { it.tuns }.firstOrNull() ?: when {
            owner == Owner.MRTT -> "mrtt1"
            side -> "csqtt25"
            owner in setOf(Owner.APP_STANDARD, Owner.MRTT_BETA) -> "csqtt1"
            else -> null
        }
        val subnet = flags["--tun-addr"]?.let { cidr ->
            val parts = cidr.substringBefore('/').split('.')
            if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}.0/24" else null
        } ?: when {
            side -> "10.66.68.0/24"
            owner in setOf(Owner.APP_STANDARD, Owner.MRTT, Owner.MRTT_BETA) -> "10.66.67.0/24"
            else -> null
        }
        return Claims(
            udpPort = flags.udpPort ?: if (owner in setOf(Owner.APP_STANDARD, Owner.APP_SIDE, Owner.SIDE_TOOL, Owner.MRTT, Owner.MRTT_BETA)) defaultPort else null,
            webPort = flags.webPort ?: if (owner in setOf(Owner.APP_STANDARD, Owner.APP_SIDE, Owner.SIDE_TOOL, Owner.MRTT_BETA)) defaultPort + 2 else null,
            iface = iface,
            subnet = subnet,
            configDir = stores.firstOrNull()?.dir,
            names = (units.map { it.id } + containers.map { it.name } + containers.map { it.image }).toSet(),
        )
    }

    private fun boxProblems(inv: Inventory, installs: List<Installation>): List<BoxProblem> {
        val problems = mutableListOf<BoxProblem>()
        // B9: two servers on one UDP port (SO_REUSEADDR lets the second bind; one of them gets no clients). The family's ports
        // only (a claim, or a family server's listener): Docker's proxy per address family and dnsmasq beside resolved are no
        // conflict of ours; each holder once, and only where their addresses overlap (B5 review F8)
        val serverPids = installs.flatMap { it.procs }.map { it.pid }.toSet()
        // claims of what can start (a unit or a container), and what runs (F12: a leftover store runs nothing)
        val live = installs.filter { it.units.isNotEmpty() || it.containers.isNotEmpty() || it.procs.isNotEmpty() }
        val familyPorts = live.mapNotNull { it.claims.udpPort }.toSet() +
            inv.listens.filter { it.proto == "udp" && it.pid in serverPids }.mapNotNull { it.port }
        inv.listens.filter { it.proto == "udp" && it.pid != null && it.port in familyPorts }.groupBy { it.port }.forEach { (port, all) ->
            val holders = all.distinctBy { it.pid }
            val clash = holders.indices.any { i -> (i + 1 until holders.size).any { j -> overlap(holders[i].addr, holders[j].addr) } }
            if (holders.size > 1 && clash) problems += BoxProblem("B9", "UDP $port: ${holders.joinToString { "${it.comm}(${it.pid})" }}")
        }
        // B10: a claimed UDP port held by a program that is not one of these servers, or by no visible pid
        live.mapNotNull { it.claims.udpPort }.distinct().forEach { port ->
            inv.listens.filter { it.proto == "udp" && it.port == port && it.pid !in serverPids }.forEach { foreign ->
                problems += BoxProblem("B10", "UDP $port held by ${foreign.comm.ifEmpty { "a process in another namespace" }}")
            }
        }
        // B11: an install log whose last run neither finished nor removed, and was an install: a status run writes a header and
        // no step (the side tool's own instructions run one after installing; B5 review F11). A report from before the
        // steps fact goes by the header alone
        inv.files.filter { it.path.endsWith("-install.log") && it.facts["finished"] == "0" && it.facts["runs"] != "0" &&
            (it.facts["steps"].isEmpty() || it.facts["steps"] != "0") }.forEach {
            problems += BoxProblem("B11", "${it.path}: ${it.facts["last_run"]} did not finish (${it.facts["failed_steps"]} steps failed)")
        }
        // B14/B24: a unit docker.service requires whose helper is gone takes Docker down at the next boot
        inv.dockerRequires.forEach { required ->
            inv.units.find { it.id == required && it.execPresent == false }?.let {
                problems += BoxProblem(if (required.startsWith("mrtt")) "B24" else "B14", "$required: ${it.exec} is missing")
            }
        }
        // B15: swg's instance on the stock port while the stock install still has its renewal timer on
        val swgOnStock = installs.any { it.owner == Owner.SWG_BARE && it.claims.udpPort == 46000 }
        if (swgOnStock && installs.any { it.owner == Owner.APP_STANDARD && it.state in setOf(InstallState.DORMANT, InstallState.STOPPED) } &&
            inv.units.any { it.id == "csqtt-letsencrypt.timer" && it.active == "active" }) {
            problems += BoxProblem("B15", "swg-panel took over the stock install, whose certificate timer still runs")
        }
        // B17: csqtt1 held by a program that is not the stock server, on a box with a stock install, whose every start deletes
        // csqtt1 (a box with no stock install has nobody to take it away)
        // (F12: a stock install that can start: a store left by the uninstall deletes nothing)
        val stock = installs.filter { it.owner in setOf(Owner.APP_STANDARD, Owner.MRTT_BETA) && (it.units.isNotEmpty() || it.containers.isNotEmpty()) }
        if (stock.isNotEmpty()) {
            inv.procs.filter { "csqtt1" in it.tuns && stock.none { install -> it in install.procs } }.forEach { holder ->
                problems += BoxProblem("B17", "csqtt1 held by ${holder.comm} (${holder.exe}); the stock server deletes it at its next start")
            }
        }
        // B20: a swg unit file with no swg-noded running
        if (inv.units.any { it.id.startsWith("swg-csqtt-") } && inv.units.none { it.id == "swg-noded.service" && it.active == "active" }) {
            problems += BoxProblem("B20", "swg-csqtt unit files and no swg-noded running")
        }
        // B21: MRTT half-migrated
        if (inv.stores.any { it.dir.startsWith("/etc/mrtt") && it.files.any { f -> f.startsWith("csqtt.db") } } ||
            inv.files.any { it.path.startsWith("/tmp/legacy-uninstall.") }) {
            problems += BoxProblem("B21", "MRTT's migration of the stock install did not finish")
        }
        // B23: the stock server still running beside MRTT, both on 10.66.67.0/24
        if (installs.any { it.owner == Owner.MRTT && it.state == InstallState.RUNNING } &&
            installs.any { it.owner == Owner.APP_STANDARD && it.state == InstallState.RUNNING }) {
            problems += BoxProblem("B23", "the stock server still runs beside MRTT on the same subnet")
        }
        return problems
    }

    /** Two listeners' addresses overlap: the same one, or either is a wildcard. */
    private fun overlap(a: String, b: String): Boolean {
        val any = setOf("0.0.0.0", "*", "::", "[::]", "")
        return a == b || a in any || b in any
    }

    /** Union-find over the views' keys. */
    private class Groups {
        private val parent = linkedMapOf<String, String>()

        fun add(x: String) {
            parent.putIfAbsent(x, x)
        }

        fun find(x: String): String {
            var root = x
            while (parent.getValue(root) != root) root = parent.getValue(root)
            var y = x
            while (y != root) {
                val next = parent.getValue(y)
                parent[y] = root
                y = next
            }
            return root
        }

        fun union(a: String, b: String) {
            add(a)
            add(b)
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }

        fun sets(): List<List<String>> = parent.keys.groupBy { find(it) }.values.toList()
    }
}
