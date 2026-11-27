package com.csqtt.client

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * APP-PLAN §4.2 rule 4: Back closes the sheet, then the dialog, then the screen, then returns to «Главная», and only
 * then leaves the app. The system's Back is pressed, so each layer's own window answers it.
 */
@RunWith(AndroidJUnit4::class)
class BackOrderTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun noSystemPromptsOverTheApp() = ShellTestKit.noSystemPromptsOverTheApp()
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    private fun shown(text: String): Boolean = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Before
    fun routes() = ShellTestKit.installRoutes()

    @After
    fun noRoutes() = ShellTestKit.removeRoutes()

    @Test
    fun backClosesTheSheetTheDialogTheScreenReturnsHomeAndLeaves() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(10_000) { shown("Логи") }
            compose.onNodeWithText("Логи").performClick()
            scenario.onActivity { it.nav.open(ShellTestKit.screen) }
            compose.waitUntil(5_000) { shown(ShellTestKit.SCREEN_TITLE) }
            scenario.onActivity { it.nav.open(ShellTestKit.dialog) }
            compose.waitUntil(5_000) { shown(ShellTestKit.DIALOG_TITLE) }
            scenario.onActivity { it.nav.open(ShellTestKit.sheet) }
            compose.waitUntil(5_000) { shown(ShellTestKit.SHEET_TITLE) }

            Espresso.pressBack()
            compose.waitUntil(5_000) { !shown(ShellTestKit.SHEET_TITLE) }
            assertTrue(shown(ShellTestKit.DIALOG_TITLE))

            Espresso.pressBack()
            compose.waitUntil(5_000) { !shown(ShellTestKit.DIALOG_TITLE) }
            assertTrue(shown(ShellTestKit.SCREEN_TITLE))

            Espresso.pressBack()
            compose.waitUntil(5_000) { !shown(ShellTestKit.SCREEN_TITLE) }
            // the tab it was opened over, with the bar again
            compose.onNodeWithText("Логи").assertIsSelected()

            Espresso.pressBack()
            compose.waitUntil(5_000) {
                var stack = -1
                scenario.onActivity { stack = it.nav.stack.size }
                stack == 0 && shown("Главная")
            }
            compose.onNodeWithText("Главная").assertIsSelected()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)

            // nothing left to close: Back leaves the app
            Espresso.pressBackUnconditionally()
            compose.waitUntil(10_000) { scenario.state != Lifecycle.State.RESUMED }
        }
    }
}
