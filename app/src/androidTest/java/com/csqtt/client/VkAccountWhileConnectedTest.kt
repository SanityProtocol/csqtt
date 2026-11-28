package com.csqtt.client

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
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
 * «Аккаунты VK» with the tunnel on (card p3-vk-run-account; the operator, 2026-11-27 «Pick/paste allowed» and 2026-11-28
 * «Allow sign-in, keep deletion guarded»). A run keeps the VK account it started with, so another saved account is
 * picked, a token is pasted and a new sign-in is opened while it runs, each stored for the next connection. What says
 * so is the apply bar, on the sheet's own foot since the sheet covers the tabs, and the line under the list that names
 * the account the tunnel is on now (card p3-apply-bar; the operator, 2026-11-28: «The same bar on the sheet») — not a
 * message that goes after four seconds, and not a line that stood there before anything was touched. A deletion asks
 * for the tunnel to be stopped, on whichever profile it is: the accounts are the phone's, and the run would go on using
 * an account the phone no longer has. Not while it stops.
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
        private const val STANDING = "Туннель включён: изменения применятся при следующем подключении."
        private const val NEXT = "Аккаунт применится при следующем подключении"
        private const val NOW_SECOND = "Сейчас: Аккаунт 2 · id $SECOND"
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
        ShellTestKit.tunnelOff()
        slot.removeVkAccount(FIRST)
        slot.removeVkAccount(SECOND)
        store.saveActiveProfile(0)
        store.deleteProfile(SLOT)
    }

    private fun count(text: String): Int = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size
    private fun shown(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    /** Whether a row with [text] is read to TalkBack as one item that is the selected one. */
    private fun saidSelected(text: String): Boolean =
        compose.onAllNodes(hasText(text, substring = true) and isSelected()).fetchSemanticsNodes().isNotEmpty()

    private fun account(): String = runBlocking { slot.vkAccessTokenUserId.first() }
    private fun stored(): String = runBlocking { slot.vkAccessToken.first() }
    private fun accounts(): List<String> = runBlocking { slot.vkAccounts.first() }.filter { it == FIRST || it == SECOND }.sorted()

    /** An account's row in the list, by its own line: «Сейчас: … id …» names an account too, and is no row. */
    private fun row(id: String) = compose.onNodeWithText("id $id · постоянный токен", substring = true)

    /** The apply bars on screen: the one over the tabs, and with a sheet open the one on its foot too. */
    private fun bars(): Int = count(ShellTestKit.BAR)

    private fun tapTheLastTrash() =
        compose.onAllNodes(hasContentDescription("Удалить", substring = true)).onLast().performScrollTo().performClick()

    @Test
    fun anAccountIsTakenForTheNextConnectionAndADeletionWaitsForTheStop() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { shown("Туннель") }
            compose.onNodeWithText("Туннель").performClick()
            compose.waitUntil(10_000) { shown("Аккаунт VK") }

            // the tunnel on for this profile, on the account the profile holds: nothing waits, and nothing is said of
            // a change that was not made — on the tab or on the sheet
            ShellTestKit.tunnelOn(SLOT, account = token('b'))
            compose.waitForIdle()
            compose.onNodeWithText("Аккаунт VK").performScrollTo().performClick()
            compose.waitUntil(5_000) { shown("Аккаунты VK") && shown("id $FIRST", substring = true) }
            assertEquals(0, bars())
            assertFalse(shown(STANDING))
            assertFalse(shown("Сейчас", substring = true))
            assertEquals(SECOND, account())

            // the other account, saved on this phone: taken, for the next connection. The sheet says which account the
            // tunnel is on, and carries the bar on its own foot (the tab's is under it); the tab's row says it too
            row(FIRST).performScrollTo().performClick()
            compose.waitUntil(5_000) { account() == FIRST }
            compose.waitUntil(5_000) { shown(NOW_SECOND) && bars() == 2 }
            compose.waitUntil(5_000) { shown("Сейчас: id $SECOND") }
            assertEquals(token('a'), stored())
            assertFalse("the message that went after four seconds", shown(NEXT))
            // the chosen account is its row alone, with no mark and no word: TalkBack hears it from the row
            compose.waitUntil(5_000) { saidSelected("id $FIRST") }
            assertFalse(saidSelected("id $SECOND"))

            // a deletion would leave the running tunnel on an account the phone no longer has: it waits for the stop,
            // nothing is asked and nothing goes
            tapTheLastTrash()
            compose.waitUntil(5_000) { shown(STOP_TO_DELETE) }
            assertFalse(shown(DELETE_QUESTION))
            assertEquals(listOf(FIRST, SECOND), accounts())
            assertEquals(FIRST, account())

            // a token pasted by hand: stored, for the next connection — the tunnel is still on the second account
            compose.onAllNodes(hasSetTextAction()).onLast().performScrollTo().performTextReplacement(token('c'))
            compose.onNodeWithText("Подключить").performScrollTo().performClick()
            compose.waitUntil(5_000) { stored() == token('c') }
            compose.waitUntil(5_000) { shown("Аккаунт VK подключён") }
            assertFalse(shown(NEXT))
            assertEquals(2, bars())
            assertTrue(shown(NOW_SECOND))
            // a pasted token names no account of the list: its row is the chosen one, and is read as one item that
            // says so (the row is no choice to tap, so it has to say it itself)
            compose.waitUntil(5_000) { saidSelected("Аккаунт этого профиля") }
            assertFalse(saidSelected("id $FIRST"))

            // a new sign-in is opened with the tunnel on: the run keeps the account it started with, whatever is signed
            // in to meanwhile
            compose.onNodeWithText("Войти в другой аккаунт").performScrollTo().performClick()
            compose.waitUntil(10_000) { shown(SIGN_IN) }
            compose.onAllNodes(hasContentDescription("Закрыть")).onLast().performClick()
            compose.waitUntil(5_000) { !shown(SIGN_IN) }
            assertEquals(token('c'), stored())

            // the account the tunnel is on, picked back: nothing waits any more, and both bars and the line go
            row(SECOND).performScrollTo().performClick()
            compose.waitUntil(5_000) { account() == SECOND }
            compose.waitUntil(5_000) { bars() == 0 && !shown("Сейчас", substring = true) }

            // the tunnel on for another profile: an account taken here is this profile's own next start, with nothing
            // to say — but a deletion waits all the same: the accounts are the phone's, and that tunnel may be on the
            // one to delete
            ShellTestKit.tunnelOff()
            ShellTestKit.tunnelOn(0)
            row(FIRST).performScrollTo().performClick()
            compose.waitUntil(5_000) { account() == FIRST }
            compose.waitForIdle()
            Thread.sleep(300)
            assertEquals(0, bars())
            assertFalse(shown("Сейчас", substring = true))
            compose.waitUntil(8_000) { !shown(STOP_TO_DELETE) }
            tapTheLastTrash()
            compose.waitUntil(5_000) { shown(STOP_TO_DELETE) }
            assertFalse(shown(DELETE_QUESTION))
            assertEquals(listOf(FIRST, SECOND), accounts())

            // while that tunnel stops it is no longer asked for: the run is ending, and a deletion is asked about
            TunnelManager.stopping.value = true
            tapTheLastTrash()
            compose.waitUntil(5_000) { shown(DELETE_QUESTION) }
            compose.onNodeWithText("Отмена").performClick()
            compose.waitUntil(5_000) { !shown(DELETE_QUESTION) }
            TunnelManager.stopping.value = false

            // the tunnel off: a pick is a pick
            ShellTestKit.tunnelOff()
            row(SECOND).performScrollTo().performClick()
            compose.waitUntil(5_000) { account() == SECOND }
            compose.waitForIdle()
            Thread.sleep(300)
            assertEquals(0, bars())
            assertEquals(token('b'), stored())
        }
    }
}
