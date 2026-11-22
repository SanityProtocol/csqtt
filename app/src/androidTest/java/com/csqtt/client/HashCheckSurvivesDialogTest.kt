package com.csqtt.client

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B5h2 (U2, APP-PLAN §4.2 rules 8 and 11): a VK hash check started in the dialog runs on after the dialog closes, a tab
 * switch and a recreate, and its results are stored for the profile it began on, even when another profile is opened
 * meanwhile.
 */
@RunWith(AndroidJUnit4::class)
class HashCheckSurvivesDialogTest {
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

        private const val SLOT = 12
        private const val OTHER = 13
        private const val HASH = "B5h2HashCheck_0123456"
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store by lazy { SettingsStore(context) }
    private val realValidator = VkHashCheck.validator

    @After
    fun restore() {
        VkHashCheck.validator = realValidator
        runBlocking {
            store.deleteProfileFromClassic(SLOT)
            store.deleteProfileFromClassic(OTHER)
        }
    }

    private fun shown(text: String): Boolean = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun storedResults(profile: Int): Map<String, VkHashValidationStatus> = runBlocking {
        VkHashValidationCodec.decode(SettingsStore(context, ProfileScope.Fixed(profile)).vkHashCheckResults.first())
    }

    @Test
    fun theCheckOutlivesTheDialogTheTabAndTheScreen() {
        // the checker answers only when the test lets it: the dialog, the tab and the screen go first
        val release = CompletableDeferred<Unit>()
        VkHashCheck.validator = { _, hashes, _, _ ->
            release.await()
            hashes.associateWith { VkHashValidationStatus.Valid }
        }
        runBlocking {
            store.clearProfile(OTHER)
            store.clearProfile(SLOT)
            store.saveActiveProfile(SLOT)
            store.saveCsqttLinkMode(false)
            store.saveVkHashesOnly(HASH)
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(10_000) { shown("Туннель") }
            compose.onNodeWithText("Туннель").performClick()
            compose.waitUntil(10_000) { shown("Ссылки VK звонков") }
            compose.onNodeWithText("Ссылки VK звонков").performClick()
            compose.waitUntil(5_000) { shown("Проверить актив") }
            compose.onNodeWithText("Проверить актив").performClick()
            compose.waitUntil(5_000) { VkHashCheck.state.value == VkHashCheck.State.Running(SLOT) }

            // the dialog closes, the tab changes, the screen is recreated, another profile is opened
            compose.waitUntil(5_000) { shown("Проверяем...") }
            compose.onNodeWithText("Сохранить").performClick()
            compose.waitUntil(5_000) { !shown("Проверяем...") }
            compose.onNodeWithText("Подкл.").performClick()
            scenario.recreate()
            compose.waitUntil(10_000) { shown("Туннель") }
            runBlocking { store.saveActiveProfile(OTHER) }

            release.complete(Unit)
            compose.waitUntil(10_000) { VkHashCheck.state.value is VkHashCheck.State.Done }
            compose.waitUntil(5_000) { storedResults(SLOT)[HASH] == VkHashValidationStatus.Valid }
        }
        assertEquals(VkHashCheck.State.Done(SLOT, listOf(HASH), 0), VkHashCheck.state.value)
        assertNull("the profile opened meanwhile got nothing", storedResults(OTHER)[HASH])
    }
}
