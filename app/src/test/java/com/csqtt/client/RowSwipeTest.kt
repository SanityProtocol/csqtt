package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a swiped row's strip rests when the finger lifts (the operator, 2026-11-28: «it needs to slide easier»): a flick
 * goes by its direction alone; a slow lift by a third of the strip's way from the end the drag found it nearer to.
 */
class RowSwipeTest {
    // four actions on a 2.75× screen: 176 dp of way, and 125 dp a second for a flick
    private val open = -484f
    private val flick = 343.75f
    private val third = -open / 3
    private fun rest(start: Float, value: Float, velocity: Float) = RowSwipe.rest(start, value, open, velocity, flick)

    @Test fun aFlickLeftOpensHoweverShortItWas() {
        assertEquals(open, rest(start = 0f, value = -20f, velocity = -flick), 0f)
        assertEquals(open, rest(start = 0f, value = -20f, velocity = -4000f), 0f)
    }

    @Test fun aFlickRightShutsHoweverShortItWas() {
        assertEquals(0f, rest(start = open, value = open + 20f, velocity = flick), 0f)
        assertEquals(0f, rest(start = open, value = open + 20f, velocity = 4000f), 0f)
    }

    @Test fun aFlicksDirectionWinsOverWhereTheStripIs() {
        // dragged almost open, then thrown back; dragged almost shut, then thrown out again
        assertEquals(0f, rest(start = 0f, value = open + 10f, velocity = flick), 0f)
        assertEquals(open, rest(start = open, value = -10f, velocity = -flick), 0f)
    }

    @Test fun aLiftJustUnderAFlickIsASlowOne() {
        assertEquals(0f, rest(start = 0f, value = -20f, velocity = -(flick - 1f)), 0f)
        assertEquals(open, rest(start = open, value = open + 20f, velocity = flick - 1f), 0f)
    }

    @Test fun aSlowLiftGoesOnAfterAThirdOfTheWay() {
        assertEquals(0f, rest(start = 0f, value = -(third - 1f), velocity = 0f), 0f)
        assertEquals(open, rest(start = 0f, value = -(third + 1f), velocity = 0f), 0f)
        assertEquals(open, rest(start = open, value = open + third - 1f, velocity = 0f), 0f)
        assertEquals(0f, rest(start = open, value = open + third + 1f, velocity = 0f), 0f)
    }

    @Test fun aStripCaughtOnItsWayCountsFromTheEndItWasNearerTo() {
        // caught near shut and moved on past a third from shut: it opens; caught near open and moved a little: it stays open
        assertEquals(open, rest(start = -100f, value = -(third + 5f), velocity = 0f), 0f)
        assertEquals(open, rest(start = open + 80f, value = open + 100f, velocity = 0f), 0f)
    }

    @Test fun theEndTheDragBeganAtIsNotAskedAgainAtTheLift() {
        // dragged slowly from shut to 0.6 of the way: it opens, though the strip is nearer to open when the finger lifts
        assertEquals(open, rest(start = 0f, value = open * 0.6f, velocity = 0f), 0f)
        // and from open to 0.4 of the way: it shuts
        assertEquals(0f, rest(start = open, value = open * 0.4f, velocity = 0f), 0f)
    }

    @Test fun theNearerEndIsToldAtHalfOfTheWay() {
        // caught at 0.4 of the way and let go: nearer to shut, and 0.4 from it is past a third — it opens
        assertEquals(open, rest(start = open * 0.4f, value = open * 0.4f, velocity = 0f), 0f)
        // caught at 0.6: nearer to open, 0.4 from it — it shuts
        assertEquals(0f, rest(start = open * 0.6f, value = open * 0.6f, velocity = 0f), 0f)
    }

    @Test fun aRowThatWasNotMovedStaysWhereItWas() {
        assertEquals(0f, rest(start = 0f, value = 0f, velocity = 0f), 0f)
        assertEquals(open, rest(start = open, value = open, velocity = 0f), 0f)
    }
}
