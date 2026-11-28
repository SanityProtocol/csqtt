package com.csqtt.client

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

/**
 * A profile row's swipe on a device (the operator, 2026-11-28, on a recording of their phone: «it needs to slide easier,
 * and when sliding back - it stops not at the end at the right border as it should»). A short flick opens the strip of
 * actions and a short flick shuts it; a slow drag goes on once it has covered a third of the way; the strip is exactly
 * as wide as its way, 44 dp an action (the canvas, F-HomeSwipe), so on the way back it is at the row's end when it goes;
 * and it goes without a tail of frames that move nothing.
 */
@RunWith(AndroidJUnit4::class)
class ProfileRowSwipeTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun noSystemPromptsOverTheApp() = ShellTestKit.noSystemPromptsOverTheApp()

        private const val SLOT = 23
        private const val RENAME = "Переименовать"
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store by lazy { SettingsStore(context) }
    private val density get() = context.resources.displayMetrics.density
    private lateinit var layoutBefore: HomeLayout
    private lateinit var sortBefore: ProfileSort
    private lateinit var orderBefore: List<Int>
    private var beta = -1

    private fun link(host: String) = "csqtt://connect?v=2&host=$host.example.net&peer=46000&password=pw&hashes=${"E".repeat(20)}"
    private fun shown(text: String): Boolean = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private fun row(): SemanticsNodeInteraction = compose.onAllNodes(hasText("Бета", substring = true) and hasClickAction()).onLast()
    private fun rowRight(): Float = row().fetchSemanticsNode().boundsInRoot.right
    private fun actions() = compose.onAllNodes(hasContentDescription(RENAME)).fetchSemanticsNodes()

    /** How much of the strip is shown, in px: from its first action's edge to the row's end. Null with no strip. */
    private fun stripShown(): Float? = actions().lastOrNull()?.let { rowRight() - it.positionInRoot.x }

    /** The strip's way, in px: 44 dp for each of the row's actions, each laid out in whole pixels. */
    private fun way(): Float =
        ProfileMenu.items(runBlocking { store.profileEntries.first() }.first { it.index == beta }).size * Math.round(44 * density).toFloat()

    /** A drag of [dx] px past the touch slop, over [millis], from a point on the row's name. */
    private fun drag(dx: Float, millis: Long) = row().performTouchInput {
        val from = centerLeft + Offset(40.dp.toPx(), 0f)
        val slop = if (dx < 0) -viewConfiguration.touchSlop else viewConfiguration.touchSlop
        swipe(if (dx < 0) center else from, (if (dx < 0) center else from) + Offset(dx + slop, 0f), durationMillis = millis)
    }

    @Before
    fun profiles() = runBlocking {
        layoutBefore = store.homeLayout.first()
        sortBefore = store.profileSort.first()
        orderBefore = store.profileOrder.first()
        store.saveHomeLayout(HomeLayout.List)
        store.clearProfile(SLOT)
        store.saveActiveProfile(SLOT)
        store.importLink(link("alpha"), target = SLOT, name = "Альфа")
        beta = store.importLink(link("beta"), target = null, name = "Бета")!!.first
        // the two of them first, whatever other profiles the device holds: Home shows three rows
        store.restoreProfileOrder(ProfileSort.Custom.stored to ProfileOrder.encode(listOf(SLOT, beta)))
    }

    @After
    fun restore() {
        compose.mainClock.autoAdvance = true
        runBlocking {
            store.saveActiveProfile(0)
            listOf(SLOT, beta).forEach { store.deleteProfile(it) }
            store.restoreProfileOrder(sortBefore.stored to ProfileOrder.encode(orderBefore))
            store.saveHomeLayout(layoutBefore)
        }
    }

    @Test
    fun aShortFlickOpensTheStripAndAShortFlickShutsIt() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { shown("Бета") }
            // 40 dp in a tenth of a second: far under a third of the way, and a flick
            drag(-40 * density, 100)
            compose.waitForIdle()
            assertEquals("the strip is not open whole", way(), stripShown() ?: 0f, 0.5f)
            drag(40 * density, 100)
            compose.waitForIdle()
            assertTrue("the strip is still there", actions().isEmpty())
        }
    }

    @Test
    fun aSlowDragGoesOnAfterAThirdOfTheWayAndReturnsBeforeIt() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { shown("Бета") }
            val way = way()
            drag(-way * 0.25f, 3_000)
            compose.waitForIdle()
            assertTrue("a quarter of the way opened the strip", actions().isEmpty())
            // more than a third, less than the half the row asked for before
            drag(-way * 0.42f, 3_000)
            compose.waitForIdle()
            assertEquals("0.42 of the way did not open the strip", way, stripShown() ?: 0f, 0.5f)
            drag(way * 0.25f, 3_000)
            compose.waitForIdle()
            assertEquals("a quarter of the way back shut the strip", way, stripShown() ?: 0f, 0.5f)
            drag(way * 0.42f, 3_000)
            compose.waitForIdle()
            assertTrue("0.42 of the way back did not shut the strip", actions().isEmpty())
        }
    }

    @Test
    fun onTheWayBackTheStripIsAtTheRowsEndWhenItGoes() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { shown("Бета") }
            row().performTouchInput { swipeLeft() }
            compose.waitForIdle()
            // as wide as its way: each action 44 dp
            assertEquals("the open strip is not as wide as its way", way(), stripShown() ?: 0f, 0.5f)

            // a tap on the row shuts it; frame by frame, how much of the strip is still shown
            compose.mainClock.autoAdvance = false
            row().performTouchInput { click(centerLeft + Offset(40.dp.toPx(), 0f)) }
            val seen = ArrayList<Float>()
            var gone = false
            for (frame in 1..60) {
                compose.mainClock.advanceTimeByFrame()
                val shows = stripShown()
                if (shows == null) { gone = true; break }
                seen += shows
            }
            assertTrue("the strip never went: $seen", gone)
            assertTrue("the strip never moved: $seen", seen.isNotEmpty())
            assertTrue("the strip went back and forth: $seen", seen.zipWithNext().all { (a, b) -> b <= a + 0.5f })
            assertTrue("the strip was taken away with ${seen.last()} px of it still shown: $seen", seen.last() <= density)
            // 0.25 s for the longest way; the spring's own tail would be a third longer, moving nothing one can see
            assertTrue("the strip took ${seen.size} frames to go: $seen", seen.size <= 18)
        }
    }
}
