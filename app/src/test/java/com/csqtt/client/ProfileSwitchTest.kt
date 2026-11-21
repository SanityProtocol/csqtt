package com.csqtt.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The operator (2026-11-21): a run stays on its profile until the user picks another one. A pick of another profile
 * reconnects a run that is on; picking the run's own profile back just selects it; adding a profile is not a pick.
 */
class ProfileSwitchTest {
    @Test
    fun `a run that is running or starting is on, never a stopping, paused or idle one`() {
        assertTrue(ProfileSwitch.runIsOn(running = true, starting = false, stopping = false, pausedOnWifi = false))
        assertTrue(ProfileSwitch.runIsOn(running = false, starting = true, stopping = false, pausedOnWifi = false))
        assertFalse(ProfileSwitch.runIsOn(running = true, starting = false, stopping = true, pausedOnWifi = false))
        assertFalse(ProfileSwitch.runIsOn(running = false, starting = false, stopping = false, pausedOnWifi = true))
        assertFalse(ProfileSwitch.runIsOn(running = false, starting = false, stopping = false, pausedOnWifi = false))
    }

    @Test
    fun `a pick reconnects only onto a profile the run is not on`() {
        assertTrue(ProfileSwitch.pickReconnects(runOn = true, picked = 2, runProfile = 0))
        assertFalse("the run's own profile picked back", ProfileSwitch.pickReconnects(runOn = true, picked = 0, runProfile = 0))
        assertFalse(ProfileSwitch.pickReconnects(runOn = false, picked = 2, runProfile = 0))
    }
}
