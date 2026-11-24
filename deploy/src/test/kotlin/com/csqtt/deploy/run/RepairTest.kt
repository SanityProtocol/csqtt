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
import java.io.File
import java.util.concurrent.TimeUnit

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
        // MRTT's beta under the stock unit name, stopped, alike: the run keeps the drop-in for its unit file (B11c verify N1)
        val beta = stopped.map { if (it.startsWith("CSQTT_INV|store|dir=/etc/csqtt|")) it.replace("|schema=", "|schema=devices_json,") else it }
        Classifier.classify(inventory(beta + dropIn)).let { c ->
            assertEquals(com.csqtt.deploy.inspect.Owner.MRTT_BETA, c.installations.single().owner)
            assertFalse(c.problems.any { it.code == "S1" })
        }
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
        // and its removal says what a lost manifest cannot find (B11c verify)
        val remove = com.csqtt.deploy.plan.Proposer.offerPlan(entry, entry.offers.last(), c, inv, Version(2, 6, 0)).will
        assertTrue(remove.joinToString(), remove.last().startsWith("Порт, который он открыл в общей зоне firewalld"))
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

    private fun function(name: String): String {
        val at = script.indexOf("\n$name() {") + 1
        return script.substring(at, script.indexOf("\n}\n", at) + 3)
    }

    @Test
    fun aCorruptManifestsHostListTakesNothingButTheInstancesOwn() {
        // the zoo's corrupt cell (P2-PLAN §2.4, tier A): a removal closed whatever the manifest's host list named, an admin's ufw
        // rule or another instance's; now only what is this instance's by its own names, the manifest trusted for nothing else
        val dir = kotlin.io.path.createTempDirectory("csqtt-corrupt").toFile()
        try {
            // its flags as «Восстановить» keeps them (a unit's and a container's read alike)
            File(dir, "unit.saved").writeText("# release 2.6.0-1abc2def\n[Service]\n" +
                "ExecStart=/opt/sanityprotocol/csqtt/0a1b2c/current/csqtt-server --listen 0.0.0.0:49784 --web-port 45734 --iface sqc0\n")
            val added = "Added user rules (see 'ufw status' for running firewall):\nufw allow 22/tcp\n" +
                "ufw allow 49784/udp comment 'sqc-0a1b2c'\nufw allow 45734/tcp comment 'sqc-0a1b2c'\nufw allow 8080/tcp comment 'admin'\n" +
                "ufw allow 51000/udp comment 'sqc-9f8e7d'\n"
            val named = listOf(
                "ufw rule=49784/udp", "ufw rule=45734/tcp", "ufw rule=8080/tcp", "ufw rule=22/tcp", "ufw rule=51000/udp",
                "firewalld-port zone=public port=49784/udp", "firewalld-port zone=public port=22/tcp",
                "firewalld-port zone=sqc0a1b2c port=45734/tcp", "firewalld-port zone=public port=",
                "firewalld-zone zone=sqc0a1b2c", "firewalld-zone zone=public", "firewalld-zone zone=sqc9f8e7d",
                "firewalld-policy policy=sqc0a1b2c-fwd", "firewalld-policy policy=allow-host-ipv6", "firewalld-reload",
                "iptables-input ports=22/tcp")
            fun ours(ufw: String): List<String> {
                val sh = File(dir, "check.sh")
                sh.writeText(ufw + "in_unit=sanity-csqtt-zz-no-such-unit.service in_state=${dir.path}\n" + function("inst_ours_only") +
                    "inst_ours_only 0a1b2c <<'EOF'\n" + named.joinToString("\n") + "\nEOF\n")
                val proc = ProcessBuilder("sh", sh.path).redirectErrorStream(true).start()
                assertTrue(proc.waitFor(30, TimeUnit.SECONDS))
                return proc.inputStream.bufferedReader().readText().lines().filter { it.isNotEmpty() }
            }
            val firewalld = listOf("firewalld-port zone=public port=49784/udp", "firewalld-port zone=sqc0a1b2c port=45734/tcp",
                "firewalld-zone zone=sqc0a1b2c", "firewalld-policy policy=sqc0a1b2c-fwd", "firewalld-reload")
            // ufw's: only its own, commented sqc-0a1b2c (ufw keeps them so on or off)
            val stub = "ufw() { [ \"\$1 \$2\" = 'show added' ] && cat ${dir.path}/added; }\n"
            File(dir, "added").writeText(added)
            assertEquals(listOf("ufw rule=49784/udp", "ufw rule=45734/tcp") + firewalld, ours(stub))
            // none of its own left in ufw: none taken, whatever the manifest names
            File(dir, "added").writeText(added.lines().filter { "sqc-0a1b2c" !in it }.joinToString("\n"))
            assertEquals(firewalld, ours(stub))
        } finally {
            dir.deleteRecursively()
        }
        // removal reads the manifest's host list through it (a lost manifest's are found by their names already)
        val takeDown = function("inst_take_down")
        assertTrue(takeDown, "grep -v '^service-' | inst_ours_only \"\$1\")" in takeDown)
    }

    @Test
    fun aCutRunsUndoTakesOnlyWhatIsTheInstancesOwn() {
        // the guard (the operator's word of 2026-11-24): «Продолжить» and a move's settle undo what the manifest they read from
        // the box names; a corrupt one must reach no path outside our roots and no host change of another's
        fun sh(body: String): List<String> {
            val dir = kotlin.io.path.createTempDirectory("csqtt-guard").toFile()
            try {
                val f = File(dir, "check.sh")
                f.writeText(body)
                val proc = ProcessBuilder("sh", f.path).redirectErrorStream(true).start()
                assertTrue(proc.waitFor(30, TimeUnit.SECONDS))
                return proc.inputStream.bufferedReader().readText().lines().filter { it.isNotEmpty() }
            } finally {
                dir.deleteRecursively()
            }
        }
        val own = listOf("dir /opt/sanityprotocol/csqtt/0a1b2c", "dir /var/lib/sanityprotocol/csqtt/0a1b2c",
            "unit /etc/systemd/system/sanity-csqtt-0a1b2c.service", "file /var/lib/sanityprotocol/csqtt/0a1b2c/docker",
            "file /etc/systemd/system/csqtt.service.d/sanity-move.conf", "container sanity-csqtt-0a1b2c",
            "image sanity-csqtt:2.6.0-1abc2def", "image sanity-csqtt-rootfs:9f8e7d6c")
        val foreign = listOf("dir /srv/admin", "dir /", "dir /var/lib/sanityprotocol/csqtt/9f8e7d", "dir /opt/sanityprotocol",
            "unit /etc/systemd/system/ssh.service", "unit /etc/systemd/system/sanity-csqtt-9f8e7d.service",
            "file /etc/passwd", "file /etc/systemd/system/../../../root/x.service.d/sanity-move.conf",
            "container sanity-csqtt-9f8e7d", "container postgres", "image postgres:16", "dir /opt/sanityprotocol/csqtt/0a1b2c/../../..")
        val lines = (own + foreign).shuffled(java.util.Random(7))
        assertEquals(lines.filter { it in own }, sh(function("mf_objects_ours") + "mf_objects_ours 0a1b2c <<'EOF'\n" +
            lines.joinToString("\n") + "\nEOF\n"))
        // the host list: a floor or the server's tuning by its key, Docker's unit as a create enabled or started it
        val lists = Regex("(?s)\nHOST_FLOORS='.*?'\n").find(script)!!.value + Regex("(?s)\nHOST_TUNING='.*?'\n").find(script)!!.value
        val host = listOf("sysctl key=net.core.rmem_max was=212992 set=67108864", "sysctl key=net.ipv4.ip_forward was=0 set=1",
            "sysctl key=net.ipv4.tcp_congestion_control was=cubic set=bbr", "sysctl key=kernel.panic was=0 set=1",
            "sysctl key=net.ipv4.ip_forward_use_pmtu was=0 set=1", "sysctl key= was=0", "service-started unit=docker.service",
            "service-enabled unit=docker.service", "service-enabled unit=ssh.service", "service-started unit=")
        assertEquals(listOf(host[0], host[1], host[2], host[6], host[7]),
            sh(lists + "ufw() { :; }\nin_unit=sanity-csqtt-zz-no-such.service in_state=/nonexistent\n" + function("inst_ours_only") +
                "inst_ours_only 0a1b2c <<'EOF'\n" + host.joinToString("\n") + "\nEOF\n"))
        // review F1: the cut run's own ports come from its plan too, wherever the box's flags went (a «Продолжить» cut again after
        // its unit file was taken): a default-zone port of its own is still closed, another's is not
        assertEquals(listOf("firewalld-port zone=public port=49784/udp", "firewalld-port zone=public port=45734/tcp"),
            sh("ufw() { :; }\nin_unit=sanity-csqtt-zz-no-such.service in_state=/nonexistent in_ports='49784/udp 45734/tcp'\n" +
                function("inst_ours_only") + "inst_ours_only 0a1b2c <<'EOF'\nfirewalld-port zone=public port=49784/udp\n" +
                "firewalld-port zone=public port=22/tcp\nfirewalld-port zone=public port=45734/tcp\nEOF\n"))
        // both paths read the cut run's manifest through the two, with the plan's ports (a removal's own stays as it was)
        for (path in listOf(function("mv_settle"), function("action_create"))) {
            assertTrue(path, "| mf_objects_ours \"\$cr_id\")" in path)
            assertTrue(path, "| in_unit=\$cr_unit in_state=\$cr_state in_ports=\$cr_open inst_ours_only \"\$cr_id\")" in path)
        }
        val both = function("mv_settle") + function("action_create")
        assertFalse(Regex("MF_OBJECTS=\\$\\(mf_objects_of \"[^\"]+\"\\)").containsMatchIn(both))
        assertFalse(Regex("MF_HOST=\\$\\(mf_host_of \"[^\"]+\"\\)").containsMatchIn(both))
    }
}
