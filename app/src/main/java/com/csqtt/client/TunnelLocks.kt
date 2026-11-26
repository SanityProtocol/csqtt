package com.csqtt.client

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.SystemClock

/**
 * P3 §1.9: what the app holds the phone awake for. The 2.5 app held the CPU and the Wi-Fi radio for the whole
 * connection, because the relay path died within minutes of the phone's sleep, and the phone's battery service said
 * «CSQTT is draining battery in the background». The 2.6 core keeps that path on the phone's own wakes (clock.rs), so
 * the locks are held only while they buy something:
 * - the CPU while the tunnel carries a transfer, while a start or a repair is under way, and for a deploy or a check;
 * - the Wi-Fi radio out of its power save for the same, and while the screen is on (a tap is answered without the
 *   radio's wake-up delay).
 * An idle tunnel behind a dark screen holds neither, and the phone sleeps as it does without the app. The CPU is not
 * held for the screen: a lit screen keeps it awake by itself, and a lock that buys nothing is still counted against
 * the app.
 *
 * Not thread-safe: the service calls it under its own lock.
 */
internal class TunnelLockPolicy(
    private val windowMs: Long = TRANSFER_WINDOW_MS,
    private val transferBytes: Long = TRANSFER_BYTES,
    private val workMaxMs: Long = WORK_HOLD_MAX_MS,
) {
    data class Locks(val cpu: Boolean, val wifi: Boolean)

    private var windowStartMs = Long.MIN_VALUE
    private var windowStartBytes = 0L
    private var carrying = false
    private var workSinceMs = Long.MIN_VALUE

    /**
     * [nowMs] counts the phone's sleep (elapsedRealtime). [run]: a tunnel run is on and not paused on Wi-Fi. [work]: a
     * start or a repair is under way ([ConnectionState.isWork]). [deploying]: a deploy or a server check runs.
     * [totalBytes]: all the run has carried so far, up and down.
     */
    fun decide(
        nowMs: Long,
        run: Boolean,
        screenOn: Boolean,
        work: Boolean,
        deploying: Boolean,
        totalBytes: Long,
    ): Locks {
        val transfer = run && carries(nowMs, totalBytes)
        val working = run && worksOn(nowMs, work)
        return Locks(
            cpu = deploying || transfer || working,
            wifi = run && (screenOn || transfer || working),
        )
    }

    /**
     * A transfer, not a heartbeat: at least [transferBytes] within [windowMs]. A lock that followed every byte would be
     * held for good by any app's ping that comes more often than the lock's tail, and the awake phone lets more of
     * such timers run; a ping, a push or a keepalive is a few hundred bytes and never reaches the mark. It ends with
     * the first whole window that did not carry it. A window a sleep fell into ends at the wake.
     */
    private fun carries(nowMs: Long, totalBytes: Long): Boolean {
        if (windowStartMs == Long.MIN_VALUE || totalBytes < windowStartBytes) {
            // the first look, or a new run's counters
            windowStartMs = nowMs
            windowStartBytes = totalBytes
            carrying = false
        } else if (nowMs - windowStartMs >= windowMs) {
            carrying = totalBytes - windowStartBytes >= transferBytes
            windowStartMs = nowMs
            windowStartBytes = totalBytes
        } else if (totalBytes - windowStartBytes >= transferBytes) {
            carrying = true
        }
        return carrying
    }

    /**
     * A start or a repair holds the phone awake until it is done, but not for a night: a server that is down keeps the
     * state at «Переподключение…», and after [workMaxMs] of it the phone may sleep. The core then waits for the tunnel
     * to be wanted (a packet, the screen) before it tries again.
     */
    private fun worksOn(nowMs: Long, work: Boolean): Boolean {
        if (!work) {
            workSinceMs = Long.MIN_VALUE
            return false
        }
        if (workSinceMs == Long.MIN_VALUE) workSinceMs = nowMs
        return nowMs - workSinceMs < workMaxMs
    }

    companion object {
        const val TRANSFER_WINDOW_MS = 10_000L
        /** 64 KB in 10 s: a voice call (about 10 KB/s) is a transfer, a page of pushes is not. */
        const val TRANSFER_BYTES = 64 * 1024L
        const val WORK_HOLD_MAX_MS = 10 * 60_000L
    }
}

/**
 * The two locks themselves (P3 §1.9), for the tunnel service: the CPU's and the Wi-Fi radio's. One holder where the
 * 2.5 app had four locks in two services. The CPU's is taken with a time limit and taken again while it is wanted, so
 * a lock never outlives a service that died without letting go by more than the limit.
 */
internal class TunnelLocks(context: Context) {
    private val appContext = context.applicationContext
    private var cpu: PowerManager.WakeLock? = null
    private var cpuExpiresAtMs = 0L
    private var wifi: WifiManager.WifiLock? = null

    val holdsCpu: Boolean
        @Synchronized get() = cpu?.isHeld == true

    val holdsWifi: Boolean
        @Synchronized get() = wifi?.isHeld == true

    /** Takes and lets go so that what is held is [want]. */
    @Synchronized
    fun hold(want: TunnelLockPolicy.Locks) {
        if (want.cpu) holdCpu() else dropCpu()
        if (want.wifi) holdWifi() else dropWifi()
    }

    fun release() = hold(TunnelLockPolicy.Locks(cpu = false, wifi = false))

    private fun holdCpu() {
        val now = SystemClock.elapsedRealtime()
        if (cpu?.isHeld == true && now < cpuExpiresAtMs - CPU_RENEWAL_WINDOW_MS) return
        dropCpu()
        cpu = appContext.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, CPU_TAG)
            ?.apply {
                setReferenceCounted(false)
                acquire(CPU_TIMEOUT_MS)
            }
        cpuExpiresAtMs = now + CPU_TIMEOUT_MS
    }

    private fun dropCpu() {
        runCatching { if (cpu?.isHeld == true) cpu?.release() }
        cpu = null
        cpuExpiresAtMs = 0L
    }

    // LOW_LATENCY acts only with the screen on and the app in front, so the deprecated HIGH_PERF mode is kept: it is the
    // one that keeps a transfer behind a dark screen out of the radio's power save
    @Suppress("DEPRECATION")
    private fun holdWifi() {
        if (wifi?.isHeld == true) return
        wifi = appContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_TAG)
            ?.apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun dropWifi() {
        runCatching { if (wifi?.isHeld == true) wifi?.release() }
        wifi = null
    }

    companion object {
        const val CPU_TAG = "csqtt:tunnel_cpu"
        const val WIFI_TAG = "csqtt:wifi_perf"
        private const val CPU_TIMEOUT_MS = 10 * 60_000L
        private const val CPU_RENEWAL_WINDOW_MS = 60_000L
    }
}
