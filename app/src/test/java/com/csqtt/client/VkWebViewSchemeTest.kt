package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M-80 / L-59: the VK login WebView loads web pages only, and an aborted redirect is not a failed login. */
class VkWebViewSchemeTest {
    @Test
    fun onlyWebSchemesLoad() {
        assertTrue(VkLoginNavigation.isWebScheme("https"))
        assertTrue(VkLoginNavigation.isWebScheme("HTTP"))
        listOf("vk", "intent", "vkontakte", "market", "javascript", "file", "", null).forEach {
            assertFalse("$it", VkLoginNavigation.isWebScheme(it))
        }
    }

    @Test
    fun anErrorFailsOnlyWhileTheFailedPageIsStillShown() {
        val failed = "https://id.vk.ru/auth?step=1"
        assertTrue(VkLoginNavigation.failsAfterGrace(failed, failed))
        assertTrue(VkLoginNavigation.failsAfterGrace(null, failed))
        assertFalse(VkLoginNavigation.failsAfterGrace("https://m.vk.ru/feed", failed))
    }

    @Test
    fun theLoginStartsOnTheMobileSite() {
        assertEquals("https://m.vk.ru/login", CsqttConstants.VkAutoHash.VK_LOGIN_URL)
    }
}
