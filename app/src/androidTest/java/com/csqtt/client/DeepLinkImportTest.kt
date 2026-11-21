package com.csqtt.client

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M-70 / M-71: a csqtt:// VIEW intent asks once where the link goes, survives a recreate while it waits, writes the
 * profile the person picked, and is not asked again when the activity is recreated afterwards.
 */
@RunWith(AndroidJUnit4::class)
class DeepLinkImportTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun noSystemPromptsOverTheApp() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val pkg = instrumentation.targetContext.packageName
            instrumentation.uiAutomation.apply {
                executeShellCommand("pm grant $pkg android.permission.POST_NOTIFICATIONS").close()
                executeShellCommand("dumpsys deviceidle whitelist +$pkg").close()
            }
        }

        private const val TITLE = "Импорт ссылки CSQTT"
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    private val link = "csqtt://connect?v=2&host=198.51.100.77&peer=46000&password=pw&hashes=${"E".repeat(20)}"

    private fun dialogShown(): Boolean = compose.onAllNodes(hasText(TITLE)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aViewIntentAsksOnceAndWritesThePickedProfile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SettingsStore(context)
        val slot = 9
        runBlocking {
            store.clearProfile(slot)
            store.saveActiveProfile(slot)
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClass(context, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            compose.waitUntil(10_000) { dialogShown() }
            // a recreate while the dialog waits keeps the link and the dialog
            scenario.recreate()
            compose.waitUntil(10_000) { dialogShown() }
            compose.onNodeWithText("Сервер: 198.51.100.77:46000").assertExists()
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasText("В профиль", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("В профиль", substring = true).performClick()
            compose.waitUntil(5_000) { !dialogShown() }
            compose.waitUntil(5_000) { runBlocking { store.csqttLink.first() } == link }
            assertEquals(slot, runBlocking { store.activeProfile.first() })
            assertTrue(runBlocking { store.csqttLinkMode.first() })

            // answered: a recreate (theme, locale, process death) never reads the intent again
            scenario.recreate()
            compose.waitForIdle()
            Thread.sleep(1_500)
            compose.waitForIdle()
            assertTrue("the link was asked for a second time", !dialogShown())
        }
    }
}
