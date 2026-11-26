package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3 §1.9: a tunnel at rest — the phone slept, the relay dropped its streams, nothing has wanted the tunnel since — is a
 * working tunnel: it makes its streams again when it is needed. The widget, the tile and the notification do not go
 * through «Переподключение…» at every wake of a night.
 */
class DormantIsConnectedTest {
    private val live = ConnectionInputs(running = true, tunconf = true, vpnReady = true, activeWorkers = 10, everConnected = true)

    @Test
    fun `at rest with no stream left is connected, wanted again with none is reconnecting`() {
        assertEquals(ConnectionState.Connected(10), deriveConnectionState(live.copy(dormant = true)))
        assertEquals(ConnectionState.Connected(0), deriveConnectionState(live.copy(activeWorkers = 0, dormant = true)))
        // the screen came on, the core is asked for its streams: the real state from now on
        assertEquals(ConnectionState.Reconnecting, deriveConnectionState(live.copy(activeWorkers = 0)))
        assertEquals(ConnectionState.Connected(7), deriveConnectionState(live.copy(activeWorkers = 7)))
    }

    @Test
    fun `rest never makes a tunnel that was not up look connected`() {
        // never connected in this run, no TUNCONF from this process, no VPN interface: what they were
        assertEquals(
            ConnectionState.Connecting,
            deriveConnectionState(ConnectionInputs(running = true, tunconf = true, vpnReady = true, dormant = true)),
        )
        assertEquals(
            ConnectionState.Reconnecting,
            deriveConnectionState(live.copy(activeWorkers = 0, tunconf = false, dormant = true)),
        )
        assertEquals(
            ConnectionState.Reconnecting,
            deriveConnectionState(live.copy(activeWorkers = 0, vpnReady = false, dormant = true)),
        )
        // and the states above it stay above it
        assertEquals(ConnectionState.Stopping, deriveConnectionState(live.copy(activeWorkers = 0, dormant = true, stopping = true)))
        assertEquals(
            ConnectionState.WaitingForNetwork,
            deriveConnectionState(live.copy(activeWorkers = 0, dormant = true, waitingForNetwork = true)),
        )
        assertEquals(ConnectionState.Idle, deriveConnectionState(ConnectionInputs(dormant = true)))
    }

    @Test
    fun `the notification names no stream count at rest`() {
        assertEquals("Подключено", tunnelNotificationTitle(ConnectionState.Connected(0)))
        assertEquals("Подключено • Потоков: 10", tunnelNotificationTitle(ConnectionState.Connected(10)))
    }

    @Test
    fun `the core's STATS says when it is at rest, and an older core never does`() {
        val prefix = CsqttConstants.General.CSQTT_EVENT_PREFIX
        val resting = TunnelEventParser.parse("""${prefix}STATS|{"active":0,"bytes_up":5,"bytes_down":7,"dormant":true}""")
        assertEquals(TunnelEventParser.Event.Stats(0, 5, 7, dormant = true), resting)
        val older = TunnelEventParser.parse("""${prefix}STATS|{"active":10,"bytes_up":5,"bytes_down":7}""")
        assertEquals(TunnelEventParser.Event.Stats(10, 5, 7, dormant = false), older)
    }

    @Test
    fun `only a start and a repair are work the phone stays awake for`() {
        for (state in listOf(ConnectionState.Preparing, ConnectionState.Connecting, ConnectionState.Reconnecting,
            ConnectionState.ServerNotResponding, ConnectionState.Stopping)) {
            assertTrue("$state", state.isWork)
        }
        for (state in listOf(ConnectionState.Idle, ConnectionState.Connected(10), ConnectionState.Connected(0),
            ConnectionState.WaitingForNetwork, ConnectionState.PausedOnWifi, ConnectionState.CaptchaNeeded,
            ConnectionState.Failed("x"))) {
            assertFalse("$state", state.isWork)
        }
    }
}
