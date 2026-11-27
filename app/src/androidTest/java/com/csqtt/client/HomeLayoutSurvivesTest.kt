package com.csqtt.client

import android.content.Context
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.csqtt.client.ui.home.homeLayoutProbe
import com.csqtt.client.ui.shell.FirstFrame
import java.util.Collections
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
 * P3-PLAN §1.3a: Home's layout is the user's choice. The header's switch changes it; it is the same after the screen is
 * made again and in a new process; and the first frame of a start already has it, before the store has answered.
 */
@RunWith(AndroidJUnit4::class)
class HomeLayoutSurvivesTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun noSystemPromptsOverTheApp() = ShellTestKit.noSystemPromptsOverTheApp()

        private const val TO_LIST = "Показать список подключений"
        private const val TO_ONE = "Показать одно подключение"
        private const val LIST_ONLY = "Профили"
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store by lazy { SettingsStore(context) }
    private lateinit var layoutBefore: HomeLayout

    private fun shown(text: String): Boolean = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    private fun described(text: String): Boolean = compose.onAllNodes(hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty()

    @Before
    fun keep() = runBlocking { layoutBefore = store.homeLayout.first() }

    @After
    fun restore() {
        homeLayoutProbe = null
        runBlocking { store.saveHomeLayout(layoutBefore) }
        // the next start's first frame is the restored layout's
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { FirstFrame(context).homeLayout == layoutBefore }
        }
    }

    @Test
    fun theChoiceOutlivesTheScreenAndTheProcessAndIsThereInTheFirstFrame() {
        runBlocking { store.saveHomeLayout(HomeLayout.One) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // one connection, the default: the switch offers the list, and the list's own heading is not there
            compose.waitUntil(10_000) { described(TO_LIST) }
            assertFalse(shown(LIST_ONLY))

            compose.onNodeWithContentDescription(TO_LIST).performClick()
            compose.waitUntil(5_000) { described(TO_ONE) && shown(LIST_ONLY) }
            assertEquals(HomeLayout.List, runBlocking { store.homeLayout.first() })

            scenario.recreate()
            compose.waitUntil(10_000) { described(TO_ONE) && shown(LIST_ONLY) }
            // the switch is Home's: another tab's header has none
            compose.onNodeWithContentDescription(TO_ONE).assertExists()
            compose.waitUntil(5_000) { FirstFrame(context).homeLayout == HomeLayout.List }
        }

        // a new process: nothing read yet. Every frame of the start is the list, the first one included
        SettingsStore.cachedHomeLayout = null
        val frames = Collections.synchronizedList(mutableListOf<HomeLayout>())
        homeLayoutProbe = { frames += it }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { shown(LIST_ONLY) }
            // the store has answered by now: a late correction would have been composed
            Thread.sleep(1_500)
            compose.waitForIdle()
        }
        homeLayoutProbe = null
        assertTrue("no frame of Home was drawn", frames.isNotEmpty())
        assertEquals("a frame in the other layout: $frames", emptyList<HomeLayout>(), frames.filter { it != HomeLayout.List })
    }
}
