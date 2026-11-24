// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.csqtt.deploy.inspect

import com.csqtt.deploy.plan.Manager
import com.csqtt.deploy.plan.Version
import com.csqtt.deploy.wire.LineCodec
import com.csqtt.deploy.wire.Report
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mixed boxes built for one question each: the shapes B5's review found the classifier getting wrong (card p2-b5-classify). */
class ClassifierBoxesTest {
    private val sha = "187224d9" + "0".repeat(56)

    private fun inventory(vararg lines: String): Inventory {
        val text = lines.joinToString("\n") { "CSQTT_INV|$it" } + "\nCSQTT_END|${lines.size}\n"
        return Inventory.from((LineCodec.parse(text) as Report.Complete).lines)
    }

    private fun classify(vararg lines: String): Classification = Classifier.classify(inventory(*lines))

    private fun entries(vararg lines: String) = inventory(*lines).let { Manager.entries(Classifier.classify(it), Version(2, 6, 0), it) }

    private fun exe(path: String, label: String = "v2.5.3") =
        "exe|sha=$sha|path=$path|size=1|label=$label|caption=0|startup=2.5.3 (SanityProtocol build of amurcanov 2.5.0)|swg=1|mrtt=0"

    private fun unit(id: String, exec: String, active: String = "active", sub: String = "running", flags: String = "", fileState: String = "enabled",
        result: String = "success", execOk: String = "1", pid: Int = 0) =
        "unit|id=$id|load=loaded|active=$active|sub=$sub|result=$result|status=0|restarts=0|pid=$pid|file_state=$fileState|" +
            "fragment=/etc/systemd/system/$id|exec=$exec|exec_ok=$execOk|flags=$flags|pre=|pre_status=|env="

    private fun proc(pid: Int, exe: String, unit: String = "", ctr: String = "", tun: String = "", flags: String = "") =
        "proc|pid=$pid|exe=$exe|deleted=0|comm=${exe.substringAfterLast('/').take(15)}|unit=$unit|ctr=$ctr|tun=$tun|sha=$sha|flags=$flags"

    private fun store(dir: String, schema: String = "table:client_groups,table:passwords", files: String = "csqtt.db,csqtt.env") =
        "store|dir=$dir|files=$files|lock=none|schema=$schema|env_keys=CSQTT_WEB_PASS|public_ip=|domain="

    @Test
    fun aStoppedDockerHidesOnlyWhatItHolds() {
        // Docker installed and stopped, and a store with no sign of Docker (a stock uninstall's leftover, a podman box's): it
        // is the leftover it looks like, removal offered (B5 verify N1)
        val plain = classify(store("/etc/csqtt"), "docker|daemon=inactive")
        assertEquals(InstallState.LEFTOVER, plain.installations.single().state)
        assertEquals(listOf(com.csqtt.deploy.plan.Action.REMOVE_LEFTOVERS),
            entries(store("/etc/csqtt"), "docker|daemon=inactive").single().offers.map { it.action })
        // a 2.0.0 Docker install's own docker dir is such a sign
        assertTrue(classify(store("/etc/csqtt", files = "csqtt.db,docker"), "docker|daemon=inactive").installations.single().dockerUnseen)
    }

    @Test
    fun aCrashLoopingContainerIsACrashLoopBesideItsDeadUnit() {
        // B16 with its container restarting, its server caught alive between two restarts (B5 verify N2)
        val c = classify(
            unit("csqtt.service", "/usr/local/bin/csqtt", active = "inactive", sub = "dead", fileState = "disabled"),
            "ctr|id=0123456789ab|name=csqtt|image=csqtt:2.5.3|state=restarting|pid=0|restarts=14|exit=1|policy=unless-stopped|net=host|labels=|flags=|mounts=/etc/csqtt:/etc/csqtt|runtime=docker",
            proc(51, "/usr/local/bin/csqtt", ctr = "0123456789ab", tun = "csqtt1"),
            store("/etc/csqtt"),
        )
        assertEquals(InstallState.CRASH_LOOPING, c.installations.single().state)
    }

