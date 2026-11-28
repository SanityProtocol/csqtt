package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Card p3-apply-bar (the operator, 2026-11-28: «That bar - for every change that require a reconnect»): what is stored
 * now is compared with what the run's start read, value by value. A change of the user's is for the next connection
 * and is named; what the app or the server wrote into the running profile is not.
 */
class RunPendingTest {
    private val manual = CsqttConstants.VkAutoHash.MODE_MANUAL
    private val api = CsqttConstants.VkAutoHash.MODE_AUTO_API
    private val js = CsqttConstants.VkAutoHash.MODE_AUTO_JS
    private val calls = CsqttConstants.VkAuth.MODE_CALLS
    private val captcha = CsqttConstants.VkAuth.MODE_CAPTCHA
    private val autoJs = CsqttConstants.VkAuth.MODE_AUTO_JS
    private val udp = CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT
    private val tcp = CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS
    private val accountA = "vk1.a." + "A".repeat(40)
    private val accountB = "vk1.a." + "B".repeat(40)

    private fun settings(
        transport: String = tcp,
        dpi: DpiSplitMode = DpiSplitMode.LIGHT,
        authMode: String = calls,
        streams: Int = 20,
    ) = TunnelStartSettings(
        secondaryVkHash = "", workersPerHash = streams, sni = "", protocol = "udp", vkAuthMode = authMode,
        captchaMode = "auto", captchaSolveMethod = "auto", fingerprint = "firefox",
        clientIds = CsqttConstants.Tunnel.DEFAULT_CLIENT_IDS, obfsMode = CsqttConstants.Tunnel.DEFAULT_OBFS_MODE,
        turnTransport = transport, dpiSplit = dpi, srtpMode = CsqttConstants.SrtpMode.AUTO, customTurnIps = "",
    )

    private val own = SourceRead(address = "203.0.113.5", port = 46000, password = "p-one", ownHashes = listOf("h1", "h2"))
    private val link = SourceRead(linkMode = true, link = "csqtt://connect?v=2&host=home.example.net", ownHashes = listOf("h1"))

    private fun read(source: SourceRead = own, settings: TunnelStartSettings = settings(), hashMode: String = api) =
        RunRead(profile = 3, source = source, settings = settings, hashMode = hashMode)

    /** A run that took [read]: on the user's own account [account] unless told otherwise. */
    private fun taken(
        read: RunRead = read(),
        account: String = accountA,
        usesAccount: Boolean = true,
        accountOwn: Boolean = true,
    ) = RunTaken(read, authMode = read.settings.vkAuthMode, usesAccount = usesAccount, account = account, accountOwn = accountOwn)

    private fun pending(taken: RunTaken, now: RunRead, nowAccount: String = accountA) = RunPending.of(taken, now, nowAccount)

    @Test
    fun `nothing waits while the store holds what the run took`() {
        assertEquals(emptySet<RunKey>(), pending(taken(), read()))
        assertEquals(emptySet<RunKey>(), pending(taken(read(link)), read(link)))
    }

    @Test
    fun `the transport the user picked under the tunnel waits, and no longer once it is put back`() {
        // the operator's own case: connected on TCP, UDP picked, and nothing said so
        val run = taken()
        val picked = read(settings = settings(transport = udp))
        assertTrue(RunKey.TRANSPORT in pending(run, picked))
        assertEquals("put back, nothing waits", emptySet<RunKey>(), pending(run, read()))
    }

