package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3 B3, the canvas's F-TunnelInvalid: what the «Туннель» form says of each field. A wrong value is always said; a
 * missing one only after a start was refused for it.
 */
class TunnelFormValidationTest {
    private fun own(
        address: String = "203.0.113.5",
        port: String = "46000",
        password: String = "secret",
        hashesReady: Boolean = true,
        required: Boolean = false,
    ) = tunnelIssues(
        linkMode = false, address = address, port = port, password = password, link = "", linkValid = false,
        hashesReady = hashesReady, required = required,
    )

    private fun link(link: String, valid: Boolean, hashesReady: Boolean = true, required: Boolean = false) = tunnelIssues(
        linkMode = true, address = "", port = "", password = "", link = link, linkValid = valid,
        hashesReady = hashesReady, required = required,
    )

    @Test
    fun `a filled form has no issue, and an empty one none either until a start was refused`() {
        assertFalse(own().any)
        assertFalse(own(address = "", port = "", password = "", hashesReady = false).any)
        assertEquals(
            TunnelIssues(address = AddressIssue.Missing, port = true, password = PasswordIssue.Missing, hashes = true),
            own(address = "", port = "", password = "", hashesReady = false, required = true),
        )
    }

    @Test
    fun `an address with a port, an IPv6 one and the phone's own are said at once`() {
        assertEquals(AddressIssue.HasPort, own(address = "203.0.113.5:46000").address)
        assertEquals(AddressIssue.HasPort, own(address = "2001:db8::1").address)
        for (local in listOf("127.0.0.1", "0.0.0.0", "localhost", "LocalHost", "::1")) {
            assertTrue(local, own(address = local).address in setOf(AddressIssue.Local, AddressIssue.HasPort))
        }
        assertEquals(AddressIssue.Local, own(address = "localhost").address)
        assertNull(own(address = "vpn.example.net").address)
    }

    @Test
    fun `a port outside 1 to 65535 is wrong, an empty one only missing`() {
        for (bad in listOf("0", "65536", "99999", "4a")) assertTrue(bad, own(port = bad).port)
        for (good in listOf("1", "46000", "65535")) assertFalse(good, own(port = good).port)
        assertFalse(own(port = "").port)
        assertTrue(own(port = "", required = true).port)
    }

    @Test
    fun `a password GETCONF cannot carry is said by its rule`() {
        for (bad in listOf("with space", "pipe|inside", "я".repeat(65), "tab\tinside")) {
            assertEquals(bad, PasswordIssue.Rule, own(password = bad).password)
        }
        for (good in listOf("secret", "я".repeat(64), "p@ss-слово")) assertNull(good, own(password = good).password)
    }

    @Test
    fun `nothing to connect with is said only after a refused start`() {
        assertFalse(own(hashesReady = false).hashes)
        assertTrue(own(hashesReady = false, required = true).hashes)
        assertFalse(own(hashesReady = true, required = true).hashes)
    }

    @Test
    fun `a link profile is judged by its link, never by the address fields it does not show`() {
        assertFalse(link("csqtt://connect?v=2&host=h&peer=1&password=p", valid = true).any)
        assertEquals(TunnelIssues(link = LinkIssue.Invalid), link("csqtt://connect?v=1", valid = false))
        assertFalse(link("", valid = false).any)
        assertEquals(TunnelIssues(link = LinkIssue.Missing, hashes = true), link("", valid = false, hashesReady = false, required = true))
    }

    @Test
    fun `an address pasted with its port is taken apart, and nothing else is`() {
        assertEquals("203.0.113.5" to 46000, splitHostPort("203.0.113.5:46000"))
        assertEquals("vpn.example.net" to 1, splitHostPort("vpn.example.net:1"))
        for (whole in listOf("203.0.113.5", "vpn.example.net:", ":46000", "host:0", "host:65536", "host:4a", "2001:db8::1", "a:b:1", "")) {
            assertNull(whole, splitHostPort(whole))
        }
    }

    @Test
    fun `an empty profile offers the other kind only while it is empty and the tunnel idle`() {
        assertEquals(ProfileModeAction.ENTER_SERVER, profileModeAction(emptyProfileLinkMode = true, field = "", tunnelBusy = false))
        assertEquals(ProfileModeAction.USE_LINK, profileModeAction(emptyProfileLinkMode = false, field = "", tunnelBusy = false))
        assertNull(profileModeAction(emptyProfileLinkMode = null, field = "", tunnelBusy = false))
        assertNull(profileModeAction(emptyProfileLinkMode = true, field = "csqtt://", tunnelBusy = false))
        assertNull(profileModeAction(emptyProfileLinkMode = false, field = "", tunnelBusy = true))
    }

    @Test
    fun `a link's note says what it is short of, and nothing when the participant has what it takes`() {
        val a = "A".repeat(20)
        val b = "B".repeat(20)
        val dead = mapOf(a to VkHashValidationStatus.Invalid, b to VkHashValidationStatus.Invalid)
        fun note(owner: List<String>, removed: Set<String> = emptySet(), results: Map<String, VkHashValidationStatus> = emptyMap(), token: Boolean = false, own: Boolean = false) =
            linkNote(owner, removed, results, token, own)

        assertNull("the owner's hashes serve", note(listOf(a, b)))
        assertEquals(LinkNote.OwnerToken, note(emptyList(), token = true))
        assertEquals(LinkNote.NeedsHashes, note(emptyList()))
        assertNull(note(emptyList(), own = true))
        assertEquals("every one of the owner's was taken out", LinkNote.NeedsHashes, note(listOf(a), removed = setOf(a)))
        assertEquals(LinkNote.Outdated, note(listOf(a, b), results = dead))
        assertNull("one of them still answers", note(listOf(a, b), results = mapOf(a to VkHashValidationStatus.Invalid)))
        assertNull("the participant's own carry it", note(listOf(a, b), results = dead, own = true))
        assertEquals("the dead one that was taken out does not count", LinkNote.Outdated, note(listOf(a, b), removed = setOf(b), results = mapOf(a to VkHashValidationStatus.Invalid)))
    }

    @Test
    fun `the participant's own fields are the filled ones and room up to what the tunnel takes`() {
        val none = List(6) { "" }
        assertEquals(6, ownHashRows(ownerInUse = 0, own = none))
        assertEquals(4, ownHashRows(ownerInUse = 3, own = none))
        assertEquals("one at least, to paste into", 1, ownHashRows(ownerInUse = 7, own = none))
        assertEquals("a filled one is never out of sight", 5, ownHashRows(ownerInUse = 6, own = listOf("", "", "", "", "x".repeat(16), "")))
    }
}
