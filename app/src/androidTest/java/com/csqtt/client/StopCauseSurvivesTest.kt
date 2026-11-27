package com.csqtt.client

import android.app.NotificationManager
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B5h1 (APP-PLAN §4.2 rules 10 and 11): a stop the user did not ask for is announced while the app is not in front, its
 * cause is under «Отключено» after a tab switch, a recreate and a process death (the stored cause, loaded as at the app's
 * start), and the next start clears it all.
 */
@RunWith(AndroidJUnit4::class)
class StopCauseSurvivesTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun noSystemPromptsOverTheApp() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val pkg = instrumentation.targetContext.packageName
            instrumentation.uiAutomation.apply {
                executeShellCommand("pm grant $pkg android.permission.POST_NOTIFICATIONS").close()
            }
        }

        private const val CAUSE = "Тест B5h1: причина остановки"
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store by lazy { SettingsStore(context) }

    private fun shown(text: String): Boolean = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun stopNoticePosted(): Boolean = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.any { it.id == CsqttConstants.Notifications.STOP_NOTIFICATION_ID }

    private fun storedCause(): String = runBlocking { store.lastStopCause.first() }

    /** As a start does: a fresh run with no cause; then no start is in progress (no tunnel runs in this test). */
    private fun startClears() {
        TunnelManager.beginStartRequest(context)
        TunnelManager.starting.value = false
        compose.waitUntil(5_000) { storedCause().isEmpty() && !stopNoticePosted() }
    }

    @Test
    fun theCauseIsAnnouncedAndOutlivesTheScreenAndTheProcess() {
        startClears()

        // no activity in front: the cause gets its own notification and is stored
        TunnelManager.recordRunFailure(CAUSE, context)
        compose.waitUntil(5_000) { stopNoticePosted() && storedCause() == CAUSE }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(10_000) { shown(CAUSE) }

            compose.onNodeWithText("Туннель").performClick()
            compose.waitUntil(5_000) { !shown(CAUSE) }
            compose.onNodeWithText("Главная").performClick()
            compose.waitUntil(5_000) { shown(CAUSE) }

            scenario.recreate()
            compose.waitUntil(10_000) { shown(CAUSE) }

            // process death: the memory is gone, and the app's start loads the stored cause
            TunnelManager.failure.value = null
            compose.waitUntil(5_000) { !shown(CAUSE) }
            TunnelManager.restoreStoppedCause(storedCause())
            compose.waitUntil(5_000) { shown(CAUSE) }

            // the next start clears the cause, the stored one and the notification
            startClears()
            compose.waitUntil(5_000) { !shown(CAUSE) }
        }
        assertFalse(stopNoticePosted())
    }
}