    @Test
    fun `each setting of the core is its own key`() {
        val run = taken()
        fun only(now: TunnelStartSettings) = pending(run, read(settings = now))
        assertEquals(setOf(RunKey.SRTP), only(settings().copy(srtpMode = CsqttConstants.SrtpMode.DTLS)))
        assertEquals(setOf(RunKey.STREAMS), only(settings(streams = 40)))
        assertEquals(setOf(RunKey.FINGERPRINT), only(settings().copy(fingerprint = "chrome")))
        assertEquals(setOf(RunKey.TURN), only(settings().copy(customTurnIps = "198.51.100.1,198.51.100.2")))
        assertEquals(setOf(RunKey.AUTH_MODE), only(settings(authMode = captcha)))
        assertEquals(setOf(RunKey.CAPTCHA_MODE), only(settings().copy(captchaMode = "rjs")))
        assertEquals(setOf(RunKey.CAPTCHA_SOLVE), only(settings().copy(captchaSolveMethod = "manual")))
        assertEquals(setOf(RunKey.DPI), only(settings(dpi = DpiSplitMode.MEDIUM)))
        // what no control sets and the core takes all the same: a config import can write them
        assertEquals(setOf(RunKey.OTHER), only(settings().copy(obfsMode = "other")))
        assertEquals(setOf(RunKey.OTHER), only(settings().copy(clientIds = "1,2")))
        assertEquals(setOf(RunKey.HASH_MODE), pending(run, read(hashMode = manual)))
    }

    @Test
    fun `a value a start carries and nothing reads is not waited for`() {
        val now = settings().copy(secondaryVkHash = "x".repeat(20), sni = "example.org", protocol = "tcp")
        assertEquals(emptySet<RunKey>(), pending(taken(), read(settings = now)))
    }

    @Test
    fun `the DPI level counts as the transport runs it`() {
        // over UDP there is no split: a level stored there changes nothing of a UDP run
        val onUdp = taken(read(settings = settings(transport = udp, dpi = DpiSplitMode.OFF)))
        assertEquals(emptySet<RunKey>(), pending(onUdp, read(settings = settings(transport = udp, dpi = DpiSplitMode.HEAVY))))
        // from TCP with a level to UDP: the transport waits, and the level the run splits with is not the next one's
        val onTcp = taken()
        assertEquals(setOf(RunKey.TRANSPORT, RunKey.DPI), pending(onTcp, read(settings = settings(transport = udp))))
        // from UDP to TCP with no level: only the transport
        assertEquals(setOf(RunKey.TRANSPORT), pending(onUdp, read(settings = settings(transport = tcp, dpi = DpiSplitMode.OFF))))
        assertEquals(DpiSplitMode.OFF, RunPending.dpiInUse(udp, DpiSplitMode.HEAVY))
        assertEquals(DpiSplitMode.HEAVY, RunPending.dpiInUse(tcp, DpiSplitMode.HEAVY))
    }

    @Test
    fun `the own server's address, port and password each wait`() {
        val run = taken()
        assertEquals(setOf(RunKey.ADDRESS), pending(run, read(own.copy(address = "198.51.100.7"))))
        assertEquals(setOf(RunKey.PORT), pending(run, read(own.copy(port = 56000))))
        assertEquals(setOf(RunKey.PASSWORD), pending(run, read(own.copy(password = "p-two"))))
        assertEquals(setOf(RunKey.OWN_HASHES), pending(run, read(own.copy(ownHashes = listOf("h1", "h3")))))
        // a link's values are not an own server's: what a config import left in them does not count
        assertEquals(emptySet<RunKey>(), pending(run, read(own.copy(link = "csqtt://x", removedOwner = setOf("o1"), acceptServerHashes = false))))
    }

    @Test
    fun `a participant's link, the owner's hashes taken out and the switch each wait`() {
        val run = taken(read(link))
        assertEquals(setOf(RunKey.LINK), pending(run, read(link.copy(link = "csqtt://connect?v=2&host=other.example.net"))))
        assertEquals(setOf(RunKey.OWNER_HASHES), pending(run, read(link.copy(removedOwner = setOf("o1")))))
        assertEquals(setOf(RunKey.ACCEPT_HASHES), pending(run, read(link.copy(acceptServerHashes = false))))
        assertEquals(setOf(RunKey.OWN_HASHES), pending(run, read(link.copy(ownHashes = emptyList()))))
        // an own server's fields are not a link's
        assertEquals(emptySet<RunKey>(), pending(run, read(link.copy(address = "203.0.113.9", port = 1, password = "x"))))
    }

    @Test
    fun `a profile that became the other kind waits as a whole connection`() {
        assertEquals(setOf(RunKey.LINK), pending(taken(), read(link.copy(ownHashes = own.ownHashes))))
        assertEquals(setOf(RunKey.ADDRESS), pending(taken(read(link)), read(own.copy(ownHashes = link.ownHashes))))
    }

