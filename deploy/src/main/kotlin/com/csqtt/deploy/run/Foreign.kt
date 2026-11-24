// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.csqtt.deploy.run

import com.csqtt.deploy.inspect.Classification
import com.csqtt.deploy.inspect.InstallState
import com.csqtt.deploy.inspect.Installation
import com.csqtt.deploy.inspect.Inventory
import com.csqtt.deploy.inspect.Owner

/**
 * Removal of a stock install, systemd or Docker (P2-PLAN §3 B11b, B13b; APP-PLAN §6.10; M-116, M-120, M-126, M-131): every
 * object it has on the box, listed before the typed confirmation, each from its stock role's own namespace (server-footprints.md
 * §1.6, §1.7), which run.sh checks again, so a plan cannot widen it. Its store stays unless [purge]; what is shared or generic is
 * named as left.
 */
object ForeignRemoval {
    /** A stock role's namespace: what its deploy.sh writes, by name (2.0.0 → 2.5.3, slim and the side tool share them). */
    enum class Role(val word: String, val store: String, val tags: List<String>, val tables: List<Int>, val units: List<String>,
        val paths: List<String>,
        /** Its Docker mode's container and image repository (P2 B13b): `csqtt` / `csqtt:<ver>`, `csqtt25` / `csqtt25:<ver>`. */
        val container: String) {
        MAIN("main", "/etc/csqtt", listOf("CSQTT_MANAGED", "CSQTT_TPROXY"), listOf(30001, 1066),
            listOf("csqtt.service", "csqtt-docker-prereq.service", "csqtt-letsencrypt.service", "csqtt-letsencrypt.timer"),
            listOf("/usr/local/bin/csqtt", "/usr/local/lib/csqtt", "/usr/local/lib/csqtt-letsencrypt-renew.sh", "/opt/csqtt-certbot",
                "/run/csqtt", "/etc/sysctl.d/99-csqtt.conf", "/etc/sysctl.d/99-csqtt-udp-buffers.conf", "/var/log/csqtt-install.log"),
            "csqtt"),
        SIDE("side", "/etc/csqtt25", listOf("CSQTT25_MANAGED", "CSQTT25_TPROXY"), listOf(30002),
            listOf("csqtt25.service", "csqtt25-docker-prereq.service", "csqtt25-letsencrypt.service", "csqtt25-letsencrypt.timer"),
            listOf("/usr/local/lib/csqtt25", "/opt/csqtt25-certbot", "/run/csqtt25", "/etc/sysctl.d/99-csqtt25.conf",
                "/etc/sysctl.d/99-csqtt25-udp-buffers.conf", "/var/log/csqtt25-install.log"),
            "csqtt25"),
    }

    /** The untagged rule every stock network-up puts at FORWARD's top, v4 and v6 (#10, M-126), as `iptables -S` prints it. */
    const val UNTAGGED = "-A FORWARD -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT"

    /** 2.0.0's subnet-less clamp in mangle FORWARD, which other software adds as well: named, never taken. */
    const val CLAMP = "-A FORWARD -p tcp -m tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu"
    private val LINEAGE = Regex("^/etc/letsencrypt/(?:live|renewal|archive)/(csqtt-ip-[0-9]{1,3}-[0-9]{1,3}-[0-9]{1,3}-[0-9]{1,3})(?:\\.conf)?$")
    private val STOCK = setOf(Owner.APP_STANDARD, Owner.APP_SIDE, Owner.SIDE_TOOL)

