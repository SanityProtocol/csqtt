// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
//
// «Создать новый» (P2-PLAN §1.5; P2 B6): a proposal for a new instance of ours that collides with nothing on the box, the
// checks an edited one is held to, and the plan a choice becomes. Every installation's claims count, running or not: a
// stopped stock install's helper kills its port's holder and deletes its TUN when it next starts (server-footprints.md §3).
package com.csqtt.deploy.plan

import com.csqtt.deploy.inspect.Classification
import com.csqtt.deploy.inspect.Installation
import com.csqtt.deploy.inspect.Inventory
import com.csqtt.deploy.inspect.Owner
import kotlin.random.Random

/** A new instance: id (6 hex), its instance name `sqc-<id>`, interface, /24, ports, local-proxy slot, web bind. */
data class Proposal(val id: String, val iface: String, val subnet: String, val udpPort: Int, val webPort: Int, val slot: Int,
    /** null: every address; "127.0.0.1": behind the user's own reverse proxy (D22). */
    val webBind: String? = null) {
    val instance: String get() = "sqc-$id"
    val unit: String get() = "sanity-csqtt-$id.service"
}

/** A field of a proposal that cannot stand, and why (the app shows it beside the field). */
data class ProposalProblem(val field: String, val reason: String)

/** A proposal could not be made: why (a seventh instance of ours, no free /24 left). */
class ProposalRefused(val reason: String) : Exception(reason)

/**
 * What the run re-checks first (§1.4): it fails cleaned, and the engine inspects again, if the box moved since. [ownPorts]
 * may be held, but only by this installation's own server (a move's stock ports); [binaryPath] and [binarySha] are its
 * unit's binary on disk, [container] its Docker container (B6 review F8).
 */
data class Expect(val freePorts: Set<Int>, val freeIface: String?, val freeSubnet: String?, val unit: String?, val binarySha: String?,
    val binaryPath: String? = null, val container: String? = null, val ownPorts: Set<Int> = emptySet())

/** A choice made: what runs, on what, what it will change and what it will not, and what the user types to confirm. */
data class Plan(val action: Action, val target: String?, val proposal: Proposal?, val expect: Expect, val will: List<String>,
    val unchanged: List<String>, val confirm: String?)

object Proposer {
    const val PORT_LO = 40_000
    const val PORT_HI = 59_999

    /** The family's own ports (main, side, the diagnostics): a stock install may come back on them. */
    val FAMILY_PORTS = setOf(46_000, 46_002, 46_003, 46_004, 46_100, 46_102)

    /** swg-panel's own pools (§1.5): its dial-in exits' 42821–42899, WireGuard's 51820, its WDTT and csqtt defaults from 56000. */
    val SWG_PORTS = (42_821..42_899).toSet() + 51_820 + (56_000..56_009)

    /** Interface names the family's own helpers own or delete (stock deletes csqtt1 at every start, B17). */
    private val FAMILY_IFACE = Regex("^(csqtt|csqtp|mrtt|mrtx)")

    /** Local-proxy slots: 0 is the stock default instance's (and swg's), 1 the stock side's. */
    val SLOTS = 2..7
    private val ID = Regex("^[0-9a-f]{6}$")
    private val IFACE = Regex("^[a-z][a-z0-9]{0,14}$")

    fun propose(classification: Classification, inv: Inventory, random: Random = Random.Default): Proposal {
        val ids = ourIds(classification, inv)
        var id: String
        do id = "%06x".format(random.nextInt(0x1000000)) while (id in ids)
        val ifaces = takenIfaces(classification, inv)
        val iface = (0..99).map { "sqc$it" }.first { it !in ifaces }
        val subnet = (0..255).map { "10.77.$it.0/24" }.firstOrNull { cidr -> overlaps(cidr, takenNets(classification, inv)).not() }
            ?: throw ProposalRefused("в 10.77.0.0/16 не осталось свободной подсети /24")
        val slot = SLOTS.firstOrNull { it !in usedSlots(classification) }
            ?: throw ProposalRefused("на сервере уже ${SLOTS.count()} наших экземпляров — больше не поддерживается")
        val ports = takenPorts(classification, inv)
        fun pick(other: Int?): Int {
            repeat(10_000) {
                val port = PORT_LO + random.nextInt(PORT_HI - PORT_LO + 1)
                if (port !in ports && port != other) return port
            }
            throw ProposalRefused("в $PORT_LO–$PORT_HI не нашлось свободного порта")
        }
        val udp = pick(null)
        return Proposal(id, iface, subnet, udp, pick(udp), slot)
    }

