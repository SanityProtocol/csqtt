package com.csqtt.client

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.csqtt.client.ShellTestKit.recreateFromSavedStateAlone
import com.csqtt.client.ShellTestKit.savedStateBytes
import com.csqtt.client.ui.shell.PendingLink
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M-70, M-71 and §4.2 rule 11 for a link's question (the canvas, F-LinkImport, F-LinkImportDup): a csqtt:// link opened
 * from the browser asks once where it goes; the question is still there after the screen is made again and in a new
 * process, without the link — which holds the connection password — being in what the system keeps of the screen; it is
 * written where the user said, and not asked again afterwards.
 */
@RunWith(AndroidJUnit4::class)
class ImportPromptSurvivesTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun noSystemPromptsOverTheApp() = ShellTestKit.noSystemPromptsOverTheApp()

        private const val TITLE = "Импорт ссылки csqtt://"
        private const val SERVER = "198.51.100.77:46000"
        private const val PASSWORD = "pw-9f3c7a51"
        private const val SLOT = 9
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store by lazy { SettingsStore(context) }
    private val link = "csqtt://connect?v=2&host=198.51.100.77&peer=46000&password=$PASSWORD&hashes=${"E".repeat(20)}"
    private val newer = "csqtt://connect?v=2&host=198.51.100.77&peer=46000&password=another-$PASSWORD&hashes=${"F".repeat(20)}"

    private fun shown(text: String): Boolean = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    private fun opened(raw: String) = Intent(Intent.ACTION_VIEW, Uri.parse(raw)).setClass(context, MainActivity::class.java)
    private fun profilesOn(raw: String) = runBlocking { store.profileEntries.first() }.filter { it.link == raw }.map { it.index }

    @After
    fun restore() = runBlocking {
        store.saveActiveProfile(0)
        (profilesOn(link) + profilesOn(newer) + SLOT).distinct().forEach { store.deleteProfile(it) }
    }

    @Test
    fun aLinkAsksOnceOutlivesTheScreenAndTheProcessAndGoesWhereTheUserSaid() {
        runBlocking {
            store.clearProfile(SLOT)
            store.saveActiveProfile(SLOT)
        }
        ActivityScenario.launch<MainActivity>(opened(link)).use { scenario ->
            compose.waitUntil(10_000) { shown(TITLE) }
            compose.onNodeWithText(SERVER).assertExists()
            // the link holds the connection password: it is not in what the system keeps of the screen
            assertFalse("the link's password is in the saved state", ShellTestKit.holds(scenario.savedStateBytes(), PASSWORD))

            // the screen made again while the question stands
            scenario.recreate()
            compose.waitUntil(10_000) { shown(TITLE) }

            // a new process: nothing in memory, and the activity from its saved state alone
            PendingLink.forget()
            scenario.recreateFromSavedStateAlone()
            compose.waitUntil(10_000) { shown(TITLE) }
            compose.onNodeWithText(SERVER).assertExists()

            // the open profile holds nothing: the link fills it, and no second profile is made
            compose.onNodeWithText("Добавить").performClick()
            compose.waitUntil(5_000) { !shown(TITLE) }
            compose.waitUntil(5_000) { runBlocking { store.csqttLink.first() } == link }
            assertEquals(SLOT, runBlocking { store.activeProfile.first() })
            assertTrue(runBlocking { store.csqttLinkMode.first() })
            assertEquals(listOf(SLOT), profilesOn(link))

            // answered: made again (theme, locale, process death), it is never asked a second time
            scenario.recreateFromSavedStateAlone()
            compose.waitForIdle()
            Thread.sleep(1_500)
            compose.waitForIdle()
            assertFalse("the link was asked for a second time", shown(TITLE))
        }
    }

    @Test
    fun aServerThatIsAProfileAlreadyIsUpdatedOrGetsASecondProfileAsTheUserSays() {
        runBlocking {
            store.clearProfile(SLOT)
            store.saveActiveProfile(SLOT)
            store.importLink(link, target = SLOT, name = "Дом")
        }
        // a second profile, which is not opened by itself
        ActivityScenario.launch<MainActivity>(opened(newer)).use {
            compose.waitUntil(10_000) { shown(TITLE) }
            compose.onNodeWithText("Этот сервер уже есть в профиле «Дом»").assertExists()
            compose.onNodeWithText("Добавить отдельным профилем").performClick()
            compose.onNodeWithText("Добавить").performClick()
            compose.waitUntil(5_000) { !shown(TITLE) }
            compose.waitUntil(5_000) { profilesOn(newer).size == 1 }
            assertEquals(SLOT, runBlocking { store.activeProfile.first() })
            assertEquals(listOf(SLOT), profilesOn(link))
        }
        // the same link again: «Обновить «Дом»» is the first answer, the open profile before another on that server
        runBlocking { store.deleteProfile(profilesOn(newer).single()) }
        ActivityScenario.launch<MainActivity>(opened(newer)).use {
            compose.waitUntil(10_000) { shown(TITLE) }
            // the choice and the button both say it
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Обновить «Дом»")).fetchSemanticsNodes().size == 2 }
            compose.onAllNodes(hasText("Обновить «Дом»"))[1].performClick()
            compose.waitUntil(5_000) { !shown(TITLE) }
            compose.waitUntil(5_000) { profilesOn(newer) == listOf(SLOT) }
            assertTrue(profilesOn(link).isEmpty())
            // its name stays
            assertEquals("Дом", runBlocking { store.profileEntries.first() }.first { it.index == SLOT }.name)
        }
    }
}
