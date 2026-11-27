package com.csqtt.client

import android.os.SystemClock
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
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
 * How Home moves when the tunnel connects and disconnects (the operator, 2026-11-27, on the first build with the new
 * Home: «alot of flickering and moving», then «I don't mind moving, just make it smoother, not "flickering" and
 * "jumping"», «We must not solve "not moving" by adding fixed useless space» and «The content of that area must always
 * be closer to the bottom than to the top»). The button is where it was in every state; no room is kept for a line that
 * is not said; the state's block sits low in its place; what is under a line that comes or goes glides, frame by frame;
 * and a stop reads two words, one after the other.
 */
@RunWith(AndroidJUnit4::class)
class HomeMotionTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun noSystemPromptsOverTheApp() = ShellTestKit.noSystemPromptsOverTheApp()

        private const val SLOT = 20
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store by lazy { SettingsStore(context) }
    private val slot by lazy { SettingsStore(context, ProfileScope.Fixed(SLOT)) }
    private lateinit var layoutBefore: HomeLayout

    // a profile that can connect: one that cannot has its own card on Home while it is off, and no hint under the time
    @Before
    fun aProfileThatCanConnect() = runBlocking {
        layoutBefore = store.homeLayout.first()
        store.clearProfile(SLOT)
        store.saveActiveProfile(SLOT)
        store.saveCsqttLinkMode(false)
        slot.saveFormAddress("203.0.113.20", null, store.profileEpoch.first())
        store.saveConnectionPassword("pw-server", forProfile = SLOT)
        slot.saveVkHashesOnly("H".repeat(20))
    }

    @After
    fun restore() {
        compose.mainClock.autoAdvance = true
        off()
        runBlocking {
            store.saveActiveProfile(0)
            store.deleteProfile(SLOT)
            store.saveHomeLayout(layoutBefore)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> hidden(name: String): MutableStateFlow<T> =
        TunnelManager::class.java.getDeclaredField(name).apply { isAccessible = true }.get(TunnelManager) as MutableStateFlow<T>

    // the tunnel's states as the manager would say them, with no tunnel: the screen reads nothing else
    private fun off() {
        TunnelManager.running.value = false; TunnelManager.starting.value = false; TunnelManager.stopping.value = false
        TunnelManager.vpnReady.value = false; TunnelManager.activeWorkers.value = 0; TunnelManager.targetWorkers.value = 0
        TunnelManager.failure.value = null; TunnelManager.traffic.value = null; TunnelManager.startProgress.value = null
        TunnelManager.uptime.value = UptimeMark()
        hidden<Boolean>("tunconfThisProcess").value = false; hidden<Boolean>("everConnected").value = false
        hidden<Int>("readyAhead").value = 0
    }

    private fun connecting() {
        TunnelManager.starting.value = true; TunnelManager.running.value = true; TunnelManager.targetWorkers.value = 10
        TunnelManager.uptime.value = UptimeMark(startedAtMs = SystemClock.elapsedRealtime() - 3_000)
        TunnelManager.startProgress.value = TunnelStartupStage.WAITING_FOR_TURN_OR_PEER
        hidden<Int>("readyAhead").value = 3
    }

    private fun connectedWithStreamsComing() {
        TunnelManager.starting.value = false; hidden<Boolean>("tunconfThisProcess").value = true; TunnelManager.vpnReady.value = true
        hidden<Int>("readyAhead").value = 0; TunnelManager.activeWorkers.value = 9
        TunnelManager.traffic.value = TrafficSnapshot(downBytesPerSecond = 1_258_000, upBytesPerSecond = 245_000, totalDown = 80_000_000, totalUp = 10_200_000, hasSpeed = true)
    }

    private fun shown(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun bounds(of: SemanticsMatcher): Rect = compose.onAllNodes(of).onFirst().fetchSemanticsNode().boundsInRoot

    /** Where a thing is: the middle of it, which a button drawn larger when the tunnel is on keeps. */
    private fun middle(of: SemanticsMatcher): Float = bounds(of).let { (it.top + it.bottom) / 2 }

    private val button = hasContentDescription("Включить VPN") or hasContentDescription("Отключить VPN") or
        hasContentDescription("Ожидание отключения VPN") or hasContentDescription("Остановка…")
    private val profiles = hasText("Профили")

    /** A connect and a disconnect, state by state, each waited for by what it shows and by the motion's end; [read] in each. */
    private fun <T> throughAConnection(read: (state: String) -> T): Map<String, T> {
        val seen = linkedMapOf<String, T>()
        fun at(state: String, shows: () -> Boolean) {
            compose.waitUntil(5_000) { shows() }
            compose.waitForIdle()
            seen[state] = read(state)
        }
        off(); at("off") { shown("Отключено") }
        connecting(); at("connecting") { shown("Подключение…") }
        connectedWithStreamsComing(); at("connected, streams coming") { shown("Подключено") && shown("9 / 10", substring = true) }
        // every stream is up: their count is news for a moment more, and then is not said
        TunnelManager.activeWorkers.value = 10
        at("connected") { shown("Подключено") && !shown("/ 10", substring = true) }
        TunnelManager.stopping.value = true; at("stopping") { shown("Остановка…") }
        off(); at("off again") { shown("Отключено") }
        return seen
    }

    @Test
    fun theButtonOfTheListIsWhereItWasInEveryStateOfAConnection() {
        runBlocking { store.saveHomeLayout(HomeLayout.List) }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { shown("Профили") }
            val places = throughAConnection { middle(button) }
            assertEquals("the button moved: $places", 1, places.values.map { Math.round(it) }.toSet().size)
        }
    }

    /** On the bench's screen, which has room for a connected card under the card at rest; a screen too low for that moves it. */
    @Test
    fun theButtonOfOneConnectionIsWhereItWasInEveryStateOfAConnection() {
        runBlocking { store.saveHomeLayout(HomeLayout.One) }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { shown("Отключено") }
            val places = throughAConnection { middle(button) }
            assertEquals("the button moved: $places", 1, places.values.map { Math.round(it) }.toSet().size)
        }
    }

    /**
     * The list follows the last line that is said, at one distance in every state: no room is kept for a line that is not
     * (the count of streams once it is no news; the hint of a profile that cannot start). Through a stop the line's
     * place is kept for the hint that follows, so the list is where it was. And the block sits low in its place, more
     * room above the button than under the last line.
     */
    @Test
    fun theListFollowsTheLastLineSaidAndTheBlockSitsLowInItsPlace() {
        runBlocking { store.saveHomeLayout(HomeLayout.List) }
        val hint = hasText("Нажмите, чтобы подключиться")
        // the last line of the block in each state; none while a stop keeps the place empty
        val last = mapOf(
            "off" to hint,
            "connecting" to hasText("Потоки: 3 / 10"),
            "connected, streams coming" to hasText("Потоки: 9 / 10"),
            "connected" to hasText("всего", substring = true),
            "off again" to hint,
        )
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { shown("Профили") }
            class Seen(val list: Float, val under: Float?, val above: Float)
            val seen = throughAConnection { state ->
                // the line first: the hint comes back a moment after «Отключено», and the list is read once it has
                last[state]?.let { line ->
                    compose.waitUntil(5_000) { compose.onAllNodes(line).fetchSemanticsNodes().isNotEmpty() }
                    compose.waitForIdle()
                }
                val list = bounds(profiles).top
                Seen(
                    list = list,
                    under = last[state]?.let { list - bounds(it).bottom },
                    above = bounds(button).top - bounds(hasText("сборка", substring = true)).bottom,
                )
            }
            val said = seen.filterValues { it.under != null }
            assertEquals("room is kept under the last line in some state: ${said.mapValues { it.value.under }}", 1,
                said.values.map { Math.round(it.under!!) }.toSet().size)
            // one line under the time when off and when connected; the streams' line under it while their count is news
            val places = seen.mapValues { Math.round(it.value.list) }
            assertEquals("$places", places.getValue("off"), places.getValue("connected"))
            assertEquals("$places", places.getValue("connected"), places.getValue("off again"))
            assertEquals("$places", places.getValue("connecting"), places.getValue("connected, streams coming"))
            assertTrue("the streams' line takes no room: $places", places.getValue("connected, streams coming") > places.getValue("connected"))
            assertEquals("the list moved for a stop: $places", places.getValue("connected"), places.getValue("stopping"))
            for ((state, it) in said) {
                assertTrue("in «$state» the block is nearer the top: ${it.above} above the button, ${it.under} under the last line", it.above > it.under!!)
            }

            // a profile that cannot start has no hint to show: the list comes up to the time, at the same distance
            runBlocking { slot.saveVkHashesOnly("") }
            compose.waitUntil(5_000) { compose.onAllNodes(hint).fetchSemanticsNodes().isEmpty() }
            compose.waitForIdle()
            val underTheTime = bounds(profiles).top - bounds(hasText("00:00:00")).bottom
            assertTrue("no line is said and the list is not higher: ${bounds(profiles).top} against ${places.getValue("off")}", bounds(profiles).top < places.getValue("off"))
            // the time's own box is not a line's, so its distance is told apart from a kept room by its size: under a line's height
            assertTrue("room is kept under the time with no line said: $underTheTime, under a line ${said.values.first().under}", underTheTime < said.values.first().under!! + 4f)
        }
    }

    /** The list's place, frame by frame with the clock held, from a [change] until it has stood still for a while. */
    private fun listThrough(change: () -> Unit): List<Float> {
        compose.waitForIdle()
        val seen = arrayListOf(bounds(profiles).top)
        compose.mainClock.autoAdvance = false
        try {
            change()
            var still = 0
            // what the screen reads comes on real threads: a moment of real time for it beside each frame of the clock
            while (seen.size < 400 && (still < 30 || seen.last() == seen.first())) {
                Thread.sleep(2)
                compose.mainClock.advanceTimeByFrame()
                val now = bounds(profiles).top
                still = if (now == seen.last()) still + 1 else 0
                seen += now
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        return seen
    }

    /**
     * What is under a line that comes or goes glides to its place: with the clock held and the frames taken one by one,
     * the list moves over many of them, never back, and no frame takes it more than half the way.
     */
    @Test
    fun theListGlidesToItsPlaceWhenALineComesAndWhenItGoes() {
        runBlocking { store.saveHomeLayout(HomeLayout.List) }
        fun assertGlide(what: String, seen: List<Float>, down: Boolean) {
            val way = if (down) seen.last() - seen.first() else seen.first() - seen.last()
            val steps = seen.zipWithNext { a, b -> if (down) b - a else a - b }
            assertTrue("$what: the list did not move by a line: ${seen.first()} to ${seen.last()}", way > 20f)
            assertTrue("$what: it went back on its way: $steps", steps.all { it > -0.5f })
            assertTrue("$what: it jumped: $steps", steps.max() <= way / 2)
            assertTrue("$what: it took fewer than five frames: $steps", steps.count { it > 0.5f } >= 5)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { shown("Профили") }
            off()
            compose.waitUntil(5_000) { shown("Нажмите, чтобы подключиться") }
            // a start: its stage takes the hint's place, and the streams' line comes under it
            assertGlide("a line comes", listThrough { connecting() }, down = true)
            compose.waitUntil(5_000) { shown("Потоки: 3 / 10") }
            // the start is given up: the streams' line goes
            assertGlide("a line goes", listThrough { off() }, down = false)
        }
    }

    /**
     * A stop reads «Остановка…» and then «Отключено», and nothing else: not «Отключено» for a frame before the wait after
     * the stop is seen (it is started by an effect, a frame late), and no third word for that wait. Every frame is read.
     */
    @Test
    fun aStopReadsTheStopAndThenOffWithNoWordBetween() {
        val words = listOf("Подключено", "Остановка…", "Отключено", "Ожидание")
        for (layout in listOf(HomeLayout.List, HomeLayout.One)) {
            runBlocking { store.saveHomeLayout(layout) }
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(10_000) { shown("Отключено") }
                off(); connecting(); connectedWithStreamsComing()
                TunnelManager.activeWorkers.value = 10
                // the wait after a stop is the shell's, started when a run that was on is seen to end
                compose.waitUntil(5_000) { shown("Подключено") }
                compose.waitForIdle()
                val read = arrayListOf<String>()
                fun frames(count: Int) = repeat(count) {
                    // the wait is 300 ms of real time: the frames are given it
                    Thread.sleep(4)
                    compose.mainClock.advanceTimeByFrame()
                    val now = words.filter { shown(it) }.joinToString("+")
                    if (read.lastOrNull() != now) read += now
                }
                compose.mainClock.autoAdvance = false
                try {
                    TunnelManager.stopping.value = true
                    frames(12)
                    off()
                    frames(150)
                } finally {
                    compose.mainClock.autoAdvance = true
                }
                assertEquals("$layout", listOf("Подключено", "Остановка…", "Отключено"), listOf("Подключено") + read.dropWhile { it == "Подключено" })
            }
        }
    }
}