    /** An edited proposal, held to what a fresh one is made from. */
    fun problems(p: Proposal, classification: Classification, inv: Inventory): List<ProposalProblem> {
        val out = mutableListOf<ProposalProblem>()
        if (!ID.matches(p.id)) out += ProposalProblem("id", "6 шестнадцатеричных цифр")
        else if (p.id in ourIds(classification, inv)) out += ProposalProblem("id", "уже занят")
        if (!IFACE.matches(p.iface)) out += ProposalProblem("iface", "1–15 латинских букв и цифр, с буквы")
        else if (FAMILY_IFACE.containsMatchIn(p.iface)) out += ProposalProblem("iface", "имя занято программами csqtt и MRTT")
        else if (p.iface in takenIfaces(classification, inv)) out += ProposalProblem("iface", "уже есть на сервере")
        val net = parse(p.subnet)
        val network = net?.let { (n, _) -> "${n ushr 24 and 255}.${n ushr 16 and 255}.${n ushr 8 and 255}.${n and 255}/24" }
        when {
            net == null || net.second != 24 -> out += ProposalProblem("subnet", "нужна подсеть /24, например 10.77.0.0/24")
            p.subnet != network -> out += ProposalProblem("subnet", "адрес сети: $network")
            !isPrivate(net.first) || inStock(net.first) -> out += ProposalProblem("subnet", "частная сеть вне 10.66.0.0/16, например 10.77.0.0/24")
            overlaps(p.subnet, takenNets(classification, inv)) -> out += ProposalProblem("subnet", "пересекается с занятой")
        }
        val ports = takenPorts(classification, inv)
        for ((field, port) in listOf("udpPort" to p.udpPort, "webPort" to p.webPort)) {
            if (port !in 1024..65_535) out += ProposalProblem(field, "1024–65535")
            else if (port in ports) out += ProposalProblem(field, "занят")
        }
        if (p.udpPort == p.webPort) out += ProposalProblem("webPort", "совпадает с UDP-портом")
        if (p.slot !in SLOTS) out += ProposalProblem("slot", "${SLOTS.first}–${SLOTS.last}")
        else if (p.slot in usedSlots(classification)) out += ProposalProblem("slot", "уже занят")
        if (p.webBind != null && p.webBind != "127.0.0.1") out += ProposalProblem("webBind", "все адреса или 127.0.0.1")
        return out
    }

    /** The plan for a new instance: nothing else on the box changes, and it says so by name. */
    fun createPlan(p: Proposal, classification: Classification): Plan = Plan(
        action = Action.CREATE,
        target = p.instance,
        proposal = p,
        expect = Expect(setOf(p.udpPort, p.webPort), p.iface, p.subnet, unit = null, binarySha = null),
        will = listOf("Новый сервер ${p.instance}: UDP ${p.udpPort}, панель ${p.webPort}" +
            (if (p.webBind != null) " (только ${p.webBind})" else "") + ", интерфейс ${p.iface}, подсеть ${p.subnet}"),
        unchanged = classification.installations.map { "${it.name} — без изменений" },
        confirm = null,
    )

    /**
     * The plan for an offer on an installation. The run expects to find the installation's unit, its binary on disk and its
     * container as they were; a move (a stock install into our layout) carries the new instance it becomes: a fresh id,
     * interface, /24 and slot, its client-facing ports kept, held only by the server it replaces (§1.7; B6 review F7).
     */
    fun offerPlan(entry: Entry, offer: Offer, classification: Classification, inv: Inventory, carried: Version,
        random: Random = Random.Default): Plan {
        val install = entry.installation
        val others = classification.installations.filter { it !== install }.map { "${it.name} — без изменений" }
        val unit = install.units.firstOrNull()
        val binary = unit?.exec?.takeIf { it.isNotEmpty() }?.let { path -> inv.exeAt(path)?.let { path to it.sha } }
        val move = install.owner != Owner.OURS && offer.action in setOf(Action.UPDATE, Action.REPAIR, Action.UPDATE_FROM_21)
        val proposal = if (move) proposeMove(install, classification, inv, random) else null
        val stockPorts = setOfNotNull(install.claims.udpPort, install.claims.webPort)
        val installed = Version.parse(install.build?.version.orEmpty())
        val db = install.stores.firstOrNull()?.dir
        val will = when (offer.action) {
            Action.CONNECT -> listOf("Чтение настроек подключения ${install.name} (на сервере ничего не меняется)")
            Action.UPDATE, Action.REPAIR -> if (install.owner == Owner.OURS) {
                if (installed != null && carried > installed) listOf("${install.name}: тот же экземпляр, сборка $carried вместо $installed, клиенты сохранятся")
                else listOf("${install.name}: тот же экземпляр, та же сборка заново, клиенты сохранятся")
            } else listOf("${install.name} переедет в наш формат как ${proposal?.instance}: клиенты, пароли, панель и порты сохранятся, " +
                "старая установка будет удалена после проверки")
            Action.UPDATE_FROM_21 -> listOf("${install.name} переедет на $carried как ${proposal?.instance}: клиенты 2.1 перестанут подключаться")
            Action.REMOVE -> listOf("${install.name} будет удалён" + (db?.let { "; база $it останется" + if (install.owner == Owner.OURS) " — «Восстановить» вернёт клиентов" else "" } ?: ""))
            Action.REMOVE_WITH_STORE, Action.REINSTALL_CLEAN, Action.REMOVE_LEFTOVERS -> listOf("${install.name} будет удалён" +
                if (install.stores.isNotEmpty()) " вместе с клиентами и настройками" else "") +
                if (offer.action == Action.REINSTALL_CLEAN) listOf("на его месте — новый, пустой") else emptyList()
            Action.RESTART -> listOf("${install.name} будет перезапущен: клиенты переподключатся")
            Action.START -> listOf("${install.name} будет запущен")
            Action.JOURNAL -> listOf("Журнал ${install.name} (на сервере ничего не меняется)")
            Action.RESTORE -> listOf("${install.name} вернётся под тем же именем, с сохранённой базой клиентов")
            Action.CREATE -> listOf("Новый сервер")
        }
        return Plan(offer.action, install.name, proposal,
            Expect(emptySet(), proposal?.iface, proposal?.subnet, unit?.id, binary?.second,
                binary?.first, install.containers.firstOrNull()?.id, if (move) stockPorts else emptySet()),
            will, if (offer.action in setOf(Action.CONNECT, Action.JOURNAL)) emptyList() else others, offer.confirm)
    }

