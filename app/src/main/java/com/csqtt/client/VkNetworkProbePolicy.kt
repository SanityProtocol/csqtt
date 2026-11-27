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
 * What the probe of one network says in the log, and when. Until this the log could not tell «no network at all» from
 * «a network VK does not answer on»: the probe said nothing (the night of 2026-11-27, five hours of «Сеть отключена»
 * with mobile data on).
 *
 * It goes by time, not by tries: a try on a network that drops VK's packets waits out its timeouts — nine seconds and
 * more — and one that fails at once takes none. The silence is said once it has lasted [SAID_AFTER_MS] since its first
 * failed try began (a network that has only just come up is not said), and again every [SAID_AGAIN_MS]; the clock is
 * the one that runs through the phone's sleep, so after a sleep the next failed try is said. That VK answers is said
 * only where its silence was. One per network, kept for as long as the network is there: a probe that is started
 * again after a Wi-Fi pause or a deploy, or an answer another check of the network gets, is the same silence ending.
 */
internal class VkProbeVoice {
    private var startedAt: Long? = null
    private var saidAt: Long? = null

    /**
     * A try that began at [triedAt] failed at [now] (elapsed real time, ms), for [reason] when one is known: the line
     * to write, or null.
     */
    @Synchronized
    fun failed(triedAt: Long, now: Long, network: String, reason: String?): String? {
        val since = saidAt ?: startedAt ?: triedAt.also { startedAt = it }
        if (now - since < (if (saidAt == null) SAID_AFTER_MS else SAID_AGAIN_MS)) return null
        saidAt = now
        val why = reason?.takeIf(String::isNotBlank)?.let { " ($it)" }.orEmpty()
        return "[СЕТЬ] VK не отвечает через $network$why · проверяем дальше"
    }

    /** VK answers through the network: the line to write, where its silence was said, and the voice starts anew. */
    @Synchronized
    fun answered(network: String): String? {
        val said = saidAt != null
        startedAt = null
        saidAt = null
        return if (said) "[СЕТЬ] VK отвечает через $network" else null
    }

    companion object {
        const val SAID_AFTER_MS = 5_000L
        const val SAID_AGAIN_MS = 5 * 60_000L
    }
}

/** The network a probe line speaks of, as «через …» takes it. */
internal fun vkProbeNetworkName(wifi: Boolean, cellular: Boolean): String = when {
    wifi -> "Wi-Fi"
    cellular -> "мобильную сеть"
    else -> "сеть"
}
