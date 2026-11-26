package com.csqtt.client

import android.app.NotificationManager
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B5h2 (APP-PLAN §4.2 rules 9 and 11): a captcha the core asked for stays the run's question after its ask ends. «Нужна
 * капча» and «Решить капчу» stay on «Подключение» across a tab switch and a recreate, with one notification posted while
 * the app was not in front, until the need ends.
 */
@RunWith(AndroidJUnit4::class)
class CaptchaPromptPersistsTest {
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

        // an address the captcha policy allows; offline, the page never loads
        private const val ASK = "https://id.vk.ru/not_robot_captcha?session_token=b5h2-test"
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun endTheNeed() {
        ManlCaptchaWebViewManager.cancelCaptcha()
        TunnelManager.starting.value = false
        scope.cancel()
    }

    private fun shown(text: String): Boolean = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun captchaNoticePosted(): Boolean = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.any { it.id == CsqttConstants.Notifications.CAPTCHA_NOTIFICATION_ID }

    @Test
    fun theNeedOutlivesItsAskTheTabAndTheScreen() {
        // a run starting (no core here) and the core's ask, while the app is not in front
        TunnelManager.starting.value = true
        scope.launch { runCatching { ManlCaptchaWebViewManager.solveCaptchaAsync(context, ASK, "b5h2-test") } }
        compose.waitUntil(5_000) { ManlCaptchaWebViewManager.liveAsk.value == ASK && captchaNoticePosted() }

        // the ask ends unanswered, as when the core stops waiting: the need and its one notification stay
        ManlCaptchaWebViewManager.notifyResult(Result.failure(Exception("timeout")))
        compose.waitUntil(5_000) { ManlCaptchaWebViewManager.liveAsk.value == null }
        assertTrue(ManlCaptchaWebViewManager.needed.value)
        assertTrue(captchaNoticePosted())

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(10_000) { shown("Нужна капча") && shown("Решить капчу") }

            compose.onNodeWithText("Туннель").performClick()
            compose.waitUntil(5_000) { !shown("Решить капчу") }
            compose.onNodeWithText("Главная").performClick()
            compose.waitUntil(5_000) { shown("Решить капчу") }

            scenario.recreate()
            compose.waitUntil(10_000) { shown("Нужна капча") && shown("Решить капчу") }

            // the button opens the captcha screen, which waits for the core's next ask
            compose.onNodeWithText("Решить капчу").performClick()
            compose.waitUntil(10_000) { ManlCaptchaWebViewManager.screenStarted }

            // the need ends (here, a stop): the screen and the notification go with it
            ManlCaptchaWebViewManager.cancelCaptcha()
            compose.waitUntil(10_000) { !ManlCaptchaWebViewManager.screenStarted && !captchaNoticePosted() }
        }
        assertFalse(ManlCaptchaWebViewManager.needed.value)
    }
}