    /** What the run removes, keeps and leaves, in the words the app shows before the typed confirmation, and its plan. */
    data class Listing(val role: Role, val units: List<String>, val paths: List<String>, val tags: List<String>, val tables: List<Int>,
        val lineages: List<String>, val untagged: Boolean, val store: String?, val purge: Boolean, val left: List<String>,
        /** Where a symlinked store points: its removal takes the link alone (B11b review F3). */
        val storeLink: String = "",
        /** Its Docker mode's container and images (P2 B13b): none for a systemd install. */
        val containers: List<String> = emptyList(), val images: List<String> = emptyList()) {
        val will: List<String> get() = listOfNotNull(
            "Будет удалено: " + (units + containers.map { "контейнер $it" } + images.map { "образ $it" } + paths +
                tags.map { "правила $it" } + tables.map { "маршрутизация $it" } +
                lineages.map { "сертификат $it" } + (if (untagged) listOf("общее правило FORWARD RELATED,ESTABLISHED") else emptyList()))
                .joinToString(", ").ifEmpty { "ничего" },
            store?.let {
                when {
                    !purge -> "База $it останется"
                    storeLink.isNotEmpty() -> "Ссылка $it будет удалена; база в $storeLink останется (её файлы не трогаются)"
                    else -> "База $it будет удалена вместе с клиентами"
                }
            },
        ) + left.map { "Останется: $it" }
    }

    fun roleOf(install: Installation): Role? = when (install.owner) {
        Owner.APP_STANDARD -> Role.MAIN
        Owner.APP_SIDE, Owner.SIDE_TOOL -> Role.SIDE
        else -> null
    }

    private fun flagsOf(install: Installation) =
        (install.procs.map { it.flags } + install.units.map { it.flags } + install.containers.map { it.flags }).firstOrNull()

    /**
     * The tag a live server's rules carry: its --instance uppercased, the default instance's CSQTT (swg's server included);
     * MRTT's server tags its own MRTT_MANAGED (B11b review F4).
     */
    private fun tagOf(install: Installation): String? {
        val flags = flagsOf(install) ?: return null
        if (install.owner == Owner.MRTT) return "MRTT_MANAGED"
        return (flags["--instance"]?.uppercase() ?: "CSQTT") + "_MANAGED"
    }

