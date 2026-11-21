package com.csqtt.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The operator (2026-11-21): a profile picked while the tunnel is on reconnects onto it; a run paused on Wi-Fi resumes
 * from the active profile anyway and a stopping one ends; «Добавить профиль» (an empty profile, made active) is refused
 * while the tunnel is on in any way.
 */
class ProfileSwitchTest {
    @Test
    fun `a pick reconnects a run that is on or starting, never a stopping, paused or idle one`() {
        assertTrue(ProfileSwitch.reconnects(running = true, starting = false, stopping = false, pausedOnWifi = false))
        assertTrue(ProfileSwitch.reconnects(running = false, starting = true, stopping = false, pausedOnWifi = false))
        assertFalse(ProfileSwitch.reconnects(running = true, starting = false, stopping = true, pausedOnWifi = false))
        assertFalse(ProfileSwitch.reconnects(running = false, starting = false, stopping = false, pausedOnWifi = true))
        assertFalse(ProfileSwitch.reconnects(running = false, starting = false, stopping = false, pausedOnWifi = false))
    }

    @Test
    fun `adding a profile is refused while the tunnel is on in any way`() {
        assertTrue(ProfileSwitch.addRefused(running = true, starting = false, stopping = false, pausedOnWifi = false))
        assertTrue(ProfileSwitch.addRefused(running = false, starting = true, stopping = false, pausedOnWifi = false))
        assertTrue(ProfileSwitch.addRefused(running = false, starting = false, stopping = true, pausedOnWifi = false))
        assertTrue(ProfileSwitch.addRefused(running = false, starting = false, stopping = false, pausedOnWifi = true))
        assertFalse(ProfileSwitch.addRefused(running = false, starting = false, stopping = false, pausedOnWifi = false))
    }
}
