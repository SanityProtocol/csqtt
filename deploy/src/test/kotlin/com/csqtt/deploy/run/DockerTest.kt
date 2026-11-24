// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.csqtt.deploy.run

import com.csqtt.deploy.bundle.Bundle
import com.csqtt.deploy.inspect.Classifier
import com.csqtt.deploy.inspect.Inventory
import com.csqtt.deploy.inspect.Owner
import com.csqtt.deploy.inspect.Preflight
import com.csqtt.deploy.plan.Action
import com.csqtt.deploy.plan.Manager
import com.csqtt.deploy.plan.Proposal
import com.csqtt.deploy.plan.Version
import com.csqtt.deploy.wire.LineCodec
import com.csqtt.deploy.wire.Report
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Docker mode (P2 B13a): the root filesystem's pins, when Docker is offered, where the box gets the rootfs, the plan. */
class DockerTest {
    private fun preflight(docker: String, machine: String = "x86_64", vararg reach: String): Preflight {
        val lines = listOf("CSQTT_PRE|docker|$docker", "CSQTT_PRE|arch|machine=$machine") + reach.map { "CSQTT_PRE|reach|$it" }
        return Preflight.from((LineCodec.parse(lines.joinToString("\n") + "\nCSQTT_END|${lines.size}\n") as Report.Complete).lines)
    }

    private fun inventory(vararg lines: String): Inventory =
        Inventory.from((LineCodec.parse(lines.joinToString("\n") + "\nCSQTT_END|${lines.size}\n") as Report.Complete).lines)

    private val docker = "bin=/usr/bin/docker|version=27.5.1|flavour=docker|enabled=enabled|active=active|rootless=0"

    @Test
    fun everyArchitectureWeBuildHasItsPin() {
        for ((machine, arch) in listOf("x86_64" to "amd64", "aarch64" to "arm64", "armv7l" to "armv7")) {
            assertEquals(arch, Rootfs.archOf(machine))
            val pin = checkNotNull(Rootfs.pin(arch)) { arch }
            assertTrue(pin.sha256, Regex("^[0-9a-f]{64}$").matches(pin.sha256) && pin.bytes in 1_000_000L..20_000_000L)
            assertEquals("sanity-csqtt-rootfs:" + pin.sha256.take(8), pin.tag)
            assertEquals("https://github.com/SanityProtocol/csqtt/releases/download/v2.6.0/csqtt-rootfs-$arch.tar.gz", Rootfs.url("2.6.0", pin))
        }
        assertNull(Rootfs.archOf("riscv64"))
    }

    @Test
    fun anUnsupportedDockerIsRefusedWithItsReason() {
        assertNull(DockerMode.refusal(preflight(docker)))
        assertEquals("Docker не установлен", DockerMode.refusal(preflight("bin=|version=|flavour=|enabled=|active=|rootless=0")))
        assertTrue(DockerMode.refusal(preflight(docker.replace("rootless=0", "rootless=1")))!!.contains("rootless"))
        assertEquals("Docker из snap не подходит", DockerMode.refusal(preflight(docker.replace("flavour=docker", "flavour=snap"))))
        assertEquals("Podman вместо Docker не подходит", DockerMode.refusal(preflight(docker.replace("flavour=docker", "flavour=podman"))))
        assertEquals("для этой архитектуры нет образа", DockerMode.refusal(preflight(docker, "riscv64")))
    }

    @Test
    fun theBoxGetsItsRootfsInC3sOrder() {
        val pin = checkNotNull(Rootfs.pin("amd64"))
        val url = Rootfs.url("2.6.0", pin)
        val reached = preflight(docker, "x86_64", "host=github.com|dns=1|https=ok")
        val unreached = preflight(docker, "x86_64", "host=github.com|dns=1|https=failed")
        val none = inventory()
        // the image there already: nothing moves
        val present = inventory("CSQTT_INV|image|ref=${pin.tag}|id=sha256:0|runtime=docker")
        assertEquals(DockerMode.Source.Present, DockerMode.source(pin, url, unreached, present, null))
        // the box reaches the release: it fetches
        assertEquals(DockerMode.Source.Fetch(url), DockerMode.source(pin, url, reached, none, null))
        // it cannot: the client's copy, only when it is the pinned one
        assertNull(DockerMode.source(pin, url, unreached, none, null))
        assertNull(DockerMode.source(pin, url, unreached, none, ByteArray(pin.bytes.toInt())))
        // a fetch that failed before: the client brings it whatever the reach says
        assertNull(DockerMode.source(pin, url, reached, none, null, fetch = false))
    }

