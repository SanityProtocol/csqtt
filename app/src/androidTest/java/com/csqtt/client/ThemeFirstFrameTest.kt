package com.csqtt.client

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.csqtt.client.ui.design.themeProbe
import com.csqtt.client.ui.shell.FirstFrame
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * M-94: the first frame is drawn in the theme the user chose, not in the system's until the store has answered. The
 * chosen mode is kept beside the store for the next start; without it the first composition has only «Система» to go by.
 */
@RunWith(AndroidJUnit4::class)
class ThemeFirstFrameTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun noSystemPromptsOverTheApp() = ShellTestKit.noSystemPromptsOverTheApp()
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store by lazy { SettingsStore(context) }
    private lateinit var modeBefore: String

    private fun shown(text: String): Boolean = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Before
    fun keep() = runBlocking { modeBefore = store.themeMode.first() }

    @After
    fun restore() {
        themeProbe = null
        ShellTestKit.shell("cmd uimode night no")
        runBlocking { store.saveThemeMode(modeBefore) }
        // the next start's first frame is the restored mode's
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { FirstFrame(context).themeMode == modeBefore }
        }
    }

    /** The theme of every composition of a start, the first one first, with the system and the stored mode at odds. */
    private fun framesOfAStart(mode: String, systemNight: Boolean): List<Boolean> {
        runBlocking { store.saveThemeMode(mode) }
        // a start that shows the mode keeps it for the next one
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { FirstFrame(context).themeMode == mode }
        }
        ShellTestKit.shell("cmd uimode night ${if (systemNight) "yes" else "no"}")
        val frames = Collections.synchronizedList(mutableListOf<Boolean>())
        themeProbe = { frames += it }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { shown("Главная") }
            // the store has answered by now: a late correction would have been composed
            Thread.sleep(1_500)
            compose.waitForIdle()
        }
        themeProbe = null
        return frames.toList()
    }

    @Test
    fun aDarkChoiceIsDarkFromTheFirstFrameOnALightSystem() {
        val frames = framesOfAStart(CsqttConstants.Theme.MODE_DARK, systemNight = false)
        assertTrue("no frame was drawn", frames.isNotEmpty())
        assertEquals("a light frame before the dark theme: $frames", emptyList<Boolean>(), frames.filterNot { it })
    }

    @Test
    fun aLightChoiceIsLightFromTheFirstFrameOnADarkSystem() {
        val frames = framesOfAStart(CsqttConstants.Theme.MODE_LIGHT, systemNight = true)
        assertTrue("no frame was drawn", frames.isNotEmpty())
        assertEquals("a dark frame before the light theme: $frames", emptyList<Boolean>(), frames.filter { it })
    }

    @Test
    fun aFirstStartAfterTheUpdateHasNoMirrorAndFollowsTheSystem() {
        context.getSharedPreferences("first_frame", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(CsqttConstants.Theme.MODE_SYSTEM, FirstFrame(context).themeMode)
        assertEquals(false, FirstFrame(context).hasServer)
    }
}
