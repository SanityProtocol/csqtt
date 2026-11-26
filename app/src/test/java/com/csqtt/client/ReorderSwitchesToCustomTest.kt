package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The canvas, F-ProfilesReorder: a row dragged in the profiles sheet makes the order the user's own, starting from the
 * order the lists were in; a row moves among the rows of its own card, and the other cards' rows keep their places.
 */
class ReorderSwitchesToCustomTest {
    // two of the user's own profiles and three nodes of a subscription, as the sheet groups them
    private val hel = ProfileEntry(0, "Хельсинки")
    private val dom = ProfileEntry(1, "Дом")
    private val msk1 = ProfileEntry(5, "msk-1", kind = ProfileKind.Subscription)
    private val msk2 = ProfileEntry(6, "msk-2", kind = ProfileKind.Subscription)
    private val ams = ProfileEntry(7, "ams-1", kind = ProfileKind.Subscription)
    private val all = listOf(hel, dom, msk1, msk2, ams)

    @Test fun aMoveStartsFromTheOrderTheListsWereIn() {
        // the lists were by name: ams-1, msk-1, msk-2, Дом, Хельсинки (which script comes first is the platform's)
        val byName = ProfileOrder.shown(all, ProfileSort.Name, emptyList())
        val nodes = byName.filter { it.kind == ProfileKind.Subscription }.map { it.index }
        assertEquals(listOf(7, 5, 6), nodes)
        // msk-2 is dragged to the top of its card
        val order = ProfileOrder.movedInGroup(byName, emptyList(), nodes, moved = 6, position = 0)!!
        val after = ProfileOrder.shown(all, ProfileSort.Custom, order)
        assertEquals(listOf(6, 7, 5), after.filter { it.kind == ProfileKind.Subscription }.map { it.index })
        // the other card's rows are where the name order had them
        assertEquals(byName.filter { it.kind != ProfileKind.Subscription }, after.filter { it.kind != ProfileKind.Subscription })
    }

    @Test fun aRowGoesBeforeTheRowThatFollowsItOrAfterTheLastOfItsCard() {
        val shown = ProfileOrder.shown(all, ProfileSort.Custom, emptyList())
        val nodes = listOf(5, 6, 7)
        assertEquals(listOf(0, 1, 6, 5, 7), ProfileOrder.movedInGroup(shown, emptyList(), nodes, moved = 5, position = 1))
        assertEquals(listOf(0, 1, 6, 7, 5), ProfileOrder.movedInGroup(shown, emptyList(), nodes, moved = 5, position = 2))
        assertEquals(listOf(0, 1, 6, 7, 5), ProfileOrder.movedInGroup(shown, emptyList(), nodes, moved = 5, position = 9))
        assertEquals(listOf(1, 0, 5, 6, 7), ProfileOrder.movedInGroup(shown, emptyList(), listOf(0, 1), moved = 0, position = 1))
        // dropped where it was: the same order, written out
        assertEquals(listOf(0, 1, 5, 6, 7), ProfileOrder.movedInGroup(shown, emptyList(), nodes, moved = 6, position = 1))
    }

    @Test fun profilesTheListsDoNotShowKeepTheirPlaceInTheOrder() {
        val hidden = ProfileEntry(9, "Скрытый", hidden = true)
        val shown = ProfileOrder.shown(all + hidden, ProfileSort.Custom, listOf(9, 0, 1, 5, 6, 7))
        assertEquals(listOf(1, 0, 5, 6, 7, 9), ProfileOrder.movedInGroup(shown, listOf(9, 0, 1, 5, 6, 7), listOf(0, 1), moved = 0, position = 1))
    }

    @Test fun aCardOfOneRowHasNothingToMove() {
        val shown = ProfileOrder.shown(all, ProfileSort.Custom, emptyList())
        assertNull(ProfileOrder.movedInGroup(shown, emptyList(), listOf(0), moved = 0, position = 0))
    }

    @Test fun homeShowsThreeRowsAndAlwaysTheSelectedOne() {
        val shown = ProfileOrder.shown(all, ProfileSort.Custom, emptyList())
        assertEquals(listOf(0, 1, 5), ProfileOrder.homeRows(shown, selected = 1).map { it.index })
        assertEquals(listOf(0, 1, 7), ProfileOrder.homeRows(shown, selected = 7).map { it.index })
        assertEquals(listOf(0, 1), ProfileOrder.homeRows(shown.take(2), selected = 0).map { it.index })
        // nothing selected among them (the store has not answered): the first three
        assertEquals(listOf(0, 1, 5), ProfileOrder.homeRows(shown, selected = -1).map { it.index })
    }

    @Test fun aDeletedProfileLeavesTheOrder() {
        val prefs = Store253Snapshots.load(Store253Snapshots.owner)
        prefs[androidx.datastore.preferences.core.stringPreferencesKey("profile_order")] = "3,0,1"
        SettingsStore.deleteProfileKeys(prefs, 0)
        assertEquals("3,1", prefs[androidx.datastore.preferences.core.stringPreferencesKey("profile_order")])
    }
}