    // ---- the VK account: compared with the account the run has now

    @Test
    fun `another account picked under the tunnel waits`() {
        assertEquals(setOf(RunKey.ACCOUNT), pending(taken(), read(), nowAccount = accountB))
    }

    @Test
    fun `a token VK refused and the app erased is no change of the user's`() {
        // the profile holds none any more: nothing to reconnect onto, and nothing the user did
        assertEquals(emptySet<RunKey>(), pending(taken(), read(), nowAccount = ""))
    }

    @Test
    fun `a run that went on with the stored account by itself has nothing waiting`() {
        // RunKeeps moved the run to B when VK refused A: the run's account is the stored one
        assertEquals(emptySet<RunKey>(), pending(taken(account = accountB), read(), nowAccount = accountB))
    }

    @Test
    fun `a pool run that started with no account waits for the one signed in to`() {
        // it would take the account by itself at its next call recovery, which may never come
        assertEquals(setOf(RunKey.ACCOUNT), pending(taken(account = ""), read(), nowAccount = accountA))
    }

    @Test
    fun `an account means nothing to a run that makes no calls with one`() {
        // manual hashes, or a link's own hashes: the stored token is not this run's, whatever mode is stored beside it
        val manualRun = taken(read(hashMode = manual), account = "", usesAccount = false)
        assertEquals(emptySet<RunKey>(), pending(manualRun, read(hashMode = manual), nowAccount = accountB))
        val linkRun = taken(read(link, hashMode = api), account = "", usesAccount = false)
        assertEquals(emptySet<RunKey>(), pending(linkRun, read(link, hashMode = api), nowAccount = accountB))
        // a link's token is not the user's account
        val tokenRun = taken(read(link), account = accountA, accountOwn = false)
        assertEquals(emptySet<RunKey>(), pending(tokenRun, read(link), nowAccount = accountB))
        // and with manual hashes stored for the next connection, the account picked beside them is not used by it
        assertEquals(setOf(RunKey.HASH_MODE), pending(taken(), read(hashMode = manual), nowAccount = accountB))
    }

    @Test
    fun `«Авто ВК» stored makes the account count whatever the hash mode says`() {
        val run = taken(read(settings = settings(authMode = autoJs), hashMode = js))
        assertEquals(setOf(RunKey.ACCOUNT), pending(run, read(settings = settings(authMode = autoJs), hashMode = js), nowAccount = accountB))
    }

    // ---- what the app writes itself

    @Test
    fun `a start's own correction of the creds mode is the record's too`() {
        // a link's hashes cannot run in «Авто ВК»: the start runs the plain calls and stores that mode
        val asRead = read(link, settings(authMode = autoJs), hashMode = js)
        val record = asRead.withModes(authMode = calls, hashMode = manual)
        assertEquals(calls, record.settings.vkAuthMode)
        assertEquals(manual, record.hashMode)
        val stored = read(link, settings(authMode = calls), hashMode = manual)
        assertEquals(emptySet<RunKey>(), pending(taken(record, usesAccount = false, account = ""), stored))
        // without it the store, as the start itself left it, would read as a change
        assertTrue(pending(taken(asRead, usesAccount = false, account = ""), stored).containsAll(setOf(RunKey.AUTH_MODE, RunKey.HASH_MODE)))
    }

    @Test
    fun `a subscription's refresh of the run's node moves the record, and only what it wrote`() {
        val node = read(link, settings(streams = 60, dpi = DpiSplitMode.OFF))
        val refreshed = read(
            link.copy(link = "csqtt://connect?v=2&host=new.example.net"), settings(streams = 40, transport = udp, dpi = DpiSplitMode.OFF),
        )
        // the user changed the transport before the refresh; the link and the count were as the run took them
        val before = read(link, settings(streams = 60, transport = udp, dpi = DpiSplitMode.OFF))
        val followed = node.following(before, refreshed)
        assertEquals(refreshed.source.link, followed.source.link)
        assertEquals(40, followed.settings.workersPerHash)
        // the transport is the user's: a change of it made meanwhile still waits
        assertEquals(setOf(RunKey.TRANSPORT), pending(taken(followed, usesAccount = false, account = ""), refreshed))
    }

