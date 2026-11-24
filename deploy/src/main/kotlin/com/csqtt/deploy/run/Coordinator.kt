// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
//
// One coordinator per VPS (P2-PLAN §1.9; APP-PLAN §4.2 rules 8–9, §6.13; P2 B7b): the deploy's state, so a question is
// never lost (K0). Only the states with something to resume are persisted (HostKeyChanged, Choosing, Running, Finished,
// Interrupted), and never a secret: the read-back is fetched again when a result is applied. Choosing keeps the
// inspection's report and the user's draft, and its actions are recomputed on restore, so an app update can never
// resurrect a stale list. A plain observable: the app wraps it in its StateFlow (no coroutines in :deploy).
package com.csqtt.deploy.run

import com.csqtt.deploy.plan.Proposal
import com.csqtt.deploy.wire.LineCodec
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

sealed interface DeployState {
    data object Idle : DeployState
    data object Connecting : DeployState
    data object Inspecting : DeployState

    /** D1: the question stays until it is answered. */
    data class HostKeyChanged(val pinned: String, val presented: String) : DeployState

    /** K0: the pending choice; [report] is the inspection's report as received, [draft] the user's edits. */
    data class Choosing(val report: String, val draft: Proposal?) : DeployState

    /** [runId] is §4.7's pendingRunId: persisted the moment a run starts, so a process death re-attaches by it. */
    data class Running(val runId: String, val phase: String = "", val percent: Int = 0, val waitingFor: String? = null) : DeployState

    data class Finished(val result: RunResult) : DeployState

    data class Interrupted(val runId: String) : DeployState
}

val DeployState.persisted: Boolean
    get() = this is DeployState.HostKeyChanged || this is DeployState.Choosing || this is DeployState.Running ||
        this is DeployState.Finished || this is DeployState.Interrupted

/** Where a VPS's state is kept: deploy-cli's file beside its target; the app's DataStore (P3). */
interface StateStore {
    fun load(): DeployState?

    /** null clears it. */
    fun save(state: DeployState?)
}

class Coordinator(private val store: StateStore) {
    @Volatile
    var state: DeployState = store.load() ?: DeployState.Idle
        private set
    private val listeners = CopyOnWriteArrayList<(DeployState) -> Unit>()

    fun observe(listener: (DeployState) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun move(next: DeployState) {
        state = next
        store.save(next.takeIf { it.persisted })
        listeners.forEach { it(next) }
    }
}

/** The states as key=value lines, every value escaped as the line protocol's fields are. */
object StateCodec {
    fun encode(state: DeployState): String {
        val fields: List<Pair<String, String>> = when (state) {
            is DeployState.HostKeyChanged -> listOf("state" to "host-key-changed", "pinned" to state.pinned, "presented" to state.presented)
            is DeployState.Choosing -> listOf("state" to "choosing", "report" to state.report) + (state.draft?.let { d ->
                listOf("draft_id" to d.id, "draft_iface" to d.iface, "draft_subnet" to d.subnet, "draft_udp" to "${d.udpPort}",
                    "draft_web" to "${d.webPort}", "draft_slot" to "${d.slot}", "draft_web_bind" to (d.webBind ?: ""))
            } ?: emptyList())
            is DeployState.Running -> listOf("state" to "running", "run_id" to state.runId, "phase" to state.phase,
                "percent" to "${state.percent}", "waiting_for" to (state.waitingFor ?: ""))
            is DeployState.Finished -> listOf("state" to "finished", "kind" to state.result.kind, "step" to state.result.step,
                "cause" to state.result.cause, "next" to state.result.next)
            is DeployState.Interrupted -> listOf("state" to "interrupted", "run_id" to state.runId)
            else -> error("$state is not persisted")
        }
        return fields.joinToString("") { (k, v) -> "$k=${LineCodec.escape(v)}\n" }
    }

    fun decode(text: String): DeployState? {
        val f = text.lines().filter { '=' in it }.associate { it.substringBefore('=') to LineCodec.unescape(it.substringAfter('=')) }
        fun int(key: String) = f[key]?.toIntOrNull()
        return when (f["state"]) {
            "host-key-changed" -> DeployState.HostKeyChanged(f["pinned"].orEmpty(), f["presented"].orEmpty())
            "choosing" -> DeployState.Choosing(f["report"].orEmpty(), f["draft_id"]?.let {
                Proposal(it, f["draft_iface"].orEmpty(), f["draft_subnet"].orEmpty(), int("draft_udp") ?: 0, int("draft_web") ?: 0,
                    int("draft_slot") ?: 0, f["draft_web_bind"]?.takeIf { b -> b.isNotEmpty() })
            })
            "running" -> DeployState.Running(f["run_id"].orEmpty(), f["phase"].orEmpty(), int("percent") ?: 0,
                f["waiting_for"]?.takeIf { it.isNotEmpty() })
            "finished" -> DeployState.Finished(RunResult(f["kind"].orEmpty(), f["step"].orEmpty(), f["cause"].orEmpty(), f["next"].orEmpty()))
            "interrupted" -> DeployState.Interrupted(f["run_id"].orEmpty())
            else -> null
        }
    }
}

/** deploy-cli's store: one file beside the target, written whole and moved into place. */
class FileStateStore(private val file: File) : StateStore {
    override fun load(): DeployState? = if (file.isFile) StateCodec.decode(file.readText()) else null

    override fun save(state: DeployState?) {
        if (state == null) {
            file.delete()
            return
        }
        val next = File(file.path + ".new")
        next.writeText(StateCodec.encode(state))
        if (!next.renameTo(file)) error("could not write ${file.path}")
    }
}
