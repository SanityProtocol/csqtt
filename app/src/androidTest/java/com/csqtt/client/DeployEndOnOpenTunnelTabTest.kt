package com.csqtt.client

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The roles batch (review F1): a deploy that ends while the Туннель tab shows its empty link profile turns the profile
 * into an own-server one, and the open tab shows the server just installed, so a later save of the form keeps it.
 */
@RunWith(AndroidJUnit4::class)
class DeployEndOnOpenTunnelTabTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun noSystemPromptsOverTheApp() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val pkg = instrumentation.targetContext.packageName
            instrumentation.uiAutomation.apply {
                executeShellCommand("pm grant $pkg android.permission.POST_NOTIFICATIONS").close()
                executeShellCommand("dumpsys deviceidle whitelist +$pkg").close()
            }
        }
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    private fun shown(text: String): Boolean = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theOpenTabShowsTheServerTheDeployInstalled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SettingsStore(context)
        val slot = 11
        val host = "203.0.113.44"
        val vps = runBlocking {
            store.clearProfile(slot)
            store.saveActiveProfile(slot)
            store.saveCsqttLinkMode(true)
            store.activeVpsForDeploy()!!
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { shown("Туннель") }
            compose.onNodeWithText("Туннель").performClick()
            compose.waitUntil(10_000) { shown("Ввести сервер вручную") }

            assertTrue(runBlocking { store.saveDeployedServer(slot, vps, host, 46000, 46002, null) })
            compose.waitUntil(10_000) { shown(host) }
            assertFalse(runBlocking { store.csqttLinkMode.first() })
            assertFalse("the empty link profile's action is still shown", shown("Ввести сервер вручную"))
            assertFalse(shown("Подключиться по ссылке csqtt://"))
            assertEquals(host, runBlocking { store.peer.first() })
        }
        runBlocking { store.deleteProfileFromClassic(slot) }
    }
}
