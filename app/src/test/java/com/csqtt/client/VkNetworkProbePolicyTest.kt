package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun theProbeSaysANetworkVkDoesNotAnswerOnAndNotAtEveryTry() {
        // the first 15 tries are a second apart, then 2, 3, 5 and 8 s, then 15 s each
        assertEquals(listOf(5, 20, 40, 60, 80, 100), (0..100).filter(::vkProbeSaysUnanswered))
        // VK answering again is said where the silence was
        assertFalse(vkProbeSaidUnanswered(0))
        assertFalse("a network that has only just come up", vkProbeSaidUnanswered(4))
        assertTrue(vkProbeSaidUnanswered(5))
        assertTrue(vkProbeSaidUnanswered(37))
    }

    @Test
    fun aProbeLineNamesItsNetwork() {
        assertEquals("[СЕТЬ] VK не отвечает через Wi-Fi · проверяем дальше", vkProbeUnansweredLine(vkProbeNetworkName(wifi = true, cellular = false)))
        assertEquals("[СЕТЬ] VK не отвечает через мобильную сеть · проверяем дальше", vkProbeUnansweredLine(vkProbeNetworkName(wifi = false, cellular = true)))
        assertEquals("[СЕТЬ] VK снова отвечает через сеть", vkProbeAnswersLine(vkProbeNetworkName(wifi = false, cellular = false)))
        // one text for a network, whatever the try: the log counts it on one record
        assertEquals(vkProbeUnansweredLine("Wi-Fi"), vkProbeUnansweredLine(vkProbeNetworkName(wifi = true, cellular = true)))
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
