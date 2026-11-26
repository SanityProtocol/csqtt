package com.csqtt.client

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.csqtt.client.Store253Snapshots.load
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The order of the profiles' lists (P3-PLAN §1.7; the canvas, F-HomeSort): five orders, pins first, hidden ones in none. */
class ProfileSortTest {
    private val hel = ProfileEntry(0, "Хельсинки", pingMs = 38, lastConnectedAt = 300, addedAt = 10)
    private val dom = ProfileEntry(1, "Дом", pingMs = 96, lastConnectedAt = 500, addedAt = 30)
    private val msk = ProfileEntry(2, "msk-1", pingMs = 162, lastConnectedAt = 0, addedAt = 20)
    private val ams = ProfileEntry(3, "ams-1", pingMs = null, lastConnectedAt = 100, addedAt = 0)
    private val all = listOf(hel, dom, msk, ams)

    private fun order(sort: ProfileSort, entries: List<ProfileEntry> = all, custom: List<Int> = emptyList()) =
        ProfileOrder.shown(entries, sort, custom).map { it.index }

    @Test
    fun theFiveOrders() {
        // the user's own: as they stand in the slots until something is moved
        assertEquals(listOf(0, 1, 2, 3), order(ProfileSort.Custom))
        assertEquals(listOf(2, 0, 3, 1), order(ProfileSort.Custom, custom = listOf(2, 0, 3, 1)))
        // a profile the own order does not name yet (a new one) comes after those it names
        assertEquals(listOf(3, 1, 0, 2), order(ProfileSort.Custom, custom = listOf(3, 1)))
        // the quickest first; no answer and never pinged last
        assertEquals(listOf(0, 1, 2, 3), order(ProfileSort.Ping))
        // by name as Russian orders it, capitals and small letters together
        val named = listOf(ProfileEntry(0, "Хельсинки"), ProfileEntry(1, "дача"), ProfileEntry(2, "Дом"), ProfileEntry(3, "Амстердам"))
        assertEquals(listOf(3, 1, 2, 0), order(ProfileSort.Name, named))
        // (which of two scripts comes first is the platform's: Android puts the phone's own first)
        assertEquals(listOf(3, 2), order(ProfileSort.Name).filter { it in setOf(2, 3) })
        assertEquals(listOf(1, 0), order(ProfileSort.Name).filter { it in setOf(0, 1) })
        // the last connected first; one never connected last
        assertEquals(listOf(1, 0, 3, 2), order(ProfileSort.Recent))
        // the newest first; one added before the date was kept last
        assertEquals(listOf(1, 2, 0, 3), order(ProfileSort.Added))
    }

    @Test
    fun whatAnOrderCannotTellApartKeepsTheUsersOwn() {
        val twins = listOf(ProfileEntry(0, "Дом"), ProfileEntry(1, "Дом"), ProfileEntry(2, "Дом"))
        for (sort in ProfileSort.entries) {
            assertEquals("$sort", listOf(2, 0, 1), order(sort, twins, custom = listOf(2, 0, 1)))
        }
    }

    @Test
    fun pinnedOnesComeFirstInTheSameOrder() {
        val pinned = listOf(hel, dom.copy(pinned = true), msk, ams.copy(pinned = true))
        assertEquals(listOf(1, 3, 0, 2), order(ProfileSort.Custom, pinned))
        assertEquals(setOf(1, 3), order(ProfileSort.Name, pinned).take(2).toSet())
        assertEquals(listOf(1, 3, 0, 2), order(ProfileSort.Recent, pinned))
    }

    @Test
    fun aHiddenProfileIsInNoneOfThem() {
        val hidden = listOf(hel, dom.copy(hidden = true, pinned = true), msk, ams)
        for (sort in ProfileSort.entries) {
            assertFalse("$sort", 1 in order(sort, hidden, custom = listOf(1, 0, 2, 3)))
            assertEquals("$sort", 3, order(sort, hidden).size)
        }
    }

    @Test
    fun theOwnOrderAfterAMove() {
        val shown = ProfileOrder.shown(all, ProfileSort.Recent, emptyList())         // 1, 0, 3, 2
        // «msk-1» dragged to the top of the list as it stands
        val moved = ProfileOrder.moved(shown, custom = emptyList(), moved = 2, position = 0)
        assertEquals(listOf(2, 1, 0, 3), moved)
        assertEquals(moved, order(ProfileSort.Custom, custom = moved))
        // past the end is the end
        assertEquals(listOf(0, 3, 2, 1), ProfileOrder.moved(shown, emptyList(), moved = 1, position = 99))
        // a profile the list does not show (a hidden one) keeps a place after the rows
        val withHidden = ProfileOrder.shown(listOf(hel, dom.copy(hidden = true), msk), ProfileSort.Custom, listOf(1, 2, 0))
        assertEquals(listOf(0, 2, 1), ProfileOrder.moved(withHidden, custom = listOf(1, 2, 0), moved = 0, position = 0))
    }

