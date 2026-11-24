// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
//
// The instance manager (CSQTT-APP-PLAN §6.13; P2-PLAN §1.3, §1.5; P2 B6): for every installation the classifier found, what
// it is to this app and what the user may do with it; for «Создать новый», a proposal that collides with nothing on the
// box; and the plan a choice becomes, with what it will change, what it will not, and the facts the run re-checks before
// it touches anything. Pure: from the Classification and the Inventory only. Nothing runs without the user's choice.
package com.csqtt.deploy.plan

import com.csqtt.deploy.inspect.Classification
import com.csqtt.deploy.inspect.InstallState
import com.csqtt.deploy.inspect.Installation
import com.csqtt.deploy.inspect.Inventory
import com.csqtt.deploy.inspect.Owner
import com.csqtt.deploy.inspect.ProtocolLine
import kotlin.random.Random

/** A server build's version, as its label or startup string names it: 2.5.3, 2.5.1-2 (swg's), 2.6.0. */
data class Version(val major: Int, val minor: Int, val patch: Int, val build: Int = 0) : Comparable<Version> {
    override fun compareTo(other: Version): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }, { it.build })

    override fun toString() = "$major.$minor.$patch" + if (build > 0) "-$build" else ""

    companion object {
        private val SHAPE = Regex("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:-(\\d+))?")

        fun parse(text: String): Version? = SHAPE.find(text.trim())?.destructured?.let { (a, b, c, d) ->
            Version(a.toIntOrNull() ?: return null, b.toIntOrNull() ?: return null, c.toIntOrNull() ?: 0, d.toIntOrNull() ?: 0)
        }
    }
}

/** What an installation's build is to this app (§1.3 «совместим», «2.1 — устарел», «другой протокол», «новее»). */
enum class Compatibility { COMPATIBLE, OUTDATED_21, OTHER_PROTOCOL, NEWER_THAN_APP, UNKNOWN }

enum class Action {
    /** «Создать новый»: a new instance of ours, from a Proposal. */
    CREATE,

    /** «Подключиться»: a small run that reads the connection back from a copy of the store and pushes nothing (M-148). */
    CONNECT,

    /** «Обновить»: ours to the carried build; a stock 2.5 install moved into our layout (§1.7). */
    UPDATE,

    /** «Обновить до 2.6» from the 2.1 line or 2.0: its clients stop connecting. */
    UPDATE_FROM_21,
    RESTART,
    START,

    /** «Починить»: ours, the same release again with the store kept; a broken stock 2.5 install, the same move as UPDATE. */
    REPAIR,
    JOURNAL,
    /** «Удалить»: the server goes, its store stays (M-110; APP-PLAN §6.10): ours comes back by «Восстановить». */
    REMOVE,

    /** «Удалить вместе с базой»: ours and its store, typed (M-134). */
    REMOVE_WITH_STORE,
    REINSTALL_CLEAN,

    /** «Восстановить»: our leftovers brought back under the same id, the store kept. */
    RESTORE,
    REMOVE_LEFTOVERS,
}

/**
 * One action offered on an installation. [confirm] is what the user types to confirm a destructive choice (M-110–M-112),
 * set when one would lose clients or settings; [warning] is said before it.
 */
data class Offer(val action: Action, val recommended: Boolean = false, val folded: Boolean = false, val confirm: String? = null,
    val warning: String? = null)

/** One line of the list: the installation, what it is to us, what may be done, and why nothing may when nothing may. */
data class Entry(val installation: Installation, val compatibility: Compatibility, val offers: List<Offer>, val note: String?)

/** A box problem with what the app can do about it (§1.3 "Box problems"): B14 and B15 get «Исправить», B9 and B17 a name. */
data class BoxIssue(val code: String, val detail: String, val repairable: Boolean)

object Manager {
    private val STOCK = setOf(Owner.APP_STANDARD, Owner.APP_SIDE, Owner.SIDE_TOOL)

    /**
     * §1.3's stock «Починить» list (B1, B2, B5, B6, B11, B18), with a crash loop and an exited container: the move fixes them;
     * and B7, a 2.1 binary failing on a 2.5 store, which «Обновить до 2.6» cures (B6 verify N1).
     */
    private val MOVABLE = setOf("B1", "B2", "B3", "B4", "B5", "B6", "B7", "B11", "B18")

    /** Instances of ours one box holds: the local-proxy slots 2–7. */
    const val MAX_OURS = 6

    /**
     * The list's entries, from the classification and the inventory it was made from (B6 verify R2: required, so no caller
     * gets the weaker list): a holder the claims do not show is found in it (a listener), and a move whose new instance cannot
     * be made is named instead of offered.
     */
    fun entries(classification: Classification, carried: Version, inv: Inventory): List<Entry> =
        classification.installations.map { entry(it, classification, carried, inv) }

