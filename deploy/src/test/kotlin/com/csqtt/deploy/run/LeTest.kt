// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.csqtt.deploy.run

import com.csqtt.deploy.bundle.Bundle
import com.csqtt.deploy.inspect.Preflight
import com.csqtt.deploy.wire.LineCodec
import com.csqtt.deploy.wire.Report
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** «Сертификат» (P2 B14): its plan, its «Будет», the read-back's certificate, and the hook our issuance and timer share. */
class LeTest {
    private val runSh: String by lazy { Bundle.bytes("run.sh").toString(Charsets.UTF_8) }

    private fun body(f: String) = runSh.substringAfter("\n$f() {").substringBefore("\n}\n")

    private fun preflight(vararg lines: String): Preflight =
        Preflight.from((LineCodec.parse(lines.joinToString("\n") + "\nCSQTT_END|${lines.size}\n") as Report.Complete).lines)

    private fun values(plan: String) = plan.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    fun thePlanNamesTheInstanceItsAddressAndWhereTheCertificateComesFrom() {
        val v = values(LeRun.plan("0a1b2c", "203.0.113.7").planText())
        assertEquals(listOf("le", "0a1b2c", "203.0.113.7", "", "", ""),
            listOf(v["action"], v["id"], v["public_ip"], v["acme_url"], v["acme_ca"], v["pip_find_links"]))
        // the zoo's Pebble: its directory, its CA (one line), its wheelhouse
        val zoo = values(LeRun.plan("0a1b2c", "192.168.77.70", LeRun.Acme("https://192.168.77.68:14000/dir",
            "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n".toByteArray(), "http://192.168.77.68:8068/wheels/")).planText())
        assertEquals("https://192.168.77.68:14000/dir", zoo["acme_url"])
        assertEquals("-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n",
            String(java.util.Base64.getDecoder().decode(zoo["acme_ca"])))
        assertEquals("http://192.168.77.68:8068/wheels/", zoo["pip_find_links"])
    }

    @Test
    fun itsWillSaysWhatItChangesOrWhyNoneComes() {
        fun will(le: String, port80: String = "holder=") = LeRun.will(preflight("CSQTT_PRE|le|$le", "CSQTT_PRE|port80|$port80|ss=1"), "203.0.113.7")
        val fresh = will("python=3.12|venv=0|certbot=|timer=|lineages=")
        assertEquals(listOf("Установит python3-venv", "Поставит certbot ${LeRun.CERTBOT} с PyPI в /opt/sanityprotocol/certbot"), fresh.take(2))
        assertTrue(fresh.joinToString(), fresh.any { it.startsWith("Получит сертификат Let's Encrypt на 203.0.113.7") && "TCP 80" in it })
        // ours there already: nothing installed again
        val again = will("python=3.12|venv=1|certbot=${LeRun.CERTBOT}|timer=active|lineages=ip-203-0-113-7:Dec_1_00:00:00_2026_GMT")
        assertFalse(again.joinToString(), again.any { "Установит" in it || "Поставит" in it })
        // none to be had: said why, the panel keeping its own certificate
        assertTrue(will("python=3.9|venv=1|certbot=|timer=|lineages=").single().contains("Python 3.9 старше 3.10"))
        assertTrue(will("python=|venv=0|certbot=|timer=|lineages=").single().contains("нет python3"))
        assertTrue(will("python=3.12|venv=1|certbot=|timer=|lineages=", "holder=nginx").single().contains("TCP 80 занят (nginx)"))
    }

    @Test
    fun theReadBackSaysWhoIssuedThePanelsCertificate() {
        val lines = listOf("CSQTT_RB|udp_port|46000", "CSQTT_RB|web_port|46002", "CSQTT_RB|main_password|m", "CSQTT_RB|web_user|zoo",
            "CSQTT_RB|web_pass|p", "CSQTT_RB|cert_sha256|" + "a".repeat(64), "CSQTT_RB|cert_issuer|CN=E8,O=Let's Encrypt,C=US",
            "CSQTT_RB|cert_not_after|Dec  1 00:00:00 2026 GMT", "CSQTT_RB|cert_le|ip-203-0-113-7")
        val c = ReadBack.parse(lines.joinToString("\n") + "\nCSQTT_END|${lines.size}\n", "203.0.113.7")
        assertEquals(listOf("CN=E8,O=Let's Encrypt,C=US", "Dec  1 00:00:00 2026 GMT", "ip-203-0-113-7"), listOf(c.certIssuer, c.certNotAfter, c.certLe))
        // an older run.sh's read-back has none of them
        val old = ReadBack.parse(lines.take(6).joinToString("\n") + "\nCSQTT_END|6\n", "203.0.113.7")
        assertEquals(listOf<String?>(null, null, null), listOf(old.certIssuer, old.certNotAfter, old.certLe))
    }