    @Test
    fun aDockerPlanCarriesItsModeAndRootfsAndTheRunReadsThem() {
        val pin = checkNotNull(Rootfs.pin("amd64"))
        val p = Proposal("0a1b2c", "sqc0", "10.77.0.0/24", 41000, 41002, 2, docker = true)
        val fetch = CreateRun.plan(p, "203.0.113.7", Core(ByteArray(1), "2.6.0"), Credentials.generate(),
            rootfs = RootfsChoice(pin, DockerMode.Source.Fetch("https://example.invalid/r.tar.gz")))
        val text = fetch.planText()
        for (line in listOf("mode=docker", "rootfs_sha256=${pin.sha256}", "rootfs_url=https://example.invalid/r.tar.gz")) {
            assertTrue(line, "\n$line\n" in "\n$text")
        }
        assertNull(fetch.rootfs)
        // a copy the client brings goes with the run; an image already there needs neither
        val bytes = ByteArray(3)
        val upload = CreateRun.plan(p, "203.0.113.7", Core(ByteArray(1), "2.6.0"), Credentials.generate(),
            rootfs = RootfsChoice(pin, DockerMode.Source.Upload(bytes)))
        assertTrue(upload.rootfs === bytes && "\nrootfs_url=\n" in "\n${upload.planText()}")
        // a systemd create carries none of it
        val plain = CreateRun.plan(p.copy(docker = false), "203.0.113.7", Core(ByteArray(1), "2.6.0"), Credentials.generate()).planText()
        assertFalse(plain, "mode=" in plain || "rootfs" in plain)
        // «Продолжить» keeps the mode
        assertTrue(checkNotNull(proposalOfKeptPlan(text)).docker)
        // and the run reads exactly these
        val create = Bundle.bytes("run.sh").toString(Charsets.UTF_8).substringAfter("action_create() {").substringBefore("\n}\n")
        for (key in listOf("mode", "rootfs_sha256", "rootfs_url")) assertTrue(key, "\$(plan $key)" in create)
    }

    private fun oursInDocker(id: String) = arrayOf(
        "CSQTT_INV|ctr|id=c0ffee|name=sanity-csqtt-$id|image=sanity-csqtt:2.6.0-0123abcd|state=running|pid=4242|restarts=0|exit=0|" +
            "policy=unless-stopped|net=host|labels=org.sanityprotocol.csqtt=sanity-csqtt-$id|flags=--listen 0.0.0.0:41234 --web-port 41236 " +
            "--config-dir /var/lib/sanityprotocol/csqtt/$id --instance sqc-$id --iface sqc0 --instance-slot 2 --host-tuning off|" +
            "mounts=/var/lib/sanityprotocol/csqtt/$id:/var/lib/sanityprotocol/csqtt/$id|runtime=docker",
        "CSQTT_INV|store|dir=/var/lib/sanityprotocol/csqtt/$id|files=csqtt.db,env,manifest.json|lock=held|schema=table:client_groups|" +
            "env_keys=CSQTT_WEB_PASS|public_ip=|domain=",
    )

    @Test
    fun anInstanceOfOursInDockerIsOfferedWhatB13aBuilds() {
        val inv = inventory(*oursInDocker("0a1b2c"))
        val c = Classifier.classify(inv)
        assertEquals(Owner.OURS, c.installations.single().owner)
        // run, connected to, restarted and removed; its update, repair, clean reinstall and restore are B13c's
        val actions = Manager.entries(c, Version(2, 7, 0), inv).single().offers.map { it.action }
        assertTrue(actions.toString(), actions.containsAll(listOf(Action.CONNECT, Action.RESTART, Action.REMOVE)))
        assertFalse(actions.toString(), actions.any { it in setOf(Action.UPDATE, Action.REINSTALL_CLEAN, Action.REPAIR) })
        // our drop-in stays for it (no S1), as for an instance of ours with a unit
        val dropIn = "CSQTT_INV|file|path=/etc/sysctl.d/99-sanity-csqtt.conf|type=f"
        assertFalse(Classifier.classify(inventory(*oursInDocker("0a1b2c"), dropIn)).problems.any { it.code == "S1" })
        // its kept store: removed, not yet restored
        val kept = inventory("CSQTT_INV|store|dir=/var/lib/sanityprotocol/csqtt/0a1b2c|files=csqtt.db,env,manifest.json,unit.saved|lock=none|" +
            "schema=table:client_groups|env_keys=CSQTT_WEB_PASS|public_ip=|domain=|kept_flags=--listen 0.0.0.0:41234 --instance sqc-0a1b2c " +
            "--iface sqc0 --instance-slot 2|kept_release=2.6.0-0123abcd|kept_docker=1")
        val keptActions = Manager.entries(Classifier.classify(kept), Version(2, 6, 0), kept).single().offers.map { it.action }
        assertFalse(keptActions.toString(), Action.RESTORE in keptActions)
        assertTrue(keptActions.toString(), Action.REMOVE_LEFTOVERS in keptActions)
    }
}
