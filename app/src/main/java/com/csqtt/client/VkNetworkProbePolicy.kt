package com.csqtt.client

internal fun vkProbeRetryDelayMs(failures: Int): Long = when (failures.coerceAtLeast(1)) {
    in 1..15 -> 1_000L
    16 -> 2_000L
    17 -> 3_000L
    18 -> 5_000L
    19 -> 8_000L
    else -> 15_000L
}

internal fun isVkProbeHttpResponse(code: Int): Boolean = code in 100..599

/**
 * When the probe says in the log that a network is there and VK does not answer through it: at the fifth try in a row
 * (five seconds: a network that has only just come up is not said), at the twentieth (half a minute, the tries now 15 s
 * apart) and at every twentieth from there on — five minutes of a phone awake. Until this the log could not tell «no
 * network at all» from «a network VK does not answer on»: the probe said nothing (the night of 2026-11-27, five hours
 * of «Сеть отключена» with mobile data on). The line is the same each time, so the log counts it on one record.
 */
internal fun vkProbeSaysUnanswered(failures: Int): Boolean = failures == 5 || (failures >= 20 && failures % 20 == 0)

/** Whether [failures] tries in a row were enough for the line above: VK answering again is then said too. */
internal fun vkProbeSaidUnanswered(failures: Int): Boolean = failures >= 5

/** The network a probe line speaks of, as «через …» takes it. */
internal fun vkProbeNetworkName(wifi: Boolean, cellular: Boolean): String = when {
    wifi -> "Wi-Fi"
    cellular -> "мобильную сеть"
    else -> "сеть"
}

internal fun vkProbeUnansweredLine(network: String): String = "[СЕТЬ] VK не отвечает через $network · проверяем дальше"

internal fun vkProbeAnswersLine(network: String): String = "[СЕТЬ] VK снова отвечает через $network"