    @Test
    fun aStoppedDockerIsNeverALeftover() {
        // the family's prereq unit: a Docker-mode stock install that waits for its daemon
        val lines = arrayOf(store("/etc/csqtt"), "file|path=/usr/local/bin/csqtt|type=f",
            unit("csqtt-docker-prereq.service", "/usr/local/lib/csqtt/docker-prereq.sh", active = "inactive", sub = "dead"))
        // Docker installed and stopped: the install may be its container's, unseen — B4, no B2, and nothing offered
        val unseen = classify(*lines, "docker|daemon=inactive").installations.single { it.name == "/etc/csqtt" }
        assertEquals(InstallState.STOPPED, unseen.state)
        assertEquals(setOf("B4"), unseen.broken)
        assertTrue(unseen.dockerUnseen)
        entries(*lines, "docker|daemon=inactive").single { it.installation.name == "/etc/csqtt" }.let {
            assertTrue(it.offers.isEmpty())
            assertEquals("Docker остановлен: запустите Docker и проверьте заново", it.note)
        }
        // no Docker on the box: a store and a binary with nothing to run them are what an install cut off leaves
        val leftover = classify(*lines).installations.single { it.name == "/etc/csqtt" }
        assertEquals(InstallState.LEFTOVER, leftover.state)
        assertEquals(setOf("B2"), leftover.broken)
    }

    @Test
    fun ownershipGoesByTheInstallsOwnPaths() {
        // one build both as our instance (seen first) and as a stock main
        val c = classify(
            exe("/opt/sanityprotocol/csqtt/0a1b2c/current/csqtt-server"),
            proc(10, "/opt/sanityprotocol/csqtt/0a1b2c/current/csqtt-server", unit = "sanity-csqtt-0a1b2c.service",
                flags = "--listen 0.0.0.0:41000 --config-dir /var/lib/sanityprotocol/csqtt/0a1b2c --instance sqc-0a1b2c"),
            unit("sanity-csqtt-0a1b2c.service", "/opt/sanityprotocol/csqtt/0a1b2c/current/csqtt-server", pid = 10,
                flags = "--listen 0.0.0.0:41000 --config-dir /var/lib/sanityprotocol/csqtt/0a1b2c --instance sqc-0a1b2c"),
            store("/var/lib/sanityprotocol/csqtt/0a1b2c"),
            proc(20, "/usr/local/bin/csqtt", unit = "csqtt.service", tun = "csqtt1"),
            unit("csqtt.service", "/usr/local/bin/csqtt", pid = 20),
            store("/etc/csqtt"),
        )
        assertEquals(mapOf("csqtt.service" to Owner.APP_STANDARD, "sanity-csqtt-0a1b2c.service" to Owner.OURS),
            c.installations.associate { it.name to it.owner })
        assertEquals("10.66.67.0/24", c.installations.single { it.owner == Owner.APP_STANDARD }.claims.subnet)
    }

    @Test
    fun aStoppedTwinFindsItsBinaryByItsOwnPath() {
        val c = classify(
            exe("/usr/local/bin/csqtt"),
            "bin|path=/usr/local/bin/csqtt|sha=$sha",
            "bin|path=/usr/local/lib/csqtt25/csqtt25|sha=$sha",
            proc(20, "/usr/local/bin/csqtt", unit = "csqtt.service", tun = "csqtt1"),
            unit("csqtt.service", "/usr/local/bin/csqtt", pid = 20),
            store("/etc/csqtt"),
            unit("csqtt25.service", "/usr/local/lib/csqtt25/csqtt25", active = "inactive", sub = "dead",
                flags = "--listen 0.0.0.0:46100 --config-dir /etc/csqtt25"),
            store("/etc/csqtt25"),
        )
        val side = c.installations.single { it.name == "csqtt25.service" }
        assertEquals(InstallState.STOPPED, side.state)
        assertEquals("2.5.3", side.build?.version)
        assertEquals("label", side.build?.source)
    }