    /**
     * Every box problem, named (B6 review F10): B14, B24 and B15 get «Исправить»; the rest are said, never dropped, so the
     * user learns why an offer is missing (B10's foreign holder of a claimed port, B11's interrupted install…).
     */
    fun boxIssues(classification: Classification): List<BoxIssue> = classification.problems.map { problem ->
        BoxIssue(problem.code, problem.detail, repairable = problem.code in setOf("B14", "B24", "B15"))
    }

    /** M-150 first, whatever the line (B6 review F3): a build newer than the carried one is never updated or repaired. */
    fun compatibility(install: Installation, carried: Version): Compatibility {
        val build = install.build ?: return Compatibility.UNKNOWN
        if (build.line == ProtocolLine.MRTT) return Compatibility.OTHER_PROTOCOL
        val version = Version.parse(build.version)
        if (version != null && version > carried) return Compatibility.NEWER_THAN_APP
        return when (build.line) {
            ProtocolLine.V20, ProtocolLine.V21 -> Compatibility.OUTDATED_21
            ProtocolLine.V25, ProtocolLine.V26 -> Compatibility.COMPATIBLE
            else -> Compatibility.UNKNOWN
        }
    }

    /** Which claim of an installation another server holds, and who: «UDP 46000» by «swg-csqtt-csqtt1.service». */
    data class Held(val what: String, val by: String)

    /**
     * Another live server holds a port of an installation that could start again: swg-panel's take-over of a stock install
     * (F1). A running installation holds its own (a clash with another is B9, named), and a leftover starts nothing, so
     * neither is held (B6 verify N2, N4). The holder is an installation's claim or a listener outside this installation (a swg
     * node's second server, a foreign process: N5). Ports only: a move never uses the old interface, and ending the stock
     * unit that would delete a foreign csqtt1 (B17) is what the move does (R1).
     */
    fun heldBy(install: Installation, classification: Classification, inv: Inventory): Held? {
        if (install.state == InstallState.RUNNING || (install.units.isEmpty() && install.containers.isEmpty())) return null
        val udp = install.claims.udpPort
        val web = install.claims.webPort
        classification.installations.firstOrNull { other ->
            other !== install && other.state in setOf(InstallState.RUNNING, InstallState.CRASH_LOOPING) &&
                ((udp != null && other.claims.udpPort == udp) || (web != null && other.claims.webPort == web))
        }?.let { other -> return Held(if (udp != null && other.claims.udpPort == udp) "UDP $udp" else "TCP $web", other.name) }
        val own = install.procs.map { it.pid }.toSet()
        // this installation's own respawn, bound before the process view saw it (the server binds UDP before its TUN), is
        // no holder: a pid the view does not know, with this installation's binary name (R's hypothetical)
        val known = inv.procs.map { it.pid }.toSet()
        val ownNames = (install.procs.map { it.comm } + install.units.map { it.exec.substringAfterLast('/').take(15) })
            .filter { it.isNotEmpty() }.toSet()
        inv.listens.firstOrNull { l ->
            l.pid != null && l.pid !in own && !(l.pid !in known && l.comm in ownNames) &&
                ((l.proto == "udp" && udp != null && l.port == udp) || (l.proto == "tcp" && web != null && l.port == web))
        }?.let { return Held("${it.proto.uppercase()} ${it.port}", it.comm.ifEmpty { "pid ${it.pid}" }) }
        return null
    }

    /** A store with clients in it: what a move carries and «Подключиться» reads (B8's WAL without its database is none, F6). */
    fun hasDatabase(install: Installation): Boolean =
        install.stores.any { store -> store.files.any { it in setOf("csqtt.db", "passwords.json", "passwords.json.imported") } }

    private fun entry(install: Installation, classification: Classification, carried: Version, inv: Inventory): Entry {
        val compatibility = compatibility(install, carried)
        // Docker stopped: what it holds is unseen. A move would race its container back onto the port, a removal would delete
        // the store of an install that only waits for its daemon (B5 review F1)
        if (install.dockerUnseen) return Entry(install, compatibility, emptyList(), "Docker остановлен: запустите Docker и проверьте заново")
        return when (install.owner) {
            Owner.SWG_BARE, Owner.SWG_DOCKER -> Entry(install, compatibility, emptyList(), "Управляется swg-panel")
            Owner.MRTT, Owner.MRTT_BETA -> Entry(install, compatibility, emptyList(), "Другой протокол (MRTT)")
            Owner.HANDMADE, Owner.UNKNOWN -> Entry(install, compatibility, emptyList(), "Чужая установка")
            Owner.OURS -> ours(install, compatibility, classification, carried, inv)
            in STOCK -> stock(install, compatibility, classification, inv)
            else -> Entry(install, compatibility, emptyList(), "Чужая установка")
        }
    }

    /** Another live server has this one's port or interface: nothing that would start this one again; removal, which B11
     *  bounds to this installation's own objects, folded and typed. */
    private fun held(install: Installation, compatibility: Compatibility, held: Held) = Entry(install, compatibility,
        listOf(Offer(Action.REMOVE, folded = true, confirm = "удалить ${install.name}",
            warning = "${held.what} занят ${held.by}: удаление его не тронет")),
        "${held.what.replaceFirstChar { it.uppercase() }} занят: ${held.by}")

