package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** One mapping from the state to what Home says and shows (P3-PLAN §1.3): every state, both layouts, the tap. */
class ConnectionVisualsTest {
    private val states = listOf(
        ConnectionState.Idle, ConnectionState.Preparing, ConnectionState.Connecting, ConnectionState.Connected(10),
        ConnectionState.Reconnecting, ConnectionState.WaitingForNetwork, ConnectionState.PausedOnWifi,
        ConnectionState.Stopping, ConnectionState.CaptchaNeeded, ConnectionState.ServerNotResponding,
        ConnectionState.Failed("VK не принял токен"),
    )

    @Test
    fun theWordIsTheStatesOwn() {
        // what the notification, the tile and the widget say is what Home says
        for (state in states) assertEquals("$state", state.label, connectionVisuals(state).label)
        // the boards' words
        assertEquals("Ожидание сети", ConnectionState.WaitingForNetwork.label)
        assertEquals("Пауза Wi-Fi", ConnectionState.PausedOnWifi.label)
        // a failed run is off; its cause is on a card, not in the state's place
        assertEquals("Отключено", ConnectionState.Failed("x").label)
        assertEquals("Отключено", ConnectionState.Idle.label)
    }

    @Test
    fun theListsDisc() {
        val disc = states.associate { it::class.simpleName to connectionVisuals(it).disc }
        assertEquals(
            mapOf(
                "Idle" to Disc.Off, "Failed" to Disc.Off, "Preparing" to Disc.Busy, "Connecting" to Disc.Busy,
                "Reconnecting" to Disc.Busy, "ServerNotResponding" to Disc.Busy, "Connected" to Disc.On,
                "WaitingForNetwork" to Disc.Dim, "Stopping" to Disc.Dim, "PausedOnWifi" to Disc.Warn,
                "CaptchaNeeded" to Disc.Warn,
            ),
            disc,
        )
    }

    @Test
    fun theStatusColourOfOneConnectionIsTheClassicPanels() {
        val tone = states.associate { it::class.simpleName to connectionVisuals(it).status }
        assertEquals(
            mapOf(
                "Idle" to StatusTone.Muted, "Preparing" to StatusTone.Accent, "Connecting" to StatusTone.Accent,
                "Connected" to StatusTone.Accent, "Failed" to StatusTone.Danger, "ServerNotResponding" to StatusTone.Danger,
                "CaptchaNeeded" to StatusTone.Danger, "Reconnecting" to StatusTone.Warn,
                "WaitingForNetwork" to StatusTone.Warn, "PausedOnWifi" to StatusTone.Warn, "Stopping" to StatusTone.Warn,
            ),
            tone,
        )
    }

    @Test
    fun aTapStartsStopsOrOpensTheCaptcha() {
        assertEquals(Tap.Start, connectionVisuals(ConnectionState.Idle).tap)
        assertEquals(Tap.Start, connectionVisuals(ConnectionState.Failed("x")).tap)
        // the captcha is opened, the run that waits for it is never stopped by the C
        assertEquals(Tap.Captcha, connectionVisuals(ConnectionState.CaptchaNeeded).tap)
        assertEquals(Tap.Nothing, connectionVisuals(ConnectionState.Stopping).tap)
        for (state in states.filter { it.powerIsOn && it != ConnectionState.CaptchaNeeded }) {
            assertEquals("$state", Tap.Stop, connectionVisuals(state).tap)
            // the wait after a stop refuses a start, never a stop
            assertEquals("$state", Tap.Stop, connectionVisuals(state, cooldown = true).tap)
        }
    }

    @Test
    fun theWaitAfterAStopRefusesAStartOnlyWhenItWouldBeOne() {
        val waiting = connectionVisuals(ConnectionState.Idle, cooldown = true)
        assertEquals(LABEL_COOLDOWN, waiting.label)
        assertEquals(Tap.Nothing, waiting.tap)
        assertEquals(Disc.Dim, waiting.disc)
        assertEquals(StatusTone.Warn, waiting.status)
        assertEquals(Tap.Nothing, connectionVisuals(ConnectionState.Failed("x"), cooldown = true).tap)
        // settings that are not complete: the tap leads to them, so it is taken at once
        assertEquals(Tap.Start, connectionVisuals(ConnectionState.Idle, cooldown = true, configurationValid = false).tap)
        assertEquals(Tap.Start, connectionVisuals(ConnectionState.Idle, configurationValid = false).tap)
    }

    @Test
    fun theHintIsSaidOnlyWhereItIsTrue() {
        assertEquals(R.string.home_hint_start, connectionVisuals(ConnectionState.Idle).hint)
        // with settings missing a tap does not connect, and the card under the button says what to do
        assertNull(connectionVisuals(ConnectionState.Idle, configurationValid = false).hint)
        assertNull(connectionVisuals(ConnectionState.Idle, cooldown = true).hint)
        assertNull(connectionVisuals(ConnectionState.Failed("x")).hint)
        assertEquals(R.string.home_hint_paused, connectionVisuals(ConnectionState.PausedOnWifi).hint)
        assertEquals(R.string.home_hint_captcha, connectionVisuals(ConnectionState.CaptchaNeeded).hint)
        for (state in listOf(ConnectionState.Connecting, ConnectionState.Connected(3), ConnectionState.Reconnecting, ConnectionState.Stopping)) {
            assertNull("$state", connectionVisuals(state).hint)
        }
    }
}
