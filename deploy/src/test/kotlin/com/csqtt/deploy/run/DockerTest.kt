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

    @Test
    fun ourImageKeepsOutOfTheStockNames() {
        // P2-PLAN §1.5: an old app's cleanup removes any container whose command holds /usr/local/bin/csqtt (2.1.9's deploy.sh)
        // or whose name or image is a stock one; ours live under /opt/sanityprotocol, labelled in our own namespace
        val script = Bundle.bytes("run.sh").toString(Charsets.UTF_8)
        val docker = listOf("dk_image", "dk_run", "action_create", "action_update", "action_restore")
            .joinToString("\n") { script.substringAfter("$it() {").substringBefore("\n}\n") }
        assertFalse("/usr/local/bin/csqtt" in docker)
        assertTrue("ENTRYPOINT [\"/opt/sanityprotocol/entrypoint\"]" in docker && "\"\$dn_img\" /opt/sanityprotocol/csqtt-server \"\$@\"" in docker)
        assertTrue("--label \"net.sanityprotocol.csqtt.instance=" in docker)
    }

    @Test
    fun ourImagesGoOnlyWhenNothingNeedsThem() {
        // the zoo on docker.io 24.0.7: removing the root filesystem's tag while an image of ours is built from it untags it all
        // the same; it goes only once no image of ours is left (a container's image Docker keeps by itself)
        val script = Bundle.bytes("run.sh").toString(Charsets.UTF_8)
        val rm = script.substringAfter("dk_image_rm() {").substringBefore("\n}\n")
        // (B13c: built on it = its one layer is their first: another root filesystem's images do not keep it)
        assertTrue(rm, "{{index .RootFS.Layers 0}}" in rm && "for di_i in \$(docker images -q sanity-csqtt" in rm)
        for (f in listOf("mf_undo", "inst_take_down")) {
            val body = script.substringAfter("$f() {").substringBefore("\n}\n")
            assertTrue(f, "dk_image_rm " in body && "docker image rm" !in body)
        }
    }

    private fun oursInDocker(id: String) = arrayOf(
        "CSQTT_INV|ctr|id=c0ffee|name=sanity-csqtt-$id|image=sanity-csqtt:2.6.0-0123abcd|state=running|pid=4242|restarts=0|exit=0|" +
            "policy=unless-stopped|net=host|labels=net.sanityprotocol.csqtt.instance=sanity-csqtt-$id|flags=--listen 0.0.0.0:41234 --web-port 41236 " +
            "--config-dir /var/lib/sanityprotocol/csqtt/$id --instance sqc-$id --iface sqc0 --instance-slot 2 --host-tuning off|" +
            "mounts=/var/lib/sanityprotocol/csqtt/$id:/var/lib/sanityprotocol/csqtt/$id|runtime=docker",
        "CSQTT_INV|store|dir=/var/lib/sanityprotocol/csqtt/$id|files=csqtt.db,env,manifest.json|lock=held|schema=table:client_groups|" +
            "env_keys=CSQTT_WEB_PASS|public_ip=|domain=",
    )

    @Test
    fun anInstanceOfOursInDockerIsOfferedWhatAnyOfOursIs() {
        val inv = inventory(*oursInDocker("0a1b2c"))
        val c = Classifier.classify(inv)
        val install = c.installations.single()
        assertEquals(Owner.OURS, install.owner)
        // B13c: run, connected to, restarted, updated, removed, reinstalled clean, as a unit's
        val actions = Manager.entries(c, Version(2, 7, 0), inv).single().offers.map { it.action }
        assertTrue(actions.toString(), actions.containsAll(listOf(Action.CONNECT, Action.UPDATE, Action.RESTART, Action.REMOVE,
            Action.REINSTALL_CLEAN)))
        assertEquals("0a1b2c", UpdateRun.idOf(install))
        // our drop-in stays for it (no S1), as for an instance of ours with a unit
        val dropIn = "CSQTT_INV|file|path=/etc/sysctl.d/99-sanity-csqtt.conf|type=f"
        assertFalse(Classifier.classify(inventory(*oursInDocker("0a1b2c"), dropIn)).problems.any { it.code == "S1" })
        // its update carries the root filesystem its new image stands on, and no unit to re-check
        val pin = checkNotNull(Rootfs.pin("amd64"))
        val update = UpdateRun.plan(install, inv, Core(ByteArray(1), "2.7.0"), RootfsChoice(pin, DockerMode.Source.Present)).planText()
        assertTrue(update, "\nmode=docker\n" in "\n$update" && "\nrootfs_sha256=${pin.sha256}\n" in "\n$update" && "expect_unit" !in update)
        // its kept store: «Восстановить», in Docker again
        val kept = inventory("CSQTT_INV|store|dir=/var/lib/sanityprotocol/csqtt/0a1b2c|files=csqtt.db,env,manifest.json,unit.saved|lock=none|" +
            "schema=table:client_groups|env_keys=CSQTT_WEB_PASS|public_ip=|domain=|kept_flags=--listen 0.0.0.0:41234 --instance sqc-0a1b2c " +
            "--iface sqc0 --instance-slot 2|kept_release=2.6.0-0123abcd|kept_docker=1")
        val keptInstall = Classifier.classify(kept).installations.single()
        assertTrue(Manager.entries(Classifier.classify(kept), Version(2, 6, 0), kept).single().offers.map { it.action }.contains(Action.RESTORE))
        val bytes = ByteArray(3)
        val restore = Maintenance.restore(keptInstall, Core(ByteArray(1), "2.6.0"), RootfsChoice(pin, DockerMode.Source.Upload(bytes)))
        assertTrue(restore.rootfs === bytes && "\nrootfs_url=\n" in "\n${restore.planText()}")
        // and the run reads them where the plans carry them
        val script = Bundle.bytes("run.sh").toString(Charsets.UTF_8)
        for (f in listOf("action_update", "action_restore")) {
            val body = script.substringAfter("$f() {").substringBefore("\n}\n")
            for (key in listOf("rootfs_sha256", "rootfs_url")) assertTrue("$f reads $key", "\$(plan $key)" in body)
        }
    }

    private val runSh = Bundle.bytes("run.sh").toString(Charsets.UTF_8)

    private fun body(f: String) = runSh.substringAfter("$f() {").substringBefore("\n}\n")

    @Test
    fun aCutDockerCreateIsContinuedOnTheImagesItMade() {
        // B13a review F1: «Продолжить» keeps the images the cut run made (its own to take if it fails), and the Docker stage runs
        // once the state dir is there, so a cut leaves each image and Docker change named
        val create = body("action_create")
        assertTrue("cr_kept_images=\$(printf '%s\\n' \"\$MF_OBJECTS\" | grep '^image ')" in create)
        assertTrue("MF_OBJECTS=\$cr_kept_images MF_HOST=''" in create)
        assertTrue(create.indexOf("mkdir -m 700 \"\$cr_state\"") in 0 until create.indexOf("\t\tdk_daemon"))
    }

    @Test
    fun dockerIsLeftAsItIsWhileAnotherInstanceOfOursReliesOnIt() {
        // B13a review F3 and its verify's N1: decided once, before anything is stopped, by our own containers alone (old ones
        // of others' revived by our start do not count; no docker call after the stop, which its socket would answer by
        // starting Docker again)
        val undo = body("host_undo")
        val decide = undo.indexOf("[ -z \"\$(docker ps -aq --filter name=^sanity-csqtt- 2>/dev/null)\" ] || hu_docker_kept=1")
        assertTrue(undo, decide in 0 until undo.indexOf("while read -r hu_kind hu_rest"))
        for (kind in listOf("service-enabled", "service-started")) assertTrue(kind, "$kind) [ \$hu_docker_kept = 1 ] ||" in undo)
        assertEquals(1, Regex("docker ps").findAll(undo).count())
    }

    @Test
    fun aLiveInstanceInDockerIsSeenWhileDockerIsStopped() {
        // B13a review F4: Docker stopped, its container unseen: the docker file's flags are its claims; no S1; its slot stays its
        val docker = "CSQTT_INV|docker|daemon=inactive"
        val store = "CSQTT_INV|store|dir=/var/lib/sanityprotocol/csqtt/0a1b2c|files=csqtt.db,env,manifest.json|lock=none|" +
            "schema=table:client_groups|env_keys=CSQTT_WEB_PASS|public_ip=|domain=|docker_flags=--listen 0.0.0.0:41234 --web-port 41236 " +
            "--config-dir /var/lib/sanityprotocol/csqtt/0a1b2c --instance sqc-0a1b2c --iface sqc0 --tun-addr 10.77.0.1/24 --instance-slot 2 --host-tuning off"
        val dropIn = "CSQTT_INV|file|path=/etc/sysctl.d/99-sanity-csqtt.conf|type=f"
        val inv = inventory(docker, store, dropIn)
        val c = Classifier.classify(inv)
        val install = c.installations.single()
        assertEquals(41234, install.claims.udpPort)
        assertFalse(c.problems.any { it.code == "S1" })
        val proposal = com.csqtt.deploy.plan.Proposer.propose(c, inv, kotlin.random.Random(1))
        assertTrue(proposal.toString(), proposal.slot != 2 && proposal.iface != "sqc0" && proposal.udpPort != 41234)
        // the run keeps the drop-in for its docker file without asking Docker (a stopped one would start through its socket),
        // and a removal takes the file
        val drop = body("host_dropin_drop")
        assertTrue(drop, "for hd_d in /var/lib/sanityprotocol/csqtt/*/docker; do [ -f \"\$hd_d\" ] && return 0; done" in drop)
        assertFalse(drop, "docker container inspect" in drop)
        assertTrue("rm -f \"\$in_state/docker\"" in body("inst_take_down"))
    }

    @Test
    fun anInstanceInDockerTunesTheHostAsItsServerWould() {
        // B13a review F5: the server's KERNEL_PARAMETERS (rust-server/tokio_io.rs), forwarding aside (a floor already), are the
        // run's host tuning for an instance in Docker, whose server cannot write /proc/sys
        val rust = java.io.File("../rust-server/tokio_io.rs").readText()
        val server = Regex("\\(\"/proc/sys/([a-z0-9_/]+)\", \"([^\"]+)\"\\)").findAll(rust.substringAfter("const KERNEL_PARAMETERS").substringBefore("];"))
            .map { it.groupValues[1].replace('/', '.') to it.groupValues[2] }.toList()
        assertTrue(server.toString(), server.size >= 10)
        val tuning = runSh.substringAfter("HOST_TUNING='").substringBefore("'").lines().map { it.substringBefore(' ') to it.substringAfter(' ') }
        assertEquals(server.filter { it.first != "net.ipv4.ip_forward" }.toSet(), tuning.toSet())
        assertTrue("if [ \$cr_docker = 1 ]; then host_sysctl tuning; else host_sysctl; fi" in body("action_create"))
        // kept even where the box has it already (a systemd instance's server may have set it at runtime, and go: B13a verify)
        val tuningCase = body("host_sysctl").substringAfter("*.tcp_window_scaling | *.tcp_fastopen | *.tcp_slow_start_after_idle)").substringBefore(";;")
        assertFalse(tuningCase, "continue" in tuningCase)
        // and a restore into Docker (B13c), as its create's
        assertTrue("if [ \$rs_docker = 1 ]; then host_sysctl tuning; else host_sysctl; fi" in body("action_restore"))
    }

    @Test
    fun aCutOrFailedRunLeavesNoRootfsOrImageFilesBehind() {
        // B13a review F7
        assertTrue("\"\$RUNS\"/*/core \"\$RUNS\"/*/rootfs 2>/dev/null" in runSh)
        assertTrue("|| { rm -rf \"\$di_ctx\"; cr_fail \"Docker would not build the image\"; }" in body("dk_image"))
    }

    @Test
    fun aDockerUpdateReChecksTheBuildTheInspectionSaw() {
        // B13c review F3: the host's copy of its build, hashed by the inspection, re-checked by the run (M-150: another phone's
        // update since fails this one cleaned)
        val sha = "a".repeat(64)
        val inv = inventory(*oursInDocker("0a1b2c"),
            "CSQTT_INV|exe|sha=$sha|path=/opt/sanityprotocol/csqtt/0a1b2c/current/csqtt-server|size=1|label=|caption=0|startup=|swg=0|mrtt=0|ctr=0",
            "CSQTT_INV|bin|path=/opt/sanityprotocol/csqtt/0a1b2c/current/csqtt-server|sha=$sha")
        val install = Classifier.classify(inv).installations.single()
        val plan = UpdateRun.plan(install, inv, Core(ByteArray(1), "2.7.0"), RootfsChoice(checkNotNull(Rootfs.pin("amd64")), DockerMode.Source.Present)).planText()
        assertTrue(plan, "\nexpect_bin=/opt/sanityprotocol/csqtt/0a1b2c/current/csqtt-server\n" in "\n$plan" && "\nexpect_sha=$sha\n" in "\n$plan")
        assertEquals("Файловая система образа уже на сервере", DockerMode.sourceWill(RootfsChoice(checkNotNull(Rootfs.pin("amd64")), DockerMode.Source.Present)))
    }

    @Test
    fun aDockerInstanceHoldsUnderCutsAndLeavesNoImageBehind() {
        // B13c review F6: the build that runs is its container's image, which must be the one `current` names
        assertTrue("{{.State.Running}} {{.Config.Image}}" in body("up_running") && "readlink \"\$up_root/current\"" in body("up_running"))
        val back = body("up_back")
        assertTrue(back, back.indexOf("up_start >/dev/null 2>&1") in 0 until back.indexOf("rm -f \"\$UP_PENDING\""))
        val restart = body("action_restart")
        assertTrue(restart, "!= \"\$rt_img\" ]; then" in restart && "run_cmd dk_run \"\$rt_ctr\" \"\$rt_img\"" in restart)
        // F2 and verify N1: the record names its boot; our entrypoint drops an earlier boot's once its own server holds its port (a
        // unit's ExecStartPost, after READY=1), never this boot's; a record a run finds is then a build that never served since,
        // rolled back as B10's (Docker counts a restart loop as running); a verified restart drops it
        assertTrue("boot=%s\\n" in body("action_update"))
        assertFalse(body("up_recover").contains("boot_id"))
        val entry = body("dk_entrypoint")
        assertTrue(entry, "netstat -lnup" in entry && ".*/csqtt-server\"" in entry && "/proc/sys/kernel/random/boot_id" in entry &&
            "!= \"\$b\" ]" in entry)
        assertTrue(entry, entry.indexOf("update.pending") in 0 until entry.indexOf("exec \"\$@\""))
        assertTrue(restart, "rm -f \"\$in_state/update.pending\"" in restart)
        // F1: a cut update's image with its directory; every release's image at a removal; the root filesystems swept by layer;
        // the update's stage rewrites no root filesystem line
        val recover = body("up_recover")
        assertTrue(recover, "dk_image_rm \"sanity-csqtt:\${rc_new##*/}\"" in recover)
        val down = body("inst_take_down")
        assertTrue(down, "for in_r in \"\$in_root\"/releases/*/; do" in down && "dk_rootfs_sweep" in down)
        assertTrue("dk_rootfs_sweep" in body("up_prune"))
        assertFalse("s|^# rootfs .*|" in body("action_update"))
        // F4: a restore asks Docker nothing while it is stopped; F5: its docker file is its own object
        val restore = body("action_restore")
        assertTrue(restore, "[ \$rs_dk_live = 1 ] && docker container inspect" in restore)
        assertTrue(restore, restore.indexOf("mf_add file \"\$in_state/docker\"") in 0 until restore.indexOf(">\"\$in_state/docker\""))
    }

    // the stock 2.5.3 app's Docker install, as the zoo's seed inspects (P2 B13b)
    private val stockDocker: List<String> by lazy {
        checkNotNull(Classifier::class.java.getResource("fixtures/v253main-docker-noble.report")).readText().lines()
            .filter { it.startsWith("CSQTT_INV|") || it.startsWith("CSQTT_PRE|") }
    }

    private fun values(plan: String) = plan.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    fun aStockDockerInstallIsRemovedWithItsContainerAndImages() {
        val inv = inventory(*stockDocker.toTypedArray())
        val c = Classifier.classify(inv)
        val install = c.installations.single()
        val l = checkNotNull(ForeignRemoval.listing(install, c, inv, purge = false))
        assertEquals(ForeignRemoval.Role.MAIN, l.role)
        assertEquals(listOf("csqtt"), l.containers)
        assertEquals(listOf("csqtt:2.5.3"), l.images)
        assertTrue(l.units.joinToString(), "csqtt-docker-prereq.service" in l.units)
        assertTrue(l.will.joinToString(), "контейнер csqtt" in l.will.first() && "образ csqtt:2.5.3" in l.will.first())
        val v = values(ForeignRemoval.plan(install, inv, l).planText())
        assertEquals("csqtt" to "csqtt:2.5.3", v["containers"] to v["images"])
        // the manager offers its move, and its removal
        val entry = Manager.entries(c, checkNotNull(Version.parse("2.6.0")), inv).single()
        assertTrue(entry.offers.joinToString { it.action.name }, entry.offers.any { it.action in setOf(Action.UPDATE, Action.REPAIR) })
        assertTrue(entry.offers.any { it.action == Action.REMOVE })
    }

    @Test
    fun aStockDockerInstallMovesIntoDockerAndItsContainerIsTheSwitch() {
        val inv = inventory(*stockDocker.toTypedArray())
        val c = Classifier.classify(inv)
        val stock = c.installations.single()
        assertTrue(MoveRun.inDocker(stock))
        val proposal = com.csqtt.deploy.plan.Proposer.proposeMove(stock, c, inv, kotlin.random.Random(3))
        val core = Core(ByteArray(1), "2.6.0")
        val pin = checkNotNull(Rootfs.pin("amd64"))
        val v = values(checkNotNull(MoveRun.plan(stock, c, inv, proposal, "203.0.113.7", core, Credentials.generate(),
            RootfsChoice(pin, DockerMode.Source.Present))).planText())
        assertEquals("docker", v["mode"])
        assertEquals("46000" to "46002", v["udp"] to v["web"])
        assertEquals(listOf("csqtt", "csqtt", "csqtt:2.5.3"), listOf(v["move_container"], v["move_containers"], v["move_images"]))
        // no stock server unit to switch or re-check: its prereq goes with the finish
        assertEquals("", v["move_unit"])
        assertFalse(v.toString(), "expect_unit" in v)
        assertTrue(v["move_units"].orEmpty(), "csqtt-docker-prereq.service" in v["move_units"].orEmpty())
        // where Docker mode is refused: ours a unit, the stock container switched off by its policy all the same
        val unit = values(checkNotNull(MoveRun.plan(stock, c, inv, proposal, "203.0.113.7", core, Credentials.generate())).planText())
        assertFalse("mode" in unit)
        assertEquals("csqtt", unit["move_container"])
        // «Продолжить»: the kept plan again, its root filesystem chosen again
        val resumed = values(checkNotNull(MoveRun.resumePlan(v + ("action" to "create"), "0123-ab", core, Credentials.generate(),
            RootfsChoice(pin, DockerMode.Source.Fetch("https://example.invalid/rootfs.tar.gz")))).planText())
        assertEquals(listOf("1", "0123-ab", "https://example.invalid/rootfs.tar.gz", "csqtt"),
            listOf(resumed["resume"], resumed["resume_of"], resumed["rootfs_url"], resumed["move_container"]))
        assertNull(MoveRun.resumePlan(mapOf("id" to "0a1b2c"), "0123-ab", core, Credentials.generate()))
    }

    @Test
    fun bothRuntimesMoveFromTheOneThatServes() {
        // B16: the stock unit beside the container; the move goes from the runtime that serves (the unit when neither does) and
        // takes both
        fun unit(active: String) = "CSQTT_INV|unit|id=csqtt.service|load=loaded|active=$active|sub=${if (active == "active") "running" else "dead"}|" +
            "result=success|status=0|restarts=0|pid=0|file_state=disabled|fragment=/etc/systemd/system/csqtt.service|exec=/usr/local/bin/csqtt|" +
            "exec_ok=0|flags=--listen 0.0.0.0:46000 --web-port 46002 --config-dir /etc/csqtt|pre=|pre_status=|env="
        val inv = inventory(*(stockDocker + unit("inactive")).toTypedArray())
        val c = Classifier.classify(inv)
        val both = c.installations.single()
        assertTrue(both.broken.joinToString(), "B16" in both.broken)
        assertTrue(MoveRun.inDocker(both))
        val entry = Manager.entries(c, checkNotNull(Version.parse("2.6.0")), inv).single()
        assertTrue(entry.offers.joinToString { it.action.name } + " / " + entry.note, entry.offers.any { it.action == Action.REPAIR })
        val proposal = com.csqtt.deploy.plan.Proposer.proposeMove(both, c, inv, kotlin.random.Random(3))
        val v = values(checkNotNull(MoveRun.plan(both, c, inv, proposal, "203.0.113.7", Core(ByteArray(1), "2.6.0"), Credentials.generate(),
            RootfsChoice(checkNotNull(Rootfs.pin("amd64")), DockerMode.Source.Present))).planText())
        assertEquals("csqtt.service" to "csqtt", v["move_unit"] to v["move_container"])
        assertTrue(v["move_units"].orEmpty(), v["move_units"].orEmpty().startsWith("csqtt.service"))
        // the unit serving, the container not: the unit's runtime
        val unitServes = stockDocker.map { it.replace("|state=running|", "|state=exited|") } + unit("active")
        assertFalse(MoveRun.inDocker(Classifier.classify(inventory(*unitServes.toTypedArray())).installations.single()))
    }
}