    @Test
    fun `a refresh does not take a change of the user's off the bar`() {
        val node = read(link, settings(streams = 20, dpi = DpiSplitMode.OFF))
        // the user picked 40 streams and pasted another link with the tunnel on: both wait
        val before = read(link.copy(link = "csqtt://connect?v=2&host=mine.example.net"), settings(streams = 40, dpi = DpiSplitMode.OFF))
        assertEquals(setOf(RunKey.LINK, RunKey.STREAMS), pending(taken(node, usesAccount = false, account = ""), before))
        // the refresh serves the node's link again and caps the count by the node's power
        val refreshed = read(link.copy(link = "csqtt://connect?v=2&host=served.example.net"), settings(streams = 30, dpi = DpiSplitMode.OFF))
        val followed = node.following(before, refreshed)
        assertEquals("the record keeps what the run took", node, followed)
        assertEquals(setOf(RunKey.LINK, RunKey.STREAMS), pending(taken(followed, usesAccount = false, account = ""), refreshed))
        // one of the two was the user's, the other not: each is told by itself
        val onlyStreams = read(link, settings(streams = 40, dpi = DpiSplitMode.OFF))
        val mixed = node.following(onlyStreams, refreshed)
        assertEquals(refreshed.source.link, mixed.source.link)
        assertEquals(20, mixed.settings.workersPerHash)
        assertEquals(setOf(RunKey.STREAMS), pending(taken(mixed, usesAccount = false, account = ""), refreshed))
    }

    @Test
    fun `a slot of an older build made a link's by its refresh raises no bar`() {
        // the tunnel is on a subscription's node stored as an own server (2.5.3); its next refresh writes the link it
        // came from in the place of the address, the port, the password and the node's hashes
        val slot = read(own.copy(ownHashes = listOf("node-hash")), settings(streams = 20, dpi = DpiSplitMode.OFF))
        val asLink = read(
            SourceRead(linkMode = true, link = "csqtt://connect?v=2&host=203.0.113.5&hashes=node-hash"), settings(streams = 20, dpi = DpiSplitMode.OFF),
        )
        assertTrue("without the record following it the refresh reads as the user's change", pending(taken(slot), asLink).isNotEmpty())
        val followed = slot.following(before = slot, now = asLink)
        assertEquals(asLink, followed)
        assertEquals(emptySet<RunKey>(), pending(taken(followed), asLink))
    }

    @Test
    fun `a value the refresh left alone stays as the run took it`() {
        val node = read(link, settings(transport = tcp, dpi = DpiSplitMode.LIGHT))
        // the user picked UDP and another fingerprint; the refresh wrote the same link and count again
        val before = read(link, settings(transport = udp, dpi = DpiSplitMode.LIGHT).copy(fingerprint = "chrome"))
        val followed = node.following(before, before)
        assertEquals(node, followed)
        assertEquals(setOf(RunKey.TRANSPORT, RunKey.DPI, RunKey.FINGERPRINT), pending(taken(followed, usesAccount = false, account = ""), before))
    }

    // ---- what the screens are given of a run

    @Test
    fun `a run is published with the mode it is in and the account it has`() {
        val params = TunnelParams(
            peer = "203.0.113.5:46000", vkHashes = "h".repeat(20), workersPerHash = 20, port = 0,
            vkAuthMode = autoJs, vkHashMode = js, vkAccessToken = accountA,
        )
        val run = RunTaken.of(read(), params)
        assertEquals(autoJs, run.authMode)
        assertTrue(run.usesAccount)
        assertTrue(run.accountOwn)
        assertEquals(accountA, run.account)
        // «Авто ВК» as a creds mode with hashes of another kind is started as the plain calls
        assertEquals(calls, RunTaken.of(read(), params.copy(vkHashMode = manual)).authMode)
        assertFalse(RunTaken.of(read(), params.copy(vkHashMode = manual)).usesAccount)
        assertFalse(RunTaken.of(read(), params.copy(vkTokenFromLink = true)).accountOwn)
    }
}
