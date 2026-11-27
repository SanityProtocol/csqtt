package com.csqtt.client

internal object WorkerCountPolicy {
    fun normalize(
        requested: Int,
        maximum: Int = CsqttConstants.Tunnel.MAX_WORKERS,
    ): Int {
        // what the core runs: whole groups of 10, at least one (5 used to be stored and ran as 10, M-84)
        val group = CsqttConstants.Tunnel.WORKERS_PER_GROUP
        val normalizedMaximum = (maximum.coerceAtLeast(group) / group) * group
        return (requested.coerceIn(group, normalizedMaximum) / group) * group
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
     * What is recommended below it is a count per hash ([StreamsPlan]), which warns and never caps.
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
