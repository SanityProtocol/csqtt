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
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * «Аккаунты VK» with the tunnel on (card p3-vk-run-account; the operator, 2026-11-27 «Pick/paste allowed» and 2026-11-28
 * «Allow sign-in, keep deletion guarded»). A run keeps the VK account it started with, so another saved account is
 * picked, a token is pasted and a new sign-in is opened while it runs, each stored for the next connection and saying
 * so. A deletion asks for the tunnel to be stopped: the run would go on using an account the phone no longer has. All
 * of it only for the profile the tunnel is on.
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
        private const val NOTE = "Туннель включён: изменения применятся при следующем подключении."
        private const val NEXT = "Аккаунт применится при следующем подключении"
        private const val STOP_TO_DELETE = "Чтобы удалить аккаунт, остановите туннель"
        private const val DELETE_QUESTION = "Удалить аккаунт VK?"
        private const val SIGN_IN = "Войдите в аккаунт VK"
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store by lazy { SettingsStore(context) }
    private val slot by lazy { SettingsStore(context, ProfileScope.Fixed(SLOT)) }
    private fun token(tail: Char) = "vk1.a." + tail.toString().repeat(40)
    private var profileBefore = -1

    // an owner in «Авто API» with two accounts signed in on the phone, on the second of them
    @Before
    fun twoAccounts() = runBlocking {
        profileBefore = TunnelManager.sourceProfile
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
        TunnelManager.sourceProfile = profileBefore
        slot.removeVkAccount(FIRST)
        slot.removeVkAccount(SECOND)
        store.saveActiveProfile(0)
        store.deleteProfile(SLOT)
    }

    private fun count(text: String): Int = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size
    private fun shown(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun account(): String = runBlocking { slot.vkAccessTokenUserId.first() }
    private fun stored(): String = runBlocking { slot.vkAccessToken.first() }
    private fun accounts(): List<String> = runBlocking { slot.vkAccounts.first() }.filter { it == FIRST || it == SECOND }.sorted()

    /** The tunnel as the manager would say it, with no tunnel: the screens read nothing else. */
    private fun tunnelOn(profile: Int) {
        TunnelManager.sourceProfile = profile
        TunnelManager.running.value = true
    }

    private fun tapTheLastTrash() =
        compose.onAllNodes(hasContentDescription("Удалить", substring = true)).onLast().performScrollTo().performClick()

    @Test
    fun anAccountIsTakenForTheNextConnectionAndADeletionWaitsForTheStop() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { shown("Туннель") }
            compose.onNodeWithText("Туннель").performClick()
            compose.waitUntil(10_000) { shown("Аккаунт VK") }
            assertEquals(0, count(NOTE))

            // the tunnel on for this profile: the tab says what a change is for (once, and once more in «Дополнительно»
            // where that is open), and the sheet, which covers the tab, says it too
            tunnelOn(SLOT)
            compose.waitUntil(5_000) { count(NOTE) > 0 }
            compose.waitForIdle()
            val onTheTab = count(NOTE)
            compose.onNodeWithText("Аккаунт VK").performScrollTo().performClick()
            compose.waitUntil(5_000) { shown("Аккаунты VK") && shown("id $FIRST", substring = true) }
            compose.waitUntil(5_000) { count(NOTE) == onTheTab + 1 }
            assertEquals(SECOND, account())

            // the other account, saved on this phone: taken, for the next connection
            compose.onNodeWithText("id $FIRST", substring = true).performScrollTo().performClick()
            compose.waitUntil(5_000) { account() == FIRST }
            compose.waitUntil(5_000) { shown(NEXT) }
            assertEquals(token('a'), stored())

            // a deletion would leave the running tunnel on an account the phone no longer has: it waits for the stop,
            // nothing is asked and nothing goes
            tapTheLastTrash()
            compose.waitUntil(5_000) { shown(STOP_TO_DELETE) }
            assertFalse(shown(DELETE_QUESTION))
            assertEquals(listOf(FIRST, SECOND), accounts())
            assertEquals(FIRST, account())

            // a token pasted by hand: stored, for the next connection (the line above was the deletion's, so this one
            // is the paste's own)
            compose.onAllNodes(hasSetTextAction()).onLast().performScrollTo().performTextReplacement(token('c'))
            compose.onNodeWithText("Подключить").performScrollTo().performClick()
            compose.waitUntil(5_000) { stored() == token('c') }
            compose.waitUntil(5_000) { shown(NEXT) }

            // a new sign-in is opened with the tunnel on: the run keeps the account it started with, whatever is signed
            // in to meanwhile
            compose.onNodeWithText("Войти в другой аккаунт").performScrollTo().performClick()
            compose.waitUntil(10_000) { shown(SIGN_IN) }
            compose.onAllNodes(hasContentDescription("Закрыть")).onLast().performClick()
            compose.waitUntil(5_000) { !shown(SIGN_IN) }
            assertEquals(token('c'), stored())

            // the tunnel on for another profile: this profile's sheet says nothing of it, and a deletion is asked about
            tunnelOn(0)
            compose.waitUntil(5_000) { count(NOTE) == 0 }
            tapTheLastTrash()
            compose.waitUntil(5_000) { shown(DELETE_QUESTION) }
            compose.onNodeWithText("Отмена").performClick()
            compose.waitUntil(5_000) { !shown(DELETE_QUESTION) }

            // the tunnel off: a pick says nothing of a next connection (the paste's line has had its four seconds first)
            TunnelManager.running.value = false
            compose.waitUntil(8_000) { !shown(NEXT) }
            compose.onNodeWithText("id $SECOND", substring = true).performScrollTo().performClick()
            compose.waitUntil(5_000) { account() == SECOND }
            compose.waitForIdle()
            Thread.sleep(300)
            assertFalse(shown(NEXT))
            assertEquals(token('b'), stored())
        }
    }
}
