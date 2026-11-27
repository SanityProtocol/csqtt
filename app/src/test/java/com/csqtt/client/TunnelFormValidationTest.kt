package com.csqtt.client

import com.csqtt.client.ui.tunnel.addressLeftKey
import com.csqtt.client.ui.tunnel.leftNoteEpoch
import com.csqtt.client.ui.tunnel.portLeftKey
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
        // the core takes six hashes in all: with three of the owner's in use, three of the participant's own can serve
        val none = List(6) { "" }
        assertEquals(6, ownHashRows(ownerInUse = 0, own = none))
        assertEquals(3, ownHashRows(ownerInUse = 3, own = none))
        assertEquals("one at least, to paste into", 1, ownHashRows(ownerInUse = 6, own = none))
        assertEquals(1, ownHashRows(ownerInUse = 7, own = none))
        assertEquals("a filled one is never out of sight", 5, ownHashRows(ownerInUse = 6, own = listOf("", "", "", "", "x".repeat(16), "")))
    }

    // the review of P3 B3 (F1, F5): a draft is typed over a stored value, and stands only while the store holds it

    @Test
    fun `a draft stands while the store still holds what it was typed over`() {
        assertTrue(draftStands(draft = "46100", base = "46000", stored = "46000"))
        assertTrue("an invalid one too: it is the user's text", draftStands(draft = "4", base = "46000", stored = "46000"))
        assertTrue("typed over nothing", draftStands(draft = "h.example", base = "", stored = ""))
        assertFalse("no draft", draftStands(draft = null, base = "46000", stored = "46000"))
    }

    @Test
    fun `a draft the store already holds is gone - it was written when the field was left`() {
        assertFalse(draftStands(draft = "46100", base = "46000", stored = "46100"))
        // the same link pasted or typed again: nothing to write, and no import that would undo what was set for it
        assertFalse(draftStands(draft = "csqtt://x", base = "csqtt://x", stored = "csqtt://x"))
    }

    @Test
    fun `a draft is gone once another screen stored a new value under it`() {
        // typed 46100 over 46000, left by a tab tap (stored), then a deploy's end wrote 46200: the old text must not
        // be shown over it, nor written back when the field is next left
        assertFalse(draftStands(draft = "46100", base = "46000", stored = "46200"))
        assertFalse("half a link typed, then a link taken by QR", draftStands(draft = "csqtt://conn", base = "", stored = "csqtt://connect?v=2"))
        assertTrue("the base not noted yet: the first keystroke", draftStands(draft = "a", base = null, stored = "b"))
    }

    // the verify of the fix round (V1): the base cannot tell «the store never changed» from «it took the draft and was
    // put back» — 46100 typed over 46000 and written as the tab was left, then a deploy's end writing 46000 again

    @Test
    fun `a draft is written once - the one its screen left with does not come back`() {
        assertTrue("by its base alone such a draft would stand again", draftStands(draft = "46100", base = "46000", stored = "46000"))
        assertTrue(draftLeft(draft = "46100", left = "46100"))
        assertTrue("an address left with its port in it: as it stood, not as it was stored", draftLeft("h.example:5000", "h.example:5000"))
        assertFalse("edited since: the note went with the edit", draftLeft(draft = "46100", left = null))
        assertFalse("another text than the one that left", draftLeft(draft = "46101", left = "46100"))
        assertFalse("no draft", draftLeft(draft = null, left = "46100"))
        assertFalse(draftLeft(draft = null, left = null))
    }

    // the verify of the fix round (V3), the review's F10 and the second round's check (C2): the marks of a refused
    // start are the profile's. The epoch changes when any profile is added, deleted or restored, and at nothing a form
    // writes

    @Test
    fun `the marks of a refused start stay while another profile is added or deleted beside theirs`() {
        val stamped = RefusedProfile(added = 1_000, inUse = true, epoch = 5)
        assertEquals(stamped.copy(epoch = 6), stamped.seenAs(added = 1_000, inUse = true, epoch = 6))
        assertEquals("seen again only many changes later", stamped.copy(epoch = 9), stamped.seenAs(1_000, true, epoch = 9))
        // a profile from before the date was kept, or one typed into an empty slot, has none
        val typed = RefusedProfile(added = 0, inUse = true, epoch = 5)
        assertEquals(typed.copy(epoch = 6), typed.seenAs(0, true, epoch = 6))
        // the empty form a start was refused for, still empty
        val empty = RefusedProfile(added = 0, inUse = false, epoch = 5)
        assertEquals(empty.copy(epoch = 6), empty.seenAs(0, false, epoch = 6))
        assertEquals("nothing changed at all", stamped, stamped.seenAs(1_000, true, epoch = 5))
    }

    @Test
    fun `the marks follow the empty form a start was refused for when its own fields fill it`() {
        val empty = RefusedProfile(added = 0, inUse = false, epoch = 5)
        assertEquals("an address typed into it", RefusedProfile(0, true, 5), empty.seenAs(0, true, epoch = 5))
        assertEquals("a link pasted into it, which dates the profile", RefusedProfile(7_000, true, 5), empty.seenAs(7_000, true, epoch = 5))
        // and from there on they stay beside other profiles' changes, as any marks do
        assertEquals(RefusedProfile(0, true, 6), empty.seenAs(0, true, 5)!!.seenAs(0, true, 6))
        assertEquals(RefusedProfile(7_000, true, 6), empty.seenAs(7_000, true, 5)!!.seenAs(7_000, true, 6))
    }

    @Test
    fun `the marks go with their profile, and the next one in its slot has none`() {
        val stamped = RefusedProfile(added = 1_000, inUse = true, epoch = 5)
        assertNull("deleted: the slot is empty", stamped.seenAs(added = 0, inUse = false, epoch = 6))
        assertNull("another profile was made in the slot", stamped.seenAs(added = 2_000, inUse = true, epoch = 7))
        val typed = RefusedProfile(added = 0, inUse = true, epoch = 5)
        assertNull("deleted, as the only profile: its emptied slot stays open", typed.seenAs(0, false, epoch = 6))
        assertNull(typed.seenAs(2_000, true, epoch = 7))
        assertNull("its address emptied: an empty form again, which nothing was refused for", typed.seenAs(0, false, epoch = 5))
        val empty = RefusedProfile(added = 0, inUse = false, epoch = 5)
        assertNull("a backup restored over the refused empty form", empty.seenAs(0, true, epoch = 6))
        assertNull("a profile made in its slot after it was left", empty.seenAs(7_000, true, epoch = 6))
        // an address typed into the refused form, and the profile then deleted
        assertNull(empty.seenAs(0, true, 5)!!.seenAs(0, false, epoch = 6))
        // seen only after both happened — an address typed, and another profile added or deleted: not told from a
        // restore, and the marks go rather than show on a profile no start was refused for
        assertNull(empty.seenAs(0, true, epoch = 6))
    }

    @Test
    fun `a note names the epoch of its form`() {
        assertEquals(7, leftNoteEpoch(addressLeftKey(profile = 3, epoch = 7)))
        assertEquals(12, leftNoteEpoch(portLeftKey(profile = 31, epoch = 12)))
        assertNull(leftNoteEpoch("tunnel:3:7:password"))
        assertNull(leftNoteEpoch("home"))
    }

    // the review of P3 B3 (F3): the zones count the hashes a start connects with

    @Test
    fun `a start uses the hashes no check found dead, and all of them when all are dead`() {
        val a = "A".repeat(20); val b = "B".repeat(20); val c = "C".repeat(20)
        val dead = VkHashValidationStatus.Invalid
        assertEquals(listOf(a, b, c), hashesAStartUses(listOf(a, b, c), emptyMap()))
        assertEquals("two of the owner's three were found dead", listOf(c), hashesAStartUses(listOf(a, b, c), mapOf(a to dead, b to dead)))
        assertEquals("every one dead: a start tries them all", listOf(a, b), hashesAStartUses(listOf(a, b), mapOf(a to dead, b to dead)))
        assertEquals("a short one is none, one given twice is one", listOf(a), hashesAStartUses(listOf(" $a ", "short", a), emptyMap()))
        assertEquals(emptyList<String>(), hashesAStartUses(emptyList(), emptyMap()))
    }
}
