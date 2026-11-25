package com.csqtt.client.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/** The tab bar (K4, D20, D28, D29): «Сервер» for a user with a server and while it is open, the other four for everyone. */
class NavVisibilityTest {
    private val four = listOf(Tab.Home, Tab.Tunnel, Tab.Logs, Tab.Routes)
    private val five = listOf(Tab.Home, Tab.Tunnel, Tab.Server, Tab.Logs, Tab.Routes)

    @Test
    fun `a participant has four tabs, a user with a server five, in the bar's order`() {
        for (open in four) {
            assertEquals(four, tabsFor(hasServer = false, open = open))
            assertEquals(five, tabsFor(hasServer = true, open = open))
        }
    }

    @Test
    fun `the open tab is always in the bar, so a restored tab is never taken away`() {
        // «Сервер» restored before the store has answered, or opened by someone who has no server yet (§4.2 rule 3)
        assertEquals(five, tabsFor(hasServer = false, open = Tab.Server))
        for (hasServer in listOf(false, true)) {
            for (open in Tab.entries) assertEquals(true, open in tabsFor(hasServer, open))
        }
    }

    @Test
    fun `leaving the server tab without a server takes it out of the bar`() {
        assertEquals(four, tabsFor(hasServer = false, open = Tab.Logs))
    }
}
