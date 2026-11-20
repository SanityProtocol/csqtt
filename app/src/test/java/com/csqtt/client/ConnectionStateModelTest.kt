package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStateModelTest {
    private val live = ConnectionInputs(running = true, tunconf = true, vpnReady = true, activeWorkers = 18)

    private fun state(input: ConnectionInputs) = deriveConnectionState(input)

    @Test
    fun eachStateFromItsInputs() {
        assertEquals(ConnectionState.Idle, state(ConnectionInputs()))
        assertEquals(ConnectionState.Preparing, state(ConnectionInputs(starting = true)))
        assertEquals(ConnectionState.Connecting, state(ConnectionInputs(running = true)))
        assertEquals(ConnectionState.Connected(18), state(live))
        assertEquals(ConnectionState.Reconnecting, state(live.copy(activeWorkers = 0, everConnected = true)))
        assertEquals(ConnectionState.WaitingForNetwork, state(live.copy(waitingForNetwork = true)))
        assertEquals(ConnectionState.WaitingForNetwork, state(ConnectionInputs(starting = true, waitingForNetwork = true)))
        assertEquals(ConnectionState.PausedOnWifi, state(ConnectionInputs(pausedOnWifi = true)))
        assertEquals(ConnectionState.Stopping, state(live.copy(stopping = true)))
        assertEquals(ConnectionState.CaptchaNeeded, state(ConnectionInputs(running = true, captchaPending = true)))
        assertEquals(ConnectionState.ServerNotResponding, state(ConnectionInputs(running = true, serverSilent = true)))
        assertEquals(ConnectionState.Failed("API 18"), state(ConnectionInputs(failure = "API 18")))
    }

    @Test
    fun connectedNeverBeforeTheProcessTunconfTheVpnAndLiveWorkers() {
        // a started process alone was «Подключено» in 2.5.3 (#45)
        assertFalse(state(live.copy(tunconf = false)) is ConnectionState.Connected)
        assertFalse(state(live.copy(vpnReady = false)) is ConnectionState.Connected)
        assertFalse(state(live.copy(activeWorkers = 0)) is ConnectionState.Connected)
        assertFalse(state(live.copy(running = false)) is ConnectionState.Connected)
    }

    @Test
    fun aCrashRestartAfterAConnectionReadsReconnecting() {
        // the restart's process has no TUNCONF yet; the run was connected before
        val restarting = ConnectionInputs(running = true, vpnReady = true, everConnected = true)
        assertEquals(ConnectionState.Reconnecting, state(restarting))
        assertEquals(ConnectionState.Connecting, state(restarting.copy(everConnected = false)))
    }

    @Test
    fun aFailureShowsOnlyWithoutARun() {
        assertEquals(ConnectionState.Connecting, state(ConnectionInputs(running = true, failure = "x")))
        assertEquals(ConnectionState.Stopping, state(ConnectionInputs(stopping = true, failure = "x")))
    }

    @Test
    fun powerAndLabels() {
        assertFalse(ConnectionState.Idle.powerIsOn)
        assertFalse(ConnectionState.Failed("x").powerIsOn)
        assertFalse(ConnectionState.Stopping.powerIsOn)
        for (on in listOf(
            ConnectionState.Preparing, ConnectionState.Connecting, ConnectionState.Connected(1),
            ConnectionState.Reconnecting, ConnectionState.WaitingForNetwork, ConnectionState.PausedOnWifi,
            ConnectionState.CaptchaNeeded, ConnectionState.ServerNotResponding,
        )) {
            assertTrue("$on", on.powerIsOn)
            assertEquals("$on", on is ConnectionState.Connected, on.label == "Подключено")
        }
        assertEquals("Подключено • Потоков: 18", tunnelNotificationTitle(ConnectionState.Connected(18)))
        assertEquals("Сервер не отвечает", tunnelNotificationTitle(ConnectionState.ServerNotResponding))
    }

    @Test
    fun vpnIntentGateDropsStaleIntents() {
        val gate = VpnIntentGate()
        val startOld = gate.stamp()
        val stopOld = gate.stamp()
        val startNew = gate.stamp()
        val reloadNew = gate.stamp()
        // the run's RELOAD and START for its current process, in either order
        assertTrue(gate.accept(false, generation = 2, sequence = reloadNew, currentGeneration = 2, runWanted = true))
        assertTrue(gate.accept(false, generation = 2, sequence = startNew, currentGeneration = 2, runWanted = true))
        // the old run's STOP, delivered after the new START: it would tear the new interface down
        assertFalse(gate.accept(true, generation = 1, sequence = stopOld, currentGeneration = 2, runWanted = true))
        // the old run's START: its process is gone
        assertFalse(gate.accept(false, generation = 1, sequence = startOld, currentGeneration = 2, runWanted = true))
        // a START once the run is off, and one with no process
        assertFalse(gate.accept(false, generation = 2, sequence = gate.stamp(), currentGeneration = 2, runWanted = false))
        assertFalse(gate.accept(false, generation = 2, sequence = gate.stamp(), currentGeneration = null, runWanted = true))
        // a STOP made after the last applied START goes through
        assertTrue(gate.accept(true, generation = 2, sequence = gate.stamp(), currentGeneration = 2, runWanted = false))
    }
}
