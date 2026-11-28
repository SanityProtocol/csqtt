package com.csqtt.client

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Card p3-apply-bar, «Переподключить» (the operator, 2026-11-28: «Keep the tunnel, show what is missing»): the tap asks
 * of the run's profile what a start asks, and with a start that would be refused the running tunnel is not touched.
 * And what the screens are given of a run.
 */
class ReconnectTapTest {
    private val udp = CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT
    private val tcp = CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS
    private val manual = CsqttConstants.VkAutoHash.MODE_MANUAL
    private val api = CsqttConstants.VkAutoHash.MODE_AUTO_API
    private val hash = "h".repeat(20)

    private fun settings(transport: String = tcp, dpi: DpiSplitMode = DpiSplitMode.LIGHT) = TunnelStartSettings(
        secondaryVkHash = "", workersPerHash = 20, sni = "", protocol = "udp", vkAuthMode = CsqttConstants.VkAuth.MODE_CALLS,
        captchaMode = "auto", captchaSolveMethod = "auto", fingerprint = "firefox",
        clientIds = CsqttConstants.Tunnel.DEFAULT_CLIENT_IDS, obfsMode = CsqttConstants.Tunnel.DEFAULT_OBFS_MODE,
        turnTransport = transport, dpiSplit = dpi, srtpMode = CsqttConstants.SrtpMode.AUTO, customTurnIps = "",
    )

    private fun taken(profile: Int = 3, settings: TunnelStartSettings = settings()) = RunTaken(
        RunRead(profile, SourceRead(address = "203.0.113.5", port = 46000, password = "secret-password"), settings, api, "sub", "9"),
        authMode = CsqttConstants.VkAuth.MODE_CALLS, usesAccount = true, account = "vk1.a." + "A".repeat(40), accountOwn = true,
    )

    // ---- the tap

    @Test
    fun `a tap reconnects a run whose profile can start`() = runTest {
        var asked = -1
        var reconnects = 0
        val tap = reconnectTap(taken(profile = 5), target = null, blocker = { asked = it; null }, reconnect = { reconnects++; true })
        assertEquals(ReconnectTap.Reconnecting, tap)
        assertEquals("the run's own profile is the one asked about", 5, asked)
        assertEquals(1, reconnects)
    }

    @Test
    fun `with settings a start would refuse the tunnel is left alone`() = runTest {
        var reconnects = 0
        val tap = reconnectTap(taken(profile = 5), target = null, blocker = { ConnectionStartBlocker.Password }, reconnect = { reconnects++; true })
        assertEquals(ReconnectTap.Blocked(5, ConnectionStartBlocker.Password), tap)
        assertEquals("the running tunnel is not touched", 0, reconnects)
    }

    @Test
    fun `with a pick of another profile under way it is that profile the tap asks about`() = runTest {
        // the reconnect goes where the run is going (reconnectRun keeps the pick's target), so that is what must start
        var asked = -1
        assertEquals(ReconnectTap.Reconnecting, reconnectTap(taken(profile = 5), target = 8, blocker = { asked = it; null }, reconnect = { true }))
        assertEquals(8, asked)
        // refused: the form opened is the target's, and opening it reconnects nothing (the run is already going there)
        val refused = reconnectTap(taken(profile = 5), target = 8, blocker = { ConnectionStartBlocker.Server }, reconnect = { true })
        assertEquals(ReconnectTap.Blocked(8, ConnectionStartBlocker.Server), refused)
    }

    @Test
    fun `no run, nothing to do, and nothing is asked`() = runTest {
        var asked = false
        assertEquals(ReconnectTap.Nothing, reconnectTap(null, target = null, blocker = { asked = true; null }, reconnect = { true }))
        assertEquals(ReconnectTap.Nothing, reconnectTap(null, target = 8, blocker = { asked = true; null }, reconnect = { true }))
        assertFalse(asked)
        // a run that ended between the tap and the reconnect
        assertEquals(ReconnectTap.Nothing, reconnectTap(taken(), target = null, blocker = { null }, reconnect = { false }))
    }

    // ---- what a start checks, for Home's tap and for the bar's

