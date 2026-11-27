package com.csqtt.client

internal object WorkerCountPolicy {
    /** The slider's step, and the fewest streams a profile runs (the operator, 2026-11-27). */
    const val STEP = 5

    /**
     * What a profile may hold: a whole number of fives, from one five up to [maximum]. The core is started with
     * `-exact-workers`, so the count held is the count that runs: its last group of ten may be half a group.
     */
    fun normalize(
        requested: Int,
        maximum: Int = CsqttConstants.Tunnel.MAX_WORKERS,
    ): Int {
        val top = (maximum.coerceAtLeast(STEP) / STEP) * STEP
        return (requested.coerceIn(STEP, top) / STEP) * STEP
    }

    /**
     * What a count ran as before the core was given exact counts: 2.5.3 ran fewer than ten as five and the rest in
     * whole tens, so 18, its stored default, ran as 10. The store's first step and everything that takes in a count
     * stored then (step 9, an older backup, an older settings file) keep to it: a profile runs what it ran.
     */
    fun wholeGroups(requested: Int): Int {
        val group = CsqttConstants.Tunnel.WORKERS_PER_GROUP
        return if (requested < group) STEP else (requested.coerceAtMost(CsqttConstants.Tunnel.MAX_WORKERS) / group) * group
    }

    fun maximumForHashes(hashCount: Int): Int {
        val count = hashCount.coerceIn(1, CsqttConstants.Tunnel.MAX_VK_HASHES)
        return (count * CsqttConstants.Tunnel.WORKERS_PER_GROUP).coerceAtMost(CsqttConstants.Tunnel.MAX_WORKERS)
    }

    fun maximumForSources(
        linkMode: Boolean,
        linkHashCount: Int,
        autoHashMode: Boolean,
        manualHashCount: Int,
    ): Int = CsqttConstants.Tunnel.MAX_WORKERS

    /**
     * The most streams a profile runs: what the core takes, and in «Авто ВК» ([accountAutoVk]) that mode's own limit.
     * Below it nothing caps a count: the zones of [StreamsPlan] colour the slider and warn.
     */
    fun maximum(accountAutoVk: Boolean): Int =
        if (accountAutoVk) CsqttConstants.Tunnel.AUTO_VK_MAX_WORKERS else CsqttConstants.Tunnel.MAX_WORKERS

    fun normalizeForHashes(requested: Int, hashCount: Int): Int =
        normalize(requested, maximumForHashes(hashCount))

    fun normalizeForHashValues(
        requested: Int,
        hashes: Iterable<String>,
        allowRedistribution: Boolean = false,
    ): Int {
        return normalize(requested, CsqttConstants.Tunnel.MAX_WORKERS)
    }

}
