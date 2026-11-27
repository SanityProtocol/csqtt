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
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

    private fun shown(text: String): Boolean = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
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

            // a password the server cannot take is said, and the stored one is left as it was
            field(PASSWORD).performTextReplacement("pw new")
            field(PASSWORD).performImeAction()
            compose.waitUntil(5_000) { shown("Без пробелов и «|», не длиннее 128 байт (64 буквы кириллицей)") }
            assertEquals("", password())
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
        ActivityScenario.launch(MainActivity::class.java).use {
            openTunnel()
            compose.onNodeWithText("Дополнительно").performScrollTo().performClick()
            // a new profile starts at twenty, the top of the green zone
            compose.waitUntil(5_000) { shown("20 на хеш (всего 20)") }
            compose.onNode(track and hasProgressBarRangeInfo(ProgressBarRangeInfo(20f, 5f..100f, 18))).assertExists()
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
            assertTrue("the thumb is back where the store has it", shown("40 на хеш (всего 40)"))

            // the same again, and «Понятно» stores it
            tap(60)
            compose.waitUntil(5_000) { shown("Внимание!") }
            compose.onNodeWithText("Понятно").performClick()
            compose.waitUntil(5_000) { streams() == 60 }
            compose.waitUntil(5_000) { shown("60 на хеш (всего 60)") && shown("Больше рекомендуемого: не больше 50 на хеш") }

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
            compose.waitUntil(5_000) { shown("30 на хеш (всего 60)") }
            assertFalse(shown("Больше рекомендуемого: не больше 50 на хеш"))
            tap(95)
            compose.waitUntil(5_000) { streams() == 95 }
            assertFalse(shown("Внимание!") || shown("Опасно!"))
            assertTrue(shown("50 на хеш (всего 95)"))
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
