package com.csqtt.client.ui.shell

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §4.2 rules 2 and 4: where the user is survives through the saved state alone, and Back closes it layer by layer. */
class NavModelTest {
    private val screen = Dest("settings", Layer.Screen)
    private val about = Dest("about", Layer.Screen)
    private val sheet = Dest("profiles", Layer.Sheet)
    private val dialog = Dest("rename", Layer.Dialog, "3")

    /** What a process death leaves: a new model on a handle holding only what the first one saved. */
    private fun restored(handle: SavedStateHandle): NavModel =
        NavModel(SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))

    @Test
    fun `back closes the sheet, then the dialog, then the screen, then returns to home, then leaves`() {
        val nav = NavModel(SavedStateHandle())
        nav.select(Tab.Logs)
        nav.open(screen)
        nav.open(dialog)
        nav.open(sheet)
        assertEquals(listOf(screen, dialog, sheet), nav.stack)

        assertTrue(nav.back())
        assertEquals(listOf(screen, dialog), nav.stack)
        assertTrue(nav.back())
        assertEquals(listOf(screen), nav.stack)
        assertTrue(nav.back())
        assertEquals(emptyList<Dest>(), nav.stack)
        assertEquals(Tab.Logs, nav.tab)
        assertTrue(nav.canGoBack)
        assertTrue(nav.back())
        assertEquals(Tab.Home, nav.tab)
        assertFalse(nav.canGoBack)
        assertFalse("nothing left: Back leaves the app", nav.back())
    }

    @Test
    fun `a sheet or a dialog stays over a screen opened after it, and is closed first`() {
        val nav = NavModel(SavedStateHandle())
        nav.open(dialog)
        nav.open(screen)
        nav.open(about)
        assertEquals(listOf(screen, about, dialog), nav.stack)
        assertTrue(nav.back())
        assertEquals(listOf(screen, about), nav.stack)
    }

    @Test
    fun `a route is open once, the newer argument in the place of the older`() {
        val nav = NavModel(SavedStateHandle())
        nav.open(Routes.linkImport("csqtt://first"))
        nav.open(sheet)
        nav.open(Routes.linkImport("csqtt://second"))
        assertEquals(listOf(sheet, Routes.linkImport("csqtt://second")), nav.stack)
    }

    @Test
    fun `showing a tab closes the screens over the tabs and keeps a question in a dialog`() {
        val nav = NavModel(SavedStateHandle())
        nav.open(screen)
        nav.open(Routes.linkImport("csqtt://x"))
        nav.select(Tab.Server)
        assertEquals(Tab.Server, nav.tab)
        assertEquals(listOf(Routes.linkImport("csqtt://x")), nav.stack)
    }

    @Test
    fun `the tab and every overlay with its argument come back from the saved state alone`() {
        val handle = SavedStateHandle()
        val nav = NavModel(handle)
        nav.select(Tab.Routes)
        nav.open(screen)
        nav.open(sheet)
        nav.open(Dest("link-import", Layer.Dialog, "csqtt://connect?v=2&host=198.51.100.7&peer=46000"))
        val back = restored(handle)
        assertEquals(Tab.Routes, back.tab)
        assertEquals(nav.stack, back.stack)

        nav.close(sheet)
        nav.back()
        assertEquals(listOf(screen), restored(handle).stack)
    }

    @Test
    fun `the saved state holds only what a Bundle takes`() {
        val handle = SavedStateHandle()
        NavModel(handle).apply {
            select(Tab.Logs)
            open(dialog)
        }
        for (key in handle.keys()) {
            val value = handle.get<Any?>(key)
            assertTrue("$key: ${value?.javaClass}", value is String || (value is ArrayList<*> && value.all { it is String }))
        }
    }

    @Test
    fun `an entry another build saved and this one cannot read is dropped, the rest kept`() {
        val handle = SavedStateHandle(
            mapOf(
                "tab" to "Dashboard",
                "stack" to arrayListOf("Sheet\u0000profiles\u0000", "Popover\u0000gone\u0000", "broken", "Dialog\u0000rename\u00003"),
            ),
        )
        val nav = NavModel(handle)
        assertEquals(Tab.Home, nav.tab)
        assertEquals(listOf(sheet, dialog), nav.stack)
    }
}
