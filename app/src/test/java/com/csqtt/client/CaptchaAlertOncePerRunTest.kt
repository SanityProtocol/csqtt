package com.csqtt.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B5g (the operator, 2026-11-22: "one alert per run is fine"): a captcha for more workers is asked every ~2 min while the
 * run is connected, each ask a new episode with its own notice; only the run's first notice alerts.
 */
class CaptchaAlertOncePerRunTest {
    @Test
    fun theRunsFirstNoticeAlertsAndLaterEpisodesStayQuiet() {
        val episode = CaptchaEpisode()
        assertTrue(episode.ask(appInFront = false))
        assertFalse(episode.takeQuiet())
        // the run connected with the workers it has: the need ends, the core asks again later
        episode.askEnded(solved = false)
        assertTrue(episode.settled())
        assertTrue(episode.ask(appInFront = false))
        assertTrue(episode.takeQuiet())
        episode.askEnded(solved = true)
        assertTrue(episode.ask(appInFront = false))
        assertTrue(episode.takeQuiet())
    }

    @Test
    fun aNewRunAlertsAgain() {
        val episode = CaptchaEpisode()
        assertTrue(episode.ask(appInFront = false))
        assertFalse(episode.takeQuiet())
        // a stop, a new start or a switch (cancelCaptcha) ends the run's episode
        episode.stop()
        assertTrue(episode.ask(appInFront = false))
        assertFalse(episode.takeQuiet())
    }

    @Test
    fun anEpisodeTheUserSawInTheAppLeavesTheAlertForTheFirstNoticeActuallyPosted() {
        val episode = CaptchaEpisode()
        // asked while the app was in front: the screen opened, no notice posted, nothing alerted
        assertFalse(episode.ask(appInFront = true))
        episode.askEnded(solved = true)
        assertTrue(episode.ask(appInFront = false))
        assertFalse(episode.takeQuiet())
    }
}
