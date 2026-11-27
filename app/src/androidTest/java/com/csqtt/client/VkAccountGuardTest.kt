package com.csqtt.client

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * «Аккаунты VK» with the tunnel on (M-79): the account is not changed. A run in «Авто API» or «Авто ВК» reads the stored
 * account again each time VK ends a call and it makes its calls anew, so an account picked or a token pasted under it
 * would be taken in the middle of the run — and a token VK refuses would stop a tunnel that was working (card
 * p3-b3-notes, the verify's finding 1: the guard was lifted for a round, on a fact that is not one).
 */
@RunWith(AndroidJUnit4::class)
class VkAccountGuardTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun noSystemPromptsOverTheApp() = ShellTestKit.noSystemPromptsOverTheApp()

        private const val SLOT = 21
        private const val FIRST = "910000001"
        private const val SECOND = "910000002"
        private const val STOP_FIRST = "Для смены аккаунта остановите туннель."
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store by lazy { SettingsStore(context) }
    private val slot by lazy { SettingsStore(context, ProfileScope.Fixed(SLOT)) }
    private fun token(tail: Char) = "vk1.a." + tail.toString().repeat(40)

    // an owner in «Авто API» with two accounts signed in on the phone, on the second of them
    @Before
    fun twoAccounts() = runBlocking {
        store.clearProfile(SLOT)
        store.saveActiveProfile(SLOT)
        store.saveCsqttLinkMode(false)
        slot.saveVkHashMode(CsqttConstants.VkAutoHash.MODE_AUTO_API)
        slot.saveVkAccessToken(token('a'), FIRST)
        slot.saveVkAccessToken(token('b'), SECOND)
    }

    @After
    fun restore() = runBlocking {
        TunnelManager.running.value = false
        slot.removeVkAccount(FIRST)
        slot.removeVkAccount(SECOND)
        store.saveActiveProfile(0)
        store.deleteProfile(SLOT)
    }

    private fun shown(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun account(): String = runBlocking { slot.vkAccessTokenUserId.first() }
    private fun stored(): String = runBlocking { slot.vkAccessToken.first() }

    @Test
    fun theAccountIsNotChangedWhileTheTunnelRuns() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { shown("Туннель") }
            compose.onNodeWithText("Туннель").performClick()
            compose.waitUntil(10_000) { shown("Аккаунт VK") }
            compose.onNodeWithText("Аккаунт VK").performScrollTo().performClick()
            compose.waitUntil(5_000) { shown("Аккаунты VK") && shown("id $FIRST", substring = true) }
            assertEquals(SECOND, account())
            // the tunnel as the manager would say it, with no tunnel: the screen reads nothing else
            TunnelManager.running.value = true
            compose.waitUntil(5_000) { shown(STOP_FIRST) }

            // the other account, saved on this phone: not taken, and the sheet says why
            compose.onNodeWithText("id $FIRST", substring = true).performScrollTo().performClick()
            compose.waitUntil(5_000) { shown("Остановите туннель перед сменой аккаунта") }
            assertEquals(SECOND, account())

            // a token pasted by hand: not stored
            compose.onAllNodes(hasSetTextAction()).onLast().performScrollTo().performTextReplacement(token('c'))
            compose.onNodeWithText("Подключить").performScrollTo().performClick()
            compose.waitForIdle()
            Thread.sleep(300)
            assertEquals(token('b'), stored())
            assertEquals(SECOND, account())

            // the tunnel off: the same pick is taken
            TunnelManager.running.value = false
            compose.waitUntil(5_000) { !shown(STOP_FIRST) }
            compose.onNodeWithText("id $FIRST", substring = true).performScrollTo().performClick()
            compose.waitUntil(5_000) { account() == FIRST }
            assertEquals(token('a'), stored())
        }
    }
}
