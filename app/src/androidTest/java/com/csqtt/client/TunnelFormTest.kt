package com.csqtt.client

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.csqtt.client.ShellTestKit.recreateFromSavedStateAlone
import com.csqtt.client.ui.shell.SecretDrafts
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
 * P3 B3: the «Туннель» form on a device. A field is stored when it is left — its focus lost, «Готово» pressed, the tab
 * left — and not while it is typed; an address typed with its port is taken apart into the two fields; a password the
 * server could not take is said and not stored; a start refused for the form's settings opens the tab on what it lacks.
 */
@RunWith(AndroidJUnit4::class)
class TunnelFormTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun noSystemPromptsOverTheApp() = ShellTestKit.noSystemPromptsOverTheApp()

        private const val SLOT = 16
        private const val OTHER = 17
        private const val ADDRESS = "Адрес сервера"
        private const val PORT = "Порт"
        private const val PASSWORD = "Пароль подключения"
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store by lazy { SettingsStore(context) }
    private val slot by lazy { SettingsStore(context, ProfileScope.Fixed(SLOT)) }
    private lateinit var layoutBefore: HomeLayout

    @Before
    fun anOwnServerProfile() = runBlocking {
        layoutBefore = store.homeLayout.first()
        store.saveHomeLayout(HomeLayout.One)
        store.clearProfile(SLOT)
        store.saveActiveProfile(SLOT)
        store.saveCsqttLinkMode(false)
    }

    @After
    fun restore() = runBlocking {
        store.saveActiveProfile(0)
        store.deleteProfile(SLOT)
        store.saveHomeLayout(layoutBefore)
    }

    private fun shown(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()
    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))
    private fun typed(label: String): String? = compose.onAllNodes(hasSetTextAction() and hasText(label))
        .fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(SemanticsProperties.EditableText)?.text
    private fun peer() = runBlocking { slot.peer.first() }
    private fun port() = runBlocking { slot.serverPeerPort.first() }
    private fun password() = runBlocking { slot.connectionPassword.first() }

    private fun openTunnel() {
        compose.waitUntil(10_000) { shown("Туннель") }
        compose.onNodeWithText("Туннель").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction() and hasText(ADDRESS)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aFieldIsStoredWhenItIsLeftAndAnAddressWithItsPortIsTakenApart() {
        runBlocking { slot.saveFormAddress("203.0.113.1", null, store.profileEpoch.first()) }
        ActivityScenario.launch(MainActivity::class.java).use {
            openTunnel()
            compose.waitUntil(5_000) { typed(ADDRESS) == "203.0.113.1" }
            field(ADDRESS).performTextReplacement("203.0.113.9:46100")
            compose.waitForIdle()
            assertEquals("typed, and not left yet: a running tunnel must not restart on half an address", "203.0.113.1", peer())

            // the field is left for another: stored, each half in its own field
            field(PORT).performClick()
            compose.waitUntil(5_000) { peer() == "203.0.113.9" && port() == 46100 }
            compose.waitUntil(5_000) { typed(ADDRESS) == "203.0.113.9" && typed(PORT) == "46100" }

            // a password is stored when its field is left; a space pasted with it is dropped, as it cannot be seen
            field(PASSWORD).performTextReplacement("pw-first ")
            field(PASSWORD).performImeAction()
            compose.waitUntil(5_000) { password() == "pw-first" }
            // one the server cannot take is said, and the stored one is left as it was
            field(PASSWORD).performTextReplacement("pw|bad")
            field(PASSWORD).performImeAction()
            compose.waitUntil(5_000) { shown("Без пробелов и «|», не длиннее 128 байт (64 буквы кириллицей)") }
            assertEquals("pw-first", password())
            field(PASSWORD).performTextReplacement("pw-new")
            field(PASSWORD).performImeAction()
            compose.waitUntil(5_000) { password() == "pw-new" }

            // a port that is none is said and not stored; an emptied field left as it is means "as it was"
            field(PORT).performTextReplacement("0")
            field(ADDRESS).performClick()
            compose.waitUntil(5_000) { shown("От 1 до 65535") }
            assertEquals(46100, port())
            field(PORT).performTextReplacement("")
            field(ADDRESS).performClick()
            compose.waitUntil(5_000) { typed(PORT) == "46100" }
        }
    }

    /**
     * The operator, 2026-11-27: the count of streams moves in fives and is said per hash; the line has four zones,
     * counted per hash (green to 20, blue to 50, orange to 70, red above), and a thumb let go in the orange or the red
     * zone, from a zone below it, is asked about before the count is stored.
     */
    @Test
    fun aCountIsAskedAboutWhenItGoesUpIntoTheOrangeOrTheRedZone() {
        runBlocking {
            slot.saveFormAddress("203.0.113.1", null, store.profileEpoch.first())
            slot.saveVkHashesOnly("H".repeat(20))
        }
        fun streams() = runBlocking { slot.workersPerHash.first() }
        val track = hasContentDescription("Потоки")
        // a finger on the line at a stop: the track's own arithmetic, a stop every five from 5 to 100
        fun tap(stop: Int) = compose.onNode(track).performScrollTo().performTouchInput {
            val inset = 10.dp.toPx()
            click(Offset(inset + (width - 2 * inset) * (stop - 5) / 95f, centerY))
        }
        // the thumb at a count: with one hash the pill says the number alone
        fun at(count: Int) = shown(count.toString()) &&
            compose.onAllNodes(track and hasProgressBarRangeInfo(ProgressBarRangeInfo(count.toFloat(), 5f..100f, 18))).fetchSemanticsNodes().isNotEmpty()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openTunnel()
            compose.onNodeWithText("Дополнительно").performScrollTo().performClick()
            // a new profile starts at twenty, the top of the green zone
            compose.waitUntil(5_000) { at(20) }
            assertTrue("one hash: fifty at most is recommended", shown("Рекомендуем не больше 50 на хеш"))

            // the blue zone asks nothing
            tap(40)
            compose.waitUntil(5_000) { streams() == 40 }
            assertFalse(shown("Внимание!"))

            // up into the orange zone: asked about, and nothing stored yet
            tap(60)
            compose.waitUntil(5_000) { shown("Внимание!") }
            assertEquals(40, streams())
            compose.onNodeWithText("Отмена").performClick()
            compose.waitUntil(5_000) { !shown("Внимание!") }
            assertEquals(40, streams())
            assertTrue("the thumb is back where the store has it", at(40))

            // the same again; the question outlives the screen being made again, and «Понятно» stores the count
            tap(60)
            compose.waitUntil(5_000) { shown("Внимание!") }
            scenario.recreateFromSavedStateAlone()
            compose.waitUntil(10_000) { shown("Внимание!") }
            assertEquals(40, streams())
            compose.onNodeWithText("Понятно").performClick()
            compose.waitUntil(5_000) { streams() == 60 }
            compose.waitUntil(5_000) { at(60) && shown("Больше рекомендуемого: не больше 50 на хеш") }

            // up into the red zone: the danger question, and then within the zone nothing more is asked
            tap(85)
            compose.waitUntil(5_000) { shown("Опасно!") }
            assertEquals(60, streams())
            compose.onNodeWithText("Понятно").performClick()
            compose.waitUntil(5_000) { streams() == 85 }
            compose.waitUntil(5_000) { shown("Опасно: больше 70 на хеш") }
            tap(95)
            compose.waitUntil(5_000) { streams() == 95 }
            assertFalse(shown("Опасно!"))
            // and down a zone nothing is asked either
            tap(60)
            compose.waitUntil(5_000) { streams() == 60 }
            assertFalse(shown("Внимание!"))

            // a second hash: the zones are counts per hash, so they stretch — the whole line is green and blue now
            runBlocking { slot.saveVkHashesOnly("H".repeat(20) + "," + "J".repeat(20)) }
            compose.waitUntil(5_000) { shown("60 (30 на хеш)") }
            assertFalse(shown("Больше рекомендуемого: не больше 50 на хеш"))
            tap(95)
            compose.waitUntil(5_000) { streams() == 95 }
            assertFalse(shown("Внимание!") || shown("Опасно!"))
            assertTrue(shown("95 (50 на хеш)"))
        }
    }

    @Test
    fun theLastFieldTypedIsStoredWhenTheTabIsLeft() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openTunnel()
            field(ADDRESS).performTextReplacement("198.51.100.7")
            compose.waitForIdle()
            assertEquals("", peer())
            compose.onNodeWithText("Главная").performClick()
            compose.waitUntil(5_000) { peer() == "198.51.100.7" }
        }
    }

    /**
     * A field typed and not left, then its profile deleted from «Профили», which opens over the tab with the field
     * still in focus: the field's write lands after the deletion, and must not bring the profile back.
     */
    @Test
    fun aDeletedProfileIsNotBroughtBackByTheFieldTypedLast() {
        runBlocking {
            slot.saveFormAddress("203.0.113.1", null, store.profileEpoch.first())
            store.renameProfile(SLOT, "Свой")
        }
        // in use: with no other profile left the emptied slot stays the open one, and is listed as such
        fun there() = SLOT in runBlocking { store.occupiedProfiles.first() }
        val profileRow = SemanticsMatcher("the tab's row of its profile") { node ->
            node.config.getOrNull(SemanticsActions.OnClick)?.label == "Профиль «Свой». Выбрать другой"
        }
        val menu = hasContentDescription("Действия: Свой")
        ActivityScenario.launch(MainActivity::class.java).use {
            openTunnel()
            compose.waitUntil(5_000) { typed(ADDRESS) == "203.0.113.1" }
            assertTrue(there())
            field(ADDRESS).performTextReplacement("203.0.113.99")
            compose.waitForIdle()
            assertEquals("typed and not left: not stored yet", "203.0.113.1", peer())

            compose.onNode(profileRow).performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(menu).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(menu).onLast().performClick()
            compose.onAllNodes(hasText("Удалить")).onLast().performClick()
            compose.waitUntil(5_000) { shown("Удалить профиль «Свой»?") }
            compose.onAllNodes(hasText("Удалить")).onLast().performClick()
            compose.waitUntil(5_000) { !there() }

            // the tab is made anew for the profile that is open now; what the old one's field held goes nowhere
            compose.waitForIdle()
            Thread.sleep(1_000)
            assertFalse("the deleted profile is back", there())
            assertEquals("", peer())
        }
    }

    /**
     * The review's F1: a field left by a tab tap is stored, and its draft must not outlive that. When another screen
     * later stores a new value under it (a deploy's end writes the port and the password), the tab shows the new
     * value, and leaving the tab again writes nothing of the old text back.
     */
    @Test
    fun aFieldLeftByATabTapFollowsWhatAnotherScreenStoresLater() {
        runBlocking { slot.saveFormAddress("203.0.113.1", null, store.profileEpoch.first()) }
        fun leaveAndSettle() {
            compose.onNodeWithText("Главная").performClick()
            compose.waitForIdle()
            Thread.sleep(1_000)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            // the port: its draft is in the tab's saved state
            openTunnel()
            compose.waitUntil(5_000) { typed(ADDRESS) == "203.0.113.1" }
            field(PORT).performTextReplacement("46100")
            compose.onNodeWithText("Главная").performClick()
            compose.waitUntil(5_000) { port() == 46100 }
            // another screen's write, as a deploy's end makes it
            runBlocking { slot.saveFormPort(46200, store.profileEpoch.first()) }
            openTunnel()
            compose.waitUntil(5_000) { typed(PORT) == "46200" }

            // the password: its draft is in memory for as long as the process lives
            field(PASSWORD).performTextReplacement("pw-typed")
            compose.onNodeWithText("Главная").performClick()
            compose.waitUntil(5_000) { password() == "pw-typed" }
            runBlocking { store.saveConnectionPassword("pw-deployed", forProfile = SLOT) }
            openTunnel()
            compose.waitUntil(5_000) { typed(PORT) == "46200" }
            // and leaving the tab again writes nothing of the old text back
            leaveAndSettle()
            assertEquals("the old text was written back over the new port", 46200, port())
            assertEquals("the old text was written back over the new password", "pw-deployed", password())
        }
    }

    /**
     * The verify of the fix round (V1): a draft is written once. A field's text that was written as its screen was
     * left does not come back with the screen when the store has been put back to the value it was typed over — the
     * end of a deploy writes the server's own port and password again — and leaving the tab again writes nothing.
     */
    @Test
    fun aFieldWrittenAsItsScreenWasLeftDoesNotComeBackOverTheValueItWasTypedOver() {
        runBlocking {
            slot.saveFormAddress("203.0.113.1", null, store.profileEpoch.first())
            store.saveConnectionPassword("pw-server", forProfile = SLOT)
        }
        fun leaveAndSettle() {
            compose.onNodeWithText("Главная").performClick()
            compose.waitForIdle()
            Thread.sleep(1_000)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // the port, left by a tab tap with the focus still in it: its draft is in the tab's saved state
            openTunnel()
            compose.waitUntil(5_000) { typed(PORT) == "46000" }
            field(PORT).performTextReplacement("46100")
            compose.onNodeWithText("Главная").performClick()
            compose.waitUntil(5_000) { port() == 46100 }
            // a deploy's end: the server's own again, the value the port was typed over
            runBlocking { slot.saveFormPort(46000, store.profileEpoch.first()) }
            openTunnel()
            compose.waitUntil(5_000) { typed(PORT) == "46000" }
            leaveAndSettle()
            assertEquals("the port typed before was written back over the server's", 46000, port())

            // the password, the same way: its draft is in memory for as long as the process lives, and it is masked
            openTunnel()
            field(PASSWORD).performTextReplacement("pw-typed")
            compose.onNodeWithText("Главная").performClick()
            compose.waitUntil(5_000) { password() == "pw-typed" }
            runBlocking { store.saveConnectionPassword("pw-server", forProfile = SLOT) }
            openTunnel()
            leaveAndSettle()
            assertEquals("the password typed before was written back over the server's", "pw-server", password())

            // left by the app going to the background: the same
            openTunnel()
            field(PORT).performTextReplacement("46300")
            scenario.moveToState(Lifecycle.State.CREATED)
            compose.waitUntil(5_000) { port() == 46300 }
            runBlocking { slot.saveFormPort(46000, store.profileEpoch.first()) }
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(5_000) { typed(PORT) == "46000" }
            leaveAndSettle()
            assertEquals("the port typed before the app was left was written back", 46000, port())

            // an address left with its port in it is the two it holds when the screen is back, not the text as typed
            openTunnel()
            field(ADDRESS).performTextReplacement("203.0.113.1:5000")
            compose.onNodeWithText("Главная").performClick()
            compose.waitUntil(5_000) { port() == 5000 }
            openTunnel()
            compose.waitUntil(5_000) { typed(ADDRESS) == "203.0.113.1" && typed(PORT) == "5000" }
        }
    }

    /**
     * The second round's check (C1): the note of a draft that left with its screen is saved with the screen, as the
     * draft is. After the process was killed in the background the two come back together, and the draft still does
     * not come back over the store: here the screen is made again from its saved state alone, with nothing of the
     * form left in the process's memory.
     */
    @Test
    fun aFieldWrittenAsItsScreenWasLeftDoesNotComeBackAfterTheProcessWasKilled() {
        runBlocking { slot.saveFormAddress("203.0.113.1", null, store.profileEpoch.first()) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openTunnel()
            compose.waitUntil(5_000) { typed(PORT) == "46000" }
            field(PORT).performTextReplacement("46100")
            compose.onNodeWithText("Главная").performClick()
            compose.waitUntil(5_000) { port() == 46100 }
            // a deploy's end writes the server's own again; then the app is left and its process killed
            runBlocking { slot.saveFormPort(46000, store.profileEpoch.first()) }
            scenario.onActivity { SecretDrafts.clear("tunnel:") }
            scenario.recreateFromSavedStateAlone()
            openTunnel()
            compose.waitUntil(5_000) { typed(PORT) == "46000" }
            compose.onNodeWithText("Главная").performClick()
            compose.waitForIdle()
            Thread.sleep(1_000)
            assertEquals("the port typed before the process was killed was written back", 46000, port())
        }
    }

    /**
     * The verify of the fix round (V3) and the review's F10: the marks of a refused start are the profile's. They are
     * still there after another profile was deleted beside it, and gone once the profile itself is: the slot it held
     * does not open full of errors for it.
     */
    @Test
    fun theMarksOfARefusedStartStayWithTheirProfileAndGoWithIt() {
        val banner = "Запуск не начат: исправьте поля ниже"
        ActivityScenario.launch(MainActivity::class.java).use {
            val start = hasContentDescription("Включить VPN")
            compose.waitUntil(10_000) { compose.onAllNodes(start).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(start).performClick()
            compose.waitUntil(10_000) { shown(banner) }
            // the form takes shape: it is a profile now, still short of a password and a hash
            field(ADDRESS).performTextReplacement("203.0.113.9")
            compose.onNodeWithText("Главная").performClick()
            compose.waitUntil(5_000) { peer() == "203.0.113.9" }
            // the screen has seen it stored: nobody deletes another profile within a frame of leaving a field
            compose.waitForIdle()

            // another profile is deleted: every form is made anew, and this one is still marked
            runBlocking { store.deleteProfile(OTHER) }
            openTunnel()
            compose.waitUntil(5_000) { shown(banner) }
            assertTrue(shown("Укажите пароль подключения"))

            // the profile itself is deleted: its slot, opened again, is an empty form with nothing said of it
            runBlocking {
                store.deleteProfile(SLOT)
                store.saveActiveProfile(SLOT)
                store.saveCsqttLinkMode(false)
            }
            compose.waitUntil(5_000) { typed(ADDRESS) == "" }
            compose.waitForIdle()
            Thread.sleep(500)
            assertFalse("the emptied slot is marked for the profile that was deleted from it", shown(banner))
            assertFalse(shown("Укажите пароль подключения"))

            // and what is typed into the emptied slot next is another profile: the marks went with the first for good
            field(ADDRESS).performTextReplacement("203.0.113.10")
            compose.onNodeWithText("Главная").performClick()
            compose.waitUntil(5_000) { peer() == "203.0.113.10" }
            openTunnel()
            compose.waitForIdle()
            Thread.sleep(500)
            assertFalse("a profile typed into the emptied slot is marked for the one deleted from it", shown(banner))
        }
    }

    /**
     * The operator's note of 2026-11-27: nothing is held while the tunnel runs. A setting the run took at its start
     * is changed all the same — it is stored for the next connection, and says so.
     */
    @Test
    fun aSettingIsChangedWhileTheTunnelRunsAndSaysItIsForTheNextConnection() {
        runBlocking {
            slot.saveFormAddress("203.0.113.1", null, store.profileEpoch.first())
            slot.saveVkHashesOnly("H".repeat(20))
        }
        val next = "Применится при следующем подключении."
        val track = hasContentDescription("Потоки")
        ActivityScenario.launch(MainActivity::class.java).use {
            openTunnel()
            compose.onNodeWithText("Дополнительно").performScrollTo().performClick()
            compose.waitUntil(5_000) { shown("MTU") }
            assertFalse("said with no tunnel running", shown(next))
            // the tunnel as the manager would say it, with no tunnel: the screen reads nothing else
            TunnelManager.running.value = true
            try {
                compose.waitUntil(5_000) { shown(next) }
                assertFalse(shown("Пока туннель включён", substring = true))
                // the count of streams, by a finger on the line at 30
                compose.onNode(track).performScrollTo().performTouchInput {
                    val inset = 10.dp.toPx()
                    click(Offset(inset + (width - 2 * inset) * (30 - 5) / 95f, centerY))
                }
                compose.waitUntil(5_000) { runBlocking { slot.workersPerHash.first() } == 30 }
                // the MTU
                compose.onNodeWithText("1500").performScrollTo().performClick()
                compose.waitUntil(5_000) { shown("1280") }
                compose.onAllNodes(hasText("1280")).onLast().performClick()
                compose.waitUntil(5_000) { runBlocking { slot.vpnMtu.first() } == 1280 }
            } finally {
                TunnelManager.running.value = false
            }
        }
    }

    /**
     * The review's F3, and its F12: what a start connects with leaves out a hash a check found dead — the one list
     * the «Потоки» zones count too — is every hash again when a check found them all dead, and takes a dead one back
     * once what the check said of it is forgotten (it was entered again).
     */
    @Test
    fun aStartLeavesOutAHashACheckFoundDead() = runBlocking {
        val live = "L".repeat(20)
        val dead = "D".repeat(20)
        suspend fun connectsWith() = buildTunnelStartIntent(context, slot)!!.getStringExtra("vk_hashes")
        slot.saveFormAddress("203.0.113.21", null, store.profileEpoch.first())
        store.saveConnectionPassword("pw-server", forProfile = SLOT)
        slot.saveVkHashesOnly("$live,$dead")
        assertEquals("$live,$dead", connectsWith())
        slot.mergeVkHashCheckResults(mapOf(dead to VkHashValidationStatus.Invalid))
        assertEquals(live, connectsWith())
        slot.mergeVkHashCheckResults(mapOf(live to VkHashValidationStatus.Invalid))
        assertEquals("every one dead: a start tries them all", "$live,$dead", connectsWith())
        slot.forgetVkHashCheckResults(setOf(dead))
        assertEquals("entered again, the dead one is the one to try", dead, connectsWith())
    }

    /**
     * The review's F2: a settings file written before «Экстра потоки» went names the switch, and its count comes in
     * as it ran — 2.5.3's stored default of 18 as the 10 it was. This build's own file names no switch: its count is
     * the count.
     */
    @Test
    fun aSettingsFileFromBeforeTheSwitchWentBringsTheCountItRan() = runBlocking {
        val file = JSONObject(store.exportConfigJson(false, true, false, false))
        suspend fun imported(count: Int, switch: Boolean?): Int {
            file.getJSONObject("deploy").apply {
                put("workers_per_hash", count)
                if (switch == null) remove("extra_workers") else put("extra_workers", switch)
            }
            assertTrue(store.importConfigJson(file.toString(), false, true, false, false))
            return slot.workersPerHash.first()
        }
        assertEquals("2.5.3's default ran as 10", 10, imported(18, false))
        assertEquals(5, imported(8, false))
        assertEquals("above 70 only the switch ran", 70, imported(95, false))
        assertEquals(90, imported(95, true))
        assertEquals("this build's own file", 15, imported(15, null))
    }

    @Test
    fun aRefusedStartOpensTheTabOnWhatTheFormLacks() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val start = hasContentDescription("Включить VPN")
            compose.waitUntil(10_000) { compose.onAllNodes(start).fetchSemanticsNodes().isNotEmpty() }
            // nothing is said of an empty form before a start was asked for
            compose.onNode(start).performClick()
            compose.waitUntil(10_000) { shown("Запуск не начат: исправьте поля ниже") }
            assertTrue(shown("Укажите адрес сервера"))
            assertTrue(shown("Укажите пароль подключения"))
            assertTrue(shown("Добавьте хотя бы один хеш звонка"))

            // what was lacking is filled in: its error goes with it
            field(ADDRESS).performTextReplacement("203.0.113.9")
            compose.waitUntil(5_000) { !shown("Укажите адрес сервера") }
        }
    }
}