    /** The instance a move becomes: our resources, the stock install's client-facing ports (its clients keep dialling them). */
    fun proposeMove(install: Installation, classification: Classification, inv: Inventory, random: Random = Random.Default): Proposal {
        val fresh = propose(classification, inv, random)
        return fresh.copy(udpPort = install.claims.udpPort ?: fresh.udpPort, webPort = install.claims.webPort ?: fresh.webPort)
    }

    private fun ourIds(c: Classification, inv: Inventory): Set<String> {
        val names = c.installations.flatMap { it.claims.names } + inv.files.map { it.path }
        return names.mapNotNull { Regex("sanity-csqtt-([0-9a-f]{6})|/sanityprotocol/csqtt/([0-9a-f]{6})").find(it) }
            .map { m -> m.groupValues.drop(1).first { it.isNotEmpty() } }.toSet()
    }

    private fun takenIfaces(c: Classification, inv: Inventory): Set<String> =
        (inv.tuns.map { it.name } + inv.addrs.map { it.dev } + c.installations.mapNotNull { it.claims.iface }).toSet()

    private fun takenNets(c: Classification, inv: Inventory): List<Pair<Int, Int>> =
        (inv.addrs.map { it.cidr } + inv.routes.map { it.dst } + c.installations.mapNotNull { it.claims.subnet }).mapNotNull { parse(it) }

    private fun takenPorts(c: Classification, inv: Inventory): Set<Int> =
        FAMILY_PORTS + SWG_PORTS + c.installations.flatMap { listOfNotNull(it.claims.udpPort, it.claims.webPort) } + inv.listens.mapNotNull { it.port }

    /** RFC 1918 space: 10/8, 172.16/12, 192.168/16. */
    private fun isPrivate(n: Int): Boolean = (n ushr 24) == 10 || (n ushr 20) == (172 shl 4 or 1) || (n ushr 16) == (192 shl 8 or 168)

    /** The stock family's 10.66.0.0/16 (10.66.67/24 the main's, 10.66.68/24 the side's). */
    private fun inStock(n: Int): Boolean = (n ushr 16) == (10 shl 8 or 66)

    /** Every slot an instance of ours uses (its --instance-slot), running or not. */
    private fun usedSlots(c: Classification): Set<Int> = c.installations.filter { it.owner == Owner.OURS }.flatMap { install ->
        install.procs.map { it.flags } + install.units.map { it.flags } + install.containers.map { it.flags }
    }.mapNotNull { it["--instance-slot"]?.toIntOrNull() }.toSet()

    /** "a.b.c.d/n" as (network, prefix); a bare address is /32. */
    internal fun parse(cidr: String): Pair<Int, Int>? {
        val parts = cidr.trim().split('/')
        val octets = parts[0].split('.').map { it.toIntOrNull() ?: return null }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return null
        val prefix = parts.getOrNull(1)?.toIntOrNull() ?: 32
        if (prefix !in 0..32) return null
        val address = octets.fold(0) { acc, o -> (acc shl 8) or o }
        val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
        return (address and mask) to prefix
    }

    private fun overlaps(cidr: String, taken: List<Pair<Int, Int>>): Boolean {
        val (net, prefix) = parse(cidr) ?: return true
        return taken.any { (other, otherPrefix) ->
            val shorter = minOf(prefix, otherPrefix)
            val mask = if (shorter == 0) 0 else -1 shl (32 - shorter)
            (net and mask) == (other and mask)
        }
    }
}
