package com.csqtt.client

import android.service.quicksettings.Tile
import com.csqtt.client.ui.legacy.workersLine
import org.junit.Assert.assertEquals
import org.junit.Test

/** The tile, the widget and the power button decide from the connection state (M-44). */
class TileFollowsStateTest {
    @Test
    fun tileStateForEveryState() {
        assertEquals(Tile.STATE_INACTIVE, tileStateFor(ConnectionState.Idle))
        assertEquals(Tile.STATE_INACTIVE, tileStateFor(ConnectionState.Failed("x")))
        assertEquals(Tile.STATE_UNAVAILABLE, tileStateFor(ConnectionState.Stopping))
        for (on in listOf(
            ConnectionState.Preparing, ConnectionState.Connecting, ConnectionState.Connected(10),
            ConnectionState.Reconnecting, ConnectionState.WaitingForNetwork, ConnectionState.PausedOnWifi,
            ConnectionState.CaptchaNeeded, ConnectionState.ServerNotResponding,
        )) {
            assertEquals("$on", Tile.STATE_ACTIVE, tileStateFor(on))
        }
    }

    @Test
    fun onlyAWorkingTunnelIsLabelledConnected() {
        assertEquals("Подключено", ConnectionState.Connected(3).label)
        assertEquals("Подключение…", ConnectionState.Connecting.label)
        assertEquals("Отключено", ConnectionState.Idle.label)
    }

    @Test
    fun homeWorkersLine() {
        assertEquals("Потоки: 18 / 20 · сервер: 30", workersLine(18, 20, 30))
        assertEquals("Потоки: 0 / 20", workersLine(0, 20, null))
    }
}
