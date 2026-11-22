package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Test

/** B5h2 (B5): a tap on the tile or the widget with «Нужна капча» opens the captcha instead of stopping the tunnel. */
class QuickActionCaptchaTest {
    @Test
    fun theTapInEachState() {
        assertEquals(QuickTap.OPEN_CAPTCHA, quickTapFor(ConnectionState.CaptchaNeeded, stopping = false))
        for (on in listOf(
            ConnectionState.Preparing, ConnectionState.Connecting, ConnectionState.Connected(3),
            ConnectionState.Reconnecting, ConnectionState.WaitingForNetwork, ConnectionState.PausedOnWifi,
            ConnectionState.ServerNotResponding,
        )) {
            assertEquals("$on", QuickTap.STOP, quickTapFor(on, stopping = false))
        }
        assertEquals(QuickTap.START, quickTapFor(ConnectionState.Idle, stopping = false))
        assertEquals(QuickTap.START, quickTapFor(ConnectionState.Failed("x"), stopping = false))
        assertEquals(QuickTap.IGNORE, quickTapFor(ConnectionState.Stopping, stopping = false))
        // a stop under way wins over everything, the captcha too
        assertEquals(QuickTap.IGNORE, quickTapFor(ConnectionState.CaptchaNeeded, stopping = true))
    }
}
