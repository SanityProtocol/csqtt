package com.csqtt.client

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Card p3-apply-bar (the operator, 2026-11-28: «Bar with «Переподключить»», «That bar - for every change that require a
 * reconnect»). A setting the running tunnel keeps from its start is changed: the bar comes at that moment, is on every
 * tab, and goes when the value is put back or the tunnel has reconnected; the changed row says what the tunnel uses
 * now. «Переподключить» asks the service for the reconnect once — or, with settings a start would refuse, leaves the
 * tunnel alone and opens «Туннель» on what is missing (the operator: «Keep the tunnel, show what is missing»).
 */
@RunWith(AndroidJUnit4::class)
class ApplyBarTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun noSystemPromptsOverTheApp() = ShellTestKit.noSystemPromptsOverTheApp()

        private const val SLOT = 22
        private const val REFUSED = "Переподключение не начато: исправьте поля ниже"
        private const val MESSAGE = "Проверка места сообщения"
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store by lazy { SettingsStore(context) }
    private val slot by lazy { SettingsStore(context, ProfileScope.Fixed(SLOT)) }
    private lateinit var layoutBefore: HomeLayout
    private val sent = mutableListOf<Intent>()
    private val sendBefore = reconnectSend

    // an own-server profile a start would take: an address, a password, a hash; the tunnel on UDP
    @Before
    fun aProfileThatCanStart() = runBlocking {
        layoutBefore = store.homeLayout.first()
        store.saveHomeLayout(HomeLayout.One)
        store.clearProfile(SLOT)
        store.saveActiveProfile(SLOT)
        store.saveCsqttLinkMode(false)
        slot.saveFormAddress("203.0.113.1", null, store.profileEpoch.first())
        slot.saveConnectionPassword("pw-run")
        slot.saveVkHashesOnly("H".repeat(20))
        // there is no tunnel to reconnect: what «Переподключить» sends to the service is recorded
        reconnectSend = { _, intent -> sent += intent }
    }

    @After
    fun restore() = runBlocking {
        reconnectSend = sendBefore
        ShellTestKit.tunnelOff()
        store.saveActiveProfile(0)
        store.deleteProfile(SLOT)
        store.saveHomeLayout(layoutBefore)
    }

    private fun shown(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()
    private fun bars(): Int = compose.onAllNodes(hasText(ShellTestKit.BAR)).fetchSemanticsNodes().size
    private fun transport(): String = runBlocking { slot.turnTransport.first() }

    private fun openMore() {
        compose.waitUntil(10_000) { shown("Туннель") }
        compose.onNodeWithText("Туннель").performClick()
        compose.waitUntil(10_000) { shown("Дополнительно") }
        compose.onNodeWithText("Дополнительно").performScrollTo().performClick()
        compose.waitUntil(5_000) { shown("Транспорт") }
    }

    /** Opens the choice that shows [current] and picks [label]. */
    private fun pick(current: String, label: String) {
        compose.onNodeWithText(current).performScrollTo().performClick()
        compose.waitUntil(5_000) { shown(label) }
        compose.onAllNodes(hasText(label)).onLast().performClick()
    }

    @Test
    fun aChangedSettingRaisesTheBarOnEveryTabUntilItIsPutBack() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openMore()
            ShellTestKit.tunnelOn(SLOT)
            compose.waitForIdle()
            assertEquals("nothing was changed", 0, bars())

            // the operator's own case: connected on UDP here, TCP picked — the answer comes at once, at the control
            pick("UDP", "TCP")
            compose.waitUntil(5_000) { transport() == CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS }
            compose.waitUntil(5_000) { bars() == 1 && shown("Сейчас: UDP") }
            assertTrue(shown(ShellTestKit.RECONNECT))
            assertTrue("no reconnect by itself", sent.isEmpty())

            // the bar is the shell's: on every tab, the old tree's too
            for (tab in listOf("Главная", "Логи", "Маршруты")) {
                compose.onNodeWithText(tab).performClick()
                compose.waitForIdle()
                compose.waitUntil(5_000) { bars() == 1 }
            }

            // put back: the bar and the row's line go («Дополнительно» is as it was left: open)
            compose.onNodeWithText("Туннель").performClick()
            compose.waitUntil(5_000) { shown("Сейчас: UDP") }
            pick("TCP", "UDP")
            compose.waitUntil(5_000) { transport() == CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT }
            compose.waitUntil(5_000) { bars() == 0 && !shown("Сейчас", substring = true) }
        }
    }

    @Test
    fun theTapAsksTheServiceForOneReconnectAndTheBarWaitsForIt() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openMore()
            ShellTestKit.tunnelOn(SLOT)
            pick("UDP", "TCP")
            compose.waitUntil(5_000) { bars() == 1 }

            compose.onNodeWithText(ShellTestKit.RECONNECT).performClick()
            compose.waitUntil(5_000) { sent.size == 1 }
            assertEquals(CsqttConstants.General.ACTION_SWITCH_PROFILE, sent.single().action)
            assertEquals("the switch a pick makes, onto the run's own profile", SLOT, TunnelManager.switchTarget)
            // until the next connection takes over the bar says so, and offers nothing to tap twice
            compose.waitUntil(5_000) { shown(ShellTestKit.RECONNECTING) }
            assertFalse(shown(ShellTestKit.RECONNECT))
            assertEquals(0, bars())

            // the connection the service made from the store: its record is the store's, so nothing waits
            ShellTestKit.tunnelOn(SLOT)
            TunnelManager.switchTarget = null
            TunnelManager.reconnecting.value = false
            compose.waitUntil(5_000) { !shown(ShellTestKit.RECONNECTING) && bars() == 0 }
            assertFalse(shown("Сейчас", substring = true))
            assertEquals(1, sent.size)
        }
    }

    /** Where the message strip ends: the block that is read out, with [MESSAGE] in it, and the strip's 12 dp under it. */
    private fun messageBottom(): Float = compose.onNode(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion) and hasAnyChild(hasText(MESSAGE)), useUnmergedTree = true,
    ).fetchSemanticsNode().boundsInRoot.bottom + 12 * context.resources.displayMetrics.density

    private fun top(text: String): Float = compose.onAllNodes(hasText(text)).onFirst().fetchSemanticsNode().boundsInRoot.top

    /**
     * A message is 16 dp over the bars at the bottom — the tab bar, and the apply bar when it is on it — and never on
     * them: the bars' height reaches it where it is laid out, since it changes with every frame while the bar opens.
     */
    @Test
    fun aMessageStaysOverTheBarsWithTheApplyBarAndWithoutIt() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { shown("Главная") }
            val messages = (context.applicationContext as CsqttApplication).owners.messages
            val density = context.resources.displayMetrics.density
            ShellTestKit.tunnelOn(SLOT)
            compose.waitForIdle()

            // the tab bar alone: the strip ends 16 dp over it (a tab's ground begins 8 dp under the bar's top)
            messages.show(MESSAGE)
            compose.waitUntil(5_000) { shown(MESSAGE) }
            compose.waitForIdle()
            val tabBarTop = compose.onAllNodes(hasText("Главная") and hasClickAction()).onFirst().fetchSemanticsNode().boundsInRoot.top - 8 * density
            val alone = messageBottom()
            assertEquals("16 dp over the tab bar", tabBarTop - 16 * density, alone, 1.5f)

            // the apply bar comes: the strip goes up by the bar's height, and ends 16 dp over it
            runBlocking { slot.saveTurnTransport(CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS) }
            compose.waitUntil(5_000) { bars() == 1 }
            compose.waitForIdle()
            messages.show(MESSAGE)
            compose.waitUntil(5_000) { shown(MESSAGE) }
            compose.waitForIdle()
            val withBar = messageBottom()
            val barText = top(ShellTestKit.BAR)
            assertTrue("the strip is over the apply bar, not on it: $withBar, the bar's text at $barText", withBar < barText)
            // the bar is 60 dp at this size and font: its text and its button are lower than that
            assertEquals("up by the bar's height", alone - 60 * density, withBar, 1.5f)

            // and back down when the bar goes
            runBlocking { slot.saveTurnTransport(CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT) }
            compose.waitUntil(5_000) { bars() == 0 }
            compose.waitForIdle()
            messages.show(MESSAGE)
            compose.waitUntil(5_000) { shown(MESSAGE) }
            compose.waitForIdle()
            assertEquals(alone, messageBottom(), 1.5f)
        }
    }

    @Test
    fun withSettingsAStartWouldRefuseTheTunnelIsLeftAndTheFormSaysWhatIsMissing() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { shown("Главная") }
            ShellTestKit.tunnelOn(SLOT)
            // the address emptied under the running tunnel: a change that waits, and one no start would take
            runBlocking { slot.saveFormAddress("", null, store.profileEpoch.first()) }
            compose.waitUntil(5_000) { bars() == 1 }

            compose.onNodeWithText(ShellTestKit.RECONNECT).performClick()
            // «Туннель» opens on what the form lacks, and says it is the reconnect that was not made
            compose.waitUntil(5_000) { shown(REFUSED) }
            assertTrue(compose.onAllNodes(hasSetTextAction() and hasText("Адрес сервера")).fetchSemanticsNodes().isNotEmpty())
            assertTrue("the running tunnel is not touched", sent.isEmpty())
            assertFalse(TunnelManager.reconnecting.value)
            assertTrue("the tunnel is still on, and the change still waits", TunnelManager.running.value && bars() == 1)
        }
    }
}
