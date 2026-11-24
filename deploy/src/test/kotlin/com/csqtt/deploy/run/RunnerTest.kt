// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.csqtt.deploy.run

import com.csqtt.deploy.inspect.Classifier
import com.csqtt.deploy.inspect.Inventory
import com.csqtt.deploy.inspect.Owner
import com.csqtt.deploy.plan.Proposal
import com.csqtt.deploy.ssh.ExecResult
import com.csqtt.deploy.ssh.Privilege
import com.csqtt.deploy.ssh.Remote
import com.csqtt.deploy.ssh.RemoteCommand
import com.csqtt.deploy.ssh.RootRunner
import com.csqtt.deploy.wire.LineCodec
import com.csqtt.deploy.wire.Report
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import kotlin.random.Random

class RunnerTest {
    private class Box(val answer: (List<String>, ByteArray?) -> ExecResult) : Remote {
        val calls = mutableListOf<List<String>>()
        override fun exec(command: RemoteCommand, stdin: ByteArray?, deadlineMs: Long): ExecResult {
            calls += command.words
            return answer(command.words, stdin)
        }
    }

    private fun ok(text: String) = ExecResult(text.toByteArray(), "", 0)
    private val runner = Runner(Box { _, _ -> ok("") }, RootRunner(Box { _, _ -> ok("") }, Privilege.Root))

    @Test
    fun aTailTakesCompleteLinesOnlyAndSkipsNoise() {
        val out = "hello from .bashrc\nCSQTT_TAIL|active|999\nCSQTT_PHASE|recheck\nCSQTT_PCT|2\nCSQTT_BEAT|apt: unattended-upgr (812)\nCSQTT_PH"
        val tail = runner.parseTail(out.toByteArray(), 100)
        assertEquals(listOf(RunEvent.Phase("recheck"), RunEvent.Percent(2), RunEvent.Beat("apt: unattended-upgr (812)")), tail.events)
        // the half-written line is the next tail's: the offset stops before it
        assertEquals(100L + "CSQTT_PHASE|recheck\nCSQTT_PCT|2\nCSQTT_BEAT|apt: unattended-upgr (812)\n".length, tail.nextOffset)
        assertTrue(tail.active)
        assertEquals("missing", runner.parseTail("no run here\n".toByteArray(), 0).state)
    }

    @Test
    fun aRunIsFollowedToItsResultOrFoundInterrupted() {
        val log = "CSQTT_RUN|r1|selftest\nCSQTT_PHASE|stage\nCSQTT_PCT|10\nCSQTT_RESULT|failed-cleaned|recheck|port 41000 is now held by nginx|check again\nCSQTT_END|4\n"
        // the log arrives in two reads, the unit still active for the first
        var reads = 0
        val box = Box { words, _ ->
            val offset = words.last().toInt()
            reads++
            val have = if (reads == 1) log.substring(0, 40) else log
            ok("CSQTT_TAIL|${if (reads == 1) "active" else "ended"}|${have.length}\n" + have.substring(minOf(offset, have.length)))
        }
        val events = mutableListOf<RunEvent>()
        val result = Runner(box, RootRunner(box, Privilege.Root)).attach("r1", pollMs = 1) { e, _ -> events += e }
        assertEquals(RunResult("failed-cleaned", "recheck", "port 41000 is now held by nginx", "check again"), result)
        assertEquals(listOf(RunEvent.Phase("stage"), RunEvent.Percent(10)), events.take(2))
        // a log that ends with no result: interrupted (a reboot, a kill)
        val cut = Box { _, _ -> ok("CSQTT_TAIL|ended|30\nCSQTT_RUN|r2|selftest\nCSQTT_PHASE|step 1\n") }
        assertNull(Runner(cut, RootRunner(cut, Privilege.Root)).attach("r2", pollMs = 1))
    }

    @Test
    fun aSecondRunIsRefusedAndNamesTheFirst() {
        val box = Box { words, stdin ->
            when {
                words.take(3) == listOf("env", "LC_ALL=C", "df") -> ok("")
                words.firstOrNull() == "mktemp" -> ok("/tmp/tmp.Up1\n")
                words.firstOrNull() == "dd" -> ok("")
                words.firstOrNull() == "sha256sum" -> ok("") // checked below by the Uploads tests; here any sum is refused
                else -> ok("CSQTT_START|busy|20261124070000-abcd\nCSQTT_END|1\n")
            }
        }
        // Uploads refuses a wrong sum: give the right one by answering with the sha of what dd received
        var written = ByteArray(0)
        val honest = Box { words, stdin ->
            when {
                words.take(3) == listOf("env", "LC_ALL=C", "df") -> ok("")
                words.firstOrNull() == "mktemp" -> ok("/tmp/tmp.Up1\n")
                words.firstOrNull() == "dd" -> { written = stdin ?: ByteArray(0); ok("") }
                words.firstOrNull() == "sha256sum" -> ok(java.security.MessageDigest.getInstance("SHA-256").digest(written)
                    .joinToString("") { "%02x".format(it) } + "  x\n")
                else -> box.answer(words, stdin)
            }
        }
        val outcome = Runner(honest, RootRunner(honest, Privilege.Root)).start("20261124071500-0001", RunPlan("selftest", mapOf("steps" to "2"), mapOf("web_pass" to "s")))
        assertEquals(StartOutcome.Busy("20261124070000-abcd"), outcome)
        // the private upload directory goes whatever the outcome
        assertTrue(honest.calls.any { it == listOf("rm", "-rf", "/tmp/tmp.Up1") })
    }

    @Test
    fun runIdsSortByTheirTime() {
        val a = Runner.newRunId(Random(1), Instant.parse("2026-11-24T07:00:00Z"))
        val b = Runner.newRunId(Random(2), Instant.parse("2026-11-24T07:00:01Z"))
        assertTrue(a < b)
        assertTrue(Regex("^[0-9]{14}-[0-9a-f]{4}$").matches(a))
    }

    @Test
    fun everyPersistedStateComesBackAndNoOtherIsKept() {
        val dir = kotlin.io.path.createTempDirectory("csqtt-state").toFile()
        try {
            val store = FileStateStore(File(dir, "vps.state"))
            val states = listOf(
                DeployState.HostKeyChanged("SHA256:old", "SHA256:new"),
                DeployState.Choosing("CSQTT_PRE|os|id=debian|name=a|b%\nCSQTT_END|1\n",
                    Proposal("1a2b3c", "sqc0", "10.77.0.0/24", 41234, 52345, 2, "127.0.0.1")),
                DeployState.Running("20261124071500-0001", "step 1", 40, "apt: unattended-upgr (812)"),
                DeployState.Finished(RunResult("ok-unreachable-from-outside", "verify", "UDP 41234 closed outside", "open it in the hoster's panel")),
                DeployState.Interrupted("20261124071500-0001"),
            )
            for (state in states) {
                val coordinator = Coordinator(store)
                coordinator.move(state)
                assertEquals(state, Coordinator(store).state)
            }
            Coordinator(store).move(DeployState.Inspecting)
            assertEquals(DeployState.Idle, Coordinator(store).state)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aRestoredChoiceIsClassifiedAgainFromItsReport() {
        val report = checkNotNull(Classifier::class.java.getResource("fixtures/v253main-systemd-noble.report")).readText()
        val restored = StateCodec.decode(StateCodec.encode(DeployState.Choosing(report, null))) as DeployState.Choosing
        val inventory = Inventory.from((LineCodec.parse(restored.report) as Report.Complete).lines)
        assertEquals(Owner.APP_STANDARD, Classifier.classify(inventory).installations.single().owner)
    }
}
