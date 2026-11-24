// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.csqtt.deploy.inspect

import com.csqtt.deploy.wire.LineCodec
import com.csqtt.deploy.wire.Report
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The classifier on the deploy zoo's seeds (P2-PLAN §2.2): each a fresh Ubuntu 24.04 cloud image on which that variant's own
 * app installed its server the way it did (its deploy.sh, its uploads, its env: .campaign/zoo/seed.sh and SEEDERS.md), then
 * inspected as root by deploy-cli over SSH (2026-11-24). One installation each, with its owner, build, state and claims.
 */
class ClassifierTest {
    private fun classify(name: String): Classification {
        val text = checkNotNull(javaClass.getResource("fixtures/$name.report")) { "no fixture $name" }.readText()
        val report = LineCodec.parse(text) as Report.Complete
        return Classifier.classify(Inventory.from(report.lines))
    }

    private data class Want(
        val owner: Owner, val version: String, val line: ProtocolLine, val sanity: Boolean, val udp: Int?, val web: Int?,
        val iface: String?, val subnet: String?, val dir: String, val tag: String?, val tproxy: Int?, val name: String,
    )

    private val seeds = mapOf(
        "v200-systemd-noble" to Want(Owner.APP_STANDARD, "2.0.0", ProtocolLine.V20, false, 46000, 46002, "csqtt1", "10.66.67.0/24",
            "/etc/csqtt", "CSQTT_MANAGED", 30001, "csqtt.service"),
        "v219-systemd-noble" to Want(Owner.APP_STANDARD, "2.1.9", ProtocolLine.V21, false, 46000, 46002, "csqtt1", "10.66.67.0/24",
            "/etc/csqtt", "CSQTT_MANAGED", 30001, "csqtt.service"),
        "v250-systemd-noble" to Want(Owner.APP_STANDARD, "2.5.0", ProtocolLine.V25, false, 46000, 46002, "csqtt1", "10.66.67.0/24",
            "/etc/csqtt", "CSQTT_MANAGED", 30001, "csqtt.service"),
        // ours 2.5.2: no caption, but its startup string says whose build it is
        "v252-systemd-noble" to Want(Owner.APP_STANDARD, "2.5.2", ProtocolLine.V25, true, 46000, 46002, "csqtt1", "10.66.67.0/24",
            "/etc/csqtt", "CSQTT_MANAGED", 30001, "csqtt.service"),
        "v253main-systemd-noble" to Want(Owner.APP_STANDARD, "2.5.3", ProtocolLine.V25, true, 46000, 46002, "csqtt1", "10.66.67.0/24",
            "/etc/csqtt", "CSQTT_MANAGED", 30001, "csqtt.service"),
        "v253side-systemd-noble" to Want(Owner.APP_SIDE, "2.5.3", ProtocolLine.V25, true, 46100, 46102, "csqtt25", "10.66.68.0/24",
            "/etc/csqtt25", "CSQTT25_MANAGED", 30002, "csqtt25.service"),
        // slim runs our 2.5.3 core under the stock names
        "slim-systemd-noble" to Want(Owner.APP_STANDARD, "2.5.3", ProtocolLine.V25, true, 46000, 46002, "csqtt1", "10.66.67.0/24",
            "/etc/csqtt", "CSQTT_MANAGED", 30001, "csqtt.service"),
        "sidetool-systemd-noble" to Want(Owner.SIDE_TOOL, "2.5.3", ProtocolLine.V25, true, 46100, 46102, "csqtt25", "10.66.68.0/24",
            "/etc/csqtt25", "CSQTT25_MANAGED", 30002, "csqtt25.service"),
        "handmade-systemd-noble" to Want(Owner.HANDMADE, "2.5.3", ProtocolLine.V25, true, 46000, 46002, "csqtt1", null,
            "/srv/my-csqtt", null, 30001, "my-csqtt.service"),
        // MRTT carries no label; its wire revision says what it is
        "mrtt-systemd-noble" to Want(Owner.MRTT, "", ProtocolLine.MRTT, false, 46000, 46002, "mrtt1", "10.66.67.0/24",
            "/etc/mrtt", "MRTT_MANAGED", null, "mrtt.service"),
    )

    @Test
    fun eachSeedIsOneInstallationWithItsOwnerBuildAndClaims() {
        for ((seed, want) in seeds) {
            val result = classify(seed)
            assertEquals("$seed: ${result.installations.map { it.name }}", 1, result.installations.size)
            val install = result.installations.single()
            assertEquals("$seed owner", want.owner, install.owner)
            assertEquals("$seed version", want.version, install.build?.version)
            assertEquals("$seed line", want.line, install.build?.line)
            assertEquals("$seed sanity build", want.sanity, install.build?.sanityBuild)
            assertEquals("$seed runtime", Runtime.SYSTEMD, install.runtime)
            assertEquals("$seed state", InstallState.RUNNING, install.state)
            assertEquals("$seed name", want.name, install.name)
            val claims = install.claims
            assertEquals("$seed udp", want.udp, claims.udpPort)
            assertEquals("$seed web", want.web, claims.webPort)
            assertEquals("$seed iface", want.iface, claims.iface)
            assertEquals("$seed subnet", want.subnet, claims.subnet)
            assertEquals("$seed dir", want.dir, claims.configDir)
            assertEquals("$seed tag", want.tag, claims.ruleTag)
            assertEquals("$seed tproxy", want.tproxy, claims.tproxyTable)
            // a running, freshly installed server shows no broken state, and the box no problem
            assertEquals("$seed broken", emptySet<String>(), install.broken)
            assertEquals("$seed problems", emptyList<BoxProblem>(), result.problems)
        }
    }

