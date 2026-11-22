package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** H5 (B5h2): a stop's cause is stored and announced, so it is masked as a log line is. */
class StopCauseMaskTest {
    private val ownerHash = "AbCdEfGh12345678ijklmn"

    @Test
    fun secretsLinksAndTheOwnersHashesAreMasked() {
        val masked = StopCause.masked(
            "FATAL_AUTH: token=vk1.a.SECRETTOKEN password: hunter2 csqtt://203.0.113.9:443/p?x=1 " +
                "https://vk.ru/call/join/Zz9yXwVuTs8rQp7o hash ${ownerHash.take(10)}… dead",
            listOf(ownerHash),
        )
        for (secret in listOf("SECRETTOKEN", "hunter2", "203.0.113.9", "Zz9yXwVuTs8rQp7o", ownerHash.take(8))) {
            assertFalse("$secret in «$masked»", masked.contains(secret))
        }
        assertTrue(masked.contains("от владельца"))
    }

    @Test
    fun anOrdinaryCauseIsKept() {
        assertEquals(StopCause.VK_SIGN_IN_AGAIN, StopCause.masked(StopCause.VK_SIGN_IN_AGAIN, emptyList()))
        assertEquals(StopCause.NO_CONNECTION_SOURCE, StopCause.masked(StopCause.NO_CONNECTION_SOURCE, listOf(ownerHash)))
    }
}
