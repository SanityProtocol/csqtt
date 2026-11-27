package com.csqtt.client

import androidx.compose.ui.test.hasContentDescription
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * «Аккаунты VK» with the tunnel on (the operator, 2026-11-27, asked with the facts: «Pick/paste allowed»). A run reads
 * its account once, at its start: another saved account is picked and a token is pasted while it runs, each stored for
 * the next connection and saying so. A new sign-in and a deletion would clear the phone's VK session, which a run in
 * «Авто ВК» lives on: they still ask for the tunnel to be stopped, and do nothing.
 */
@RunWith(AndroidJUnit4::class)
class VkAccountWhileConnectedTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun noSystemPromptsOverTheApp() = ShellTestKit.noSystemPromptsOverTheApp()

        private const val SLOT = 21
        private const val FIRST = "910000001"
        private const val SECOND = "910000002"
        private const val NEXT = "Аккаунт применится при следующем подключении"
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

    @Test
    fun anAccountIsPickedAndATokenPastedForTheNextConnectionAndASignInOrADeletionWaitsForAStop() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { shown("Туннель") }
            compose.onNodeWithText("Туннель").performClick()
            compose.waitUntil(10_000) { shown("Аккаунт VK") }
            compose.onNodeWithText("Аккаунт VK").performScrollTo().performClick()
            compose.waitUntil(5_000) { shown("Аккаунты VK") && shown("id $FIRST", substring = true) }
            assertEquals(SECOND, account())
            // the tunnel as the manager would say it, with no tunnel: the screen reads nothing else
            TunnelManager.running.value = true
            compose.waitUntil(5_000) { shown("Туннель включён: выбранный аккаунт применится", substring = true) }

            // the other account, saved on this phone: taken, for the next connection
            compose.onNodeWithText("id $FIRST", substring = true).performScrollTo().performClick()
            compose.waitUntil(5_000) { account() == FIRST }
            compose.waitUntil(5_000) { shown(NEXT) }
            assertEquals(token('a'), runBlocking { slot.vkAccessToken.first() })

            // a sign-in would clear VK's session on the phone: it waits for a stop, and no window opens
            compose.onNodeWithText("Войти в другой аккаунт").performScrollTo().performClick()
            compose.waitUntil(5_000) { shown("Чтобы войти в другой аккаунт, остановите туннель") }
            assertFalse(shown("Войдите в аккаунт VK"))

            // so would a deletion: nothing is asked and nothing goes
            compose.onAllNodes(hasContentDescription("Удалить", substring = true)).onLast().performScrollTo().performClick()
            compose.waitUntil(5_000) { shown("Чтобы удалить аккаунт, остановите туннель") }
            assertFalse(shown("Удалить аккаунт VK?"))
            assertEquals(listOf(FIRST, SECOND), runBlocking { slot.vkAccounts.first() }.filter { it == FIRST || it == SECOND }.sorted())

            // a token pasted by hand: stored, for the next connection
            compose.onAllNodes(hasSetTextAction()).onLast().performScrollTo().performTextReplacement(token('c'))
            compose.onNodeWithText("Подключить").performScrollTo().performClick()
            compose.waitUntil(5_000) { runBlocking { slot.vkAccessToken.first() } == token('c') }
            assertTrue(shown(NEXT))

            // the tunnel off: the same pick says nothing of a next connection, and a deletion is asked about again
            TunnelManager.running.value = false
            compose.waitUntil(5_000) { !shown("Туннель включён: выбранный аккаунт применится", substring = true) }
            compose.onAllNodes(hasContentDescription("Удалить", substring = true)).onLast().performScrollTo().performClick()
            compose.waitUntil(5_000) { shown("Удалить аккаунт VK?") }
            compose.onNodeWithText("Отмена").performClick()
        }
    }
}