    private fun hookText(): String {
        val start = runSh.indexOf("le_hook_text() {")
        return runSh.substring(runSh.indexOf("cat <<'EOF'\n", start) + "cat <<'EOF'\n".length, runSh.indexOf("\nEOF\n", start) + 1)
    }

    /** The hook run in sh with its commands stubbed (they echo what they are asked), the box's dirs under [dir]. */
    private fun hook(dir: File, args: String, stubs: Map<String, String>): String {
        val bin = File(dir, "bin").apply { mkdirs() }
        for ((name, script) in stubs) File(bin, name).apply { writeText("#!/bin/sh\n$script\n"); setExecutable(true) }
        val hook = File(dir, "le-hook.sh").apply { writeText(hookText()) }
        val proc = ProcessBuilder("sh", "-c", "sh ${hook.path} $args").redirectErrorStream(true).apply {
            environment()["PATH"] = "${bin.path}:/usr/bin:/bin"
            environment()["LE_DIR"] = File(dir, "le").path
            environment()["SQC_DIR"] = File(dir, "sqc").path
            environment().remove("RENEWED_LINEAGE")
        }.start()
        assertTrue(proc.waitFor(30, TimeUnit.SECONDS))
        return proc.inputStream.bufferedReader().readText()
    }

    @Test
    fun aLineageGoesIntoEveryLiveInstanceThatUsesItEachServerToldToReload() {
        val dir = kotlin.io.path.createTempDirectory("csqtt-le").toFile()
        try {
            val live = File(dir, "le/live/ip-203-0-113-7").apply { mkdirs() }
            File(live, "fullchain.pem").writeText("CHAIN\n")
            File(live, "privkey.pem").writeText("KEY\n")
            fun instance(id: String, lineage: String, vararg files: String) = File(dir, "sqc/$id").apply {
                mkdirs()
                File(this, "le").writeText("$lineage\n")
                for (f in files) File(this, f).writeText("x\n")
            }
            val unit = instance("aaaaaa", "ip-203-0-113-7")
            val docker = instance("bbbbbb", "ip-203-0-113-7", "docker")
            val other = instance("cccccc", "ip-198-51-100-1")
            val kept = instance("dddddd", "ip-203-0-113-7", "unit.saved")
            // (the hook sends what they print away: they write what they were asked into a file of their own)
            val calls = File(dir, "calls")
            hook(dir, "deploy ip-203-0-113-7", mapOf("systemctl" to "echo systemctl \"$@\" >>${calls.path}",
                "docker" to "echo docker \"$@\" >>${calls.path}", "logger" to "echo logger \"$@\" >>${calls.path}"))
            assertEquals(setOf("systemctl kill -s USR1 --kill-whom=main sanity-csqtt-aaaaaa.service", "docker kill -s USR1 sanity-csqtt-bbbbbb"),
                calls.readText().trim().lines().toSet())
            for (d in listOf(unit, docker)) {
                assertEquals("CHAIN\n", File(d, "web_cert.pem").readText())
                assertEquals("KEY\n", File(d, "web_key.pem").readText())
                val mode = java.nio.file.Files.getPosixFilePermissions(File(d, "web_key.pem").toPath())
                assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), mode)
            }
            // another lineage's instance and a kept store: untouched
            assertFalse(File(other, "web_cert.pem").exists() || File(kept, "web_cert.pem").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theChallengesPortIsOpenedWhereInputIsClosedAndOnlyThatClosedAfter() {
        val dir = kotlin.io.path.createTempDirectory("csqtt-le").toFile()
        try {
            File(dir, "le").mkdirs()
            // INPUT drops; no ufw, no firewalld: an iptables rule of ours, noted, then deleted (until none is left)
            val stubs = mapOf("systemctl" to "echo inactive", "iptables" to """
                case "$*" in
                *"-S INPUT"*) echo '-P INPUT DROP' ;;
                *" -I INPUT "*) echo "iptables $*" ;;
                *" -D INPUT "*) [ -e "${dir.path}/deleted" ] && exit 1; : >"${dir.path}/deleted"; echo "iptables $*" ;;
                esac""".trimIndent())
            assertEquals("iptables -w 5 -I INPUT -p tcp --dport 80 -m comment --comment SANITY_CSQTT_LE -j ACCEPT", hook(dir, "open", stubs).trim())
            assertEquals("iptables\n", File(dir, "le/port80").readText())
            // a second open while one stands adds nothing
            assertEquals("", hook(dir, "open", stubs).trim())
            assertEquals("iptables -w 5 -D INPUT -p tcp --dport 80 -m comment --comment SANITY_CSQTT_LE -j ACCEPT", hook(dir, "close", stubs).trim())
            assertFalse(File(dir, "le/port80").exists())
            // INPUT open: nothing to open, nothing to close
            val open = stubs + ("iptables" to "case \"$*\" in *\"-S INPUT\"*) echo '-P INPUT ACCEPT' ;; *) echo \"iptables $*\" ;; esac")
            assertEquals("", hook(dir, "open", open).trim() + hook(dir, "close", open).trim())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun ourCertbotIsIsolatedShortLivedAndTornDownWithTheLastInstance() {
        val le = body("action_le")
        // M-135: the host's cli.ini and renewal hooks never run; our own state dirs (the distro's certbot.timer renews
        // /etc/letsencrypt); the short-lived profile, never a retry without it
        assertTrue(le, "--config /dev/null --no-directory-hooks --config-dir \"\$LE_DIR\"" in le)
        assertTrue(le, "--preferred-profile shortlived --ip-address \"\$le_ip\"" in le)
        assertEquals(1, Regex("certonly").findAll(le).count())
        assertTrue(le, "renew_before_expiry = 3 days" in le)
        // the challenge's port opened and closed around it; the lineage written before it is deployed; the timer after
        assertTrue(le, le.indexOf("\"\$LE_HOOK\" open") in 0 until le.indexOf("certonly") && le.indexOf("certonly") < le.indexOf("\"\$LE_HOOK\" close"))
        assertTrue(le, le.indexOf(">\"\$in_state/le.new\"") in 0 until le.indexOf("\"\$LE_HOOK\" deploy"))
        // the renewal is the hook's, isolated the same way, its own hooks around it
        val hook = hookText()
        assertTrue(hook, "--config /dev/null --no-directory-hooks --config-dir \"\$LE_DIR\"" in hook && "--deploy-hook \"\$0 deploy\"" in hook)
        // the last instance's removal takes the timer, the venv and the ACME state; every removal drops its reference
        assertTrue(body("inst_take_down").contains("le_drop \"\$in_state\""))
        assertTrue(body("le_drop").contains("rm -rf \"\$LE_VENV\" \"\$LE_DIR\""))
        assertTrue(runSh.contains("\tle) action_le ;;"))
        // the pin the engine says is the one run.sh installs
        assertTrue(runSh.contains("LE_CERTBOT=${LeRun.CERTBOT}\n"))
    }

    @Test
    fun aStockInstallOnADomainIsOfferedNoMove() {
        // PR3's domain mode (P2 B14): ours certifies an IP alone, so its move would drop the domain's certificate
        val lines = checkNotNull(com.csqtt.deploy.inspect.Classifier::class.java.getResource("fixtures/v253main-systemd-noble.report")).readText()
            .lines().filter { it.startsWith("CSQTT_INV|") || it.startsWith("CSQTT_PRE|") }
        fun entry(lines: List<String>): com.csqtt.deploy.plan.Entry {
            val inv = com.csqtt.deploy.inspect.Inventory.from((LineCodec.parse(lines.joinToString("\n") + "\nCSQTT_END|${lines.size}\n") as Report.Complete).lines)
            return com.csqtt.deploy.plan.Manager.entries(com.csqtt.deploy.inspect.Classifier.classify(inv), checkNotNull(com.csqtt.deploy.plan.Version.parse("2.6.0")), inv).single()
        }
        val moves = setOf(com.csqtt.deploy.plan.Action.UPDATE, com.csqtt.deploy.plan.Action.REPAIR, com.csqtt.deploy.plan.Action.UPDATE_FROM_21)
        assertTrue(entry(lines).offers.any { it.action in moves })
        val onDomain = entry(lines.map { if (it.startsWith("CSQTT_INV|store|")) it.replace(Regex("\\|domain=(?=\\||$)"), "|domain=vpn.example.org") else it })
        assertFalse(onDomain.offers.joinToString { it.action.name }, onDomain.offers.any { it.action in moves })
        assertTrue(onDomain.note.orEmpty(), "vpn.example.org" in onDomain.note.orEmpty())
        assertTrue(onDomain.offers.any { it.action == com.csqtt.deploy.plan.Action.REMOVE })
    }
}
