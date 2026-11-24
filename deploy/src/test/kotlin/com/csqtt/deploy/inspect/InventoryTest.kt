// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.csqtt.deploy.inspect

import com.csqtt.deploy.wire.LineCodec
import com.csqtt.deploy.wire.Report
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryTest {
    /**
     * inspect.sh as root beside a stock-style core (P1's, /usr/local/bin/csqtt on /etc/csqtt, started with --password on its
     * argv), in a privileged container (2026-11-24): cut to the views it shows, plus the views a container has not (a unit,
     * Docker's, a helper, an install log), in the shapes inspect.sh prints them.
     */
    private val report = """
        CSQTT_INV|exe|sha=b02bf5c9f45ceac7ed479cc073fa6cd447ee719e64dda776aa716af952ea4d5d|path=/usr/local/bin/csqtt|size=11392520|label=v2.6.0|caption=1|startup=2.6.0 (SanityProtocol build of amurcanov 2.5.0) Tunnel|swg=1|mrtt=0
        CSQTT_INV|proc|pid=8|exe=/usr/local/bin/csqtt|deleted=0|comm=csqtt|unit=csqtt.service|ctr=|tun=csqtt1|sha=b02bf5c9f45ceac7ed479cc073fa6cd447ee719e64dda776aa716af952ea4d5d|flags=--listen 0.0.0.0:46000 --web-port 46002 --config-dir /etc/csqtt
        CSQTT_INV|unit|id=csqtt.service|load=loaded|active=active|sub=running|result=success|status=0|restarts=0|pid=8|file_state=enabled|fragment=/etc/systemd/system/csqtt.service|exec=/usr/local/bin/csqtt|exec_ok=1|flags=--listen 0.0.0.0:46000 --web-port 46002 --config-dir /etc/csqtt|pre=/usr/local/lib/csqtt/network-up.sh,/usr/local/lib/csqtt/tun-recover.sh|env=/etc/csqtt/csqtt.env
        CSQTT_INV|unit|id=csqtt-docker-prereq.service|load=loaded|active=inactive|sub=dead|result=success|status=0|restarts=0|pid=0|file_state=enabled|fragment=/etc/systemd/system/csqtt-docker-prereq.service|exec=/usr/local/lib/csqtt/docker-prereq.sh|exec_ok=0|flags=|pre=|env=
        CSQTT_INV|dockerdeps|requires=csqtt-docker-prereq.service|wants=
        CSQTT_INV|ctr|id=0123456789ab|name=csqtt25|image=csqtt25:2.5.3|state=restarting|pid=0|restarts=7|exit=1|policy=unless-stopped|net=host|labels=com.csqtt.managed=true,com.csqtt.component=server|flags=--listen=0.0.0.0:46100 --web-port 46102 --config-dir /srv/csqtt25 --instance csqtt25 --iface csqtt25 --tun-addr 10.66.68.1/24 --no-nat|mounts=/etc/csqtt25:/srv/csqtt25,/run/csqtt25:/run/csqtt25|runtime=docker
        CSQTT_INV|image|ref=csqtt25:2.5.3|id=sha256:5f0e|runtime=docker
        CSQTT_INV|store|dir=/etc/csqtt|files=csqtt.db,csqtt.env,.server.lock,web_cert.pem|lock=held|schema=device_limit,table:client_groups,table:meta,table:passwords|env_keys=CSQTT_PUBLIC_IP,WEB_PASS,WEB_USER|public_ip=203.0.113.9|domain=
        CSQTT_INV|tun|name=csqtt1|state=UNKNOWN|persist=off
        CSQTT_INV|addr|dev=csqtt1|cidr=10.66.67.1/24
        CSQTT_INV|route|dst=10.66.67.0/24|dev=csqtt1|table=main
        CSQTT_INV|iprule|rule=30001:	from all fwmark 0x7531/0x7531 lookup 30001
        CSQTT_INV|listen|proto=udp|addr=0.0.0.0|port=46000|comm=csqtt|pid=8
        CSQTT_INV|listen|proto=tcp|addr=127.0.0.53%25lo|port=53|comm=|pid=
        CSQTT_INV|fw|v=4|table=nat|kind=rule|text=-A POSTROUTING -s 10.66.67.0/24 ! -o csqtt1 -m comment --comment CSQTT_MANAGED -j MASQUERADE
        CSQTT_INV|fw|v=4|table=filter|kind=chain|text=SQC-1A2B3C_MANAGED_FWD
        CSQTT_INV|nft|tables=ip filter,ip nat,inet swg_fwd
        CSQTT_INV|file|path=/usr/local/lib/csqtt/network-up.sh|type=f|sha=9f|role=0|exit21=1|notsent=0|version=2.5.3
        CSQTT_INV|file|path=/var/log/csqtt-install.log|type=f|runs=3|last_run==== CSQTT Installer v2.5.3 — 2026-11-07 ===|last_line=CSQTT_DEPLOY_OK
        CSQTT_END|19
    """.trimIndent() + "\n"

    private fun inventory(): Inventory = Inventory.from((LineCodec.parse(report) as Report.Complete).lines)

    @Test
    fun everyViewIsReadWithItsJoinKeys() {
        val inv = inventory()
        val proc = inv.procs.single()
        assertEquals(8, proc.pid)
        assertEquals("csqtt.service", proc.unit)
        assertEquals(listOf("csqtt1"), proc.tuns)
        assertEquals("/etc/csqtt", proc.flags.configDir)
        assertEquals(46000, proc.flags.udpPort)
        assertEquals(46002, proc.flags.webPort)
        val exe = inv.exes.getValue(proc.sha)
        assertEquals("v2.6.0", exe.label)
        assertTrue(exe.caption && exe.swgFlags && !exe.mrtt)

        val unit = inv.units.first()
        assertEquals(listOf("/usr/local/lib/csqtt/network-up.sh", "/usr/local/lib/csqtt/tun-recover.sh"), unit.pre)
        assertEquals(listOf("/etc/csqtt/csqtt.env"), unit.envFiles)
        assertEquals(true, unit.execPresent)
        // B14's signature: a unit docker.service requires, whose helper is gone
        val prereq = inv.units.single { it.id in inv.dockerRequires }
        assertEquals(false, prereq.execPresent)

        val ctr = inv.containers.single()
        assertEquals("csqtt25", ctr.flags["--instance"])
        assertEquals(46100, ctr.flags.udpPort)
        assertTrue(ctr.flags.has("--no-nat"))
        assertNull(ctr.flags["--no-nat"])
        assertEquals("true", ctr.labels["com.csqtt.managed"])
        assertEquals("/etc/csqtt25" to "/srv/csqtt25", ctr.mounts.first())
        assertEquals(7, ctr.restarts)

        val store = inv.stores.single()
        assertTrue(store.locked)
        assertTrue("table:client_groups" in store.schema && "device_limit" in store.schema)
        assertFalse("table:devices" in store.schema)
        assertEquals("203.0.113.9", store.publicIp)

        assertEquals(listOf("127.0.0.53%lo"), inv.listens.filter { it.proto == "tcp" }.map { it.addr })
        assertEquals(1, inv.firewall.count { it.isChain })
        assertEquals(listOf("ip filter", "ip nat", "inet swg_fwd"), inv.nftTables)
        assertEquals(listOf("30001:\tfrom all fwmark 0x7531/0x7531 lookup 30001"), inv.ipRules)
        val log = inv.files.single { it.path.endsWith("install.log") }
        assertEquals("CSQTT_DEPLOY_OK", log.facts["last_line"])
        assertEquals("=== CSQTT Installer v2.5.3 — 2026-11-07 ===", log.facts["last_run"])
        assertEquals("1", inv.files.single { it.path.endsWith(".sh") }.facts["exit21"])
    }

    @Test
    fun theServersFlagsAreReadInEveryForm() {
        val flags = ServerFlags.parse("--listen=0.0.0.0:46100 --no-nat --instance csqtt25 --config-dir /srv/x --no-web")
        assertEquals(46100, flags.udpPort)
        assertEquals("csqtt25", flags["--instance"])
        assertEquals("/srv/x", flags.configDir)
        assertTrue(flags.has("--no-nat") && flags.has("--no-web"))
        assertNull(ServerFlags.parse("").udpPort)
    }

    @Test
    fun aLineInspectShDoesNotKnowYetIsSkipped() {
        val lines = (LineCodec.parse("CSQTT_INV|someday|x=1\nCSQTT_INV|proc|pid=x\nCSQTT_END|2\n") as Report.Complete).lines
        val inv = Inventory.from(lines)
        assertTrue(inv.procs.isEmpty())
    }
}
