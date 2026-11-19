package com.csqtt.client

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A cold start draws the classic interface: the only one since V2 was removed (P1 B1). */
@RunWith(AndroidJUnit4::class)
class ClassicStartTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun noSystemPromptsOverTheApp() {
            // the first-start notification and battery-optimisation prompts would stop the activity (and its UI) mid-test
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val pkg = instrumentation.targetContext.packageName
            instrumentation.uiAutomation.apply {
                executeShellCommand("pm grant $pkg android.permission.POST_NOTIFICATIONS").close()
                executeShellCommand("dumpsys deviceidle whitelist +$pkg").close()
            }
        }
    }

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun coldStartShowsTheClassicToolbar() {
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Сканировать QR-код"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Сканировать QR-код").assertExists()
    }
}
