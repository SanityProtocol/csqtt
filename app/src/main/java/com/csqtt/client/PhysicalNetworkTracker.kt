package com.csqtt.client

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal enum class PhysicalNetworkTransition {
    NONE,
    AVAILABLE,
    CHANGED,
    UNAVAILABLE,
}

internal enum class PhysicalNetworkPauseReason {
    AIRPLANE_MODE,
    OFFLINE,
}

internal fun physicalNetworkPauseReason(airplaneModeOn: Boolean): PhysicalNetworkPauseReason =
    if (airplaneModeOn) PhysicalNetworkPauseReason.AIRPLANE_MODE else PhysicalNetworkPauseReason.OFFLINE

internal fun isEligiblePhysicalNetwork(
    hasInternet: Boolean,
    notVpn: Boolean,
    wifi: Boolean,
    cellular: Boolean,
): Boolean = hasInternet && notVpn && (wifi || cellular)

internal class PhysicalNetworkTracker<T> {
    private val active = mutableSetOf<T>()
    private val usableState = MutableStateFlow(false)

    /** Whether a usable network exists, written under the same lock as the set: never a stale value last. */
    val usable: StateFlow<Boolean> get() = usableState

    @Synchronized
    fun update(network: T, usable: Boolean): PhysicalNetworkTransition {
        val wasAvailable = active.isNotEmpty()
        val changed = if (usable) active.add(network) else active.remove(network)
        val isAvailable = active.isNotEmpty()
        usableState.value = isAvailable
        return when {
            !wasAvailable && isAvailable -> PhysicalNetworkTransition.AVAILABLE
            wasAvailable && !isAvailable -> PhysicalNetworkTransition.UNAVAILABLE
            changed && wasAvailable && isAvailable -> PhysicalNetworkTransition.CHANGED
            else -> PhysicalNetworkTransition.NONE
        }
    }

    @Synchronized
    fun clear() {
        active.clear()
        usableState.value = false
    }

    @Synchronized
    fun hasUsableNetwork(): Boolean = active.isNotEmpty()

    @Synchronized
    fun isUsable(network: T): Boolean = network in active

}
