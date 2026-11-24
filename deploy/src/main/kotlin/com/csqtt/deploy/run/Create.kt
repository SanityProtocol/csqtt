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
                    "Откроет в firewalld: ${ports.joinToString(", ")}; интерфейс ${p.iface} получит свою зону с выходом наружу (порты сервера ему закрыты)"
                else -> "Откроет ${ports.joinToString(" и ")} в iptables, если входящие там закрыты"
            },
        )
    }

    /** The run «Создать новый» starts: the proposal's facts in the plan, the passwords and the panel's path in its secrets. */
    fun plan(p: Proposal, publicIp: String, core: Core, credentials: Credentials, resume: Boolean = false): RunPlan = RunPlan(
        action = "create",
        values = linkedMapOf(
            "id" to p.id, "iface" to p.iface, "subnet" to p.subnet, "udp" to p.udpPort.toString(), "web" to p.webPort.toString(),
            "slot" to p.slot.toString(), "web_bind" to p.webBind.orEmpty(), "version" to core.version, "core_sha" to core.sha256,
            "public_ip" to publicIp, "web_user" to credentials.webUser,
            // what the run re-checks before it makes anything (§1.4): still free, or it fails cleaned
            "ports" to "${p.udpPort} ${p.webPort}",
        ) + if (resume) mapOf("resume" to "1") else emptyMap(),
        secrets = linkedMapOf("main_password" to credentials.mainPassword, "web_pass" to credentials.webPass,
            "secret_path" to credentials.secretPath),
        core = core.bytes,
    )
}

/**
 * «Продолжить» (B8 review F1): the proposal of a create a reboot or a kill cut off, from the plan its run kept on the box
 * (no secret in it); run again with resume, it undoes what that run left and creates again, with new credentials.
 */
fun proposalOfKeptPlan(text: String): Proposal? {
    val v = text.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    if (v["action"] != "create") return null
    return Proposal(v["id"] ?: return null, v["iface"] ?: return null, v["subnet"] ?: return null, v["udp"]?.toIntOrNull() ?: return null,
        v["web"]?.toIntOrNull() ?: return null, v["slot"]?.toIntOrNull() ?: return null, v["web_bind"]?.takeIf { it.isNotEmpty() })
}

/** What the app needs to connect to an instance and to open its panel; [certSha256] pins the panel (null: not readable). */
class Connection(val host: String, val udpPort: Int, val webPort: Int, val webBind: String?, val mainPassword: String,
    val webUser: String, val webPass: String, val secretPath: String, val certSha256: String?) {
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
        )
    }
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
