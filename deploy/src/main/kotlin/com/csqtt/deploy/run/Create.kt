// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
//
// «Создать новый» (P2-PLAN §1.5; P2 B8): the run a proposal becomes, the credentials a new instance is given, and the
// connection read back from the box afterwards. Nothing secret is kept by the engine: the credentials reach the box once
// (the run's secrets file, deleted when the run ends) and come back through the read-back, a short root command whose
// answer goes to the engine alone and never into a log (§1.9: the read-back is fetched again whenever it is needed).
package com.csqtt.deploy.run

import com.csqtt.deploy.bundle.Bundle
import com.csqtt.deploy.plan.Proposal
import com.csqtt.deploy.ssh.RootRunner
import com.csqtt.deploy.wire.LineCodec
import com.csqtt.deploy.wire.Report
import java.security.MessageDigest
import java.security.SecureRandom

/** The server build the engine installs: its bytes for the box's architecture, and the version its `--version` names. */
class Core(val bytes: ByteArray, val version: String) {
    val sha256: String = sha256Hex(bytes)
}

/** A new instance's credentials: made here, sent once, never persisted; the panel may change them afterwards. */
class Credentials(val mainPassword: String, val webUser: String, val webPass: String, val secretPath: String) {
    init {
        // they travel as key=value lines and land in a systemd EnvironmentFile and a JSON file: plain characters only
        for ((name, value) in listOf("main password" to mainPassword, "panel login" to webUser, "panel password" to webPass,
            "secret path" to secretPath)) {
            require(value.isNotEmpty() && value.all { it in SAFE }) { "the $name may hold only Latin letters, digits, - _ and ." }
        }
    }

    override fun toString() = "Credentials(panel login $webUser)"