    fun listing(install: Installation, classification: Classification, inv: Inventory, purge: Boolean): Listing? {
        val role = roleOf(install) ?: return null
        // a stock Docker install (P2 B13b): its own container, by its role's name alone, and every image of its repository
        val containers = install.containers.map { it.name }.filter { it == role.container }
        if (containers.size != install.containers.size) return null
        val images = inv.images.map { it.ref }.filter { it.substringBefore(':') == role.container && ':' in it }.distinct()
        val others = classification.installations.filter { it !== install }
        val left = mutableListOf<String>()
        val units = role.units.filter { u -> inv.units.any { it.id == u } || inv.files.any { it.path == "/etc/systemd/system/$u" } }
        val paths = role.paths.filter { p -> inv.files.any { it.path == p } }
        val live = others.filter { it.state in setOf(InstallState.RUNNING, InstallState.CRASH_LOOPING) }
        // a tag another live server's rules carry too (swg's default-instance server on a take-over, B15): its rules stay
        val sharedBy = live.firstOrNull { tagOf(it) == role.tags.first() }
        val tags = if (sharedBy != null) {
            left += "правила ${role.tags.joinToString("/")}: их же ведёт ${sharedBy.name}"
            emptyList()
        } else role.tags.filter { tag -> inv.firewall.any { !it.isChain && Regex("--comment \"?$tag([:\" ]|$)").containsMatchIn(it.text) } }
        // the policy routing is the instance's slot's: a live server on the same one (the default instance for the main —
        // swg's, MRTT's — csqtt25 for the side) keeps it, whatever its tag (B11b review F4)
        val slotOwner = live.firstOrNull { other -> flagsOf(other) != null && flagsOf(other)?.get("--instance") == (if (role == Role.MAIN) null else "csqtt25") }
        val tables = if (slotOwner != null) {
            left += "маршрутизация ${role.tables.joinToString("/")}: её же ведёт ${slotOwner.name}"
            emptyList()
        } else role.tables.filter { t -> inv.ipRules.any { it.trimEnd().endsWith("lookup $t") } }
        // shared by every stock install: they go with the last one
        val lastStock = others.none { it.owner in STOCK }
        val lineages = inv.files.mapNotNull { LINEAGE.find(it.path)?.groupValues?.get(1) }.distinct()
        // the untagged FORWARD rule is a 2.5 network-up's (added when missing): an install of another line never put it there,
        // so the one on the box is someone else's (B11b review F2); and only a 2.5 install needs it (a 2.1 install's own accepts
        // on its tunnel cover it), so it goes with the last stock install of the 2.5 line, whatever 2.1 install stays (B11b
        // verify N1: a 2.1.9 main beside a 2.5 side, removed after it, kept it for good)
        val untaggedThere = inv.firewall.any { !it.isChain && it.text == UNTAGGED }
        val untaggedOurs = untaggedThere && install.build?.line == com.csqtt.deploy.inspect.ProtocolLine.V25
        val other25 = others.firstOrNull { it.owner in STOCK && it.build?.line == com.csqtt.deploy.inspect.ProtocolLine.V25 }
        if (untaggedThere && !untaggedOurs) left += "правило FORWARD RELATED,ESTABLISHED: эта установка его не ставила"
        if (!lastStock && lineages.isNotEmpty()) {
            left += "общие с ${others.first { it.owner in STOCK }.name}: " + lineages.joinToString(", ") { "сертификат $it" }
        }
        if (untaggedOurs && other25 != null) left += "общее с ${other25.name}: правило FORWARD RELATED,ESTABLISHED"
        if (inv.files.any { it.path == "/etc/modules-load.d/bbr.conf" }) left += "/etc/modules-load.d/bbr.conf (такой файл пишут и другие программы)"
        // 2.0.0's deploy tagged its SSH opening: run.sh never takes an INPUT rule for a port sshd listens on (a box whose INPUT
        // drops would refuse every new SSH login)
        val sshPorts = inv.listens.filter { it.proto == "tcp" && it.comm == "sshd" }.mapNotNull { it.port }.toSet()
        inv.firewall.filter { line -> !line.isChain && line.text.startsWith("-A INPUT ") && tags.any { "--comment $it" in line.text } &&
            sshPorts.any { Regex("--dport $it( |$)").containsMatchIn(line.text) } }.map { it.text.substringAfter("--dport ").substringBefore(' ') }
            .distinct().forEach { left += "правило ${role.tags.first()} для SSH (порт $it): без него новый вход по SSH может закрыться" }
        // what 2.0.0's server added untagged (its subnet's FORWARD and NAT), and the subnet-less TCPMSS clamp others add as well
        val subnet = if (role == Role.MAIN) "10.66.67.0/24" else "10.66.68.0/24"
        inv.firewall.count { !it.isChain && "--comment" !in it.text && subnet in it.text }.takeIf { it > 0 }
            ?.let { left += "правила без метки для $subnet ($it, так их ставил 2.0.0)" }
        if (inv.firewall.any { !it.isChain && it.text == CLAMP }) left += "общий TCPMSS-clamp в FORWARD (такой ставят и другие программы)"
        val store = install.stores.firstOrNull { it.dir == role.store }
        return Listing(role, units, paths, tags, tables, if (lastStock) lineages else emptyList(), untaggedOurs && other25 == null, store?.dir,
            purge, left, store?.linkTarget.orEmpty(), containers, images)
    }

    /**
     * The run: each list comma-separated (stock names hold no comma), the server unit and binary re-checked as inspected. A
     * store goes only with [Listing.purge], and a symlinked one loses its link, never its target.
     */
    fun plan(install: Installation, inv: Inventory, listing: Listing): RunPlan {
        val unit = install.units.firstOrNull { it.id == listing.role.units.first() }
        val sha = unit?.let { inv.exeAt(it.exec)?.sha }
        return RunPlan("remove-foreign", linkedMapOf(
            "role" to listing.role.word, "purge" to if (listing.purge) "1" else "0",
            "units" to listing.units.joinToString(","), "paths" to listing.paths.joinToString(","),
            "tags" to listing.tags.joinToString(","), "tables" to listing.tables.joinToString(","),
            "lineage" to listing.lineages.joinToString(","), "untagged" to if (listing.untagged) "1" else "0",
            "store" to listing.store.orEmpty(),
            "containers" to listing.containers.joinToString(","), "images" to listing.images.joinToString(","),
        ) + (if (unit != null) mapOf("expect_unit" to unit.id) else emptyMap()) +
            (if (unit != null && sha != null) mapOf("expect_bin" to unit.exec, "expect_sha" to sha) else emptyMap()))
    }
}