    private data class Seen(val owner: Owner, val state: InstallState, val broken: Set<String>)

    /**
     * The broken states of server-footprints.md §4, each planted on a seed by .campaign/zoo/plant-broken.sh (B1: the binary
     * removed; B9: a second server on the port; B13: the stock uninstall run; …) and inspected as the seeds were. Each lists
     * every installation the classifier must see and the box's problems.
     */
    private val planted = mapOf(
        // inspected while systemd still restarted it, each start failing for the missing binary
        "v253main-systemd-noble+B1" to (listOf(Seen(Owner.APP_STANDARD, InstallState.CRASH_LOOPING, setOf("B1"))) to emptySet<String>()),
        "v253main-systemd-noble+B2" to (listOf(Seen(Owner.APP_STANDARD, InstallState.LEFTOVER, setOf("B2"))) to emptySet()),
        "v253main-systemd-noble+B3" to (listOf(Seen(Owner.APP_STANDARD, InstallState.FAILED, setOf("B3"))) to emptySet()),
        "v253main-systemd-noble+B5" to (listOf(Seen(Owner.APP_STANDARD, InstallState.RUNNING, setOf("B5"))) to emptySet()),
        "v253main-systemd-noble+B6" to (listOf(Seen(Owner.APP_STANDARD, InstallState.STOPPED, setOf("B6"))) to emptySet()),
        "v250-systemd-noble+B7" to (listOf(Seen(Owner.APP_STANDARD, InstallState.FAILED, setOf("B3", "B7"))) to emptySet()),
        "v253main-systemd-noble+B8" to (listOf(Seen(Owner.APP_STANDARD, InstallState.STOPPED, setOf("B8"))) to emptySet()),
        "v253main-systemd-noble+B9" to (listOf(Seen(Owner.APP_STANDARD, InstallState.RUNNING, emptySet()),
            Seen(Owner.HANDMADE, InstallState.RUNNING, emptySet())) to setOf("B9")),
        "v253main-systemd-noble+B10" to (listOf(Seen(Owner.APP_STANDARD, InstallState.STOPPED, emptySet())) to setOf("B10")),
        "v253main-systemd-noble+B11" to (listOf(Seen(Owner.APP_STANDARD, InstallState.RUNNING, emptySet())) to setOf("B11")),
        "v253main-systemd-noble+B13" to (listOf(Seen(Owner.APP_STANDARD, InstallState.LEFTOVER, emptySet())) to emptySet()),
        "v253main-systemd-noble+B14" to (listOf(Seen(Owner.APP_STANDARD, InstallState.RUNNING, emptySet())) to setOf("B14")),
        "v253main-systemd-noble+B17" to (listOf(Seen(Owner.APP_STANDARD, InstallState.STOPPED, emptySet())) to setOf("B17")),
        "slim-systemd-noble+B18" to (listOf(Seen(Owner.APP_STANDARD, InstallState.CRASH_LOOPING, setOf("B3", "B18"))) to emptySet()),
        "v253main-systemd-noble+B20" to (listOf(Seen(Owner.APP_STANDARD, InstallState.RUNNING, emptySet()),
            Seen(Owner.SWG_BARE, InstallState.STOPPED, setOf("B1"))) to setOf("B20")),
        "mrtt-systemd-noble+B21" to (listOf(Seen(Owner.MRTT, InstallState.RUNNING, emptySet())) to setOf("B21")),
        "mrtt-systemd-noble+B22" to (listOf(Seen(Owner.MRTT, InstallState.LEFTOVER, setOf("B22"))) to emptySet()),
        "mrtt-systemd-noble+B24" to (listOf(Seen(Owner.MRTT, InstallState.RUNNING, emptySet())) to setOf("B24")),
    )

    @Test
    fun eachPlantedStateIsToldApart() {
        val wrong = mutableListOf<String>()
        for ((fixture, expected) in planted) {
            val result = classify(fixture)
            val seen = result.installations.map { Seen(it.owner, it.state, it.broken) }.sortedBy { it.owner }
            val problems = result.problems.map { it.code }.toSet()
            if (seen != expected.first.sortedBy { it.owner } || problems != expected.second) {
                wrong += "$fixture: saw $seen ${result.problems}, wanted ${expected.first} ${expected.second}"
            }
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun aFreshImageHoldsNoInstallation() {
        for (image in listOf("noble", "debian-12-genericcloud-amd64", "alma-9-nft")) {
            val result = classify(image)
            assertTrue("$image: ${result.installations.map { it.name }}", result.installations.isEmpty())
            assertEquals(image, emptyList<BoxProblem>(), result.problems)
        }
    }
}
