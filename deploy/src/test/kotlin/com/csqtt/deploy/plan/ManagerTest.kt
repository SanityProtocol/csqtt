// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.csqtt.deploy.plan

import com.csqtt.deploy.inspect.Classification
import com.csqtt.deploy.inspect.Classifier
import com.csqtt.deploy.inspect.Inventory
import com.csqtt.deploy.wire.LineCodec
import com.csqtt.deploy.wire.Report
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/** The instance manager on the deploy zoo's real reports (B5's fixtures) and on boxes built for one question each. */
class ManagerTest {
    private val carried = Version(2, 6, 0)

    private fun inventory(text: String): Inventory = Inventory.from((LineCodec.parse(text) as Report.Complete).lines)

    private fun fixture(name: String): Pair<Classification, Inventory> {
        val text = checkNotNull(Classifier::class.java.getResource("fixtures/$name.report")) { "no fixture $name" }.readText()
        val inv = inventory(text)
        return Classifier.classify(inv) to inv
    }

    private fun box(vararg lines: String): Pair<Classification, Inventory> {
        val inv = inventory(lines.joinToString("\n") + "\nCSQTT_END|${lines.size}\n")
        return Classifier.classify(inv) to inv
    }

    private fun offers(name: String, version: Version = carried): Pair<Entry, List<Action>> {
        val entry = Manager.entries(fixture(name).first, version).single()
        return entry to entry.offers.map { it.action }
    }

    @Test
    fun eachOwnerAndStateGetsItsActions() {
        // a running stock 2.5: «Обновить» (the move) first, «Подключиться», and removal folded, typed
        offers("v253main-systemd-noble").let { (entry, actions) ->
            assertEquals(Compatibility.COMPATIBLE, entry.compatibility)
            assertEquals(listOf(Action.UPDATE, Action.CONNECT, Action.REMOVE), actions)
            assertTrue(entry.offers.first().recommended)
            val remove = entry.offers.last()
            assertTrue(remove.folded)
            assertEquals("удалить csqtt.service", remove.confirm)
        }
        // the 2.1 line: «Обновить до 2.6» with its warning, never the plain update
        offers("v219-systemd-noble").let { (entry, actions) ->
            assertEquals(Compatibility.OUTDATED_21, entry.compatibility)
            assertEquals(listOf(Action.UPDATE_FROM_21, Action.REMOVE), actions)
            assertEquals("Клиенты 2.1 перестанут подключаться", entry.offers.first().warning)
        }
        offers("v200-systemd-noble").let { (_, actions) -> assertEquals(Action.UPDATE_FROM_21, actions.first()) }
        // the side tool and the app's side: the same move
        assertEquals(Action.UPDATE, offers("sidetool-systemd-noble").second.first())
        assertEquals(Action.UPDATE, offers("v253side-systemd-noble").second.first())
        // what is not ours to touch: no action, and why
        for ((name, note) in listOf("mrtt-systemd-noble" to "Другой протокол (MRTT)", "handmade-systemd-noble" to "Чужая установка")) {
            val (entry, actions) = offers(name)
            assertTrue(name, actions.isEmpty())
            assertEquals(name, note, entry.note)
        }
        // broken stock: «Починить», the same move; a crash loop too
        assertEquals(Action.REPAIR, offers("v253main-systemd-noble+B1").second.first())
        assertEquals(Action.REPAIR, offers("slim-systemd-noble+B18").second.first())
        assertEquals(Action.REPAIR, offers("v253main-systemd-noble+B5").second.first())
        // the stock uninstall's leftovers: removal only, typed (its store holds the clients)
        offers("v253main-systemd-noble+B13").let { (entry, actions) ->
            assertEquals(listOf(Action.REMOVE_LEFTOVERS), actions)
            assertNotNull(entry.offers.single().confirm)
        }
    }

    @Test
    fun aNewerBuildIsNeverUpdated() {
        // M-150: an app carrying 2.5.0 meets 2.5.3 — connect or remove, «Обновить» refused with the reason
        val (entry, actions) = offers("v253main-systemd-noble", Version(2, 5, 0))
        assertEquals(Compatibility.NEWER_THAN_APP, entry.compatibility)
        assertFalse(Action.UPDATE in actions || Action.REPAIR in actions)
        assertEquals("Обновить нельзя: установлена более новая версия", entry.note)
    }

    @Test
    fun versionsCompareAsReleasesDo() {
        assertTrue(Version.parse("2.5.10")!! > Version.parse("2.5.9")!!)
        assertTrue(Version.parse("2.5.1-2")!! > Version.parse("2.5.1")!!)
        assertTrue(Version.parse("2.6.0")!! > Version.parse("2.5.3 (SanityProtocol build of amurcanov 2.5.0)")!!)
        assertNull(Version.parse("v1.0-beta1"))
    }

