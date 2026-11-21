package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Test

/** The roles batch: the Деплой (2) and Логи (3) tabs follow the two app-wide switches in «Настройки». */
class NavVisibilityTest {
    @Test
    fun `each combination of the two switches shows its tabs`() {
        assertEquals(listOf(0, 1, 2, 3, 4), resolveActiveNavIds(showDeploy = true, showLogs = true))
        assertEquals(listOf(0, 1, 2, 4), resolveActiveNavIds(showDeploy = true, showLogs = false))
        assertEquals(listOf(0, 1, 3, 4), resolveActiveNavIds(showDeploy = false, showLogs = true))
        assertEquals(listOf(0, 1, 4), resolveActiveNavIds(showDeploy = false, showLogs = false))
    }

    @Test
    fun `a tab that is hidden is left for the connection tab, a shown one is kept`() {
        val noDeploy = resolveActiveNavIds(showDeploy = false, showLogs = true)
        assertEquals(0, tabAfterNavChange(2, noDeploy))
        assertEquals(3, tabAfterNavChange(3, noDeploy))
        val noLogs = resolveActiveNavIds(showDeploy = true, showLogs = false)
        assertEquals(0, tabAfterNavChange(3, noLogs))
        assertEquals(2, tabAfterNavChange(2, noLogs))
        for (tab in listOf(0, 1, 4)) assertEquals(tab, tabAfterNavChange(tab, resolveActiveNavIds(false, false)))
    }
}
