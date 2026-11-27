package com.csqtt.client

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.csqtt.client.ShellTestKit.recreateFromSavedStateAlone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P3 B3 (U4, the canvas's F-RiskDialog): the warning before «Авто ВК» or TCP is taken is still there, with the choice
 * it was asked for, after the screen is made again; the choice is stored only on «Понятно», «Отмена» leaves everything
 * as it was, and «Не напоминать» is remembered.
 */
@RunWith(AndroidJUnit4::class)
class RiskDialogSurvivesTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun noSystemPromptsOverTheApp() = ShellTestKit.noSystemPromptsOverTheApp()

        private const val SLOT = 18
        private const val WARNING = "Внимание!"
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store by lazy { SettingsStore(context) }
    private val slot by lazy { SettingsStore(context, ProfileScope.Fixed(SLOT)) }
    private var autoVkBefore = false
    private var tcpBefore = false

    @Before
    fun aProfileThatWasNeverWarned() = runBlocking {
        autoVkBefore = store.autoJsRiskAcknowledged.first()
        tcpBefore = store.tcpTransportRiskAcknowledged.first()
        store.saveAutoJsRiskAcknowledged(false)
        store.saveTcpTransportRiskAcknowledged(false)
        store.clearProfile(SLOT)
        store.saveActiveProfile(SLOT)
        store.saveCsqttLinkMode(false)
    }

    @After
    fun restore() = runBlocking {
        store.saveActiveProfile(0)
        store.deleteProfile(SLOT)
        store.saveAutoJsRiskAcknowledged(autoVkBefore)
        store.saveTcpTransportRiskAcknowledged(tcpBefore)
    }

    private fun shown(text: String): Boolean = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    private fun hashMode() = runBlocking { slot.vkHashMode.first() }
    private fun authMode() = runBlocking { slot.vkAuthMode.first() }
    private fun transport() = runBlocking { slot.turnTransport.first() }

    /** Opens the select that shows [current] and picks [label]: the menu's item is the last node with the text. */
    private fun pick(current: String, label: String) {
        compose.onNodeWithText(current).performScrollTo().performClick()
        compose.waitUntil(5_000) { shown(label) }
        compose.onAllNodes(hasText(label)).onLast().performClick()
    }

    @Test
    fun theWarningAndItsChoiceOutliveTheScreenAndOnlyTheAnswerStores() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(10_000) { shown("Туннель") }
            compose.onNodeWithText("Туннель").performClick()
            compose.waitUntil(10_000) { shown("Режим хешей") }

            // «Авто ВК» is asked about first; the screen is made again; nothing is stored until the answer
            pick("Ручной", "Авто ВК")
            compose.waitUntil(5_000) { shown(WARNING) }
            assertEquals(CsqttConstants.VkAutoHash.MODE_MANUAL, hashMode())
            scenario.recreateFromSavedStateAlone()
            compose.waitUntil(10_000) { shown(WARNING) }
            assertEquals(CsqttConstants.VkAutoHash.MODE_MANUAL, hashMode())
            compose.onNodeWithText("Понятно").performClick()
            compose.waitUntil(5_000) { hashMode() == CsqttConstants.VkAutoHash.MODE_AUTO_JS && authMode() == CsqttConstants.VkAuth.MODE_AUTO_JS }
            assertFalse("not ticked, not remembered", runBlocking { store.autoJsRiskAcknowledged.first() })
            compose.waitUntil(5_000) { !shown(WARNING) }

            // «Отмена» leaves the transport as it was
            compose.onNodeWithText("Дополнительно").performScrollTo().performClick()
            compose.waitUntil(5_000) { shown("Транспорт") }
            pick("UDP", "TCP")
            compose.waitUntil(5_000) { shown(WARNING) }
            compose.onNodeWithText("Отмена").performClick()
            compose.waitUntil(5_000) { !shown(WARNING) }
            assertEquals(CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT, transport())

            // «Не напоминать» with «Понятно»: taken, and not asked again. This question too is still there after the
            // screen was made again: it is one route for every kind of it
            pick("UDP", "TCP")
            compose.waitUntil(5_000) { shown(WARNING) }
            scenario.recreateFromSavedStateAlone()
            compose.waitUntil(10_000) { shown(WARNING) }
            assertEquals(CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT, transport())
            compose.onNodeWithText("Не напоминать").performClick()
            compose.onNodeWithText("Понятно").performClick()
            // two writes, the choice and then the tick
            compose.waitUntil(5_000) {
                transport() == CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS && runBlocking { store.tcpTransportRiskAcknowledged.first() }
            }
            compose.waitUntil(5_000) { shown("Обход DPI (Gooby)") }
            pick("TCP", "UDP")
            compose.waitUntil(5_000) { transport() == CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT }
            pick("UDP", "TCP")
            compose.waitUntil(5_000) { transport() == CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS }
            assertFalse(shown(WARNING))
        }
    }
}