    private fun check(
        linkMode: Boolean = false,
        link: String = "",
        peer: String = "203.0.113.5",
        password: String = "p",
        hashes: String = hash,
        hashMode: String = manual,
        token: Boolean = false,
        authMode: String = CsqttConstants.VkAuth.MODE_CALLS,
        hashChecks: String = "{}",
        removedOwner: Set<String> = emptySet(),
    ) = StartCheck(
        linkMode = linkMode, link = link, peer = peer, manualPorts = false, peerPort = 46000, password = password,
        passwordReadable = true, ownHashes = hashes, hashChecks = hashChecks, authMode = authMode,
        hashMode = hashMode, tokenPresent = token, removedOwner = removedOwner,
    )

    private val ownerHash = "o".repeat(20)
    private fun link(hashes: List<String> = emptyList(), token: String = "") =
        com.csqtt.client.ui.legacy.utils.formatCsqttLink("home.example.net", 46000, "pw", hashes, token)

    @Test
    fun `an own server with its address and a hash can start`() {
        assertNull(startBlocker(check()))
    }

    @Test
    fun `what the form lacks is named`() {
        assertEquals(ConnectionStartBlocker.Server, startBlocker(check(peer = "")))
        assertEquals("the port belongs in its own field", ConnectionStartBlocker.Server, startBlocker(check(peer = "203.0.113.5:46000")))
        assertEquals(ConnectionStartBlocker.Hashes, startBlocker(check(hashes = "")))
        assertEquals("a short hash is none", ConnectionStartBlocker.Hashes, startBlocker(check(hashes = "short")))
        assertEquals(ConnectionStartBlocker.Port, startBlocker(check().copy(manualPorts = true, peerPort = 0)))
        assertEquals("an auto mode needs an account", ConnectionStartBlocker.Hashes, startBlocker(check(hashes = "", hashMode = api)))
        assertNull(startBlocker(check(hashes = "", hashMode = api, token = true)))
        assertEquals(ConnectionStartBlocker.Link, startBlocker(check(linkMode = true, link = "not a link")))
    }

    @Test
    fun `an address is taken as typed, spaces aside, and not this phone's own`() {
        assertNull(startBlocker(check(peer = "  203.0.113.5 ")))
        for (own in listOf("127.0.0.1", "localhost", "0.0.0.0", "::1")) {
            assertEquals(own, ConnectionStartBlocker.Server, startBlocker(check(peer = own)))
        }
    }

    @Test
    fun `«Авто ВК» as the creds mode needs an account whatever the hash mode says`() {
        val autoVk = CsqttConstants.VkAuth.MODE_AUTO_JS
        assertEquals(ConnectionStartBlocker.Hashes, startBlocker(check(authMode = autoVk, hashMode = manual)))
        assertNull(startBlocker(check(authMode = autoVk, hashMode = manual, token = true)))
    }

    @Test
    fun `hashes a check found dead still start a profile that has no other`() {
        // every typed hash marked dead: the start goes on with them rather than be refused for a check's word
        val dead = VkHashValidationCodec.encode(mapOf(hash to VkHashValidationStatus.Invalid))
        assertNull(startBlocker(check(hashChecks = dead)))
    }

    @Test
    fun `a link starts on its owner's hashes still in use, on its token, or on the participant's own`() {
        // the owner's hashes
        assertNull(startBlocker(check(linkMode = true, link = link(hashes = listOf(ownerHash)), hashes = "")))
        // all of them taken out, and nothing else: no start
        assertEquals(
            ConnectionStartBlocker.Hashes,
            startBlocker(check(linkMode = true, link = link(hashes = listOf(ownerHash)), hashes = "", removedOwner = setOf(ownerHash))),
        )
        // …unless the participant has a hash of their own
        assertNull(startBlocker(check(linkMode = true, link = link(hashes = listOf(ownerHash)), hashes = hash, removedOwner = setOf(ownerHash))))
        // an old link's own token
        assertNull(startBlocker(check(linkMode = true, link = link(token = "vk1.a." + "T".repeat(40)), hashes = "")))
        // a link with neither, and nothing of the participant's
        assertEquals(ConnectionStartBlocker.Hashes, startBlocker(check(linkMode = true, link = link(), hashes = "")))
    }

    // ---- what the screens are given

