package com.csqtt.client

/**
 * P3 §1.9: what the app keeps of the core's rest. The core says `dormant` in STATS while the phone has slept and nothing
 * has wanted the tunnel since: streams the relay dropped in the sleep are made again only when a packet, the screen or a
 * network asks for them. Meanwhile a stream count of zero is no fault, so the app restarts nothing over it, and a tunnel
 * that had streams when it went to rest is shown as connected. One that had none (a repair that had failed before the
 * phone slept) keeps its real state: «Переподключение…» is never painted over, and its ten minutes of held locks are
 * not given again (TunnelLockPolicy).
 */
internal class TunnelRest {
    /** The core is at rest. */
    @Volatile
    var dormant = false
        private set

    /** At rest, and the tunnel had streams when it went to rest: «Подключено», whatever the count is now. */
    @Volatile
    var connected = false
        private set

    /** Whether a stream count of zero may start the app's recovery (a restart of the core): never at rest. */
    val recovers: Boolean
        get() = !dormant

    /**
     * A STATS reading: [activeBefore] is the stream count the app had, [active] and [dormant] are what the core says
     * now. True when the tunnel is wanted again with no stream left: the core makes them again, and the recovery a fall
     * to zero arms is armed now, for the case it cannot.
     */
    fun onStats(activeBefore: Int, active: Int, dormant: Boolean): Boolean {
        val wokeEmpty = this.dormant && !dormant && active == 0
        if (dormant && !this.dormant) connected = activeBefore > 0 || active > 0
        if (!dormant) connected = false
        this.dormant = dormant
        return wokeEmpty
    }

    /** The core's process is gone, or a new one started: nothing is at rest. */
    fun reset() {
        dormant = false
        connected = false
    }
}
