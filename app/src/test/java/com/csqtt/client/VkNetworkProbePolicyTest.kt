package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VkNetworkProbePolicyTest {
    @Test
    fun retryDelayIsBounded() {
        assertEquals(1_000L, vkProbeRetryDelayMs(0))
        assertEquals(1_000L, vkProbeRetryDelayMs(15))
        assertEquals(2_000L, vkProbeRetryDelayMs(16))
        assertEquals(15_000L, vkProbeRetryDelayMs(20))
    }

    @Test
    fun theProbeSaysASilenceOnceItHasLastedAndThenEveryFiveMinutes() {
        val voice = VkProbeVoice()
        // tries that fail at once, a second apart
        assertNull("a network that has only just come up", voice.failed(1_000, 1_050, "Wi-Fi", "UnknownHostException"))
        assertNull(voice.failed(5_950, 5_999, "Wi-Fi", null))
        assertEquals(
            "[СЕТЬ] VK не отвечает через Wi-Fi (SocketTimeoutException) · проверяем дальше",
            voice.failed(5_990, 6_000, "Wi-Fi", "SocketTimeoutException"),
        )
        assertNull("said: not again for five minutes", voice.failed(300_000, 6_000 + 299_999, "Wi-Fi", "SocketTimeoutException"))
        assertEquals("[СЕТЬ] VK не отвечает через Wi-Fi · проверяем дальше", voice.failed(300_000, 6_000 + 300_000, "Wi-Fi", null))
        assertNull(voice.failed(600_000, 6_000 + 300_000 + 299_999, "Wi-Fi", null))
        assertEquals("a reason that is no word is none", "[СЕТЬ] VK не отвечает через Wi-Fi · проверяем дальше", voice.failed(606_000, 606_000, "Wi-Fi", ""))
    }

    @Test
    fun aTryThatWaitedOutItsTimeoutsIsSaidAtOnce() {
        // a network that drops VK's packets: the first try alone takes nine seconds and more — and so does the first
        // one after a night's sleep, however short the wake
        val voice = VkProbeVoice()
        assertEquals(
            "[СЕТЬ] VK не отвечает через мобильную сеть (SocketTimeoutException) · проверяем дальше",
            voice.failed(0, 9_200, vkProbeNetworkName(wifi = false, cellular = true), "SocketTimeoutException"),
        )
    }

    @Test
    fun aSilenceOutlivesItsProbeAndItsEndIsSaidOnlyWhereItWasSaid() {
        // VK answered before anything was said: nothing to say
        val quick = VkProbeVoice()
        assertNull(quick.failed(0, 1_000, "Wi-Fi", null))
        assertNull(quick.answered("Wi-Fi"))

        // the probe is started again for the same network (a Wi-Fi pause or a deploy cancelled it): the same silence
        val voice = VkProbeVoice()
        assertNull(voice.failed(0, 100, "Wi-Fi", null))
        assertEquals("[СЕТЬ] VK не отвечает через Wi-Fi · проверяем дальше", voice.failed(4_900, 5_000, "Wi-Fi", null))
        assertEquals("[СЕТЬ] VK отвечает через Wi-Fi", voice.answered("Wi-Fi"))
        assertNull("said once", voice.answered("Wi-Fi"))
        // and the network's next silence starts from its own first failed try, not from the one before
        assertNull(voice.failed(100_000, 101_000, "Wi-Fi", null))
        assertNull(voice.failed(104_000, 104_999, "Wi-Fi", null))
        assertEquals("[СЕТЬ] VK не отвечает через Wi-Fi · проверяем дальше", voice.failed(104_900, 105_000, "Wi-Fi", null))
    }

    @Test
    fun aProbeLineNamesItsNetwork() {
        assertEquals("Wi-Fi", vkProbeNetworkName(wifi = true, cellular = false))
        assertEquals("a network that is both is said as the Wi-Fi it is used as", "Wi-Fi", vkProbeNetworkName(wifi = true, cellular = true))
        assertEquals("мобильную сеть", vkProbeNetworkName(wifi = false, cellular = true))
        assertEquals("its capabilities are gone", "сеть", vkProbeNetworkName(wifi = false, cellular = false))
    }

    @Test
    fun anyHttpResponseMeansVkIsReachable() {
        assertTrue(isVkProbeHttpResponse(100))
        assertTrue(isVkProbeHttpResponse(200))
        assertTrue(isVkProbeHttpResponse(403))
        assertTrue(isVkProbeHttpResponse(599))
        assertFalse(isVkProbeHttpResponse(99))
        assertFalse(isVkProbeHttpResponse(600))
    }
}