    @Test
    fun `a screen is told what the run uses, and never a secret`() {
        val uses = RunUses.of(taken(), accountId = "1234567")
        assertFalse(uses.linkMode)
        assertEquals("203.0.113.5", uses.address)
        assertEquals(46000, uses.port)
        assertEquals(tcp, uses.transport)
        assertEquals(DpiSplitMode.LIGHT, uses.dpi)
        assertEquals(20, uses.streams)
        assertEquals("sub", uses.turnSubnet)
        assertEquals("9", uses.turnLimit)
        assertEquals("1234567", uses.accountId)
        assertTrue(uses.hasAccount)
        // nothing of it holds the password or the token
        assertFalse(uses.toString().contains("secret-password"))
        assertFalse(uses.toString().contains("vk1.a."))
    }

    @Test
    fun `each line of «Сейчас» is the run's own value, not the one beside it`() {
        // every value another than its neighbours' and than the defaults, so a line fed from the wrong field shows
        val read = RunRead(
            profile = 3,
            source = SourceRead(linkMode = true, link = "csqtt://x", acceptServerHashes = false),
            settings = settings().copy(
                vkAuthMode = CsqttConstants.VkAuth.MODE_AUTO_JS, captchaMode = "rjs", captchaSolveMethod = "manual",
                fingerprint = "chrome", srtpMode = CsqttConstants.SrtpMode.DTLS,
            ),
            hashMode = CsqttConstants.VkAutoHash.MODE_AUTO_JS,
        )
        // the creds mode the run is in is the run's, not the stored one
        val run = RunTaken(read, authMode = CsqttConstants.VkAuth.MODE_CAPTCHA, usesAccount = true, account = "", accountOwn = true)
        val uses = RunUses.of(run, accountId = "")
        assertTrue(uses.linkMode)
        assertEquals(CsqttConstants.VkAutoHash.MODE_AUTO_JS, uses.hashMode)
        assertEquals(CsqttConstants.VkAuth.MODE_CAPTCHA, uses.authMode)
        assertEquals("rjs", uses.captchaMode)
        assertEquals("manual", uses.captchaSolve)
        assertEquals(CsqttConstants.SrtpMode.DTLS, uses.srtp)
        assertEquals("chrome", uses.fingerprint)
        assertFalse(uses.acceptServerHashes)
        assertFalse("a run with no account says so", uses.hasAccount)
    }

    @Test
    fun `the level a UDP run splits with is none`() {
        assertEquals(DpiSplitMode.OFF, RunUses.of(taken(settings = settings(transport = udp, dpi = DpiSplitMode.HEAVY)), "").dpi)
    }

    @Test
    fun `the bar is offered only for a run that is on and has something waiting`() {
        val uses = RunUses.of(taken(), "")
        fun view(keys: Set<RunKey>, on: Boolean) = RunView(3, keys, uses, on, reconnecting = false)
        assertTrue(view(setOf(RunKey.TRANSPORT), on = true).offered)
        assertFalse("nothing waits", view(emptySet(), on = true).offered)
        // stopping, or paused on Wi-Fi: its resume reads the store by itself
        assertFalse(view(setOf(RunKey.TRANSPORT), on = false).offered)
        assertTrue(ProfileSwitch.runIsOn(running = true, starting = false, stopping = false, pausedOnWifi = false))
        assertFalse(ProfileSwitch.runIsOn(running = true, starting = false, stopping = true, pausedOnWifi = false))
        assertFalse(ProfileSwitch.runIsOn(running = true, starting = false, stopping = false, pausedOnWifi = true))
        // a row says «Сейчас» only on the run's own profile
        assertTrue(view(setOf(RunKey.TRANSPORT), on = true).waits(3, RunKey.TRANSPORT))
        assertFalse(view(setOf(RunKey.TRANSPORT), on = true).waits(4, RunKey.TRANSPORT))
        assertFalse(view(setOf(RunKey.TRANSPORT), on = true).waits(3, RunKey.SRTP))
    }

    @Test
    fun `a refusal is told from the one before it, of the same profile too`() {
        var clock = 100L
        val refusals = Refusals { clock++ }
        assertNull(refusals.current.value)
        refusals.refuse(3)
        val first = refusals.current.value
        refusals.refuse(3)
        assertEquals(3, refusals.current.value?.profile)
        assertTrue(first != refusals.current.value)
    }
}
