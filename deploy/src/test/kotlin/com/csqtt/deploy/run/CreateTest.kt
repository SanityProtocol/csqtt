// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.csqtt.deploy.run

import com.csqtt.deploy.plan.Proposal
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.concurrent.thread

class CreateTest {
    private val proposal = Proposal("0a1b2c", "sqc0", "10.77.0.0/24", 41_234, 52_345, 2)

    @Test
    fun theCredentialsAreFreshAndPlain() {
        val a = Credentials.generate()
        val b = Credentials.generate()
        assertTrue(a.mainPassword != b.mainPassword && a.webPass != b.webPass && a.secretPath != b.secretPath)
        assertEquals(16, a.mainPassword.length)
        assertTrue(a.secretPath.all { it.isLowerCase() || it.isDigit() })
        assertFalse("a password must never reach a log through toString", a.mainPassword in a.toString())
        for (bad in listOf("with space", "quote\"", "back\\slash", "дом", "")) {
            try {
                Credentials(bad, "admin", "Abcdefgh1", "path")
                fail("accepted «$bad»")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun thePlanCarriesFactsAndTheSecretsGoApart() {
        val credentials = Credentials("MainPass16chars0", "admin", "WebPassWebPassWebPa1", "s3cr3tpath01")
        val core = Core("not really a binary".toByteArray(), "2.6.0")
        val plan = CreateRun.plan(proposal, "203.0.113.7", core, credentials)
        val text = plan.planText()
        for (secret in listOf(credentials.mainPassword, credentials.webPass, credentials.secretPath)) {
            assertFalse("$secret in the plan", secret in text)
            assertTrue(secret in plan.secretsText())
        }
        assertTrue("action=create\n" in text && "id=0a1b2c\n" in text && "udp=41234\n" in text && "web=52345\n" in text)
        assertTrue("ports=41234 52345\n" in text && "iface=sqc0\n" in text && "public_ip=203.0.113.7\n" in text)
        assertTrue("core_sha=${core.sha256}\n" in text && "web_bind=\n" in text && "web_user=admin\n" in text)
        assertEquals(64, core.sha256.length)
        try {
            RunPlan("x", mapOf("a" to "two\nlines"))
            fail("a value with a newline was accepted")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun theRunReChecksWhatThePlanCarries() {
        // run_recheck reads these plan keys (B7b review backlog: nothing tied them); a create plan carries its own three
        val script = com.csqtt.deploy.bundle.Bundle.bytes("run.sh").toString(Charsets.UTF_8)
        val recheck = script.substringAfter("run_recheck() {").substringBefore("\n}\n")
        val read = Regex("\\$\\(plan ([a-z_]+)\\)").findAll(recheck).map { it.groupValues[1] }.toSet()
        // (action and id: an update cut off before holds the binary its plan expects, B10 review F2; core_sha: an update that
        // landed before a cut finds its own target, B10 verify of round 2, L1; own_ports: a move's, B12; move_role: a move cut
        // off after its marker is settled first, B12 review F2)
        assertEquals(setOf("ports", "subnet", "iface", "resume", "expect_unit", "expect_sha", "expect_bin", "action", "id", "core_sha",
            "own_ports", "move_role"), read)
        val plan = CreateRun.plan(proposal, "203.0.113.7", Core(ByteArray(1), "2.6.0"), Credentials.generate()).planText()
        for (key in listOf("ports", "subnet", "iface", "action")) assertTrue(key, "\n$key=" in "\n$plan")
        // and the create action reads only keys the plan has
        val create = script.substringAfter("action_create() {").substringBefore("\n}\n")
        val used = Regex("\\$\\(plan ([a-z_]+)\\)").findAll(create).map { it.groupValues[1] }.toSet()
        // (resume only on a continuation: «Продолжить», B8 review F1; the move's keys only on a move, B12; Docker's only in its
        // mode, B13a: DockerTest)
        for (key in used - setOf("resume", "resume_of", "move_role", "move_unit", "move_store", "mode", "rootfs_sha256", "rootfs_url")) {
            assertTrue("create reads $key", "\n$key=" in "\n$plan")
        }
    }

    @Test
    fun aMovedInstanceIsReadAndUndoneAsItsRulesSay() {
        val script = com.csqtt.deploy.bundle.Bundle.bytes("run.sh").toString(Charsets.UTF_8)
        fun body(f: String) = script.substringAfter("$f() {").substringBefore("\n}\n")
        // B12 review F1: a moved instance's env carries the stock app's quoted lines, so every reader unquotes (one reader)
        assertFalse(Regex("sed -n '?\"?s/\\^CSQTT_").containsMatchIn(script))
        assertTrue("env_unquote" in script.substringAfter("inst_env() {").substringBefore("\n"))
        for (f in listOf("action_update", "action_restore", "cmd_readback")) assertTrue(f, "inst_env " in body(f))
        // F2: a move cut off after its marker is settled before the stock unit and binary are re-checked
        val recheck = body("run_recheck")
        assertTrue(recheck.indexOf("/moved\" ]; then") in 0 until recheck.indexOf("rr_unit is gone"))
        // F3: a rolled-back move takes its marker before anything else; F4: the stock server starts again only if it ran
        val create = body("action_create")
        val cleanup = create.substringAfter("action_cleanup() {").substringBefore("\n\t}\n")
        assertTrue(cleanup, cleanup.indexOf("rm -f \"\$cr_state/moved\"") in 0 until cleanup.indexOf("mf_undo"))
        assertTrue(cleanup, "[ \$cr_mv_ran = 1 ]" in cleanup)
        assertTrue(create.indexOf("stock-ran") in 0 until create.indexOf("systemctl stop \"\$cr_mv_unit\""))
        assertTrue("[ \$cr_mv_ran = 0 ] || run_cmd systemctl start" in body("mv_settle"))
        // F5: the stock store a move leaves is marked as moved, and inspect says so
        assertTrue(".sanity-moved" in body("mv_finish"))
        assertTrue("\"moved=\$is_moved\"" in com.csqtt.deploy.bundle.Bundle.bytes("inspect.sh").toString(Charsets.UTF_8))
    }

    @Test
    fun aMovePlanCarriesWhatItsRunReadsAndLeavesWhatOursTakesOver() {
        fun fixture(name: String) = checkNotNull(com.csqtt.deploy.inspect.Classifier::class.java.getResource("fixtures/$name.report"))
            .readText().let { com.csqtt.deploy.inspect.Inventory.from((com.csqtt.deploy.wire.LineCodec.parse(it) as com.csqtt.deploy.wire.Report.Complete).lines) }
        val inv = fixture("v253main-systemd-noble")
        val c = com.csqtt.deploy.inspect.Classifier.classify(inv)
        val stock = c.installations.single()
        val proposal = com.csqtt.deploy.plan.Proposer.proposeMove(stock, c, inv, kotlin.random.Random(3))
        val text = checkNotNull(MoveRun.plan(stock, c, inv, proposal, "203.0.113.7", Core(ByteArray(1), "2.6.0"), Credentials.generate())).planText()
        val v = text.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        // it is a create on the stock ports, which the stock server holds until the switch
        assertEquals("create", v["action"])
        assertEquals("46000" to "46002", v["udp"] to v["web"])
        assertEquals("46000,46002", v["own_ports"])
        assertEquals(Triple("main", "csqtt.service", "/etc/csqtt"), Triple(v["move_role"], v["move_unit"], v["move_store"]))
        assertEquals("csqtt.service", v["expect_unit"])
        // its finish takes the stock objects, and never the LE lineage ours takes over (B14)
        assertTrue(v["move_units"]!!.startsWith("csqtt.service") && "/usr/local/bin/csqtt" in v["move_paths"]!!)
        assertEquals("", v["move_lineage"])
        // what create's run, its finish and its settle read is all in the plan
        val script = com.csqtt.deploy.bundle.Bundle.bytes("run.sh").toString(Charsets.UTF_8)
        val reads = listOf("action_create", "mv_finish", "mv_settle").flatMap { f ->
            Regex("\\$\\(plan ([a-z_]+)\\)").findAll(script.substringAfter("$f() {").substringBefore("\n}\n")).map { it.groupValues[1] }.toList()
        }.toSet() - setOf("resume", "resume_of", "mode", "rootfs_sha256", "rootfs_url")
        for (key in reads) assertTrue("the move reads $key", key in v)
        // a non-stock installation has no move
        val ours = fixture("handmade-systemd-noble")
        val oc = com.csqtt.deploy.inspect.Classifier.classify(ours)
        assertEquals(null, MoveRun.plan(oc.installations.single(), oc, ours, proposal, "203.0.113.7", Core(ByteArray(1), "2.6.0"), Credentials.generate()))
    }

    @Test
    fun thePlanSaysWhatTheHostGets() {
        fun preflight(vararg lines: String) = com.csqtt.deploy.inspect.Preflight.from(
            (com.csqtt.deploy.wire.LineCodec.parse(lines.joinToString("\n") + "\nCSQTT_END|${lines.size}\n") as com.csqtt.deploy.wire.Report.Complete).lines)
        val ufw = CreateRun.hostChanges(preflight("CSQTT_PRE|firewall|ufw=active|firewalld=inactive", "CSQTT_PRE|forwarding|ipv4=0"), proposal)
        assertTrue(ufw.joinToString(), "Откроет в ufw: UDP 41234, TCP 52345" in ufw && ufw.any { it.startsWith("Включит пересылку") })
        val firewalld = CreateRun.hostChanges(preflight("CSQTT_PRE|firewall|firewalld=active", "CSQTT_PRE|nat|iptables=0|probe=skipped",
            "CSQTT_PRE|forwarding|ipv4=1"), proposal.copy(webBind = "127.0.0.1"))
        assertTrue(firewalld.joinToString(), firewalld.first().startsWith("Установит iptables") &&
            firewalld.any { it.startsWith("Откроет в firewalld: UDP 41234;") } && firewalld.any { it.startsWith("Закрепит пересылку") })
    }

    @Test
    fun anUpdateReChecksTheUnitAndTheBinaryItWasPlannedOn() {
        val sha = "ab".repeat(32)
        val text = listOf(
            "CSQTT_INV|exe|sha=$sha|path=/opt/sanityprotocol/csqtt/0a1b2c/current/csqtt-server|size=1|label=v2.6.0|caption=1|startup=2.6.0|swg=1|mrtt=0",
            "CSQTT_INV|bin|path=/opt/sanityprotocol/csqtt/0a1b2c/current/csqtt-server|sha=$sha",
            "CSQTT_INV|unit|id=sanity-csqtt-0a1b2c.service|load=loaded|active=active|sub=running|result=success|status=0|restarts=0|pid=0|" +
                "file_state=enabled|fragment=/etc/systemd/system/sanity-csqtt-0a1b2c.service|exec=/opt/sanityprotocol/csqtt/0a1b2c/current/csqtt-server|" +
                "exec_ok=1|flags=--listen 0.0.0.0:41234 --config-dir /var/lib/sanityprotocol/csqtt/0a1b2c --instance sqc-0a1b2c|pre=|pre_status=|env=",
        ).joinToString("\n") + "\nCSQTT_END|3\n"
        val inv = com.csqtt.deploy.inspect.Inventory.from((com.csqtt.deploy.wire.LineCodec.parse(text) as com.csqtt.deploy.wire.Report.Complete).lines)
        val install = com.csqtt.deploy.inspect.Classifier.classify(inv).installations.single()
        assertEquals("0a1b2c", UpdateRun.idOf(install))
        val plan = UpdateRun.plan(install, inv, Core(ByteArray(3), "2.6.1")).planText()
        for (line in listOf("action=update", "id=0a1b2c", "version=2.6.1", "expect_unit=sanity-csqtt-0a1b2c.service",
            "expect_bin=/opt/sanityprotocol/csqtt/0a1b2c/current/csqtt-server", "expect_sha=$sha")) assertTrue(line, "\n$line\n" in "\n$plan")
        // the update action reads only keys its plan carries (resume aside)
        val script = com.csqtt.deploy.bundle.Bundle.bytes("run.sh").toString(Charsets.UTF_8)
        val update = script.substringAfter("action_update() {").substringBefore("\n}\n")
        for (key in Regex("\\$\\(plan ([a-z_]+)\\)").findAll(update).map { it.groupValues[1] }.toSet()) assertTrue(key, "\n$key=" in "\n$plan")
    }

    @Test
    fun removalRestoreAndRestartCarryWhatTheirRunsRead() {
        fun inv(vararg lines: String) = com.csqtt.deploy.inspect.Inventory.from((com.csqtt.deploy.wire.LineCodec.parse(
            lines.joinToString("\n") + "\nCSQTT_END|${lines.size}\n") as com.csqtt.deploy.wire.Report.Complete).lines)
        // a kept store: no unit, no release; its id from its state dir
        val leftover = com.csqtt.deploy.inspect.Classifier.classify(inv(
            "CSQTT_INV|store|dir=/var/lib/sanityprotocol/csqtt/0a1b2c|files=csqtt.db,env,manifest.json|lock=none|schema=table:client_groups|env_keys=CSQTT_WEB_PASS|public_ip=|domain=",
        )).installations.single()
        assertEquals("0a1b2c", Maintenance.idOf(leftover))
        val script = com.csqtt.deploy.bundle.Bundle.bytes("run.sh").toString(Charsets.UTF_8)
        fun reads(action: String) = Regex("\\$\\(plan ([a-z_]+)\\)").findAll(script.substringAfter("$action() {").substringBefore("\n}\n"))
            .map { it.groupValues[1] }.toSet()
        for ((action, plan) in listOf(
            "action_remove" to Maintenance.remove(leftover, purge = true),
            "action_restore" to Maintenance.restore(leftover, Core(ByteArray(2), "2.6.0")),
            "action_restart" to Maintenance.restart(leftover),
        )) {
            val text = plan.planText()
            for (key in reads(action)) assertTrue("$action reads $key", "\n$key=" in "\n$text")
        }
        assertTrue("\npurge=1\n" in "\n" + Maintenance.remove(leftover, purge = true).planText())
        assertTrue("\npurge=0\n" in "\n" + Maintenance.remove(leftover, purge = false).planText())
    }

    @Test
    fun theReadBackIsParsedAndItsFailuresNamed() {
        val text = """
            motd noise
            CSQTT_RB|public_ip|203.0.113.7
            CSQTT_RB|udp_port|41234
            CSQTT_RB|web_port|52345
            CSQTT_RB|web_bind|
            CSQTT_RB|web_user|admin
            CSQTT_RB|web_pass|WebPass
            CSQTT_RB|secret_path|s3cr3t
            CSQTT_RB|cert_sha256|${"ab".repeat(32)}
            CSQTT_RB|main_password|p%7Ca%25ss
            CSQTT_END|9
        """.trimIndent() + "\n"
        val c = ReadBack.parse(text, "vps.example")
        assertEquals("203.0.113.7", c.host)
        assertEquals("p|a%ss", c.mainPassword)
        assertEquals("https://203.0.113.7:52345/s3cr3t/", c.panelUrl)
        assertNull(c.webBind)
        assertFalse(c.panelLocalOnly)
        assertEquals("ab".repeat(32), c.certSha256)
        assertFalse("WebPass" in c.toString())
        // no public address on the box: the host the engine signed in to
        assertEquals("vps.example", ReadBack.parse(text.replace("public_ip|203.0.113.7", "public_ip|"), "vps.example").host)
        for ((broken, why) in listOf(
            "CSQTT_RB|error|sqc-0a1b2c is not installed\nCSQTT_END|1\n" to "sqc-0a1b2c is not installed",
            text.replace("CSQTT_END|9\n", "") to "the read-back was cut off",
            text.replace("CSQTT_RB|main_password|p%7Ca%25ss\n", "").replace("END|9", "END|8") to "the read-back has no main_password",
        )) {
            try {
                ReadBack.parse(broken, "h")
                fail("parsed: $broken")
            } catch (e: ReadBackFailed) {
                assertEquals(why, e.reason)
            }
        }
    }

    @Test
    fun theClientHelloHoldsWhatTheServerRequires() {
        val hello = Outside.clientHello(ByteArray(32) { it.toByte() })
        fun u16(at: Int) = ((hello[at].toInt() and 0xff) shl 8) or (hello[at + 1].toInt() and 0xff)
        fun u24(at: Int) = ((hello[at].toInt() and 0xff) shl 16) or u16(at + 1)
        assertEquals(22, hello[0].toInt())
        assertEquals(0xfefd, u16(1))
        assertEquals(hello.size - 13, u16(11))
        assertEquals(1, hello[13].toInt())
        val body = u24(14)
        assertEquals(body, u24(22))
        assertEquals(hello.size - 25, body)
        var at = 25
        assertEquals(0xfefd, u16(at)); at += 2 + 32
        assertEquals(0, hello[at].toInt()); at += 1 // session id
        assertEquals(0, hello[at].toInt()); at += 1 // cookie
        assertEquals(2, u16(at)); assertEquals(0xc02b, u16(at + 2)); at += 4
        assertEquals(1, hello[at].toInt()); assertEquals(0, hello[at + 1].toInt()); at += 2
        val end = at + 2 + u16(at); at += 2
        assertEquals(hello.size, end)
        val extensions = mutableMapOf<Int, List<Int>>()
        while (at < end) {
            val type = u16(at); val length = u16(at + 2)
            extensions[type] = (0 until length).map { hello[at + 4 + it].toInt() and 0xff }
            at += 4 + length
        }
        assertEquals(listOf<Int>(), extensions[23])
        assertEquals(listOf(0, 2, 0, 23), extensions[10])
        assertEquals(listOf(0, 2, 4, 3), extensions[13])
        assertEquals(listOf(0, 2, 0, 1, 0), extensions[14])
    }

    @Test
    fun theUdpCheckHearsAServerAndNotSilence() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
            val responder = thread {
                val buffer = ByteArray(2_048)
                val packet = DatagramPacket(buffer, buffer.size)
                server.receive(packet)
                // the first record of a server flight: handshake, DTLS 1.2, a ServerHello
                val answer = ByteArray(40).also { it[0] = 22; it[1] = 0xfe.toByte(); it[2] = 0xfd.toByte(); it[13] = 2 }
                server.send(DatagramPacket(answer, answer.size, packet.socketAddress))
            }
            assertTrue(Outside.udp("127.0.0.1", server.localPort, tries = 2, waitMs = 1_000))
            responder.join()
        }
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { silent ->
            assertFalse(Outside.udp("127.0.0.1", silent.localPort, tries = 1, waitMs = 300))
        }
    }

    @Test
    fun aLostAnswerIsAskedForAgainFromANewEndpoint() {
        // the server answers one ClientHello per source endpoint (B8 review F2); here its first answer is lost on the way
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
            val seen = mutableSetOf<Int>()
            val responder = thread {
                val buffer = ByteArray(2_048)
                repeat(3) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try { server.receive(packet) } catch (_: Exception) { return@thread }
                    val first = seen.isEmpty()
                    if (seen.add(packet.port) && !first) {
                        val answer = ByteArray(40).also { it[0] = 22; it[1] = 0xfe.toByte(); it[2] = 0xfd.toByte(); it[13] = 2 }
                        server.send(DatagramPacket(answer, answer.size, packet.socketAddress))
                        return@thread
                    }
                }
            }
            server.soTimeout = 5_000
            assertTrue(Outside.udp("127.0.0.1", server.localPort, tries = 3, waitMs = 500))
            responder.join(5_000)
        }
    }

    @Test
    fun aCutOffCreateIsContinuedFromItsKeptPlan() {
        val kept = CreateRun.plan(proposal.copy(webBind = "127.0.0.1"), "203.0.113.7", Core(ByteArray(1), "2.6.0"), Credentials.generate()).planText()
        assertEquals(proposal.copy(webBind = "127.0.0.1"), proposalOfKeptPlan(kept))
        assertEquals(null, proposalOfKeptPlan("action=selftest\nsteps=2\n"))
        val again = CreateRun.plan(proposal, "203.0.113.7", Core(ByteArray(1), "2.6.0"), Credentials.generate(), resumeOf = "20261124084322-26af").planText()
        assertTrue("\nresume=1\n" in "\n$again" && "\nresume_of=20261124084322-26af\n" in "\n$again")
    }

    @Test
    fun thePanelIsReachedOverItsPinnedCertificateOnly() {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val name = X500Name("CN=csqtt")
        val now = System.currentTimeMillis()
        val cert: X509Certificate = JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(now - 3_600_000), Date(now + 86_400_000), name, keys.public)
                .build(JcaContentSignerBuilder("SHA256withECDSA").build(keys.private)))
        val password = "zoo".toCharArray()
        val store = KeyStore.getInstance("PKCS12").apply { load(null, null); setKeyEntry("panel", keys.private, password, arrayOf(cert)) }
        val context = SSLContext.getInstance("TLS").apply {
            init(KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }.keyManagers, null, null)
        }
        context.serverSocketFactory.createServerSocket(0, 4, InetAddress.getLoopbackAddress()).use { server ->
            val paths = mutableListOf<String>()
            val acceptor = thread(isDaemon = true) {
                repeat(2) {
                    try {
                        server.accept().use { socket ->
                            val request = socket.getInputStream().bufferedReader().readLine().orEmpty()
                            paths += request
                            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            val pin = sha256Hex(cert.encoded)
            assertEquals(PanelAnswer.Status(200), Outside.panel("127.0.0.1", server.localPort, "/s3cr3t/", pin, 5_000))
            assertEquals("GET /s3cr3t/ HTTP/1.1", paths.single())
            assertEquals(PanelAnswer.WrongCertificate, Outside.panel("127.0.0.1", server.localPort, "/s3cr3t/", "00".repeat(32), 5_000))
            acceptor.join(5_000)
        }
        val closed = ServerSocket(0).use { it.localPort }
        assertTrue(Outside.panel("127.0.0.1", closed, "/", null, 2_000) is PanelAnswer.NoAnswer)
    }
}
