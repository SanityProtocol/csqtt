package com.csqtt.client

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.csqtt.client.ShellTestKit.recreateFromSavedStateAlone
import com.csqtt.client.ui.shell.Routes
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P3 B3 (U4, the canvas's F-VkAccounts and F-VkSignIn): the sign-in to VK is a route of the shell, opened for a
 * profile, so it is still there — over the accounts sheet it was opened from — after the screen is made again, and it
 * closes back to that sheet.
 */
@RunWith(AndroidJUnit4::class)
class VkSignInSurvivesTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun noSystemPromptsOverTheApp() = ShellTestKit.noSystemPromptsOverTheApp()

        private const val SLOT = 19
        private const val SIGN_IN = "Войдите в аккаунт VK"
        private const val ACCOUNTS = "Аккаунты VK"
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store by lazy { SettingsStore(context) }

    @Before
    fun anOwnerInAutoApi() = runBlocking {
        store.clearProfile(SLOT)
        store.saveActiveProfile(SLOT)
        store.saveCsqttLinkMode(false)
        SettingsStore(context, ProfileScope.Fixed(SLOT)).saveVkHashMode(CsqttConstants.VkAutoHash.MODE_AUTO_API)
    }

    @After
    fun restore() = runBlocking {
        store.saveActiveProfile(0)
        store.deleteProfile(SLOT)
    }

    private fun shown(text: String): Boolean = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theSignInIsStillThereAfterTheScreenIsMadeAgain() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(10_000) { shown("Туннель") }
            compose.onNodeWithText("Туннель").performClick()
            compose.waitUntil(10_000) { shown("Аккаунт VK") }
            assertTrue("no account yet", shown("Не подключён"))
            compose.onNodeWithText("Аккаунт VK").performClick()
            compose.waitUntil(5_000) { shown(ACCOUNTS) }
            // the button's words depend on whether this device has signed in before
            compose.onAllNodes(hasText("Войти в аккаунт VK") or hasText("Войти в другой аккаунт")).onLast().performClick()
            compose.waitUntil(10_000) { shown(SIGN_IN) }

            scenario.recreateFromSavedStateAlone()
            compose.waitUntil(10_000) { shown(SIGN_IN) }
            var routes = emptyList<Pair<String, String>>()
            scenario.onActivity { activity -> routes = activity.nav.stack.map { it.route to it.arg } }
            assertEquals(listOf(Routes.VK_ACCOUNTS to SLOT.toString(), Routes.VK_SIGN_IN to SLOT.toString()), routes)

            // its own ✕ closes it back to the sheet it was opened from
            compose.onAllNodes(hasContentDescription("Закрыть")).onLast().performClick()
            compose.waitUntil(5_000) { !shown(SIGN_IN) }
            assertTrue(shown(ACCOUNTS))
        }
    }
}
