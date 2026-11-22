package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B5h2 (B4): one captcha episode per need. The core asks again after its 60 s captcha lock and its auto chain, so an ask
 * that times out or fails keeps the need; a solved captcha, the run connecting or getting credentials, or a stop end it.
 */
class CaptchaEpisodeTest {
    @Test
    fun anAskOpensTheEpisodeAndATimeoutOrAnErrorKeepsIt() {
        val episode = CaptchaEpisode()
        episode.ask(appInFront = false)
        assertTrue(episode.open)
        assertTrue(episode.live)

        assertFalse(episode.askEnded(solved = false))
        assertTrue(episode.open)
        assertFalse(episode.live)
    }

    @Test
    fun aSolvedCaptchaEndsIt() {
        val episode = CaptchaEpisode()
        episode.ask(appInFront = false)
        assertTrue(episode.askEnded(solved = true))
        assertFalse(episode.open)
    }

    @Test
    fun connectingOrCredentialsEndItOnlyWithNoAskLive() {
        val episode = CaptchaEpisode()
        episode.ask(appInFront = false)
        // an ask is live: the user may be solving it right now
        assertFalse(episode.settled())
        assertTrue(episode.open)

        episode.askEnded(solved = false)
        assertTrue(episode.settled())
        assertFalse(episode.open)
        // nothing open, nothing to end
        assertFalse(episode.settled())
    }

    @Test
    fun aStopEndsItWithAnAskLive() {
        val episode = CaptchaEpisode()
        episode.ask(appInFront = false)
        assertTrue(episode.stop())
        assertFalse(episode.open)
        assertFalse(episode.live)
        assertFalse(episode.stop())
    }

    @Test
    fun oneNotificationPerEpisode() {
        val episode = CaptchaEpisode()
        assertTrue(episode.ask(appInFront = false))
        episode.askEnded(solved = false)
        // the core's next ask in the same episode posts nothing more
        assertFalse(episode.ask(appInFront = false))
        // solved: a later need is a new episode, with its own notification
        episode.askEnded(solved = true)
        assertTrue(episode.ask(appInFront = false))
    }

    @Test
    fun theFirstAskThatFindsTheAppNotInFrontPostsIt() {
        val episode = CaptchaEpisode()
        assertFalse(episode.ask(appInFront = true))
        episode.askEnded(solved = false)
        assertTrue(episode.ask(appInFront = false))
    }

    @Test
    fun aWorkingTunnelIsNotHiddenByACaptchaForMoreWorkers() {
        val live = ConnectionInputs(running = true, tunconf = true, vpnReady = true, activeWorkers = 6, captchaNeeded = true)
        assertEquals(ConnectionState.Connected(6), deriveConnectionState(live))
        assertEquals(
            ConnectionState.CaptchaNeeded,
            deriveConnectionState(live.copy(activeWorkers = 0, everConnected = true)),
        )
        assertEquals(ConnectionState.CaptchaNeeded, deriveConnectionState(ConnectionInputs(starting = true, captchaNeeded = true)))
        // no network: the captcha cannot load either
        assertEquals(
            ConnectionState.WaitingForNetwork,
            deriveConnectionState(live.copy(activeWorkers = 0, waitingForNetwork = true)),
        )
        // a silent server is said, not hidden by a captcha that cannot help it (review F4)
        assertEquals(
            ConnectionState.ServerNotResponding,
            deriveConnectionState(live.copy(activeWorkers = 0, serverSilent = true)),
        )
        assertEquals(ConnectionState.Stopping, deriveConnectionState(live.copy(stopping = true)))
        assertEquals(ConnectionState.Idle, deriveConnectionState(ConnectionInputs(captchaNeeded = true)))
    }
}
