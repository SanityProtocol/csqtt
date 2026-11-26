package com.csqtt.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whatever Home tells or asks is in both of its layouts (P3-PLAN §1.3a: a layout never hides a question). The notices are
 * one list, made here; each layout draws it by a `when` over the sealed kinds with no `else`, so a kind one of them does
 * not draw does not compile.
 */
class QuestionsInBothLayoutsTest {
    private fun notices(
        state: ConnectionState,
        notifications: Boolean = true,
        wifi: WifiStatus? = null,
        linkMissing: Boolean = false,
        subscription: SubscriptionMeta? = null,
        handOver: HandOverResult? = null,
    ) = homeNotices(state, notifications, wifi, linkMissing, subscription, handOver)

    @Test
    fun aWorkingTunnelWithNothingToSayHasNoNotice() {
        assertEquals(emptyList<HomeNotice>(), notices(ConnectionState.Connected(10)))
        assertEquals(emptyList<HomeNotice>(), notices(ConnectionState.Idle))
    }

    @Test
    fun aStopTheUserDidNotAskForSaysItsCauseAndTheWayOut() {
        assertEquals(
            listOf(HomeNotice.Stopped(StopCause.VK_SIGN_IN_AGAIN, vkSignIn = true, appSettings = false)),
            notices(ConnectionState.Failed(StopCause.VK_SIGN_IN_AGAIN)),
        )
        for (cause in listOf(StopCause.SYSTEM_KILLED, StopCause.STOPPED_OUTSIDE)) {
            assertEquals(listOf(HomeNotice.Stopped(cause, vkSignIn = false, appSettings = true)), notices(ConnectionState.Failed(cause)))
        }
        assertEquals(
            listOf(HomeNotice.Stopped(StopCause.DEVICE_LIMIT, vkSignIn = false, appSettings = false)),
            notices(ConnectionState.Failed(StopCause.DEVICE_LIMIT)),
        )
    }

    @Test
    fun theCaptchaAndTheNotificationsLine() {
        assertEquals(listOf(HomeNotice.Captcha), notices(ConnectionState.CaptchaNeeded))
        // without notifications the captcha prompt cannot show: said whatever the state is
        assertEquals(listOf(HomeNotice.NotificationsOff), notices(ConnectionState.Idle, notifications = false))
        assertEquals(
            listOf(HomeNotice.NotificationsOff, HomeNotice.Captcha),
            notices(ConnectionState.CaptchaNeeded, notifications = false),
        )
    }

    @Test
    fun theWifiQuestions() {
        val rule = WifiRule.ONLY_CHECKED
        assertEquals(
            listOf(HomeNotice.PausedOn("Дом")),
            notices(ConnectionState.PausedOnWifi, wifi = WifiStatus(rule, name = "Дом")),
        )
        // the network's name is said for a pause only
        assertEquals(emptyList<HomeNotice>(), notices(ConnectionState.Connected(3), wifi = WifiStatus(rule, name = "Дом")))
        assertEquals(
            listOf(HomeNotice.WifiNameUnknown),
            notices(ConnectionState.Connected(3), wifi = WifiStatus(rule, nameUnknown = true)),
        )
        assertEquals(
            listOf(HomeNotice.WifiOffer("Office", rule)),
            notices(ConnectionState.Connected(3), wifi = WifiStatus(rule, name = "Office", offer = "Office")),
        )
    }

    @Test
    fun aProfileThatWaitsForItsLinkSaysSoWhileNothingRuns() {
        assertEquals(listOf(HomeNotice.NeedLink), notices(ConnectionState.Idle, linkMissing = true))
        assertEquals(listOf(HomeNotice.NeedLink, HomeNotice.Stopped("x", vkSignIn = false, appSettings = false)).toSet(),
            notices(ConnectionState.Failed("x"), linkMissing = true).toSet())
        // a run on another profile is not this profile's missing link
        assertEquals(listOf<HomeNotice>(), notices(ConnectionState.Connected(3), linkMissing = true))
        assertEquals(listOf<HomeNotice>(), notices(ConnectionState.Stopping, linkMissing = true))
        assertEquals(listOf<HomeNotice>(), notices(ConnectionState.Idle))
    }

    @Test
    fun theOutcomesOfBackgroundWork() {
        val failed = SubscriptionMeta(title = "Мой VPN", lastError = "Сертификат сервера подписки изменился")
        val skipped = SubscriptionMeta(title = "Мой VPN", lastSkipped = "2 узла пропущены")
        val said = notices(ConnectionState.Idle, subscription = failed).single() as HomeNotice.Outcome
        assertTrue(said.bad)
        assertTrue("Мой VPN" in said.text)
        val warned = notices(ConnectionState.Idle, subscription = skipped).single() as HomeNotice.Outcome
        assertFalse(warned.bad)
        assertTrue(warned.warn)
        // a refresh that went well says nothing
        assertEquals(emptyList<HomeNotice>(), notices(ConnectionState.Idle, subscription = SubscriptionMeta(title = "Мой VPN")))
        val handed = notices(ConnectionState.Idle, handOver = HandOverResult(ok = true, text = "Хеши переданы серверу", at = 0L)).single() as HomeNotice.Outcome
        assertFalse(handed.bad || handed.warn)
        assertTrue((notices(ConnectionState.Idle, handOver = HandOverResult(false, "Сервер не принял хеши", 0L)).single() as HomeNotice.Outcome).bad)
    }

    @Test
    fun eachLayoutDrawsEveryKindByAnExhaustiveWhen() {
        val kinds = listOf("NotificationsOff", "Stopped", "Captcha", "PausedOn", "WifiNameUnknown", "WifiOffer", "NeedLink", "Outcome")
        // the kinds this test knows are the kinds there are
        val declared = Regex("""(?:data object|data class) (\w+)""").findAll(
            source("HomeNotices.kt", ui = false).substringAfter("sealed interface HomeNotice").substringBefore("\ninternal fun homeNotices"),
        ).map { it.groupValues[1] }.toList()
        assertEquals(kinds.sorted(), declared.sorted())
        for (layout in listOf("HomeOne.kt", "HomeList.kt")) {
            val whens = noticeWhens(source(layout, ui = true))
            assertTrue("$layout draws the notices", whens.isNotEmpty())
            for (branches in whens) {
                assertFalse("$layout: an else would let a new kind go undrawn", branches.any { it.trimStart().startsWith("else") })
                for (kind in kinds) assertTrue("$layout: $kind", branches.any { "HomeNotice.$kind" in it })
            }
        }
    }

    /** Each `when (notice) {` of [text]: the lines of its own branches, those one step in from it. */
    private fun noticeWhens(text: String): List<List<String>> {
        val lines = text.lines()
        fun indent(line: String) = line.length - line.trimStart().length
        return lines.withIndex().filter { it.value.trimEnd().endsWith("when (notice) {") }.map { (start, head) ->
            val depth = indent(head)
            lines.drop(start + 1).takeWhile { !(it.trim() == "}" && indent(it) == depth) }
                .filter { it.isNotBlank() && indent(it) == depth + 4 }
        }
    }

    private fun source(name: String, ui: Boolean): String {
        val path = "src/main/java/com/csqtt/client/" + (if (ui) "ui/home/" else "") + name
        return listOf(File("app/$path"), File(path)).first(File::isFile).readText()
    }
}