    private fun ours(install: Installation, compatibility: Compatibility, classification: Classification, carried: Version,
        inv: Inventory): Entry {
        heldBy(install, classification, inv)?.let { return held(install, compatibility, it) }
        val newer = compatibility == Compatibility.NEWER_THAN_APP
        val installed = Version.parse(install.build?.version.orEmpty())
        val update = !newer && installed != null && carried > installed
        val purge = Offer(Action.REMOVE_WITH_STORE, folded = true, confirm = "удалить ${install.name} вместе с базой")
        // a newer instance (another phone's app) is run, connected to and removed, never rebuilt from our older core (F4)
        val offers = when (install.state) {
            InstallState.RUNNING -> listOfNotNull(
                Offer(Action.CONNECT, recommended = !update),
                if (update) Offer(Action.UPDATE, recommended = true) else null,
                Offer(Action.RESTART),
                Offer(Action.REMOVE),
                purge,
                if (newer) null else Offer(Action.REINSTALL_CLEAN, folded = true, confirm = "удалить ${install.name} вместе с базой"),
            )
            InstallState.CRASH_LOOPING, InstallState.FAILED -> listOfNotNull(
                if (newer) null else Offer(Action.REPAIR, recommended = true),
                Offer(Action.JOURNAL, recommended = newer),
                Offer(Action.REMOVE),
                purge,
            )
            InstallState.STOPPED, InstallState.DORMANT -> listOf(Offer(Action.START, recommended = true), Offer(Action.REMOVE), purge)
            InstallState.LEFTOVER -> listOfNotNull(
                if (newer) null else Offer(Action.RESTORE, recommended = true),
                Offer(Action.REMOVE_LEFTOVERS, recommended = newer, confirm = if (install.stores.isNotEmpty()) "удалить ${install.name}" else null),
            )
        }.sortedByDescending { it.recommended }
        return Entry(install, compatibility, offers, if (newer) "Обновить нельзя: установлена более новая версия" else null)
    }

    private fun stock(install: Installation, compatibility: Compatibility, classification: Classification, inv: Inventory): Entry {
        heldBy(install, classification, inv)?.let { return held(install, compatibility, it) }
        val confirm = "удалить ${install.name}"
        val database = hasDatabase(install)
        // a leftover with nothing to repair is removed, its every object listed and typed (§1.3); B2's binary with no unit is
        // an install cut off, and gets the move (B6 review F2)
        if (install.state == InstallState.LEFTOVER && install.units.isEmpty() && install.containers.isEmpty() &&
            install.broken.none { it in MOVABLE }) {
            return Entry(install, compatibility, listOf(Offer(Action.REMOVE_LEFTOVERS, recommended = true, confirm = confirm)), null)
        }
        val connect = if (database) Offer(Action.CONNECT) else null
        // the server goes, the store stays (§6.10), typed: it is someone else's install
        val remove = Offer(Action.REMOVE, folded = true, confirm = confirm)
        val blocking = install.broken.filterNot { it in MOVABLE }
        val noMove = when {
            !database -> "База клиентов не найдена: переносить нечего"
            blocking.isNotEmpty() -> "Сначала нужно разобраться: ${blocking.joinToString()}"
            classification.installations.count { it.owner == Owner.OURS } >= MAX_OURS -> "На сервере уже $MAX_OURS наших экземпляров"
            // the instance the move becomes must be possible (no free /24 under a 10/8 route…): named, not offered (N3)
            else -> try {
                Proposer.proposeMove(install, classification, inv)
                null
            } catch (refused: ProposalRefused) {
                "Переезд невозможен: ${refused.reason}"
            }
        }
        return when (compatibility) {
            // never downgrade (M-150): a newer build than ours is connected to or removed, never «updated»
            Compatibility.NEWER_THAN_APP -> Entry(install, compatibility, listOfNotNull(connect?.copy(recommended = true), remove),
                "Обновить нельзя: установлена более новая версия")
            Compatibility.UNKNOWN -> Entry(install, compatibility, listOfNotNull(connect?.copy(recommended = true), remove),
                "Сборка не определена: обновлять её не будем")
            Compatibility.OUTDATED_21 -> Entry(install, compatibility, listOfNotNull(
                if (noMove == null) Offer(Action.UPDATE_FROM_21, recommended = true, warning = "Клиенты 2.1 перестанут подключаться") else null,
                remove,
            ), noMove)
            else -> {
                val broken = install.state != InstallState.RUNNING || install.broken.isNotEmpty()
                val move = when {
                    noMove != null -> null
                    broken -> Offer(Action.REPAIR, recommended = true)
                    else -> Offer(Action.UPDATE, recommended = true)
                }
                Entry(install, compatibility, listOfNotNull(move, connect?.copy(recommended = move == null), remove), noMove)
            }
        }
    }
}
