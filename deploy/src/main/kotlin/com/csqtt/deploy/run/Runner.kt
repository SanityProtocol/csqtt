// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
//
// The engine's side of a run (P2-PLAN §1.4; P2 B7b): a run is started on the VPS by run.sh as a transient unit of its own,
// so it goes on whatever happens to the phone; the engine follows its log by offset, as the login user (no sudo per poll),
// and can cancel it. The log's marker lines become events; the last is the result.
package com.csqtt.deploy.run

import com.csqtt.deploy.bundle.Bundle
import com.csqtt.deploy.ssh.Remote
import com.csqtt.deploy.ssh.RemoteCommand
import com.csqtt.deploy.ssh.RootRunner
import com.csqtt.deploy.ssh.Uploads
import com.csqtt.deploy.wire.LineCodec
import com.csqtt.deploy.wire.Report
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/** What a run does: its action and its facts (no secret: the plan file is readable), and its secrets apart (0600). */
class RunPlan(val action: String, val values: Map<String, String> = emptyMap(), val secrets: Map<String, String> = emptyMap()) {
    internal fun planText(): String = (mapOf("action" to action) + values).entries.joinToString("") { "${it.key}=${it.value}\n" }

    internal fun secretsText(): String = secrets.entries.joinToString("") { "${it.key}=${it.value}\n" }
}

sealed interface RunEvent {
    data class Phase(val name: String) : RunEvent
    data class Percent(val value: Int) : RunEvent
    /** The run is waiting, and says for what (a package lock's holder included). */
    data class Beat(val waitingFor: String) : RunEvent
    data class Step(val name: String, val command: String) : RunEvent
    data class Output(val line: String) : RunEvent
    data class Result(val result: RunResult) : RunEvent
}

/** ok, ok-unreachable-from-outside, failed-cleaned, failed-rolled-back or failed-not-rolled-back; with the step, the cause, what next. */
data class RunResult(val kind: String, val step: String, val cause: String, val next: String) {
    val ok: Boolean get() = kind.startsWith("ok")
}

sealed interface StartOutcome {
    data class Started(val runId: String) : StartOutcome
    /** Another run holds the box: the app offers to watch it. */
    data class Busy(val activeRunId: String) : StartOutcome
    data class Refused(val reason: String) : StartOutcome
}

/** A tail: the events of the complete lines since [offset], the offset after them, and whether the run still lives. */
data class Tail(val events: List<RunEvent>, val nextOffset: Long, val state: String) {
    val active: Boolean get() = state == "active"
}

/** How following a run ended (B7b review F1): its result; cut off with none; or no such run on the box at all. */
sealed interface Attached {
    data class Done(val result: RunResult) : Attached

    /** The run's unit ended with no result: a reboot, a kill. «Продолжить» runs it again. */
    data object Interrupted : Attached

    /** The box has no such run: it never started (a death between keeping its id and starting it), or it was pruned. */
    data object NotFound : Attached
}

/** What a cancel did (B7b review F3). */
sealed interface CancelOutcome {
    /** The run stops at its next safe point. */
    data object Requested : CancelOutcome

    /** The run had already ended: nothing to stop. */
    data object Ended : CancelOutcome

    data object NotFound : CancelOutcome

    /** The run is past the point where a cancel could undo it (B8's switch), or the box said why not. */
    data class Refused(val reason: String) : CancelOutcome
}

data class RunInfo(val id: String, val active: Boolean, val result: String, val action: String) {
    /** A log with no result and no live unit: the run was cut off (a reboot, an OOM kill). */
    val interrupted: Boolean get() = !active && result.isEmpty()
}

class Runner(private val remote: Remote, private val root: RootRunner) {
    companion object {
        const val RUNS = "/var/lib/sanityprotocol/csqtt-deploy"
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)

        /** A run id starts with its UTC time, so the box keeps its newest runs by name. */
        fun newRunId(random: Random = Random.Default, now: Instant = Instant.now()): String =
            STAMP.format(now) + "-" + "%04x".format(random.nextInt(0x10000))

