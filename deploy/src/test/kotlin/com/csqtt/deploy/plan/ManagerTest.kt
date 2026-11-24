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
        val (c, inv) = fixture(name)
        val entry = Manager.entries(c, version, inv).single()
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
        // the rest are named, never dropped (B6 review F10)
        assertEquals(listOf("B11" to false), Manager.boxIssues(fixture("v253main-systemd-noble+B11").first).map { it.code to it.repairable })
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
        val entry = Manager.entries(c, carried, inv).single()
        val move = Proposer.offerPlan(entry, entry.offers.first(), c, inv, carried)
        assertTrue(move.will.single(), "переедет в наш формат" in move.will.single())
        // the move becomes an instance of ours that keeps the stock install's ports, held by nothing but that install
        val becomes = move.proposal!!
        assertEquals(46000 to 46002, becomes.udpPort to becomes.webPort)
        assertEquals("sqc0" to "10.77.0.0/24", becomes.iface to becomes.subnet)
        assertEquals(setOf(46000, 46002), move.expect.ownPorts)
        assertEquals("/usr/local/bin/csqtt", move.expect.binaryPath)
        // the run finds the unit and the binary it was planned on, or fails cleaned and the engine inspects again
        assertEquals("csqtt.service", move.expect.unit)
        assertEquals(64, move.expect.binarySha?.length)
        val remove = Proposer.offerPlan(entry, entry.offers.last(), c, inv, carried)
        assertEquals("удалить csqtt.service", remove.confirm)
        assertTrue(remove.will.single(), "база /etc/csqtt останется" in remove.will.single())
    }

    private fun ours(id: String, slot: Int, state: String, version: String = "2.6.0", sub: String = if (state == "active") "running" else "dead") = arrayOf(
        "CSQTT_INV|exe|sha=${id}000000000000000000000000000000000000000000000000000000000|path=/opt/sanityprotocol/csqtt/$id/current/csqtt-server|" +
            "size=1|label=v$version|caption=1|startup=$version|swg=1|mrtt=0",
        "CSQTT_INV|bin|path=/opt/sanityprotocol/csqtt/$id/current/csqtt-server|sha=${id}000000000000000000000000000000000000000000000000000000000",
        "CSQTT_INV|unit|id=sanity-csqtt-$id.service|load=loaded|active=$state|sub=$sub|result=${if (state == "failed") "exit-code" else "success"}|" +
            "status=0|restarts=${if (state == "activating") 5 else 0}|pid=0|file_state=enabled|fragment=/etc/systemd/system/sanity-csqtt-$id.service|" +
            "exec=/opt/sanityprotocol/csqtt/$id/current/csqtt-server|exec_ok=1|flags=--listen 0.0.0.0:4${slot}111 " +
            "--config-dir /var/lib/sanityprotocol/csqtt/$id --instance sqc-$id --iface sqc$slot --instance-slot $slot|pre=|pre_status=|env=",
        "CSQTT_INV|store|dir=/var/lib/sanityprotocol/csqtt/$id|files=csqtt.db,env,manifest.json|lock=none|schema=table:client_groups|env_keys=CSQTT_WEB_PASS|public_ip=|domain=",
    )

    private fun stockMain(active: String = "active", label: String = "v2.5.3", fileState: String = "enabled") = arrayOf(
        "CSQTT_INV|exe|sha=${"5".repeat(64)}|path=/usr/local/bin/csqtt|size=1|label=$label|caption=0|startup=${label.removePrefix("v")}|swg=1|mrtt=0",
        "CSQTT_INV|bin|path=/usr/local/bin/csqtt|sha=${"5".repeat(64)}",
        "CSQTT_INV|unit|id=csqtt.service|load=loaded|active=$active|sub=${if (active == "active") "running" else "dead"}|result=success|status=0|" +
            "restarts=0|pid=0|file_state=$fileState|fragment=/etc/systemd/system/csqtt.service|exec=/usr/local/bin/csqtt|exec_ok=1|flags=|pre=|pre_status=|env=",
        "CSQTT_INV|store|dir=/etc/csqtt|files=csqtt.db,csqtt.env|lock=none|schema=table:client_groups|env_keys=CSQTT_WEB_PASS|public_ip=|domain=",
    )

    @Test
    fun aServerSwgPanelTookOverIsNeverStartedAgain() {
        // swg-panel adopted the stock install: csqtt.service disabled and stopped, swg's server on 46000 and csqtt1
        val (c, swgInv) = box(*stockMain(active = "inactive", fileState = "disabled"),
            "CSQTT_INV|proc|pid=70|exe=/usr/local/bin/csqtt-swg|deleted=0|comm=csqtt-swg|unit=swg-csqtt-csqtt1.service|ctr=|tun=csqtt1|sha=|" +
                "flags=--listen 0.0.0.0:46000 --desired /x --config-dir /opt/swg-csqtt/csqtt1",
            "CSQTT_INV|unit|id=swg-csqtt-csqtt1.service|load=loaded|active=active|sub=running|result=success|status=0|restarts=0|pid=70|" +
                "file_state=enabled|fragment=/etc/systemd/system/swg-csqtt-csqtt1.service|exec=/usr/local/bin/csqtt-swg|exec_ok=1|" +
                "flags=--listen 0.0.0.0:46000 --desired /x --config-dir /opt/swg-csqtt/csqtt1|pre=|pre_status=|env=")
        val stock = Manager.entries(c, carried, swgInv).single { it.installation.name == "csqtt.service" }
        assertEquals(listOf(Action.REMOVE), stock.offers.map { it.action })
        assertTrue(stock.offers.single().folded && stock.offers.single().confirm != null)
        assertEquals("UDP 46000 занят: swg-csqtt-csqtt1.service", stock.note)
    }

    @Test
    fun anInstallCutOffIsRepairedAndAStoreWithoutItsDatabaseIsNot() {
        // B2: the binary with no unit, an install cut off: the move, not deletion (B6 review F2)
        assertEquals(Action.REPAIR, offers("v253main-systemd-noble+B2").second.first())
        // B8: the WAL without its database: nothing to move or read (F6)
        offers("v253main-systemd-noble+B8").let { (entry, actions) ->
            assertFalse(Action.REPAIR in actions || Action.UPDATE in actions || Action.CONNECT in actions)
            assertTrue(entry.note.orEmpty(), entry.note.orEmpty().isNotEmpty())
        }
    }

    @Test
    fun noBuildNewerThanOursIsEverRebuiltFromOurs() {
        // any line: a 2.7 or a 2.10 stock build is newer, never moved (F3)
        for (label in listOf("v2.7.0", "v2.10.0", "v3.0.0")) {
            val entry = box(*stockMain(label = label)).let { (c, i) -> Manager.entries(c, carried, i) }.single()
            assertEquals(label, Compatibility.NEWER_THAN_APP, entry.compatibility)
            assertEquals(label, listOf(Action.CONNECT, Action.REMOVE), entry.offers.map { it.action })
        }
        // ours from another phone's newer app: run, read, removed; never repaired or reinstalled from our older core (F4)
        for ((state, sub) in listOf("activating" to "auto-restart", "active" to "running", "failed" to "failed")) {
            val entry = box(*ours("0a1b2c", 2, state, version = "2.6.1", sub = sub)).let { (c, i) -> Manager.entries(c, carried, i) }.single()
            val actions = entry.offers.map { it.action }
            assertFalse(state, actions.any { it in setOf(Action.REPAIR, Action.UPDATE, Action.REINSTALL_CLEAN, Action.RESTORE) })
            assertEquals("Обновить нельзя: установлена более новая версия", entry.note)
        }
        assertEquals(null, Version.parse("99999999999.1"))
    }

    private fun kept(id: String, slot: Int, release: String) =
        "CSQTT_INV|store|dir=/var/lib/sanityprotocol/csqtt/$id|files=csqtt.db,env,manifest.json,unit.saved|lock=none|" +
            "schema=table:client_groups|env_keys=CSQTT_WEB_PASS|public_ip=|domain=|kept_flags=--listen 0.0.0.0:4${slot}111 --web-port " +
            "5${slot}222 --config-dir /var/lib/sanityprotocol/csqtt/$id --instance sqc-$id --iface sqc$slot --tun-addr 10.77.$slot.1/24 " +
            "--instance-slot ${slot + 2}|kept_release=$release"

    @Test
    fun aKeptStoreKeepsItsPlaceAndOnlyAKeptStoreIsRestored() {
        // B11a review F3: «Удалить» kept sqc-0a1b2c's store; a new instance takes none of its ports, interface, subnet or slot
        val (c, inv) = box(kept("0a1b2c", 0, "2.6.0-5dafed6b"))
        val entry = Manager.entries(c, carried, inv).single()
        assertEquals(listOf(Action.RESTORE, Action.REMOVE_LEFTOVERS), entry.offers.map { it.action })
        assertTrue(entry.offers.first().recommended)
        assertEquals(Compatibility.COMPATIBLE, entry.compatibility)
        for (seed in 1..20) {
            val p = Proposer.propose(c, inv, Random(seed))
            assertEquals("sqc1", p.iface)
            assertEquals("10.77.1.0/24", p.subnet)
            assertEquals(3, p.slot)
            assertTrue(p.udpPort != 40111 && p.webPort != 40111 && p.udpPort != 50222 && p.webPort != 50222)
        }
        // a store of ours with no kept flags (a create cut off: «Продолжить» is its): removed, never «restored»
        val (c2, inv2) = box("CSQTT_INV|store|dir=/var/lib/sanityprotocol/csqtt/0a1b2c|files=csqtt.db,env,manifest.json|lock=none|" +
            "schema=table:client_groups|env_keys=CSQTT_WEB_PASS|public_ip=|domain=")
        Manager.entries(c2, carried, inv2).single().offers.let { offers ->
            assertEquals(listOf(Action.REMOVE_LEFTOVERS), offers.map { it.action })
            assertTrue(offers.single().recommended)
        }
        // F6: a store a newer build ran (another phone's app) is not restored from our older core
        val (c3, inv3) = box(kept("0a1b2c", 0, "2.6.1-0060bd53"))
        Manager.entries(c3, carried, inv3).single().let { newer ->
            assertEquals(Compatibility.NEWER_THAN_APP, newer.compatibility)
            assertEquals(listOf(Action.REMOVE_LEFTOVERS), newer.offers.map { it.action })
            // verify N2: its clients are the newer app's to bring back, so its removal is folded and typed, recommended to nobody
            val removal = newer.offers.single()
            assertTrue(removal.folded && !removal.recommended && removal.confirm != null)
            assertEquals("Восстановить можно приложением версии 2.6.1 или новее", newer.note)
        }
    }

    @Test
    fun removalKeepsTheStoreAndPurgingIsTyped() {
        val (c, inv) = box(*ours("0a1b2c", 2, "active"))
        val entry = Manager.entries(c, carried, inv).single()
        val remove = entry.offers.single { it.action == Action.REMOVE }
        assertEquals(null, remove.confirm)
        assertTrue(Proposer.offerPlan(entry, remove, c, inv, carried).will.single().contains("«Восстановить» вернёт клиентов"))
        assertEquals("удалить sanity-csqtt-0a1b2c.service вместе с базой", entry.offers.single { it.action == Action.REMOVE_WITH_STORE }.confirm)
    }

    @Test
    fun anEditedProposalStaysClearOfTheFamilyAndOfPublicSpace() {
        val (c, inv) = fixture("v253main-systemd-noble")
        val p = Proposer.propose(c, inv, Random(5))
        val fields = { q: Proposal -> Proposer.problems(q, c, inv).map { it.field to it.reason } }
        assertEquals(listOf("iface" to "имя занято программами csqtt и MRTT"), fields(p.copy(iface = "csqtt7")))
        for (net in listOf("1.1.1.0/24", "224.0.0.0/24", "10.66.70.0/24")) assertEquals(net, "subnet", fields(p.copy(subnet = net)).single().first)
        assertEquals(listOf("subnet" to "адрес сети: 10.77.5.0/24"), fields(p.copy(subnet = "10.77.5.9/24")))
        assertEquals(emptyList<Pair<String, String>>(), fields(p.copy(subnet = "192.168.240.0/24")))
        // swg-panel's pools are never proposed
        repeat(300) { seed ->
            val q = Proposer.propose(c, inv, Random(seed))
            for (port in listOf(q.udpPort, q.webPort)) assertFalse("$port", port in Proposer.SWG_PORTS)
        }
    }

    @Test
    fun roundTwoHeldOnlyWhatCouldStartAndMovesThatCanBeMade() {
        // a 2.1 binary failing on a 2.5 store (B7): «Обновить до 2.6» is its cure (B6 verify N1)
        assertEquals(Action.UPDATE_FROM_21, offers("v250-systemd-noble+B7").second.first())
        // a running server clashing with another on its port (B9) keeps its own offers (N4)
        assertEquals(Action.UPDATE, fixture("v253main-systemd-noble+B9").let { (c, i) -> Manager.entries(c, carried, i) }
            .single { it.installation.name == "csqtt.service" }.offers.first().action)
        val swg = arrayOf(
            "CSQTT_INV|proc|pid=70|exe=/usr/local/bin/csqtt-swg|deleted=0|comm=csqtt-swg|unit=swg-csqtt-csqtt1.service|ctr=|tun=csqtt1|sha=|" +
                "flags=--listen 0.0.0.0:46000 --desired /x --config-dir /opt/swg-csqtt/csqtt1",
            "CSQTT_INV|unit|id=swg-csqtt-csqtt1.service|load=loaded|active=active|sub=running|result=success|status=0|restarts=0|pid=70|" +
                "file_state=enabled|fragment=/etc/systemd/system/swg-csqtt-csqtt1.service|exec=/usr/local/bin/csqtt-swg|exec_ok=1|" +
                "flags=--listen 0.0.0.0:46000 --desired /x --config-dir /opt/swg-csqtt/csqtt1|pre=|pre_status=|env=")
        // a store alone, left beside a live server on its port: still «Удалить остатки», never a removal that keeps it (N2)
        // (the stock uninstall keeps csqtt.env: a store without its WEB_PASS would be B5, which «Починить» gives new credentials)
        val (leftover, leftoverInv) = box("CSQTT_INV|store|dir=/etc/csqtt|files=csqtt.db,csqtt.env|lock=none|schema=table:client_groups|" +
            "env_keys=CSQTT_WEB_PASS,CSQTT_WEB_USER|public_ip=|domain=", *swg)
        assertEquals(listOf(Action.REMOVE_LEFTOVERS), Manager.entries(leftover, carried, leftoverInv).single { it.installation.name == "/etc/csqtt" }.offers.map { it.action })
        // a swg node's second server, unseen by the claims, holds the stopped stock install's port: found through the listeners (N5)
        val node = arrayOf(
            "CSQTT_INV|ctr|id=0123456789ab|name=swg-node|image=swg-node:1.8|state=running|pid=80|restarts=0|exit=0|policy=always|net=host|labels=|flags=|mounts=|runtime=docker",
            "CSQTT_INV|proc|pid=81|exe=/usr/local/bin/csqtt-swg|deleted=0|comm=csqtt-swg|unit=|ctr=0123456789ab|tun=csqtt3|sha=|flags=--listen 0.0.0.0:56012 --desired /a",
            "CSQTT_INV|proc|pid=82|exe=/usr/local/bin/csqtt-swg|deleted=0|comm=csqtt-swg|unit=|ctr=0123456789ab|tun=csqtt1|sha=|flags=--listen 0.0.0.0:46000 --desired /b",
            "CSQTT_INV|listen|proto=udp|addr=0.0.0.0|port=56012|comm=csqtt-swg|pid=81",
            "CSQTT_INV|listen|proto=udp|addr=0.0.0.0|port=46000|comm=csqtt-swg|pid=82")
        val (c, inv) = box(*stockMain(active = "inactive", fileState = "disabled"), *node)
        val stock = Manager.entries(c, carried, inv).single { it.installation.name == "csqtt.service" }
        assertEquals(listOf(Action.REMOVE), stock.offers.map { it.action })
        assertEquals("UDP 46000 занят: csqtt-swg", stock.note)
        // a move whose new instance cannot be made (every 10.77 /24 under a 10/8 route) is named, not offered (N3)
        val (routed, routedInv) = box(*stockMain(), "CSQTT_INV|route|dst=10.0.0.0/8|dev=wg0|table=main")
        val entry = Manager.entries(routed, carried, routedInv).single()
        assertFalse(Action.UPDATE in entry.offers.map { it.action })
        assertTrue(entry.note.orEmpty(), entry.note.orEmpty().startsWith("Переезд невозможен:"))
    }

    @Test
    fun roundThreeOnlyPortsBlockAMove() {
        // +B17: python3 holds csqtt1, UDP 46000 is free: the move, which ends the stock unit that would delete it, stays (R1)
        assertEquals(Action.REPAIR, offers("v253main-systemd-noble+B17").second.first())
        // the install's own respawn, bound before the process view saw it, holds nothing
        val (c, inv) = box(*stockMain(active = "activating"),
            "CSQTT_INV|listen|proto=udp|addr=0.0.0.0|port=46000|comm=csqtt|pid=4242")
        assertEquals(null, Manager.heldBy(c.installations.single(), c, inv))
        // a foreign process on the web port does hold it
        val (w, winv) = box(*stockMain(active = "inactive"), "CSQTT_INV|listen|proto=tcp|addr=0.0.0.0|port=46002|comm=nginx|pid=99",
            "CSQTT_INV|proc|pid=99|exe=/usr/sbin/nginx|deleted=0|comm=nginx|unit=nginx.service|ctr=|tun=|sha=|flags=")
        assertEquals(Manager.Held("TCP 46002", "nginx"), Manager.heldBy(w.installations.single { it.name == "csqtt.service" }, w, winv))
    }
}
