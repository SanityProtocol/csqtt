package com.csqtt.client

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A cold start draws the one shell (P3 B0): its header, the tab bar with «Главная» open, and classic's screens inside it
 * until their cards replace them. The bar follows what the store holds (K4, D28): four tabs for a participant, «Сервер»
 * for a user with a server, and the way to «Сервер» for one who has none yet.
 */
@RunWith(AndroidJUnit4::class)
class ShellStartTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun noSystemPromptsOverTheApp() = ShellTestKit.noSystemPromptsOverTheApp()
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    private val store by lazy { SettingsStore(InstrumentationRegistry.getInstrumentation().targetContext) }
    private lateinit var before: String

    private fun shown(text: String): Boolean = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Before
    fun keepTheStore() = runBlocking { before = store.exportBackupJson(serverSecrets = true) }

    @After
    fun putTheStoreBack() = runBlocking { assertTrue(store.restoreBackupJson(before)) }

    @Test
    fun coldStartDrawsTheShellWithClassicInside() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { shown("Главная") }
            compose.onNodeWithText("Главная").assertIsSelected()
            for (tab in listOf("Туннель", "Логи", "Маршруты")) compose.onNodeWithText(tab).assertExists()
            // the header (§8.4): the build's name under the wordmark and the version; no «by amurcanov», no ♡
            compose.onNodeWithText("сборка SanityProtocol").assertExists()
            compose.onNodeWithContentDescription("CSQTT").assertExists()
            assertTrue(compose.onAllNodes(hasText("by amurcanov", substring = true)).fetchSemanticsNodes().isEmpty())
            assertTrue(compose.onAllNodes(hasContentDescription("Поддержать разработчика")).fetchSemanticsNodes().isEmpty())
            // classic's header buttons, once, in the shell's header
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasContentDescription("Сканировать QR-код")).fetchSemanticsNodes().size == 1
            }
        }
    }

    @Test
    fun theBarFollowsTheStoreAndSomeoneWithoutAServerCanOpenTheTab() {
        runBlocking { assertTrue(store.restoreBackupJson(ShellTestKit.backup(withServer = false))) }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { shown("Главная") }
            // a participant: four tabs
            compose.waitUntil(10_000) { !shown("Сервер") }

            // the way in: «Настройки» → «Установить свой сервер» opens the tab, and it stays while it is open
            compose.onNodeWithContentDescription("Быстрые настройки").performClick()
            compose.waitUntil(5_000) { shown("Установить свой сервер") }
            compose.onNodeWithText("Установить свой сервер").performClick()
            compose.waitUntil(5_000) { shown("Сервер") && !shown("Установить свой сервер") }
            compose.onNodeWithText("Сервер").assertIsSelected()
            // left with no server made: the tab leaves the bar
            compose.onNodeWithText("Логи").performClick()
            compose.waitUntil(5_000) { !shown("Сервер") }

            // a server appears in the store (a deploy's end, a restore): the tab is there for good, the entry is not
            runBlocking { assertTrue(store.restoreBackupJson(ShellTestKit.backup(withServer = true))) }
            compose.waitUntil(10_000) { shown("Сервер") }
            compose.onNodeWithText("Логи").assertIsSelected()
            compose.onNodeWithContentDescription("Быстрые настройки").performClick()
            compose.waitUntil(5_000) { shown("Проверить обновления") }
            assertTrue(!shown("Установить свой сервер"))
        }
    }
}