    @Test
    fun boxProblemsAreRepairedOrNamed() {
        assertEquals(listOf(BoxIssue("B14", Manager.boxIssues(fixture("v253main-systemd-noble+B14").first).single().detail, true)),
            Manager.boxIssues(fixture("v253main-systemd-noble+B14").first))
        assertFalse(Manager.boxIssues(fixture("v253main-systemd-noble+B9").first).single().repairable)
        assertTrue(Manager.boxIssues(fixture("v253main-systemd-noble+B11").first).isEmpty())
    }

    @Test
    fun aProposalCollidesWithNothing() {
        // the stock 2.5.3 seed: its ports, csqtt1 and 10.66.67.0/24 are taken; the box's own addresses too
        val (c, inv) = fixture("v253main-systemd-noble")
        repeat(200) { seed ->
            val p = Proposer.propose(c, inv, Random(seed))
            assertEquals(emptyList<ProposalProblem>(), Proposer.problems(p, c, inv))
            assertTrue(p.udpPort in Proposer.PORT_LO..Proposer.PORT_HI && p.webPort in Proposer.PORT_LO..Proposer.PORT_HI)
            assertTrue(p.udpPort != p.webPort && p.udpPort !in Proposer.FAMILY_PORTS && p.webPort !in Proposer.FAMILY_PORTS)
            assertEquals("sqc0", p.iface)
            assertEquals("10.77.0.0/24", p.subnet)
            assertEquals(2, p.slot)
            assertEquals("sqc-${p.id}", p.instance)
        }
    }

    @Test
    fun takenSubnetsPortsAndSlotsAreSkippedOrRefused() {
        // 10.77.0.0/24 an address, 10.77.1.0/24 a route, 10.77.2.0/24 a claim: the first free is 10.77.3.0/24
        val (c, inv) = box(
            "CSQTT_INV|addr|dev=eth0|cidr=10.77.0.5/24",
            "CSQTT_INV|route|dst=10.77.1.0/24|dev=wg0|table=main",
            "CSQTT_INV|addr|dev=sqc0|cidr=10.77.2.1/24",
            "CSQTT_INV|listen|proto=udp|addr=0.0.0.0|port=41000|comm=wireguard|pid=1",
        )
        val p = Proposer.propose(c, inv, Random(7))
        assertEquals("10.77.3.0/24", p.subnet)
        assertEquals("sqc1", p.iface)
        // an edit that takes a listener's port, the family's, or an unknown slot says which field and why
        val bad = p.copy(udpPort = 41000, webPort = 46002, slot = 9, webBind = "0.0.0.0", subnet = "10.77.1.0/24")
        assertEquals(setOf("udpPort", "webPort", "slot", "webBind", "subnet"), Proposer.problems(bad, c, inv).map { it.field }.toSet())
        // six instances of ours already hold slots 2–7: a seventh is refused with the reason
        val ours = (2..7).flatMap { slot ->
            val id = "00000$slot"
            listOf("CSQTT_INV|unit|id=sanity-csqtt-$id.service|load=loaded|active=active|sub=running|result=success|status=0|restarts=0|" +
                "pid=0|file_state=enabled|fragment=/etc/systemd/system/sanity-csqtt-$id.service|" +
                "exec=/opt/sanityprotocol/csqtt/$id/current/csqtt-server|exec_ok=1|flags=--listen 0.0.0.0:4${slot}111 " +
                "--config-dir /var/lib/sanityprotocol/csqtt/$id --instance sqc-$id --instance-slot $slot|pre=|pre_status=|env=")
        }
        val (full, fullInv) = box(*ours.toTypedArray())
        try {
            Proposer.propose(full, fullInv, Random(1))
            fail("a seventh instance was proposed")
        } catch (refused: ProposalRefused) {
            assertTrue(refused.reason, "не поддерживается" in refused.reason)
        }
    }

    @Test
    fun aPlanSaysWhatChangesAndWhatDoesNot() {
        val (c, inv) = fixture("v253main-systemd-noble")
        val create = Proposer.createPlan(Proposer.propose(c, inv, Random(3)), c)
        assertEquals(Action.CREATE, create.action)
        assertEquals(listOf("csqtt.service — без изменений"), create.unchanged)
        val entry = Manager.entries(c, carried).single()
        val move = Proposer.offerPlan(entry, entry.offers.first(), c)
        assertTrue(move.will.single(), "переедет в наш формат" in move.will.single())
        // the run finds the unit and the binary it was planned on, or fails cleaned and the engine inspects again
        assertEquals("csqtt.service", move.expect.unit)
        assertEquals(64, move.expect.binarySha?.length)
        val remove = Proposer.offerPlan(entry, entry.offers.last(), c)
        assertEquals("удалить csqtt.service", remove.confirm)
    }
}
