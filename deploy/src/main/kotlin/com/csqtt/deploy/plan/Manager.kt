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
            Version(a.toInt(), b.toInt(), c.toIntOrNull() ?: 0, d.toIntOrNull() ?: 0)
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
    REMOVE,
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

    fun entries(classification: Classification, carried: Version): List<Entry> =
        classification.installations.map { entry(it, carried) }

    fun boxIssues(classification: Classification): List<BoxIssue> = classification.problems.mapNotNull { problem ->
        when (problem.code) {
            "B14", "B24", "B15" -> BoxIssue(problem.code, problem.detail, repairable = true)
            "B9", "B17" -> BoxIssue(problem.code, problem.detail, repairable = false)
            else -> null
        }
    }

    fun compatibility(install: Installation, carried: Version): Compatibility {
        val build = install.build ?: return Compatibility.UNKNOWN
        return when (build.line) {
            ProtocolLine.MRTT -> Compatibility.OTHER_PROTOCOL
            ProtocolLine.V20, ProtocolLine.V21 -> Compatibility.OUTDATED_21
            ProtocolLine.V25, ProtocolLine.V26 -> {
                val version = Version.parse(build.version)
                if (version != null && version > carried) Compatibility.NEWER_THAN_APP else Compatibility.COMPATIBLE
            }
            ProtocolLine.UNKNOWN -> Compatibility.UNKNOWN
        }
    }

    private fun entry(install: Installation, carried: Version): Entry {
        val compatibility = compatibility(install, carried)
        val name = install.name
        val clients = "удалить $name"
        return when (install.owner) {
            Owner.SWG_BARE, Owner.SWG_DOCKER -> Entry(install, compatibility, emptyList(), "Управляется swg-panel")
            Owner.MRTT, Owner.MRTT_BETA -> Entry(install, compatibility, emptyList(), "Другой протокол (MRTT)")
            Owner.HANDMADE, Owner.UNKNOWN -> Entry(install, compatibility, emptyList(), "Чужая установка")
            Owner.OURS -> Entry(install, compatibility, ours(install, compatibility, carried, clients), null)
            in STOCK -> stock(install, compatibility, clients)
            else -> Entry(install, compatibility, emptyList(), "Чужая установка")
        }
    }

    private fun ours(install: Installation, compatibility: Compatibility, carried: Version, confirm: String): List<Offer> {
        val newerCarried = Version.parse(install.build?.version.orEmpty())?.let { carried > it } == true
        return when (install.state) {
            InstallState.RUNNING -> listOfNotNull(
                Offer(Action.CONNECT, recommended = !newerCarried),
                if (newerCarried) Offer(Action.UPDATE, recommended = true) else null,
                Offer(Action.RESTART),
                Offer(Action.REMOVE, confirm = confirm),
                Offer(Action.REINSTALL_CLEAN, folded = true, confirm = confirm),
            ).sortedByDescending { it.recommended }
            InstallState.CRASH_LOOPING, InstallState.FAILED ->
                listOf(Offer(Action.REPAIR, recommended = true), Offer(Action.JOURNAL), Offer(Action.REMOVE, confirm = confirm))
            InstallState.STOPPED, InstallState.DORMANT -> listOf(Offer(Action.START, recommended = true), Offer(Action.REMOVE, confirm = confirm))
            InstallState.LEFTOVER -> listOf(Offer(Action.RESTORE, recommended = true),
                Offer(Action.REMOVE_LEFTOVERS, confirm = if (install.stores.isNotEmpty()) confirm else null))
        }.filterNot { it.action == Action.UPDATE && compatibility == Compatibility.NEWER_THAN_APP }
    }

    private fun stock(install: Installation, compatibility: Compatibility, confirm: String): Entry {
        if (install.state == InstallState.LEFTOVER && install.units.isEmpty() && install.containers.isEmpty()) {
            return Entry(install, compatibility, listOf(Offer(Action.REMOVE_LEFTOVERS, recommended = true,
                confirm = if (install.stores.isNotEmpty()) confirm else null)), null)
        }
        return when (compatibility) {
            // never downgrade (M-150): a newer build than ours is connected to or removed, never «updated»
            Compatibility.NEWER_THAN_APP -> Entry(install, compatibility,
                listOf(Offer(Action.CONNECT, recommended = true), Offer(Action.REMOVE, confirm = confirm)),
                "Обновить нельзя: установлена более новая версия")
            Compatibility.OUTDATED_21 -> Entry(install, compatibility, listOf(
                Offer(Action.UPDATE_FROM_21, recommended = true, warning = "Клиенты 2.1 перестанут подключаться"),
                Offer(Action.REMOVE, folded = true, confirm = confirm),
            ), null)
            else -> {
                val broken = install.state != InstallState.RUNNING || install.broken.isNotEmpty()
                val first = if (broken) Offer(Action.REPAIR, recommended = true) else Offer(Action.UPDATE, recommended = true)
                Entry(install, compatibility, listOfNotNull(
                    first,
                    // read back from a copy of its store, which needs no running server
                    if (install.stores.isNotEmpty()) Offer(Action.CONNECT) else null,
                    Offer(Action.REMOVE, folded = true, confirm = confirm),
                ), null)
            }
        }
    }
}
