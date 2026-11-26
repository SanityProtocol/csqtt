package com.csqtt.client

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P3 §1.9: what Android itself holds for the app (`dumpsys power`, `dumpsys wifi`) follows the lock policy. An idle tunnel
 * behind a dark screen holds no wake lock and no Wi-Fi lock; a transfer, a start and a repair hold both; a lit screen
 * holds the radio alone. The 2.5 app held four locks for the whole connection, which is what the phone's battery service
 * reported.
 */
@RunWith(AndroidJUnit4::class)
class LocksFollowTrafficTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val locks = TunnelLocks(instrumentation.targetContext)
    private val policy = TunnelLockPolicy()
    private var bytes = 0L

    private fun dumpsys(service: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("dumpsys $service"))
            .use { it.readBytes().decodeToString() }

    /** The locks held now: the lines under «Wake Locks: size=N» (the dump's log of past ones names the tag for good). */
    private fun cpuHeld() = dumpsys("power").lineSequence()
        .dropWhile { !it.startsWith("Wake Locks: size=") }.drop(1)
        .takeWhile { it.isNotBlank() }
        .any { TunnelLocks.CPU_TAG in it }

    /** `dumpsys wifi` lists the locks it holds for apps, each by its tag. */
    private fun wifiHeld() = dumpsys("wifi").lineSequence()
        .any { TunnelLocks.WIFI_TAG in it && "WifiLock{" in it }

    private fun at(seconds: Long, screenOn: Boolean = false, work: Boolean = false, deploying: Boolean = false) =
        locks.hold(policy.decide(seconds * 1_000L, run = true, screenOn = screenOn, work = work, deploying = deploying, totalBytes = bytes))

    @After
    fun letGo() = locks.release()

    @Test
    fun anIdleTunnelHoldsNothingAndATransferHoldsBoth() {
        // idle, screen off: Android holds nothing of ours
        at(0)
        assertFalse("a wake lock for an idle tunnel", cpuHeld())
        assertFalse("a Wi-Fi lock for an idle tunnel", wifiHeld())
        assertFalse(locks.holdsCpu || locks.holdsWifi)

        // the screen: the radio alone
        at(2, screenOn = true)
        assertFalse("a wake lock for a lit screen", cpuHeld())
        assertTrue("no Wi-Fi lock under a lit screen", wifiHeld())
        at(4)
        assertFalse(wifiHeld())

        // a transfer: both, until a whole window carried nothing
        bytes += 300 * 1024L
        at(6)
        assertTrue("no wake lock for a transfer", cpuHeld())
        assertTrue("no Wi-Fi lock for a transfer", wifiHeld())
        at(12)
        assertTrue(cpuHeld())
        at(22)
        assertFalse("the wake lock outlived the transfer", cpuHeld())
        assertFalse("the Wi-Fi lock outlived the transfer", wifiHeld())

        // a repair, then a deploy (the CPU alone), then nothing again
        at(24, work = true)
        assertTrue(cpuHeld() && wifiHeld())
        at(26)
        assertFalse(cpuHeld() || wifiHeld())
        at(28, deploying = true)
        assertTrue(cpuHeld())
        assertFalse(wifiHeld())
        locks.release()
        assertFalse(cpuHeld() || wifiHeld())
    }
}
