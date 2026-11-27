package com.csqtt.client

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.csqtt.client.ShellTestKit.recreateFromSavedStateAlone
import com.csqtt.client.ShellTestKit.savedStateBytes
import com.csqtt.client.ui.shell.SecretDrafts
import com.csqtt.client.ui.shell.Tab
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
 * APP-PLAN §4.2 rules 2 and 11 for the shell: the open tab, a screen, a sheet with a draft in it and a dialog stay
 * through a tab change, a recreate and a return from the saved state alone (the view models gone, as after a process
 * death; what only a new process changes is NavModelTest's); a password being typed is in memory and never in what the
 * system keeps of the activity.
 */
@RunWith(AndroidJUnit4::class)
class NavRestoreTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun noSystemPromptsOverTheApp() = ShellTestKit.noSystemPromptsOverTheApp()

        private const val DRAFT = "Дача у озера"
        private const val SECRET = "correct-horse-battery-staple"
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    private fun shown(text: String): Boolean = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    // a field's label is its own text, beside what is typed in it
    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))

    /** What the password field draws is its mask, and it is marked as a password for TalkBack. */
    private fun passwordIsMasked(): Boolean {
        val config = field(ShellTestKit.SECRET_LABEL).fetchSemanticsNode().config
        val drawn = config[SemanticsProperties.EditableText].text
        return drawn.length == SECRET.length && !drawn.contains(SECRET) && config.contains(SemanticsProperties.Password)
    }

    @Before
    fun routes() = ShellTestKit.installRoutes()

    @After
    fun noRoutes() = ShellTestKit.removeRoutes()

    private fun everythingIsStillThere(scenario: ActivityScenario<MainActivity>, step: String) {
        compose.waitUntil(10_000) { shown(ShellTestKit.DIALOG_TITLE) && shown(ShellTestKit.SHEET_TITLE) }
        // the draft typed into the sheet's field
        compose.waitUntil(5_000) { shown(DRAFT) }
        scenario.onActivity { activity ->
            assertEquals(step, Tab.Logs, activity.nav.tab)
            assertEquals(step, listOf(ShellTestKit.screen, ShellTestKit.sheet, ShellTestKit.dialog), activity.nav.stack)
        }
    }

    @Test
    fun theTabTheOverlaysAndADraftOutliveATabChangeARecreateAndTheLossOfTheViewModels() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(10_000) { shown("Логи") }
            compose.onNodeWithText("Логи").performClick()
            compose.onNodeWithText("Логи").assertIsSelected()

            scenario.onActivity { it.nav.open(ShellTestKit.screen) }
            compose.waitUntil(5_000) { shown(ShellTestKit.SCREEN_TITLE) }
            scenario.onActivity { it.nav.open(ShellTestKit.sheet) }
            compose.waitUntil(5_000) { shown(ShellTestKit.SHEET_TITLE) }
            field(ShellTestKit.NAME_LABEL).performTextInput(DRAFT)
            field(ShellTestKit.SECRET_LABEL).performTextInput(SECRET)
            scenario.onActivity { it.nav.open(ShellTestKit.dialog) }
            everythingIsStillThere(scenario, "opened")
            assertEquals(SECRET, SecretDrafts[ShellTestKit.SECRET_KEY])
            assertTrue("the password is on the screen", passwordIsMasked())

            // what the system keeps of the activity holds the draft and never the password (§4.2 rule 2, M-174)
            val saved = scenario.savedStateBytes()
            assertTrue("the draft is saved", ShellTestKit.holds(saved, DRAFT))
            assertFalse("the password is saved", ShellTestKit.holds(saved, SECRET))

            // a recreate: a theme or a locale change
            scenario.recreate()
            everythingIsStillThere(scenario, "after a recreate")
            assertEquals("the password draft outlives a recreate", SECRET, SecretDrafts[ShellTestKit.SECRET_KEY])
            assertTrue("the password is on the screen after a recreate", passwordIsMasked())

            // as after a process death: the view models are gone, only the saved state comes back
            scenario.recreateFromSavedStateAlone()
            everythingIsStillThere(scenario, "from the saved state alone")

            // a tab asked for from outside the bar (a notification's tap) shows it: the screen over the tabs closes,
            // the sheet and the question stay until they are answered
            scenario.onActivity { it.nav.select(Tab.Tunnel) }
            compose.waitUntil(5_000) { !shown(ShellTestKit.SCREEN_TITLE) }
            assertTrue(shown(ShellTestKit.DIALOG_TITLE) && shown(ShellTestKit.SHEET_TITLE))
            assertTrue("the sheet was rebuilt and lost its draft", shown(DRAFT))
            scenario.onActivity { activity ->
                assertEquals(Tab.Tunnel, activity.nav.tab)
                assertEquals(listOf(ShellTestKit.sheet, ShellTestKit.dialog), activity.nav.stack)
            }
        }
    }
}