    companion object {
        private val SAFE = (('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('-', '_', '.')).toSet()
        private val MIXED = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        private val LOWER = ('a'..'z') + ('0'..'9')

        fun generate(random: SecureRandom = SecureRandom()): Credentials {
            fun pick(n: Int, from: List<Char>) = String(CharArray(n) { from[random.nextInt(from.size)] })
            return Credentials(pick(16, MIXED), "admin", pick(20, MIXED), pick(12, LOWER))
        }
    }
}

object CreateRun {
    /**
     * The address clients and the panel's links use: the box's own public IPv4 (the preflight's uplink source), or, behind
     * NAT, the address the engine signed in to.
     */
    fun publicAddress(preflight: com.csqtt.deploy.inspect.Preflight, sshHost: String): String {
        val src = preflight.facts("ipv4")["src"]
        return if (src.isNotEmpty() && !com.csqtt.deploy.inspect.PreflightRules.isPrivate(src)) src else sshHost
    }

    /**
     * What the run will change on the host (APP-PLAN §6.9, P2-PLAN §1.6; P2 B9), for the plan's «Будет», from the facts the
     * inspection read: forwarding, the raise-only floors, the openings through the firewall the box uses, a missing iptables.
     */
    fun hostChanges(preflight: com.csqtt.deploy.inspect.Preflight, p: Proposal): List<String> {
        val ports = listOf("UDP ${p.udpPort}") + if (p.webBind == "127.0.0.1") emptyList() else listOf("TCP ${p.webPort}")
        val firewall = preflight.facts("firewall")
        return listOfNotNull(
            if (preflight.facts("nat")["iptables"] == "0") "Установит iptables (без него сервер не выпустит клиентов в интернет)" else null,
            if (preflight.facts("forwarding")["ipv4"] == "1") "Закрепит пересылку пакетов (ip_forward) после перезагрузки"
            else "Включит пересылку пакетов (ip_forward), и после перезагрузки тоже",
            "Поднимет сетевые буферы до значений 2.5.3 там, где они ниже (/etc/sysctl.d/99-sanity-csqtt.conf)",
            when {
                firewall["ufw"] == "active" -> "Откроет в ufw: ${ports.joinToString(", ")}"
                firewall["firewalld"] == "active" ->
                    "Откроет в firewalld: ${ports.joinToString(", ")}; интерфейс ${p.iface} получит свою зону с выходом наружу " +
                        "(другие порты сервера клиентам закрыты, поэтому маршрут «Локальный прокси» здесь работать не будет); " +
                        "firewalld перечитает настройки, и его временные правила (например, баны fail2ban) сбросятся"
                else -> "Откроет ${ports.joinToString(" и ")} в iptables, если входящие там закрыты"
            },
        )
    }

    /** The run «Создать новый» starts: the proposal's facts in the plan, the passwords and the panel's path in its secrets. */
    /** [resumeOf]: the run a reboot or a kill cut off, which this one continues («Продолжить»): only what it left is undone. */
    /**
     * [rootfs]: Docker mode's root filesystem and where the box gets it (C3), required with [Proposal.docker]: the run fetches
     * it from the URL, finds its image already there, or takes the client's copy uploaded with the run.
     */
    fun plan(p: Proposal, publicIp: String, core: Core, credentials: Credentials, resumeOf: String? = null,
        rootfs: RootfsChoice? = null): RunPlan = RunPlan(
        action = "create",
        values = linkedMapOf(
            "id" to p.id, "iface" to p.iface, "subnet" to p.subnet, "udp" to p.udpPort.toString(), "web" to p.webPort.toString(),
            "slot" to p.slot.toString(), "web_bind" to p.webBind.orEmpty(), "version" to core.version, "core_sha" to core.sha256,
            "public_ip" to publicIp, "web_user" to credentials.webUser,
            // what the run re-checks before it makes anything (§1.4): still free, or it fails cleaned
            "ports" to "${p.udpPort} ${p.webPort}",
        ) + (if (resumeOf != null) mapOf("resume" to "1", "resume_of" to resumeOf) else emptyMap()) +
            if (p.docker) dockerKeys(checkNotNull(rootfs) { "a Docker create needs its root filesystem" }) else emptyMap(),
        secrets = linkedMapOf("main_password" to credentials.mainPassword, "web_pass" to credentials.webPass,
            "secret_path" to credentials.secretPath),
        core = core.bytes,
        rootfs = (rootfs?.source as? DockerMode.Source.Upload)?.bytes?.takeIf { p.docker },
    )

    internal fun upload(rootfs: RootfsChoice): ByteArray? = (rootfs.source as? DockerMode.Source.Upload)?.bytes

    internal fun dockerKeys(rootfs: RootfsChoice): Map<String, String> = linkedMapOf(
        "mode" to "docker", "rootfs_sha256" to rootfs.pin.sha256,
        "rootfs_url" to ((rootfs.source as? DockerMode.Source.Fetch)?.url ?: ""),
    )
}

/**
 * «Обновить» (also «Починить» and «Обновить до 2.6») on a stock install = moving it into our layout (P2-PLAN §1.7; P2 B12):
 * a create of [proposal] (Proposer.proposeMove: our id, interface, subnet and slot, the stock install's ports) that takes its
 * place through one marker, its store, certificate and panel login copied over at the switch; once verified, the stock install
 * goes through the foreign removal's list, its store kept (DE-T3) and its LE lineage kept for ours (B14). The run re-checks the
 * stock unit and binary as inspected; the stock server's hold on its own ports is no conflict. A stock Docker install (P2
 * B13b): its container's restart policy is the switch (kept, set to `no`, the container stopped; put back by an undo), ours in
 * Docker with a root filesystem, else a unit; the finish takes its container and images as its removal does.
 */
object MoveRun {
    fun plan(install: com.csqtt.deploy.inspect.Installation, classification: com.csqtt.deploy.inspect.Classification,
        inv: com.csqtt.deploy.inspect.Inventory, proposal: Proposal, publicIp: String, core: Core, credentials: Credentials,
        rootfs: RootfsChoice? = null): RunPlan? {
        val listing = ForeignRemoval.listing(install, classification, inv, purge = false) ?: return null
        val role = listing.role
        val store = listing.store ?: return null
        val unit = install.units.firstOrNull { it.id == role.units.first() }
        val sha = unit?.let { inv.exeAt(it.exec)?.sha }
        // a stock Docker install (P2 B13b): its container's restart policy is the switch; ours in Docker with [rootfs], else a unit
        val container = listing.containers.firstOrNull()
        val create = CreateRun.plan(proposal.copy(docker = rootfs != null), publicIp, core, credentials, rootfs = rootfs)
        val move = linkedMapOf(
            "move_role" to role.word, "move_unit" to unit?.id.orEmpty(), "move_store" to store,
            "move_container" to container.orEmpty(),
            "move_units" to listing.units.joinToString(","), "move_paths" to listing.paths.joinToString(","),
            "move_tags" to listing.tags.joinToString(","), "move_tables" to listing.tables.joinToString(","),
            "move_containers" to listing.containers.joinToString(","), "move_images" to listing.images.joinToString(","),
            "move_lineage" to "", "move_untagged" to if (listing.untagged) "1" else "0",
            "own_ports" to listOfNotNull(install.claims.udpPort, install.claims.webPort).joinToString(","),
        ) + (if (unit != null) mapOf("expect_unit" to unit.id) else emptyMap()) +
            (if (unit != null && sha != null) mapOf("expect_bin" to unit.exec, "expect_sha" to sha) else emptyMap())
        return RunPlan(create.action, create.values + move, create.secrets, create.core, create.rootfs)
    }

    /**
     * The stock runtime that serves is Docker (P2 B13b): its container, unless a unit of it runs instead (B16, both there: the
     * unit when neither runs). Its move goes into our Docker mode where the box allows one (DockerMode.refusal), else a unit.
     */
    fun inDocker(install: com.csqtt.deploy.inspect.Installation): Boolean {
        val container = install.containers.firstOrNull() ?: return false
        val unit = install.units.firstOrNull() ?: return true
        return container.state == "running" && unit.active != "active"
    }

    /**
     * «Продолжить» a move a reboot or a kill cut off: the cut run's own plan again with resume, new credentials (only the
     * fallbacks of a stock env that had none), and a move into Docker's root filesystem chosen again ([rootfs], required then:
     * P2 B13b), as a Docker create's «Продолжить». Null: the kept plan is no move.
     */
    fun resumePlan(kept: Map<String, String>, resumeOf: String, core: Core, credentials: Credentials, rootfs: RootfsChoice? = null): RunPlan? {
        if (kept["move_role"].isNullOrEmpty()) return null
        val choice = if (kept["mode"] == "docker") checkNotNull(rootfs) { "a move into Docker needs its root filesystem" } else null
        return RunPlan("create", kept - "action" + (if (choice != null) CreateRun.dockerKeys(choice) else emptyMap()) +
            mapOf("resume" to "1", "resume_of" to resumeOf),
            linkedMapOf("main_password" to credentials.mainPassword, "web_pass" to credentials.webPass, "secret_path" to credentials.secretPath),
            core.bytes, choice?.let { CreateRun.upload(it) })
    }
}

/**
 * «Продолжить» (B8 review F1): the proposal of a create a reboot or a kill cut off, from the plan its run kept on the box
 * (no secret in it); run again with resume, it undoes what that run left and creates again, with new credentials.
 */
fun proposalOfKeptPlan(text: String): Proposal? {
    val v = text.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    if (v["action"] != "create") return null
    return Proposal(v["id"] ?: return null, v["iface"] ?: return null, v["subnet"] ?: return null, v["udp"]?.toIntOrNull() ?: return null,
        v["web"]?.toIntOrNull() ?: return null, v["slot"]?.toIntOrNull() ?: return null, v["web_bind"]?.takeIf { it.isNotEmpty() },
        docker = v["mode"] == "docker")
}

/** An instance of ours to another build, or the same one again («Починить»): P2-PLAN §3 B10. */
object UpdateRun {
    private val OURS = Regex("^sanity-csqtt-([0-9a-f]{6})\\.service$")

    private val OURS_IN_DOCKER = Regex("^sanity-csqtt-([0-9a-f]{6})$")

    /** The instance's id, by its unit's name or its container's (P2 B13c); null when the installation is not one of ours. */
    fun idOf(install: com.csqtt.deploy.inspect.Installation): String? =
        install.units.firstNotNullOfOrNull { OURS.find(it.id)?.groupValues?.get(1) }
            ?: install.containers.firstNotNullOfOrNull { OURS_IN_DOCKER.find(it.name)?.groupValues?.get(1) }

    /**
     * The run: the instance and the build it goes to; what it re-checks first is the unit and the binary the inspection saw
     * (§1.4), so a box that changed since fails cleaned and is inspected again. No secret: the instance keeps all of its own.
     */
    fun plan(install: com.csqtt.deploy.inspect.Installation, inv: com.csqtt.deploy.inspect.Inventory, core: Core,
        rootfs: RootfsChoice? = null): RunPlan {
        val id = idOf(install) ?: throw IllegalArgumentException("${install.name} is not an instance of ours")
        // an instance in Docker (P2 B13c): no unit to re-check; its new image stands on the root filesystem the plan names
        if (install.units.isEmpty() && install.containers.isNotEmpty()) {
            val choice = checkNotNull(rootfs) { "an update in Docker needs its root filesystem" }
            // the host's copy of its build, as the inspection hashed it: another phone's update since fails this one cleaned
            // (M-150; B13c review F3)
            val bin = "/opt/sanityprotocol/csqtt/$id/current/csqtt-server"
            val sha = inv.exeAt(bin)?.sha
            return RunPlan("update", linkedMapOf("id" to id, "version" to core.version, "core_sha" to core.sha256) +
                (if (sha != null) mapOf("expect_bin" to bin, "expect_sha" to sha) else emptyMap()) +
                CreateRun.dockerKeys(choice), core = core.bytes, rootfs = CreateRun.upload(choice))
        }
        val unit = install.units.first()
        val sha = inv.exeAt(unit.exec)?.sha
        return RunPlan("update", linkedMapOf(
            "id" to id, "version" to core.version, "core_sha" to core.sha256, "expect_unit" to unit.id,
        ) + (if (sha != null) mapOf("expect_bin" to unit.exec, "expect_sha" to sha) else emptyMap()), core = core.bytes)
    }

    /** An instance of ours in Docker, running or kept: its update and restore carry a root filesystem (P2 B13c). */
    fun inDocker(install: com.csqtt.deploy.inspect.Installation): Boolean =
        (install.units.isEmpty() && install.containers.isNotEmpty()) || install.stores.any { it.keptDocker }
}

/**
 * Removal, restore and restart of an instance of ours (P2-PLAN §3 B11; APP-PLAN §6.10). Removal keeps the store unless
 * [purge] (typed in the app, M-110, M-134); «Восстановить» brings a kept store back under the same id with its clients.
 */
object Maintenance {
    fun remove(install: com.csqtt.deploy.inspect.Installation, purge: Boolean): RunPlan =
        RunPlan("remove", linkedMapOf("id" to idOf(install), "purge" to if (purge) "1" else "0"))

    fun restore(install: com.csqtt.deploy.inspect.Installation, core: Core, rootfs: RootfsChoice? = null): RunPlan {
        val values = linkedMapOf("id" to idOf(install), "version" to core.version, "core_sha" to core.sha256)
        // a store an instance in Docker kept comes back in Docker (P2 B13c)
        if (!install.stores.any { it.keptDocker }) return RunPlan("restore", values, core = core.bytes)
        val choice = checkNotNull(rootfs) { "a restore in Docker needs its root filesystem" }
        return RunPlan("restore", values + CreateRun.dockerKeys(choice), core = core.bytes, rootfs = CreateRun.upload(choice))
    }

    /** «Перезапустить», and «Запустить» for a stopped one: the unit (re)started, verified. */
    fun restart(install: com.csqtt.deploy.inspect.Installation): RunPlan = RunPlan("restart", linkedMapOf("id" to idOf(install)))

    /** «Журнал»: the unit's last [lines], read as root (nothing changes on the box). */
    fun journal(root: RootRunner, id: String, lines: Int = 200): List<String> {
        val result = root.runScript(Bundle.bytes("lib/marker.sh") + Bundle.bytes("run.sh"), listOf("journal", id, lines.toString()), 60_000)
        val report = LineCodec.parse(result.stdoutText) as? Report.Complete ?: return emptyList()
        return report.lines.filter { it.kind == "JOURNAL" }.map { it.fields.firstOrNull().orEmpty() }
    }

    /** The id of an instance of ours: its unit's name, or, for a kept store (a leftover), its state dir. */
    fun idOf(install: com.csqtt.deploy.inspect.Installation): String = UpdateRun.idOf(install)
        ?: install.stores.firstNotNullOfOrNull { Regex("^/var/lib/sanityprotocol/csqtt/([0-9a-f]{6})$").find(it.dir)?.groupValues?.get(1) }
        ?: throw IllegalArgumentException("${install.name} is not an instance of ours")

    /** «Починить» for an instance of ours whose manifest was lost (§1.3; P2 B11c): rebuilt from its live objects, by the id. */
    fun rebuildManifest(install: com.csqtt.deploy.inspect.Installation): RunPlan =
        RunPlan("rebuild-manifest", linkedMapOf("id" to idOf(install)))
}

/**
 * «Исправить» for the box problems that have a repair (§1.3 "Box problems"; P2 B11c): B14/B24, a Docker prereq unit whose
 * helper is gone, which takes Docker down at its next start; B15, the stock certificate timer still running beside swg-panel's
 * take-over; S1, our sysctl drop-in left with no instance of ours (B11a verify R1). The run re-checks the condition before it
 * changes anything.
 */
object BoxRepair {
    /** The run and what it will do, in the words the app shows before «Исправить» (§1.3: every plan carries its «Будет»). */
    class Fix(val run: RunPlan, val will: List<String>)

    /**
     * [dockerStopped]: Docker was down when the box was inspected; a B14/B24 repair then starts it, its «Будет» says so (it
     * may have been stopped on purpose: B11c review F4).
     */
    fun plan(problem: com.csqtt.deploy.inspect.BoxProblem, dockerStopped: Boolean = false): Fix? {
        if (problem.code !in com.csqtt.deploy.plan.Manager.REPAIRABLE || problem.subject.isEmpty()) return null
        val docker = dockerStopped && problem.code in setOf("B14", "B24")
        val will = when (problem.code) {
            "B14", "B24" -> listOf("${problem.subject} будет удалён: Docker больше не будет его требовать") +
                if (docker) listOf("Docker сейчас остановлен: он будет запущен, а с ним контейнеры по их политике перезапуска") else emptyList()
            "B15" -> listOf("Таймер сертификата старой установки (${problem.subject}) будет выключен")
            "S1" -> listOf("Наш файл ${problem.subject} будет удалён: значения, которые он поднял, вернутся к прежним, если их " +
                "никто не менял с тех пор; пересылка останется включённой до перезагрузки")
            else -> emptyList()
        }
        return Fix(RunPlan("repair-box", linkedMapOf("code" to problem.code, "unit" to problem.subject) +
            if (docker) mapOf("start_docker" to "1") else emptyMap()), will)
    }
}

/** What the app needs to connect to an instance and to open its panel; [certSha256] pins the panel (null: not readable). */
class Connection(val host: String, val udpPort: Int, val webPort: Int, val webBind: String?, val mainPassword: String,
    val webUser: String, val webPass: String, val secretPath: String, val certSha256: String?,
    /** Who issued the panel's certificate and until when, and the Let's Encrypt lineage it follows (P2 B14): null when unknown. */
    val certIssuer: String? = null, val certNotAfter: String? = null, val certLe: String? = null) {
    val panelPath: String get() = "/" + secretPath.trim('/').let { if (it.isEmpty()) "" else "$it/" }
    val panelUrl: String get() = "https://${if (':' in host) "[$host]" else host}:$webPort$panelPath"

    /** Behind the user's own reverse proxy (D22): the panel is not meant to be reached from outside. */
    val panelLocalOnly: Boolean get() = webBind == "127.0.0.1"

    override fun toString() = "Connection($host, UDP $udpPort, panel $webPort)"
}

class ReadBackFailed(val reason: String) : Exception(reason)

object ReadBack {
    /** The connection to instance `sqc-<id>`, read on the box as root; [fallbackHost] when the box names no public address. */
    fun read(root: RootRunner, instanceId: String, fallbackHost: String): Connection {
        val result = root.runScript(Bundle.bytes("lib/marker.sh") + Bundle.bytes("run.sh"), listOf("readback", instanceId), 60_000)
        return parse(result.stdoutText, fallbackHost)
    }

    internal fun parse(text: String, fallbackHost: String): Connection {
        val report = LineCodec.parse(text) as? Report.Complete ?: throw ReadBackFailed("the read-back was cut off")
        val f = report.lines.filter { it.kind == "RB" && it.fields.size >= 2 }.associate { it.fields[0] to it.fields[1] }
        f["error"]?.let { throw ReadBackFailed(it) }
        fun need(key: String) = f[key]?.takeIf { it.isNotEmpty() } ?: throw ReadBackFailed("the read-back has no $key")
        fun port(key: String) = need(key).toIntOrNull()?.takeIf { it in 1..65_535 } ?: throw ReadBackFailed("the read-back's $key is not a port")
        return Connection(
            host = f["public_ip"].orEmpty().ifEmpty { fallbackHost },
            udpPort = port("udp_port"),
            webPort = port("web_port"),
            webBind = f["web_bind"]?.takeIf { it.isNotEmpty() },
            mainPassword = need("main_password"),
            webUser = need("web_user"),
            webPass = need("web_pass"),
            secretPath = f["secret_path"].orEmpty(),
            certSha256 = f["cert_sha256"]?.takeIf { it.length == 64 },
            certIssuer = f["cert_issuer"]?.takeIf { it.isNotEmpty() },
            certNotAfter = f["cert_not_after"]?.takeIf { it.isNotEmpty() },
            certLe = f["cert_le"]?.takeIf { it.isNotEmpty() },
        )
    }
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
