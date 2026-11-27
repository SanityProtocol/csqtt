package com.csqtt.client

internal class WorkerRecoveryPolicy(
    private val minimumPeak: Int = CsqttConstants.Tunnel.WORKERS_PER_GROUP,
) {
    private var peak = 0
    private var armedTarget = 0

    @Synchronized
    fun observe(active: Int): Boolean {
        val bounded = active.coerceAtLeast(0)
        peak = maxOf(peak, bounded)
        if (armedTarget != 0 && bounded >= armedTarget) {
            armedTarget = 0
            return true
        }
        return false
    }

    /**
     * Arms the recovery once the run has had its first whole group of streams up — or all of [runCount], for a run of
     * fewer than a group (the slider's first stop is 5; a server may allow as few as 3).
     */
    @Synchronized
    fun armAtZero(runCount: Int = minimumPeak): Int? {
        if (armedTarget != 0) return armedTarget
        if (peak < minOf(minimumPeak, runCount.coerceAtLeast(1))) return null
        armedTarget = peak
        return armedTarget
    }

    @Synchronized
    fun shouldRecover(active: Int, target: Int): Boolean =
        armedTarget == target && active.coerceAtLeast(0) < target

    @Synchronized
    fun reset() {
        peak = 0
        armedTarget = 0
    }
}