    @Test
    fun aStoreThatWentThroughTwoFiveIsTwoFiveAndAStoreGivesNoVersion() {
        // the binary is gone (B1); the store has 2.1's devices and device_id beside 2.5's client_groups
        val c = classify(
            unit("csqtt.service", "/usr/local/bin/csqtt", active = "failed", sub = "failed", result = "exit-code", execOk = "0"),
            store("/etc/csqtt", schema = "device_id,table:client_groups,table:devices,table:passwords"),
        )
        val install = c.installations.single()
        assertEquals(ProtocolLine.V25, install.build?.line)
        assertEquals("", install.build?.version)
        assertEquals(setOf("B1"), install.broken)
    }

    @Test
    fun aTwoOneServerStoppedCleanlyIsNotB7() {
        val v219 = "exe|sha=$sha|path=/usr/local/bin/csqtt|size=1|label=v2.1.9|caption=0|startup=2.1.9|swg=0|mrtt=0"
        val stopped = classify(v219, "bin|path=/usr/local/bin/csqtt|sha=$sha",
            unit("csqtt.service", "/usr/local/bin/csqtt", active = "inactive", sub = "dead"),
            store("/etc/csqtt", schema = "device_id,table:client_groups,table:devices,table:passwords")).installations.single()
        assertFalse("B7" in stopped.broken)
        val failing = classify(v219, "bin|path=/usr/local/bin/csqtt|sha=$sha",
            unit("csqtt.service", "/usr/local/bin/csqtt", active = "failed", sub = "failed", result = "exit-code"),
            store("/etc/csqtt", schema = "device_id,table:client_groups,table:devices,table:passwords")).installations.single()
        assertTrue("B7" in failing.broken)
    }

    @Test
    fun foreignTunHoldersInContainersAreNoInstallation() {
        val c = classify(
            "ctr|id=0123456789ab|name=amnezia-openvpn|image=amnezia-openvpn|state=running|pid=30|restarts=0|exit=0|policy=always|net=bridge|labels=|flags=|mounts=|runtime=docker",
            "proc|pid=30|exe=/usr/sbin/openvpn|deleted=0|comm=openvpn|unit=|ctr=0123456789ab|tun=tun0|sha=|flags=",
        )
        assertTrue(c.installations.isEmpty())
    }

    @Test
    fun b9IsTheFamilysPortsOnOverlappingAddressesOnly() {
        // Docker's proxy once per address family on a published port, resolved and dnsmasq on 53: none of ours
        val docker = classify(
            "listen|proto=udp|addr=0.0.0.0|port=51820|comm=docker-proxy|pid=2001",
            "listen|proto=udp|addr=::|port=51820|comm=docker-proxy|pid=2002",
            "listen|proto=udp|addr=127.0.0.53%lo|port=53|comm=systemd-resolve|pid=500",
            "listen|proto=udp|addr=127.0.0.1|port=53|comm=dnsmasq|pid=600",
        )
        assertTrue(docker.problems.isEmpty())
        // the stock server and a hand-made one on 46000: a conflict, each holder named once
        val clash = classify(
            proc(20, "/usr/local/bin/csqtt", unit = "csqtt.service", tun = "csqtt1"), unit("csqtt.service", "/usr/local/bin/csqtt", pid = 20),
            store("/etc/csqtt"),
            "listen|proto=udp|addr=0.0.0.0|port=46000|comm=csqtt|pid=20",
            "listen|proto=udp|addr=::|port=46000|comm=csqtt|pid=20",
            "listen|proto=udp|addr=0.0.0.0|port=46000|comm=my-server|pid=40",
        )
        assertEquals(listOf(BoxProblem("B9", "UDP 46000: csqtt(20), my-server(40)")), clash.problems.filter { it.code == "B9" })
    }

