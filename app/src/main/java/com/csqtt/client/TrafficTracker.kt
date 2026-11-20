package com.csqtt.client

import java.util.Locale

/** The traffic of a run as STATS gives it (M-89): totals across the run's processes and the speed of the last interval. */
data class TrafficSnapshot(
    val downBytesPerSecond: Long = 0L,
    val upBytesPerSecond: Long = 0L,
    val totalDown: Long = 0L,
    val totalUp: Long = 0L,
    /** False until a process has sent two readings: no speed is known yet. */
    val hasSpeed: Boolean = false,
)

/**
 * STATS carries each process's cumulative bytes. A new process (generation) starts its counters at zero, so the tracker
 * carries the earlier processes' totals and starts the speed again from the new process's first reading: a restart
 * never shows a spike. A counter that goes down is taken as a new process too. It keeps only the last reading.
 */
internal class TrafficTracker {
    private var generation = Long.MIN_VALUE
    private var lastUp = 0L
    private var lastDown = 0L
    private var lastAtMs = 0L
    private var carriedUp = 0L
    private var carriedDown = 0L

    @Synchronized
    fun reset() {
        generation = Long.MIN_VALUE
        lastUp = 0L
        lastDown = 0L
        lastAtMs = 0L
        carriedUp = 0L
        carriedDown = 0L
    }

    @Synchronized
    fun onStats(generation: Long, bytesUp: Long, bytesDown: Long, nowMs: Long): TrafficSnapshot {
        val sameProcess = generation == this.generation && bytesUp >= lastUp && bytesDown >= lastDown
        val snapshot = if (sameProcess) {
            val elapsedMs = nowMs - lastAtMs
            if (elapsedMs > 0) {
                TrafficSnapshot(
                    downBytesPerSecond = (bytesDown - lastDown) * 1_000L / elapsedMs,
                    upBytesPerSecond = (bytesUp - lastUp) * 1_000L / elapsedMs,
                    totalDown = carriedDown + bytesDown,
                    totalUp = carriedUp + bytesUp,
                    hasSpeed = true,
                )
            } else {
                TrafficSnapshot(totalDown = carriedDown + bytesDown, totalUp = carriedUp + bytesUp)
            }
        } else {
            if (this.generation != Long.MIN_VALUE) {
                carriedUp += lastUp
                carriedDown += lastDown
            }
            this.generation = generation
            TrafficSnapshot(totalDown = carriedDown + bytesDown, totalUp = carriedUp + bytesUp)
        }
        lastUp = bytesUp
        lastDown = bytesDown
        lastAtMs = nowMs
        return snapshot
    }
}

internal fun formatBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L).toDouble()
    return when {
        value < 1024.0 -> "${bytes.coerceAtLeast(0L)} Б"
        value < 1024.0 * 1024.0 -> String.format(Locale.US, "%.0f КБ", value / 1024.0)
        value < 1024.0 * 1024.0 * 1024.0 -> String.format(Locale.US, "%.1f МБ", value / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f ГБ", value / (1024.0 * 1024.0 * 1024.0))
    }
}

/** «↓ 1.2 МБ/с ↑ 300 КБ/с · всего 120.0 МБ»; only the totals until a speed is known; empty before any traffic. */
internal fun formatTraffic(snapshot: TrafficSnapshot?): String {
    if (snapshot == null) return ""
    val total = "всего ${formatBytes(snapshot.totalDown + snapshot.totalUp)}"
    if (!snapshot.hasSpeed) return total
    return "↓ ${formatBytes(snapshot.downBytesPerSecond)}/с ↑ ${formatBytes(snapshot.upBytesPerSecond)}/с · $total"
}

/**
 * The tunnel notification is redrawn only while the screen is interactive, at least [minIntervalMs] apart, and only when
 * its text changed (M-48); a state change is posted at once (the screen still has to be on).
 */
internal class NotificationThrottle(private val minIntervalMs: Long = 5_000L) {
    private var lastText: String? = null
    private var lastState: String? = null
    private var lastPostMs = 0L

    @Synchronized
    fun shouldPost(state: String, text: String, nowMs: Long, screenOn: Boolean, visible: () -> Boolean = { true }): Boolean {
        if (!screenOn) return false
        val stateChanged = state != lastState
        // an unchanged text is posted again only when the notification is gone (asked last: it is a system call)
        if (!stateChanged && text == lastText && visible()) return false
        if (!stateChanged && lastText != null && nowMs - lastPostMs < minIntervalMs) return false
        lastState = state
        lastText = text
        lastPostMs = nowMs
        return true
    }

    /** The next post goes out whatever it says (the notification was swiped or replaced). */
    @Synchronized
    fun invalidate() {
        lastText = null
        lastState = null
        lastPostMs = 0L
    }
}