    @Test
    fun theStoredOrderDropsWhatIsNotAProfile() {
        assertEquals(listOf(2, 0, 31), ProfileOrder.decode("2,0, 31 ,2,x,-1,32,"))
        assertEquals(emptyList<Int>(), ProfileOrder.decode(null))
        assertEquals(listOf(2, 0, 31), ProfileOrder.decode(ProfileOrder.encode(listOf(2, 0, 31))))
    }

    @Test
    fun theStoreSaysWhatARowNeeds() {
        val prefs = load(
            """{"strings": {"peer": "203.0.113.5", "profile_name": "Хельсинки",
                            "csqtt_link_3": "${Store253Snapshots.participantLink}", "profile_name_3": "Дом",
                            "peer_5": "192.0.2.10", "subscription_url_5": "https://sub.example.invalid/s",
                            "subscription_title_5": "Мой VPN", "profile_name_5": "msk-1"},
                "booleans": {"csqtt_link_mode_3": true, "profile_pinned": true, "profile_hidden_5": true},
                "longs": {"profile_last_connected_3": 500, "profile_added_at_5": 20},
                "ints": {"active_profile": 3}}""",
        )
        val entries = SettingsStore.profileEntries(prefs).associateBy { it.index }
        assertEquals(setOf(0, 3, 5), entries.keys)
        assertEquals(ProfileKind.OwnServer, entries.getValue(0).kind)
        assertEquals("203.0.113.5", entries.getValue(0).peer)
        assertTrue(entries.getValue(0).pinned)
        assertEquals(ProfileKind.Link, entries.getValue(3).kind)
        assertEquals(Store253Snapshots.participantLink, entries.getValue(3).link)
        assertEquals(500L, entries.getValue(3).lastConnectedAt)
        assertEquals(ProfileKind.Subscription, entries.getValue(5).kind)
        assertEquals("Мой VPN", entries.getValue(5).subscriptionTitle)
        assertTrue(entries.getValue(5).hidden)
        assertEquals(20L, entries.getValue(5).addedAt)
        assertEquals(listOf("Хельсинки", "Дом", "msk-1"), listOf(0, 3, 5).map { entries.getValue(it).name })
    }

    @Test
    fun aProfileGetsItsDateWhenItsSlotIsFilled() {
        val added = longPreferencesKey("profile_added_at_1")
        val prefs = load("""{"strings": {"peer": "203.0.113.5"}, "ints": {"active_profile": 0}}""")
        assertEquals(1, SettingsStore.addProfileKeys(prefs, linkMode = true, now = 1_000L))
        assertEquals(1_000L, prefs[added])
        // the date is the first one: a later write of the same profile keeps it
        SettingsStore.stampAdded(prefs, 1, now = 9_000L)
        assertEquals(1_000L, prefs[added])
        // a pin and the dates go with the profile, so the next one in the slot starts with its own
        prefs[booleanPreferencesKey("profile_pinned_1")] = true
        SettingsStore.deleteProfileKeys(prefs, 1)
        assertNull(prefs[added])
        assertNull(prefs[booleanPreferencesKey("profile_pinned_1")])
        assertEquals(1, SettingsStore.addProfileKeys(prefs, linkMode = true, now = 5_000L))
        assertEquals(5_000L, prefs[added])
    }

    @Test
    fun aSubscriptionsNodeIsDatedWhenItTakesASlotNotWhenItIsRefreshed() {
        val node = SubscriptionProfileWrite(5, Store253Snapshots.participantLink, null, "msk-1", "Мой VPN", "https://sub.example.invalid/s", null)
        // a node from before the dates were kept: a refresh writes into its slot and gives it none
        val old = load("""{"strings": {"csqtt_link_5": "${Store253Snapshots.participantLink}", "subscription_url_5": "https://sub.example.invalid/s"},
                           "booleans": {"csqtt_link_mode_5": true}, "ints": {"active_profile": 0}}""")
        SettingsStore.writeSubscriptionNode(old, node, now = 9_000L)
        assertNull(old[longPreferencesKey("profile_added_at_5")])
        // a node that takes a free slot is added now, and a later refresh keeps that date
        val fresh = load("""{"strings": {"peer": "203.0.113.5"}, "ints": {"active_profile": 0}}""")
        SettingsStore.writeSubscriptionNode(fresh, node, now = 1_000L)
        SettingsStore.writeSubscriptionNode(fresh, node, now = 9_000L)
        assertEquals(1_000L, fresh[longPreferencesKey("profile_added_at_5")])
    }
}
