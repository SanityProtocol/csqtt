package com.csqtt.client

import com.csqtt.client.ui.legacy.utils.parseCsqttLink
import java.net.URLEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** B5b1, M-99: a link carries only a password GETCONF can carry, in both link formats, as the dialogs require. */
class TunnelPasswordRuleTest {
    private fun encoded(password: String) = URLEncoder.encode(password, "UTF-8").replace("+", "%20")
    private fun v2(password: String) =
        parseCsqttLink("csqtt://connect?v=2&host=203.0.113.7&peer=46000&password=${encoded(password)}")
    private fun legacy(password: String) = parseCsqttLink("csqtt://${encoded(password)}@203.0.113.7:46000")

    @Test
    fun `a password GETCONF cannot carry is refused in both formats`() {
        for (bad in listOf("a|b", "a b", "a\tb", "a\u0007b", "x".repeat(129), "ж".repeat(65))) {
            assertNull("v2 «$bad»", v2(bad))
            assertNull("legacy «$bad»", legacy(bad))
        }
    }

    @Test
    fun `any other password is taken, Cyrillic and symbols too, up to 128 bytes`() {
        for (good in listOf("ж".repeat(64), "x".repeat(128), "p@ss+=%$", "Пароль!#")) {
            assertEquals(good, v2(good)?.password)
            assertEquals(good, legacy(good)?.password)
        }
    }
}
