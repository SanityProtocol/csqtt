package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3 §1.9: the core at rest. The app restarts nothing over a stream count of zero while the rest lasts, arms its
 * recovery when the tunnel is wanted again with no stream left, and shows «Подключено» only for a tunnel that had
 * streams when it went to rest.
 */
class TunnelRestTest {
    @Test
    fun `a tunnel that carried when the phone slept is connected at rest, and restarts nothing at zero`() {
        val rest = TunnelRest()
        // the phone slept: the core says so at once, its ten streams still counted
        assertFalse(rest.onStats(activeBefore = 10, active = 10, dormant = true))
        assertTrue(rest.dormant && rest.connected)
        assertFalse("a zero count at rest would restart the core", rest.recovers)
        // the relay dropped them in the sleep
        assertFalse(rest.onStats(activeBefore = 10, active = 0, dormant = true))
        assertTrue(rest.connected)
        assertFalse(rest.recovers)
        // a packet from the phone: wanted again with none left, so the recovery is armed for the case the core cannot
        assertTrue(rest.onStats(activeBefore = 0, active = 0, dormant = false))
        assertFalse(rest.dormant || rest.connected)
        assertTrue(rest.recovers)
        // its streams come back: nothing more to arm
        assertFalse(rest.onStats(activeBefore = 0, active = 6, dormant = false))
    }

    @Test
    fun `a tunnel whose repair had failed before the phone slept keeps its real state at rest`() {
        val rest = TunnelRest()
        // «Переподключение…» for ten minutes, no stream; then the phone sleeps
        assertFalse(rest.onStats(activeBefore = 0, active = 0, dormant = true))
        assertTrue(rest.dormant)
        assertFalse("a repair that failed is shown as connected", rest.connected)
        assertFalse(rest.recovers)
        // the same through every later reading of the rest
        assertFalse(rest.onStats(activeBefore = 0, active = 0, dormant = true))
        assertFalse(rest.connected)
        // and the state the screens derive from it stays «Переподключение…», which is the work the locks' ten minutes
        // are counted through, so they are not given again
        val state = deriveConnectionState(
            ConnectionInputs(running = true, tunconf = true, vpnReady = true, everConnected = true, dormant = rest.connected),
        )
        assertEquals(ConnectionState.Reconnecting, state)
        assertTrue(state.isWork)
        assertTrue(rest.onStats(activeBefore = 0, active = 0, dormant = false))
    }

    @Test
    fun `wanted again with streams left arms nothing, and a few streams at the sleep are a working tunnel`() {
        val rest = TunnelRest()
        assertFalse(rest.onStats(activeBefore = 0, active = 3, dormant = true))
        assertTrue(rest.connected)
        assertFalse(rest.onStats(activeBefore = 3, active = 3, dormant = false))
        assertFalse(rest.connected)
    }

    @Test
    fun `an older core never rests, and a new process starts with no rest`() {
        val rest = TunnelRest()
        assertFalse(rest.onStats(activeBefore = 10, active = 0, dormant = false))
        assertTrue(rest.recovers)
        assertFalse(rest.connected)
        rest.onStats(activeBefore = 10, active = 10, dormant = true)
        rest.reset()
        assertFalse(rest.dormant || rest.connected)
        assertTrue(rest.recovers)
    }
}
