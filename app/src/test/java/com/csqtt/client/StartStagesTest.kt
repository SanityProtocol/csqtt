package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A start says what it is doing (P3-PLAN §1.3a; the operator, 2026-11-26). */
class StartStagesTest {
    private val connecting = ConnectionState.Connecting

    @Test
    fun theThreeStagesComeInOrderFromTheProgressModel() {
        val progress = TunnelStartupProgress()
        val seen = mutableListOf<StartStage?>()
        // the service prepares, no process yet: VK's access is what it is after
        seen += startStage(ConnectionState.Preparing, progress = null, tunconf = false)
        seen += startStage(connecting, progress.stage(), tunconf = false)
        progress.credentialReceived()
        seen += startStage(connecting, progress.stage(), tunconf = false)
        progress.streamReady()
        seen += startStage(connecting, progress.stage(), tunconf = false)
        assertEquals(listOf(StartStage.VkAccess, StartStage.VkAccess, StartStage.Server, StartStage.Vpn), seen)
        // never backwards: credentials for more streams, after one is ready, change nothing
        progress.credentialReceived()
        assertEquals(StartStage.Vpn, startStage(connecting, progress.stage(), tunconf = false))
    }

    @Test
    fun theTunconfAloneLeavesOnlyTheVpn() {
        assertEquals(StartStage.Vpn, startStage(connecting, TunnelStartupStage.WAITING_FOR_CREDENTIALS, tunconf = true))
        assertEquals(StartStage.Vpn, startStage(connecting, TunnelStartupStage.WAITING_FOR_TURN_OR_PEER, tunconf = true))
    }

    @Test
    fun onlyAStartHasAStage() {
        for (state in listOf(
            ConnectionState.Idle, ConnectionState.Connected(10), ConnectionState.Reconnecting,
            ConnectionState.WaitingForNetwork, ConnectionState.PausedOnWifi, ConnectionState.Stopping,
            ConnectionState.CaptchaNeeded, ConnectionState.ServerNotResponding, ConnectionState.Failed("x"),
        )) {
            assertNull("$state", startStage(state, TunnelStartupStage.READY, tunconf = true))
        }
    }

    @Test
    fun theStreamsAreSaidOnceTheCoreRuns() {
        assertTrue(StreamsLine.shown(connecting, dormant = false, active = 0, running = 10, fullSince = null, nowMs = 0))
        // while the service still prepares there is no core, and no count of its streams to say
        assertFalse(StreamsLine.shown(ConnectionState.Preparing, dormant = false, active = 0, running = 0, fullSince = null, nowMs = 0))
    }

    @Test
    fun aReadyRaisesTheCountAndTheNextReportSetsIt() {
        // three by the core's last report, one more said it is ready since
        assertEquals(4, streamsShown(active = 3, readySince = 1, running = 10))
        // the report that follows has it, and what was ahead of the report is dropped
        assertEquals(4, streamsShown(active = 4, readySince = 0, running = 10))
        // never above what the core runs, the server's limit when that is lower
        assertEquals(10, streamsShown(active = 9, readySince = 3, running = 10))
        assertEquals(6, streamsShown(active = 5, readySince = 4, running = StreamsLine.running(target = 10, serverLimit = 6)))
        // without a run there is nothing to raise
        assertEquals(0, streamsShown(active = 0, readySince = 2, running = 0))
    }
}