        private fun script(): ByteArray = Bundle.bytes("lib/marker.sh") + Bundle.bytes("run.sh")
    }

    fun start(runId: String, plan: RunPlan): StartOutcome {
        val script = script()
        val dir = Uploads.privateDir(remote, script.size.toLong())
        try {
            Uploads.writeFile(remote, "$dir/run.sh", script)
            Uploads.writeFile(remote, "$dir/plan", plan.planText().toByteArray())
            // the private directory is the login user's, 0700; run.sh installs the secrets 0600 for root and the directory goes
            if (plan.secrets.isNotEmpty()) Uploads.writeFile(remote, "$dir/secrets", plan.secretsText().toByteArray())
            val result = root.run(RemoteCommand.of("/bin/sh", "$dir/run.sh", "start", runId, dir))
            val line = (LineCodec.parse(result.stdoutText) as? Report.Complete)?.lines?.singleOrNull { it.kind == "START" }
                ?: return StartOutcome.Refused("run.sh start gave no report (exit ${result.exitStatus}): ${result.stderr.trim().take(200)}")
            return when (line.fields.firstOrNull()) {
                "started" -> StartOutcome.Started(runId)
                "busy" -> StartOutcome.Busy(line.fields.getOrElse(1) { "" })
                else -> StartOutcome.Refused(line.fields.drop(1).joinToString(" "))
            }
        } finally {
            Uploads.removeDir(remote, dir)
        }
    }

    /** The log's complete lines since [offset], as the login user; a line still being written waits for the next tail. */
    fun tail(runId: String, offset: Long): Tail {
        val result = remote.exec(RemoteCommand.of("/bin/sh", "$RUNS/$runId/run.sh", "tail", runId, offset.toString()), null, 30_000)
        return parseTail(result.stdout, offset)
    }

    internal fun parseTail(out: ByteArray, offset: Long): Tail {
        // anything an rc file printed comes first; the header line is ours
        val text = String(out, Charsets.ISO_8859_1)
        val at = text.indexOf("CSQTT_TAIL|")
        if (at < 0) return Tail(emptyList(), offset, "missing")
        val headerEnd = text.indexOf('\n', at)
        if (headerEnd < 0) return Tail(emptyList(), offset, "missing")
        val header = text.substring(at, headerEnd).split('|')
        val body = out.copyOfRange(headerEnd + 1, out.size)
        val lastNewline = body.lastIndexOf('\n'.code.toByte())
        val complete = if (lastNewline < 0) ByteArray(0) else body.copyOfRange(0, lastNewline + 1)
        val events = String(complete, Charsets.UTF_8).split('\n').mapNotNull { LineCodec.parseLine(it) }.mapNotNull { line ->
            val f = line.fields
            when (line.kind) {
                "PHASE" -> RunEvent.Phase(f.getOrElse(0) { "" })
                "PCT" -> f.getOrNull(0)?.toIntOrNull()?.let { RunEvent.Percent(it) }
                "BEAT" -> RunEvent.Beat(f.getOrElse(0) { "" })
                "STEP" -> RunEvent.Step(f.getOrElse(0) { "" }, f.getOrElse(1) { "" })
                "OUT" -> RunEvent.Output(f.getOrElse(0) { "" })
                "RESULT" -> RunEvent.Result(RunResult(f.getOrElse(0) { "" }, f.getOrElse(1) { "" }, f.getOrElse(2) { "" }, f.getOrElse(3) { "" }))
                else -> null
            }
        }
        return Tail(events, offset + complete.size, header.getOrElse(1) { "missing" })
    }

    /**
     * Follows a run to its result, from [offset]: every event to [onEvent], a tail every [pollMs]. A run that ended with no
     * result was interrupted; a run the box does not have was never started there (or was pruned): the two are told apart.
     */
    fun attach(runId: String, offset: Long = 0, pollMs: Long = 2_000, onEvent: (RunEvent, Long) -> Unit = { _, _ -> }): Attached {
        var at = offset
        while (true) {
            val tail = tail(runId, at)
            // no log, or no run.sh to ask: cmd_start makes both before the unit, so this run never started here
            if (tail.state == "missing") return Attached.NotFound
            at = tail.nextOffset
            tail.events.forEach { onEvent(it, at) }
            tail.events.filterIsInstance<RunEvent.Result>().lastOrNull()?.let { return Attached.Done(it.result) }
            if (!tail.active) {
                // the unit is gone: one more read for lines written just before it ended, then it is interrupted
                val last = tail(runId, at)
                last.events.forEach { onEvent(it, last.nextOffset) }
                return last.events.filterIsInstance<RunEvent.Result>().lastOrNull()?.let { Attached.Done(it.result) } ?: Attached.Interrupted
            }
            Thread.sleep(pollMs)
        }
    }

    fun cancel(runId: String): CancelOutcome {
        val result = root.run(RemoteCommand.of("/bin/sh", "$RUNS/$runId/run.sh", "cancel", runId))
        // no run.sh to run: no such run
        val line = (LineCodec.parse(result.stdoutText) as? Report.Complete)?.lines?.singleOrNull { it.kind == "CANCEL" }
            ?: return CancelOutcome.NotFound
        return when (line.fields.firstOrNull()) {
            "requested" -> CancelOutcome.Requested
            "ended" -> CancelOutcome.Ended
            "missing" -> CancelOutcome.NotFound
            else -> CancelOutcome.Refused(line.fields.drop(1).joinToString(" ").ifEmpty { line.fields.firstOrNull().orEmpty() })
        }
    }

    /** The runs the box keeps, read with run.sh on stdin (no run needs to exist), as the login user. */
    fun status(): List<RunInfo> {
        val result = remote.exec(RemoteCommand.of("/bin/sh", "-s", "status"), script(), 30_000)
        val report = LineCodec.parse(result.stdoutText) as? Report.Complete ?: return emptyList()
        return report.lines.filter { it.kind == "RUN" }.map { line ->
            val f = line.fields.associate { it.substringBefore('=') to it.substringAfter('=', "") }
            RunInfo(f["id"].orEmpty(), f["active"] == "1", f["result"].orEmpty(), f["action"].orEmpty())
        }
    }
}
