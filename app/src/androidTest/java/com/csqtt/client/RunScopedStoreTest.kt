package com.csqtt.client

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The run reads its own profile (the operator, 2026-11-21): while the tunnel runs on one profile and another is open in
 * the screens, a run-scoped store reads and writes the run's routing and VPN settings, and an edit in the open profile
 * never reaches them — through the real DataStore.
 */
@RunWith(AndroidJUnit4::class)
class RunScopedStoreTest {
    @Test
    fun theRunsSettingsAreItsOwnWhileAnotherProfileIsOpen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val screens = SettingsStore(context)
        val run = SettingsStore(context, ProfileScope.Run)
        val runSlot = 15
        val openSlot = 14
        try {
            screens.clearProfile(runSlot)
            screens.clearProfile(openSlot)
            screens.saveActiveProfile(runSlot)
            screens.saveExcludeRuZonesEnabled(true)
            screens.saveAppRoutingAll(true)
            run.saveRunProfile(runSlot)

            // «Добавить профиль» while connected: another profile is open, with settings of its own
            screens.saveActiveProfile(openSlot)
            screens.saveExcludeRuZonesEnabled(false)
            screens.saveAppRoutingAll(false)

            assertTrue("the run keeps its RU-zone rule", run.routingSnapshot.first().excludeRuZonesEnabled)
            assertTrue("the run keeps its app routing", run.vpnSettings.first().allApps)
            assertEquals(runSlot, run.selectedProfileIndex.first())
            assertFalse(screens.routingSnapshot.first().excludeRuZonesEnabled)
            assertEquals(openSlot, screens.selectedProfileIndex.first())

            // a run-scoped write lands on the run's profile, not the open one
            run.saveExcludeRuZonesEnabled(false)
            assertFalse(run.routingSnapshot.first().excludeRuZonesEnabled)
            screens.saveActiveProfile(runSlot)
            assertFalse(screens.routingSnapshot.first().excludeRuZonesEnabled)

            // no run: a run-scoped store follows the active profile
            run.clearRunProfile()
            screens.saveActiveProfile(openSlot)
            assertEquals(openSlot, run.selectedProfileIndex.first())
        } finally {
            run.clearRunProfile()
            screens.clearProfile(runSlot)
            screens.clearProfile(openSlot)
        }
    }
}
