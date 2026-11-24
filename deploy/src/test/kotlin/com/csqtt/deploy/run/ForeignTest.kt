// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.csqtt.deploy.run

import com.csqtt.deploy.bundle.Bundle
import com.csqtt.deploy.inspect.Classifier
import com.csqtt.deploy.inspect.Inventory
import com.csqtt.deploy.wire.LineCodec
import com.csqtt.deploy.wire.Report
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class ForeignTest {
    private fun lines(name: String): List<String> =
        checkNotNull(Classifier::class.java.getResource("fixtures/$name.report")) { "no fixture $name" }.readText()
            .lines().filter { it.startsWith("CSQTT_INV|") || it.startsWith("CSQTT_PRE|") }

    private fun inventory(lines: List<String>): Inventory =
        Inventory.from((LineCodec.parse(lines.joinToString("\n") + "\nCSQTT_END|${lines.size}\n") as Report.Complete).lines)

    private fun listing(lines: List<String>, name: String, purge: Boolean = false): ForeignRemoval.Listing? {
        val inv = inventory(lines)
        val c = Classifier.classify(inv)
        return ForeignRemoval.listing(c.installations.single { it.name == name }, c, inv, purge)
    }

    private val shared = listOf(
        "CSQTT_INV|fw|v=4|table=filter|kind=rule|text=${ForeignRemoval.UNTAGGED}",
        "CSQTT_INV|fw|v=6|table=filter|kind=rule|text=${ForeignRemoval.UNTAGGED}",
        "CSQTT_INV|file|path=/etc/letsencrypt/live/csqtt-ip-203-0-113-9|type=d",
        "CSQTT_INV|file|path=/etc/letsencrypt/renewal/csqtt-ip-203-0-113-9.conf|type=f",
        "CSQTT_INV|iprule|rule=30001:\tfrom all fwmark 0x7531/0x7531 lookup 30001",
    )

    @Test
    fun aStockMainInstallIsListedObjectByObject() {
        val l = checkNotNull(listing(lines("v253main-systemd-noble"), "csqtt.service"))
        assertEquals(ForeignRemoval.Role.MAIN, l.role)
        assertEquals(listOf("csqtt.service"), l.units)
        assertEquals(listOf("/usr/local/bin/csqtt", "/usr/local/lib/csqtt", "/opt/csqtt-certbot", "/run/csqtt", "/etc/sysctl.d/99-csqtt.conf",
            "/etc/sysctl.d/99-csqtt-udp-buffers.conf", "/var/log/csqtt-install.log"), l.paths)
        assertEquals(listOf("CSQTT_MANAGED"), l.tags)
        assertEquals("/etc/csqtt", l.store)
        assertFalse(l.untagged)
        assertTrue(l.will.joinToString(), "База /etc/csqtt останется" in l.will)
        assertTrue(l.will.joinToString(), l.will.any { it.startsWith("Останется: /etc/modules-load.d/bbr.conf") })
        // purge: the store goes too, said so
        assertTrue(checkNotNull(listing(lines("v253main-systemd-noble"), "csqtt.service", purge = true)).will
            .contains("База /etc/csqtt будет удалена вместе с клиентами"))
    }

    @Test
    fun whatEveryStockInstallSharesGoesWithTheLastOne() {
        // alone: the untagged FORWARD rule (#10), the LE lineage and its policy routing go with it
        val alone = checkNotNull(listing(lines("v253main-systemd-noble") + shared, "csqtt.service"))
        assertTrue(alone.untagged)
        assertEquals(listOf("csqtt-ip-203-0-113-9"), alone.lineages)
        assertEquals(listOf(30001), alone.tables)
        // beside the side install: both stay, named as left
        val both = lines("v253main-systemd-noble") + lines("v253side-systemd-noble") + shared
        val main = checkNotNull(listing(both, "csqtt.service"))
        assertFalse(main.untagged)
        assertEquals(emptyList<String>(), main.lineages)
        assertTrue(main.will.joinToString(), main.will.any { it.startsWith("Останется: общие с csqtt25.service") })
        // the side's own namespace: its unit, its directory, its tag; the main's untouched
        val side = checkNotNull(listing(both, "csqtt25.service"))
        assertEquals(ForeignRemoval.Role.SIDE, side.role)
        assertEquals(listOf("csqtt25.service"), side.units)
        assertTrue(side.paths.joinToString(), side.paths.all { it.contains("csqtt25") })
        assertTrue(side.tags.all { it.startsWith("CSQTT25_") })
    }

    @Test
    fun rulesALiveServerSharesTheTagOfStay() {
        // swg-panel took over the stock install (B15): its default-instance server tags its rules CSQTT_MANAGED as well
        val swg = listOf(
            "CSQTT_INV|proc|pid=70|exe=/usr/local/bin/csqtt-swg|deleted=0|comm=csqtt-swg|unit=swg-csqtt-csqtt1.service|ctr=|tun=csqtt1|sha=|" +
                "flags=--listen 0.0.0.0:46000 --desired /x --config-dir /opt/swg-csqtt/csqtt1",
            "CSQTT_INV|unit|id=swg-csqtt-csqtt1.service|load=loaded|active=active|sub=running|result=success|status=0|restarts=0|pid=70|" +
                "file_state=enabled|fragment=/etc/systemd/system/swg-csqtt-csqtt1.service|exec=/usr/local/bin/csqtt-swg|exec_ok=1|" +
                "flags=--listen 0.0.0.0:46000 --desired /x --config-dir /opt/swg-csqtt/csqtt1|pre=|pre_status=|env=",
        )
        val l = checkNotNull(listing(lines("v253main-systemd-noble") + shared + swg, "csqtt.service"))
        assertEquals(emptyList<String>(), l.tags)
        assertEquals(emptyList<Int>(), l.tables)
        assertTrue(l.will.joinToString(), l.will.any { it.contains("их же ведёт swg-csqtt-csqtt1.service") })
    }

    @Test
    fun twoZeroZeroLeavesItsSshOpeningAndItsUntaggedRulesNamed() {
        // 2.0.0's deploy tagged an SSH opening (run.sh never takes an INPUT rule for sshd's port) and its server added its
        // subnet's FORWARD and NAT untagged; the subnet-less clamp is generic
        val l = checkNotNull(listing(lines("v200-systemd-noble") +
            "CSQTT_INV|fw|v=4|table=mangle|kind=rule|text=${ForeignRemoval.CLAMP}", "csqtt.service"))
        assertEquals(listOf("CSQTT_MANAGED"), l.tags)
        val will = l.will.joinToString("\n")
        assertTrue(will, "Останется: правило CSQTT_MANAGED для SSH (порт 22): без него новый вход по SSH может закрыться" in l.will)
        assertTrue(will, "Останется: правила без метки для 10.66.67.0/24 (3, так их ставил 2.0.0)" in l.will)
        assertTrue(will, "Останется: общий TCPMSS-clamp в FORWARD (такой ставят и другие программы)" in l.will)
        // a 2.5 install opens no SSH port: nothing of that kind is said
        assertFalse(checkNotNull(listing(lines("v253main-systemd-noble"), "csqtt.service")).will.any { "SSH" in it })
    }

    @Test
    fun theUntaggedRuleIsTakenOnlyFromTheLineThatPutsItThere() {
        // B11b review F2: a 2.5 network-up adds it when missing; 2.1.9 never does, so the one on its box is someone else's
        val v219 = checkNotNull(listing(lines("v219-systemd-noble") + shared.take(2), "csqtt.service"))
        assertFalse(v219.untagged)
        assertTrue(v219.will.joinToString(), "Останется: правило FORWARD RELATED,ESTABLISHED: эта установка его не ставила" in v219.will)
        assertTrue(checkNotNull(listing(lines("v253main-systemd-noble") + shared.take(2), "csqtt.service")).untagged)
    }

    @Test
    fun aSymlinkedStoreLosesItsLinkAndTheListingSaysSo() {
        // B11b review F3: the run unlinks a symlinked config dir and never touches what it points to; the listing says so
        val linked = lines("v253main-systemd-noble").map {
            if (it.startsWith("CSQTT_INV|store|dir=/etc/csqtt|")) "$it|link=/srv/csqtt-data" else it
        }
        val purge = checkNotNull(listing(linked, "csqtt.service", purge = true))
        assertEquals("/srv/csqtt-data", purge.storeLink)
        assertTrue(purge.will.joinToString(), "Ссылка /etc/csqtt будет удалена; база в /srv/csqtt-data останется (её файлы не трогаются)" in purge.will)
        assertTrue("База /etc/csqtt останется" in checkNotNull(listing(linked, "csqtt.service")).will)
    }

    @Test
    fun mrttBesideKeepsTheRoutingItSharesAndNoneOfTheStockTags() {
        // B11b review F4: MRTT tags its rules MRTT_MANAGED, so the stock main's CSQTT_MANAGED rules go; the default instance's
        // policy routing (30001) is MRTT's as well, so it stays
        val l = checkNotNull(listing(lines("v253main-systemd-noble") + lines("mrtt-systemd-noble") + shared.drop(4), "csqtt.service"))
        assertEquals(listOf("CSQTT_MANAGED"), l.tags)
        assertEquals(emptyList<Int>(), l.tables)
        assertTrue(l.will.joinToString(), l.will.any { it.startsWith("Останется: маршрутизация 30001/1066: её же ведёт mrtt") })
    }

    @Test
    fun noneButAStockSystemdInstallIsAForeignRemoval() {
        // MRTT, swg's own and ours are not this action's
        assertNull(listing(lines("mrtt-systemd-noble"), "mrtt.service"))
        assertNull(listing(lines("handmade-systemd-noble"), Classifier.classify(inventory(lines("handmade-systemd-noble"))).installations.single().name))
    }

    @Test
    fun everyValueAPlanCarriesIsInItsRolesNamespaceAndNothingElseIs() {
        val inv = inventory(lines("v253main-systemd-noble") + shared)
        val c = Classifier.classify(inv)
        val install = c.installations.single { it.name == "csqtt.service" }
        val plan = ForeignRemoval.plan(install, inv, checkNotNull(ForeignRemoval.listing(install, c, inv, purge = true))).planText()
        val values = plan.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals("csqtt.service", values["expect_unit"])
        assertEquals(64, values["expect_sha"]?.length)
        val checks = buildList {
            for ((key, kind) in listOf("units" to "unit", "paths" to "path", "tags" to "tag", "tables" to "table", "lineage" to "lineage")) {
                values[key].orEmpty().split(',').filter { it.isNotEmpty() }.forEach { add(Triple(kind, it, 0)) }
            }
            add(Triple("store", values.getValue("store"), 0))
            // what run.sh refuses whatever a plan says
            add(Triple("path", "/etc/passwd", 1)); add(Triple("path", "/usr/local/bin/csqtt/../../../etc", 1))
            add(Triple("unit", "ssh.service", 1)); add(Triple("unit", "csqtt25.service", 1)); add(Triple("store", "/etc", 1))
            add(Triple("tag", "SQC-0A1B2C_MANAGED", 1)); add(Triple("table", "254", 1))
            add(Triple("lineage", "csqtt-ip-1-2-3-4/../..", 1)); add(Triple("lineage", "csqtt-ip-", 1)); add(Triple("lineage", "other", 1))
        }
        val script = Bundle.bytes("run.sh").toString(Charsets.UTF_8)
        val frOk = script.substring(script.indexOf("fr_ok() {"), script.indexOf("\n}\n", script.indexOf("fr_ok() {")) + 3)
        val dir = kotlin.io.path.createTempDirectory("csqtt-fr").toFile()
        try {
            val sh = File(dir, "check.sh").apply {
                writeText(frOk + checks.joinToString("") { (kind, value, _) -> "fr_ok main $kind '$value'; echo \$?\n" })
            }
            val proc = ProcessBuilder("sh", sh.path).redirectErrorStream(true).start()
            assertTrue(proc.waitFor(30, TimeUnit.SECONDS))
            val codes = proc.inputStream.bufferedReader().readText().trim().lines()
            assertEquals(checks.size, codes.size)
            checks.zip(codes).forEach { (check, code) -> assertEquals("fr_ok main ${check.first} ${check.second}", check.third.toString(), code) }
        } finally {
            dir.deleteRecursively()
        }
    }
}