    @Test
    fun mrttsCascadeExitIsNotItsServer() {
        val c = classify(
            "exe|sha=$sha|path=/usr/local/bin/mrtt|size=1|label=|caption=0|startup=|swg=0|mrtt=1",
            proc(5, "/usr/local/lib/mrtt-exit-client", unit = "mrtt-exit-1.service", tun = "mrtx1", flags = "--listen 127.0.0.1:0"),
            unit("mrtt-exit-1.service", "/usr/local/lib/mrtt-exit-client", active = "activating", sub = "auto-restart"),
            proc(9, "/usr/local/bin/mrtt", unit = "mrtt.service", tun = "mrtt1", flags = "--listen 0.0.0.0:46000"),
            unit("mrtt.service", "/usr/local/bin/mrtt", pid = 9, flags = "--listen 0.0.0.0:46000"),
            store("/etc/mrtt", files = "mrtt.db,mrtt.env"),
        )
        val mrtt = c.installations.single()
        assertEquals(Owner.MRTT, mrtt.owner)
        assertEquals("mrtt.service", mrtt.name)
        assertEquals(InstallState.RUNNING, mrtt.state)
        assertEquals(46000, mrtt.claims.udpPort)
        assertEquals("mrtt1", mrtt.claims.iface)
    }

    @Test
    fun aStatusRunIsNoInterruptedInstall() {
        val log = "file|path=/var/log/csqtt25-install.log|type=f|runs=2|last_run==== CSQTT Installer v2.5.3 ===|last_line=x|finished=0|failed_steps=0"
        assertTrue(classify("$log|steps=0").problems.none { it.code == "B11" })
        assertTrue(classify("$log|steps=4").problems.any { it.code == "B11" })
    }

    @Test
    fun aLeftoverStoreTakesNothingAwayFromCsqtt1() {
        // the stock install uninstalled with its store kept, then swg-panel's server on csqtt1: nothing deletes it any more
        val c = classify(store("/etc/csqtt"),
            proc(70, "/usr/local/bin/csqtt-swg", unit = "swg-csqtt-a.service", tun = "csqtt1", flags = "--desired /x --config-dir /opt/swg-csqtt/a"))
        assertTrue(c.problems.none { it.code == "B17" })
    }

    @Test
    fun aRunningContainerOutranksItsDeadUnit() {
        val c = classify(
            unit("csqtt.service", "/usr/local/bin/csqtt", active = "inactive", sub = "dead", fileState = "disabled"),
            "ctr|id=0123456789ab|name=csqtt|image=csqtt:2.5.3|state=running|pid=50|restarts=0|exit=0|policy=unless-stopped|net=host|labels=|flags=|mounts=/etc/csqtt:/etc/csqtt|runtime=docker",
            store("/etc/csqtt"),
        )
        val install = c.installations.single()
        assertEquals(InstallState.RUNNING, install.state)
        assertTrue("B16" in install.broken)
    }

    @Test
    fun mrttsBetaIsKnownByItsStoreAlone() {
        // its binary gone and its container unseen: the store's devices_json still says whose it is
        val c = classify(unit("csqtt.service", "/usr/local/bin/csqtt", active = "failed", sub = "failed", result = "exit-code", execOk = "0"),
            store("/etc/csqtt", schema = "devices_json,table:passwords"))
        val beta = c.installations.single()
        assertEquals(Owner.MRTT_BETA, beta.owner)
        assertEquals(ProtocolLine.MRTT, beta.build?.line)
        assertTrue(entries(unit("csqtt.service", "/usr/local/bin/csqtt", active = "failed", sub = "failed", result = "exit-code", execOk = "0"),
            store("/etc/csqtt", schema = "devices_json,table:passwords")).single().offers.isEmpty())
    }
}
