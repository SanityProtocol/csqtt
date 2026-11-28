package com.csqtt.client

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
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
 * The profiles' rows on a device (the canvas, F-Profiles, F-ProfileActions, F-HomeSwipe), which «Главная» and the sheet
 * share: a tap picks and the sheet closes once the pick is stored (M-97); the menu hides with the way back; the swipe
 * shows the same actions in the row; a rename and its revert; TalkBack's move makes the order the user's own; the
 * selected profile is not offered «Скрыть»; a hidden one that is picked is shown again; a long press let go in place
 * picks nothing, and one that is dragged moves the row; a delete asks first.
 */
@RunWith(AndroidJUnit4::class)
class ProfilesSheetTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun noSystemPromptsOverTheApp() = ShellTestKit.noSystemPromptsOverTheApp()

        private const val SLOT = 9
        private const val SHEET = "Мои профили"
        private const val ALL = "Все профили и подписки"
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store by lazy { SettingsStore(context) }
    private lateinit var layoutBefore: HomeLayout
    private lateinit var sortBefore: ProfileSort
    private lateinit var orderBefore: List<Int>
    private var beta = -1
    private var gamma = -1

    private fun link(host: String) = "csqtt://connect?v=2&host=$host.example.net&peer=46000&password=pw&hashes=${"E".repeat(20)}"
    private fun shown(text: String): Boolean = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private fun entries() = runBlocking { store.profileEntries.first() }
    private fun entry(profile: Int) = entries().first { it.index == profile }

    /** The node on top: with the sheet open, «Главная»'s own rows are still drawn under it. */
    private fun last(text: String): SemanticsNodeInteraction = compose.onAllNodes(hasText(text, substring = true)).onLast()
    private fun menuOf(name: String): SemanticsNodeInteraction = compose.onAllNodes(hasContentDescription("Действия: $name")).onLast()

    /** A profile's row in the sheet: what can be tapped, not a message that names the profile. */
    private fun row(name: String): SemanticsNodeInteraction =
        compose.onAllNodes(hasText(name, substring = true) and hasClickAction()).onLast()

    /**
     * Waits for the sheet's row under [name] after «Переименовать»: its dialog is gone, and the row is drawn with the
     * name. The dialog's own field holds the name too, and can be tapped: only the row has the menu.
     */
    private fun awaitRenamedRow(name: String) = compose.waitUntil(5_000) {
        !shown("Переименовать профиль") && compose.onAllNodes(hasContentDescription("Действия: $name")).fetchSemanticsNodes().isNotEmpty()
    }

    private fun openSheet() {
        compose.waitUntil(10_000) { shown(ALL) }
        last(ALL).performClick()
        compose.waitUntil(5_000) { shown(SHEET) }
    }

    @Before
    fun profiles() = runBlocking {
        layoutBefore = store.homeLayout.first()
        sortBefore = store.profileSort.first()
        orderBefore = store.profileOrder.first()
        store.saveHomeLayout(HomeLayout.List)
        // a message the test before raised is the app's, not its screen's: it would still be shown, and name a profile
        (context.applicationContext as CsqttApplication).owners.messages.let { messages ->
            messages.current.value?.let { messages.dismiss(it.id) }
        }
        store.clearProfile(SLOT)
        store.saveActiveProfile(SLOT)
        store.importLink(link("alpha"), target = SLOT, name = "Альфа")
        beta = store.importLink(link("beta"), target = null, name = "Бета")!!.first
        gamma = store.importLink(link("gamma"), target = null, name = "Гамма")!!.first
    }

    @After
    fun restore() = runBlocking {
        store.saveActiveProfile(0)
        listOf(SLOT, beta, gamma).forEach { store.deleteProfile(it) }
        store.restoreProfileOrder(sortBefore.stored to ProfileOrder.encode(orderBefore))
        store.saveHomeLayout(layoutBefore)
    }

    @Test
    fun theRowsPickHideRenameMoveAndDelete() {
        ActivityScenario.launch(MainActivity::class.java).use {
            // a tap picks, and the sheet closes once the pick is stored
            openSheet()
            row("Бета").performClick()
            compose.waitUntil(5_000) { !shown(SHEET) }
            assertEquals(beta, runBlocking { store.activeProfile.first() })
            // in the list the tinted row says which one is picked: no message names it
            assertFalse(shown("Подключение: Бета"))

            // the menu hides a profile, and the message in the sheet is the way back
            openSheet()
            menuOf("Гамма").performClick()
            last("Скрыть").performClick()
            compose.waitUntil(5_000) { entry(gamma).hidden }
            compose.waitUntil(5_000) { shown("Профиль «Гамма» скрыт") && shown("Скрытые · 1") }
            last("Отменить").performClick()
            compose.waitUntil(5_000) { !entry(gamma).hidden }

            // a hidden profile that is picked is shown again: hidden once more, found under «Скрытые», tapped
            menuOf("Гамма").performClick()
            last("Скрыть").performClick()
            compose.waitUntil(5_000) { entry(gamma).hidden }
            compose.onAllNodes(hasContentDescription("Показать скрытые")).onLast().performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Свернуть скрытые")).fetchSemanticsNodes().isNotEmpty() }
            row("Гамма").performClick()
            compose.waitUntil(5_000) { !shown(SHEET) }
            compose.waitUntil(5_000) { runBlocking { store.activeProfile.first() } == gamma && !entry(gamma).hidden }
            openSheet()
            row("Бета").performClick()
            compose.waitUntil(5_000) { !shown(SHEET) && runBlocking { store.activeProfile.first() } == beta }
            openSheet()

            // the selected profile is never hidden: it is not offered
            menuOf("Бета").performClick()
            compose.waitUntil(5_000) { shown("Закрепить") }
            assertFalse(shown("Скрыть"))
            // the menu is a window of the sheet's: Back goes to it through the system, as a finger's would
            ShellTestKit.shell("input keyevent 4")
            compose.waitUntil(5_000) { !shown("Закрепить") }

            // a swipe left shows the same actions in the row: rename
            row("Гамма").performTouchInput { swipeLeft() }
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Переименовать")).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(hasContentDescription("Переименовать")).onLast().performClick()
            compose.waitUntil(5_000) { shown("Переименовать профиль") }
            compose.onNode(hasSetTextAction()).performTextReplacement("Дельта")
            last("Сохранить").performClick()
            compose.waitUntil(5_000) { entry(gamma).name == "Дельта" }
            // and the name it came with is one tap away
            awaitRenamedRow("Дельта")
            menuOf("Дельта").performClick()
            // the menu is a window of its own: its items are there a frame after the tap, not with it
            compose.waitUntil(5_000) { shown("Переименовать") }
            last("Переименовать").performClick()
            compose.waitUntil(5_000) { shown("Вернуть исходное имя") }
            last("Вернуть исходное имя").performClick()
            last("Сохранить").performClick()
            compose.waitUntil(5_000) { entry(gamma).name == "Гамма" }

            // TalkBack's «Переместить вверх»: the order becomes the user's own, the row one place up in its card
            awaitRenamedRow("Гамма")
            val actions = row("Гамма").fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
            assertTrue("the row has no move action: ${actions.map { it.label }}", actions.any { it.label == "Переместить вверх" })
            compose.runOnUiThread { actions.first { it.label == "Переместить вверх" }.action() }
            compose.waitUntil(5_000) { runBlocking { store.profileSort.first() } == ProfileSort.Custom }
            compose.waitUntil(5_000) {
                val order = runBlocking { store.profileOrder.first() }
                order.indexOf(gamma) in 0 until order.indexOf(beta)
            }

            // a long press that lifts a row and is let go in place picks nothing: the sheet stays, the open profile too
            row("Альфа").performTouchInput {
                down(center)
                advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
                up()
            }
            compose.waitForIdle()
            Thread.sleep(500)
            assertTrue("a long press picked the row", shown(SHEET))
            assertEquals(beta, runBlocking { store.activeProfile.first() })

            // a long press lifts a row, and it is dropped where it is let go: «Бета» one place up, over «Гамма» again
            row("Бета").performTouchInput {
                down(center)
                advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
                moveBy(Offset(0f, -height.toFloat()))
                advanceEventTime(100)
                up()
            }
            compose.waitUntil(5_000) {
                val order = runBlocking { store.profileOrder.first() }
                order.indexOf(beta) in 0 until order.indexOf(gamma)
            }

            // a delete asks first
            menuOf("Альфа").performClick()
            last("Удалить").performClick()
            compose.waitUntil(5_000) { shown("Удалить профиль «Альфа»?") }
            last("Удалить").performClick()
            compose.waitUntil(5_000) { entries().none { it.index == SLOT } }
        }
    }

    /**
     * P3 B3 (the operator, 2026-11-27): «Одно подключение» has no list, so the icon in its card's corner is the way to
     * another connection — the switch with two or more, which opens the sheet, and «+» with one, which opens «Добавить
     * профиль». A pick made for this layout is named by a short message: the card never names its connection.
     */
    @Test
    fun theCornerControlOfOneConnectionOpensTheSheetAndAPickIsNamed() {
        runBlocking { store.saveHomeLayout(HomeLayout.One) }
        ActivityScenario.launch(MainActivity::class.java).use {
            val switch = hasContentDescription("Выбрать подключение")
            compose.waitUntil(10_000) { compose.onAllNodes(switch).fetchSemanticsNodes().isNotEmpty() }
            assertFalse("the card never names the connection", shown("Альфа"))
            compose.onNode(switch).performClick()
            compose.waitUntil(5_000) { shown(SHEET) }
            row("Бета").performClick()
            compose.waitUntil(5_000) { !shown(SHEET) }
            compose.waitUntil(5_000) { shown("Подключение: Бета") }
            assertEquals(beta, runBlocking { store.activeProfile.first() })

            // with one connection left there is nothing to switch to: the icon is «+», and adds. Whatever else this
            // device's store holds is taken out for the look, and put back after
            val kept = runBlocking { store.exportBackupJson(serverSecrets = true) }
            try {
                runBlocking { entries().filter { it.index != beta }.forEach { store.deleteProfile(it.index) } }
                val add = hasContentDescription("Добавить подключение")
                compose.waitUntil(10_000) { compose.onAllNodes(add).fetchSemanticsNodes().isNotEmpty() }
                assertTrue(compose.onAllNodes(switch).fetchSemanticsNodes().isEmpty())
                compose.onNode(add).performClick()
                // «Добавить профиль» itself, with its ways — «Профили» has a button of that name too
                compose.waitUntil(5_000) { shown("Добавить профиль") && shown("Вручную") && shown("По подписке") }
                assertFalse("«+» opened the list of profiles", shown(SHEET))
            } finally {
                runBlocking { assertTrue(store.restoreBackupJson(kept)) }
            }
        }
    }
}
