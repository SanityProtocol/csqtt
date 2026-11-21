package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B5h1 (APP-PLAN §4.2 rule 10): every stop the user did not ask for has a cause that says what to do; the first one of a
 * run is the one shown; it is announced only while the app is not in front; and an earlier process's cause comes back at
 * the app's start only while no run began meanwhile.
 */
class RunOutcomeTest {
    @Test
    fun `a stop that made no VK calls names what to do`() {
        assertEquals(StopCause.VK_CALLS_NOT_CREATED, StopCause.vkCallsNotCreated(tokenInvalid = false, tokenFromLink = false))
        assertEquals(StopCause.VK_CALLS_NOT_CREATED, StopCause.vkCallsNotCreated(tokenInvalid = false, tokenFromLink = true))
        assertEquals("Войдите в VK заново", StopCause.vkCallsNotCreated(tokenInvalid = true, tokenFromLink = false))
        assertEquals(
            "Ссылка устарела: попросите у владельца новую",
            StopCause.vkCallsNotCreated(tokenInvalid = true, tokenFromLink = true),
        )
    }

    @Test
    fun `every cause fits a notification line and a start error carries its message`() {
        val causes = listOf(
            StopCause.VK_CALLS_NOT_CREATED, StopCause.VK_SIGN_IN_AGAIN, StopCause.LINK_TOKEN_EXPIRED,
            StopCause.ALL_HASHES_DEAD, StopCause.NO_HASHES_LEFT, StopCause.NO_CONNECTION_SOURCE,
            StopCause.PASSWORD_UNREADABLE, StopCause.NO_HASHES_OR_TOKEN, StopCause.FOREGROUND_REFUSED,
            StopCause.VPN_REVOKED,
        )
        assertEquals(causes.size, causes.toSet().size)
        for (cause in causes) assertTrue(cause, cause.length <= 60)
        assertEquals("Ошибка запуска: disk full", StopCause.startError(" disk full "))
        assertEquals("Ошибка запуска: неизвестная", StopCause.startError(null))
        assertEquals("Ошибка запуска: неизвестная", StopCause.startError("  "))
    }

    @Test
    fun `the first cause of a run is the one kept`() {
        val fatal = FirstFatal()
        assertTrue(fatal.record(StopCause.ALL_HASHES_DEAD))
        assertFalse(fatal.record(StopCause.FOREGROUND_REFUSED))
        assertEquals(StopCause.ALL_HASHES_DEAD, fatal.cause)
    }

    @Test
    fun `its own notification only while the app is not in front`() {
        assertTrue(StopCause.announces(appInFront = false))
        assertFalse(StopCause.announces(appInFront = true))
    }

    @Test
    fun `an earlier process's cause comes back only while nothing runs, starts or holds a cause`() {
        val stored = StopCause.VK_SIGN_IN_AGAIN
        assertEquals(stored, StopCause.restored(stored, running = false, starting = false, current = null))
        assertNull(StopCause.restored(stored, running = true, starting = false, current = null))
        assertNull("a system restart's restore began", StopCause.restored(stored, running = false, starting = true, current = null))
        assertNull(StopCause.restored(stored, running = false, starting = false, current = StopCause.NO_HASHES_LEFT))
        assertNull(StopCause.restored("", running = false, starting = false, current = null))
        assertNull(StopCause.restored(null, running = false, starting = false, current = null))
    }
}
