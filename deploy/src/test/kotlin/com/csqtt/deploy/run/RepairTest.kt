// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.csqtt.deploy.run

import com.csqtt.deploy.bundle.Bundle
import com.csqtt.deploy.inspect.Classifier
import com.csqtt.deploy.inspect.Inventory
import com.csqtt.deploy.plan.Action
import com.csqtt.deploy.plan.Manager
import com.csqtt.deploy.plan.Version
import com.csqtt.deploy.wire.LineCodec
import com.csqtt.deploy.wire.Report
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepairTest {
    private fun inventory(lines: List<String>): Inventory =
        Inventory.from((LineCodec.parse(lines.joinToString("\n") + "\nCSQTT_END|${lines.size}\n") as Report.Complete).lines)

    private fun fixture(name: String): List<String> =
        checkNotNull(Classifier::class.java.getResource("fixtures/$name.report")) { "no fixture $name" }.readText()
            .lines().filter { it.startsWith("CSQTT_INV|") || it.startsWith("CSQTT_PRE|") }

    private val script = Bundle.bytes("run.sh").toString(Charsets.UTF_8)

    private fun reads(function: String): Set<String> =
        Regex("\\$\\(plan ([a-z_]+)\\)").findAll(script.substringAfter("$function() {").substringBefore("\n}\n")).map { it.groupValues[1] }.toSet()

    private fun ours(id: String, files: String) = listOf(
        "CSQTT_INV|unit|id=sanity-csqtt-$id.service|load=loaded|active=active|sub=running|result=success|status=0|restarts=0|pid=0|" +
            "file_state=enabled|fragment=/etc/systemd/system/sanity-csqtt-$id.service|exec=/opt/sanityprotocol/csqtt/$id/current/csqtt-server|" +
            "exec_ok=1|flags=--listen 0.0.0.0:41234 --config-dir /var/lib/sanityprotocol/csqtt/$id --instance sqc-$id --iface sqc0 " +
            "--instance-slot 2|pre=|pre_status=|env=",
        "CSQTT_INV|store|dir=/var/lib/sanityprotocol/csqtt/$id|files=$files|lock=none|schema=table:client_groups|env_keys=CSQTT_WEB_PASS|public_ip=|domain=",
    )

    @Test
    fun aDockerPrereqWithoutItsHelperIsRepairedByItsUnit() {
        val c = Classifier.classify(inventory(fixture("v253main-systemd-noble+B14")))
        val problem = c.problems.single { it.code == "B14" }
        assertEquals("csqtt-docker-prereq.service", problem.subject)
        assertTrue(Manager.boxIssues(c).single { it.code == "B14" }.repairable)
        val fix = checkNotNull(BoxRepair.plan(problem))
        val plan = fix.run.planText()
        assertTrue(plan, "\ncode=B14\n" in "\n$plan" && "\nunit=csqtt-docker-prereq.service\n" in "\n$plan")
        // Docker running: it is left as it is; Docker down: its start is the plan's, said in its «Будет» (B11c review F4)
        assertFalse(plan, "start_docker" in plan)
        assertEquals(listOf("csqtt-docker-prereq.service будет удалён: Docker больше не будет его требовать"), fix.will)
        val down = checkNotNull(BoxRepair.plan(problem, dockerStopped = true))
        assertTrue("\nstart_docker=1\n" in "\n${down.run.planText()}")
        assertTrue(down.will.last(), down.will.last().startsWith("Docker сейчас остановлен: он будет запущен"))
        // every repair says what it will do
        for (code in Manager.REPAIRABLE) assertTrue(code, BoxRepair.plan(com.csqtt.deploy.inspect.BoxProblem(code, "", "x"))!!.will.isNotEmpty())
        // the run reads what the plan carries, and has a case for every problem the manager calls repairable
        assertEquals(setOf("code", "unit", "start_docker"), reads("action_repair_box"))
        val cases = script.substringAfter("action_repair_box() {").substringBefore("\n}\n")
        for (code in Manager.REPAIRABLE) assertTrue(code, Regex("\"$code [^\"]+\"").containsMatchIn(cases))
        // a problem without a repair gets no plan
        assertNull(BoxRepair.plan(com.csqtt.deploy.inspect.BoxProblem("B9", "UDP 46000: two holders")))
    }

    @Test
    fun ourDropInLeftForNobodyIsABoxProblemAndOnlyThen() {
        val dropIn = "CSQTT_INV|file|path=/etc/sysctl.d/99-sanity-csqtt.conf|type=f"
        // B11a verify R1: nothing of ours, no csqtt server beside: S1, «Исправить»
        Classifier.classify(inventory(listOf(dropIn))).let { c ->
            val s1 = c.problems.single { it.code == "S1" }
            assertEquals("/etc/sysctl.d/99-sanity-csqtt.conf", s1.subject)
            assertTrue(Manager.boxIssues(c).single { it.code == "S1" }.repairable)
        }
        // an instance of ours keeps it; so does a csqtt server running beside (stock, swg's)
        assertFalse(Classifier.classify(inventory(listOf(dropIn) + ours("0a1b2c", "csqtt.db,env,manifest.json"))).problems.any { it.code == "S1" })
        assertFalse(Classifier.classify(inventory(fixture("v253main-systemd-noble") + dropIn)).problems.any { it.code == "S1" })
        // and a stock install that is installed and stopped: slim refuses to start with forwarding off (B11c review F3)
        val stopped = fixture("slim-systemd-noble").filter { !it.startsWith("CSQTT_INV|proc|") }.map {
            if (it.startsWith("CSQTT_INV|unit|id=csqtt.service|")) it.replace("|active=active|sub=running|", "|active=inactive|sub=dead|") else it
        }
        assertTrue(stopped.any { "|active=inactive|sub=dead|" in it })
        assertFalse(Classifier.classify(inventory(stopped + dropIn)).problems.any { it.code == "S1" })
        // the run keeps it alike: by the stock unit files, as by ours
        val keep = script.substringAfter("host_dropin_drop() {").substringBefore("\n}\n")
        assertTrue("/etc/systemd/system/csqtt.service /etc/systemd/system/csqtt25.service" in keep)
        // no drop-in, no problem
        assertFalse(Classifier.classify(inventory(fixture("v253main-systemd-noble"))).problems.any { it.code == "S1" })
    }

    @Test
    fun anInstanceOfOursWhoseManifestIsLostIsRepairedByRebuildingIt() {
        val inv = inventory(ours("0a1b2c", "csqtt.db,env"))
        val c = Classifier.classify(inv)
        val entry = Manager.entries(c, Version(2, 6, 0), inv).single()
        assertEquals(listOf(Action.REBUILD_MANIFEST, Action.REMOVE), entry.offers.map { it.action })
        assertTrue(entry.offers.first().recommended)
        assertEquals("Манифест экземпляра потерян: «Починить» соберёт его заново", entry.note)
        // its «Будет» is the rebuild's, never an update's (B11c review F1), whatever the build
        val will = com.csqtt.deploy.plan.Proposer.offerPlan(entry, entry.offers.first(), c, inv, Version(2, 6, 0)).will
        assertTrue(will.first(), will.first().startsWith("${entry.installation.name}: его манифест будет собран заново") && will.none { "сборка" in it })
        // its removal finds the openings by their names as the rebuild does (B11c review F2)
        val takeDown = script.substringAfter("inst_take_down() {").substringBefore("\n}\n")
        assertTrue(takeDown, "inst_found_host \"$1\"" in takeDown)
        assertTrue("MF_HOST=\$(inst_found_host \"\$rbm_id\")" in script.substringAfter("action_rebuild_manifest() {").substringBefore("\n}\n"))
        val plan = Maintenance.rebuildManifest(entry.installation).planText()
        assertTrue(plan, "\nid=0a1b2c\n" in "\n$plan" && plan.startsWith("action=rebuild-manifest\n"))
        assertEquals(setOf("id"), reads("action_rebuild_manifest"))
        // with its manifest there, the instance is offered what an instance of ours is
        val whole = inventory(ours("0a1b2c", "csqtt.db,env,manifest.json"))
        assertFalse(Manager.entries(Classifier.classify(whole), Version(2, 6, 0), whole).single().offers.map { it.action }
            .any { it == Action.REBUILD_MANIFEST || it == Action.REPAIR })
    }
}
