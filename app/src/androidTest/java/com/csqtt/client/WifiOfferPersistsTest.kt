package com.csqtt.client

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B5f2 (APP-PLAN §4.2 rules 8 and 11): «Запомнить «<сеть>»?» is the service's offer, held above the screens. It stays on
 * «Подключение» across a tab switch and a recreate, and the answer is stored from the app's scope, under the rule it was
 * offered in.
 */
@RunWith(AndroidJUnit4::class)
class WifiOfferPersistsTest {
    private companion object {
        const val NETWORK = "B5f2-Дом"
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    private val store get() = SettingsStore(InstrumentationRegistry.getInstrumentation().targetContext)

    @Before
    fun aListRuleOn() = runBlocking {
        store.saveAutoPauseOnWifi(true)
        store.saveWifiRule(WifiRule.ONLY_CHECKED)
    }

    @After
    fun backToDefaults() = runBlocking {
        TunnelManager.wifiStatus.value = null
        store.forgetWifiNetwork(NETWORK)
        store.saveWifiRule(WifiRule.ALL)
        store.saveAutoPauseOnWifi(false)
    }

    private fun shown(text: String): Boolean = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theOfferOutlivesTheTabAndTheScreenAndItsAnswerIsStored() {
        // what the service publishes for a run on a Wi-Fi the list does not know yet (no service here)
        TunnelManager.wifiStatus.value = WifiStatus(rule = WifiRule.ONLY_CHECKED, name = NETWORK, offer = NETWORK)
        val question = WifiCopy.offer(NETWORK)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(10_000) { shown(question) && shown(WifiRule.ONLY_CHECKED.rememberEffect) }

            compose.onNodeWithText("Туннель").performClick()
            compose.waitUntil(5_000) { !shown(question) }
            compose.onNodeWithText("Подкл.").performClick()
            compose.waitUntil(5_000) { shown(question) }

            scenario.recreate()
            compose.waitUntil(10_000) { shown(question) }

            compose.onNodeWithText("Запомнить").performClick()
            compose.waitUntil(10_000) {
                runBlocking { store.wifiRules.first() }.let { NETWORK in it.saved && NETWORK in it.checked }
            }
        }
    }
}
