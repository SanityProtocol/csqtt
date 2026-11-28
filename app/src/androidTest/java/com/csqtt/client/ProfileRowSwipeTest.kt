package com.csqtt.client

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
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
 * actions and a short flick shuts it, and a lift is a flick from 125 dp a second; a slow drag goes on once it has
 * covered a third of the way from the end it began at; the strip is exactly as wide as its way, 44 dp an action (the
 * canvas, F-HomeSwipe), so on the way back it is at the row's end when it goes, and it goes without frames that have
 * nothing of it to show; a row whose actions change under an open strip keeps the strip in its place, and one whose
 * actions change while its strip shuts is left shut.
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
    private fun row(name: String = "Бета"): SemanticsNodeInteraction =
        compose.onAllNodes(hasText(name, substring = true) and hasClickAction()).onLast()
    private fun rowRight(name: String = "Бета"): Float = row(name).fetchSemanticsNode().boundsInRoot.right
    private fun actions(description: String = RENAME) = compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes()

    /** How much of the strip is shown, in px: from its first action's edge to the row's end. Null with no strip. */
    private fun stripShown(name: String = "Бета"): Float? = actions().lastOrNull()?.let { rowRight(name) - it.positionInRoot.x }

    /** One action's cell, in px: 44 dp, laid out in whole pixels. */
    private val cell: Float get() = Math.round(44 * density).toFloat()

    /** The strip's way on a row of [profile], in px: a cell for each of its actions. */
    private fun way(profile: Int = beta, selected: Boolean = false): Float =
        ProfileMenu.items(runBlocking { store.profileEntries.first() }.first { it.index == profile }, selected).size * cell

    /** A drag of [dx] px past the touch slop, over [millis], from a point on the row's name. */
    private fun drag(dx: Float, millis: Long) = row().performTouchInput {
        val from = centerLeft + Offset(40.dp.toPx(), 0f)
        val slop = if (dx < 0) -viewConfiguration.touchSlop else viewConfiguration.touchSlop
        swipe(if (dx < 0) center else from, (if (dx < 0) center else from) + Offset(dx + slop, 0f), durationMillis = millis)
    }

    /** A drag of [dx] px past the touch slop at [dpPerSecond], the finger lifted at that speed. */
    private fun dragAt(dx: Float, dpPerSecond: Int) {
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        drag(dx, Math.round((Math.abs(dx) + slop) / (dpPerSecond * density) * 1000).toLong())
    }

    /** Frame by frame after a tap or a lift that shuts the strip: how much of it each frame shows, until it is gone. */
    private fun framesUntilGone(): List<Float> {
        val seen = ArrayList<Float>()
        for (frame in 1..60) {
            compose.mainClock.advanceTimeByFrame()
            seen += stripShown() ?: return seen
        }
        throw AssertionError("the strip never went: $seen")
    }

    private fun assertGoesAtTheRowsEnd(seen: List<Float>) {
        assertTrue("the strip never moved: $seen", seen.isNotEmpty())
        assertTrue("the strip went back and forth: $seen", seen.zipWithNext().all { (a, b) -> b <= a + 0.5f })
        // a pixel at most: a way a few pixels short of the strip's width leaves that much of it to the end
        assertTrue("the strip was taken away with ${seen.last()} px of it still shown: $seen", seen.last() <= 1.5f)
        // the settle is over half a pixel from its end: the spring's own tail would be six more frames of a strip that
        // is drawn with nothing of it in the row
        assertTrue("the strip was drawn with nothing of it to show: $seen", seen.count { it < 0.5f } <= 1)
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
            // the end the drag began at is not asked again at the lift: 0.6 of the way from shut leaves the strip nearer
            // to open, and it opens; 0.6 of the way back, and it shuts
            drag(-way * 0.6f, 3_000)
            compose.waitForIdle()
            assertEquals("0.6 of the way did not open the strip", way, stripShown() ?: 0f, 0.5f)
            drag(way * 0.6f, 3_000)
            compose.waitForIdle()
            assertTrue("0.6 of the way back did not shut the strip", actions().isEmpty())
        }
    }

    @Test
    fun aLiftIsAFlickFrom125DpASecond() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { shown("Бета") }
            // 40 dp is far under a third of the way: only the lift's speed can open the strip
            dragAt(-40 * density, dpPerSecond = 80)
            compose.waitForIdle()
            assertTrue("a lift at 80 dp a second opened the strip", actions().isEmpty())
            dragAt(-40 * density, dpPerSecond = 190)
            compose.waitForIdle()
            assertEquals("a lift at 190 dp a second did not open the strip", way(), stripShown() ?: 0f, 0.5f)
            dragAt(40 * density, dpPerSecond = 80)
            compose.waitForIdle()
            assertEquals("a lift at 80 dp a second shut the strip", way(), stripShown() ?: 0f, 0.5f)
            dragAt(40 * density, dpPerSecond = 190)
            compose.waitForIdle()
            assertTrue("a lift at 190 dp a second did not shut the strip", actions().isEmpty())
        }
    }

    @Test
    fun anOpenStripKeepsItsPlaceWhenTheRowsActionsChange() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { shown("Бета") }
            // «Альфа» is the open profile: its row is not offered «Скрыть»
            row("Альфа").performTouchInput { swipeLeft() }
            compose.waitForIdle()
            assertTrue("the open profile's row offers «Скрыть»", actions("Скрыть").isEmpty())
            assertEquals("the open strip is not as wide as its way", way(SLOT, selected = true), stripShown("Альфа") ?: 0f, 0.5f)
            // another profile is picked: «Альфа» is one of the others now, and its row gains «Скрыть» under the open strip
            row("Бета").performClick()
            compose.waitUntil(5_000) { runBlocking { store.activeProfile.first() } == beta && actions("Скрыть").isNotEmpty() }
            compose.waitForIdle()
            assertEquals("the strip is not in its place with the new action", way(SLOT), stripShown("Альфа") ?: 0f, 0.5f)
            val delete = actions("Удалить").last()
            assertEquals("the strip's last action is not at the row's end", rowRight("Альфа"), delete.positionInRoot.x + delete.size.width, 0.5f)
        }
    }

    @Test
    fun aRowWhoseActionsChangeWhileItsStripShutsIsLeftShut() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { shown("Бета") }
            row("Альфа").performTouchInput { swipeLeft() }
            compose.waitForIdle()
            // a tap shuts the strip; a frame into its way another profile is picked, and the row gains «Скрыть» under it
            compose.mainClock.autoAdvance = false
            row("Альфа").performTouchInput { click(centerLeft + Offset(40.dp.toPx(), 0f)) }
            compose.mainClock.advanceTimeByFrame()
            runBlocking { store.saveActiveProfile(beta) }
            var gainedOnTheWay = false
            for (frame in 1..60) {
                // the store answers in real time, the frames come by the test's clock
                Thread.sleep(25)
                compose.mainClock.advanceTimeByFrame()
                if (actions().isEmpty()) break
                if (actions("Скрыть").isNotEmpty()) gainedOnTheWay = true
            }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            assertTrue("the row's actions did not change while its strip was shutting: nothing was tried", gainedOnTheWay)
            // the fit to the new actions waits for the way out to end: fitted at once, the strip would stand open again
            assertTrue("the strip a tap was shutting is open again", actions().isEmpty())
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
            assertGoesAtTheRowsEnd(framesUntilGone())

            // and the same after a lift, the way a finger shuts it
            compose.mainClock.autoAdvance = true
            row().performTouchInput { swipeLeft() }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            drag(40 * density, 100)
            assertGoesAtTheRowsEnd(framesUntilGone())
        }
    }
}
