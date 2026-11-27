package com.csqtt.client

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B5g (session 12's live check): a server's routing rule belongs to the run. «Маршрут» shows «синхронизирована с сервером»
 * instead of its controls only on the profile the run is on; a profile opened during the run (an added one) keeps its
 * own controls, and its edits never reload the running VPN (ExceptionsTab.reloadIfRunsHere).
 */
@RunWith(AndroidJUnit4::class)
class RoutingTabOpenProfileTest {
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

        private const val RUN = 14
        private const val OTHER = 15
        private const val SYNCED = "Маршрутизация синхронизирована с сервером"
        private const val CONTROLS = "Маршрутизация приложений"
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store by lazy { SettingsStore(context) }

    @After
    fun restore() = runBlocking {
        TunnelManager.serverRoutingActive.value = false
        TunnelManager.applyRunIdentity(TunnelManager.RunIdentity(-1, null, emptyList()))
        store.saveActiveProfile(0)
        store.deleteProfile(RUN)
        store.deleteProfile(OTHER)
    }

    private fun shown(text: String): Boolean = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theServerRuleReplacesTheControlsOnlyOnTheRunsProfile() {
        runBlocking { store.saveActiveProfile(RUN) }
        // what a run on RUN sets when its server pushes a rule
        TunnelManager.applyRunIdentity(TunnelManager.RunIdentity(RUN, null, emptyList()))
        TunnelManager.serverRoutingActive.value = true
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { shown("Маршруты") }
            compose.onNodeWithText("Маршруты").performClick()
            compose.waitUntil(5_000) { shown(SYNCED) && !shown(CONTROLS) }

            // another profile opened during the run: its own controls
            runBlocking { store.saveActiveProfile(OTHER) }
            compose.waitUntil(5_000) { shown(CONTROLS) && !shown(SYNCED) }

            // back on the run's profile: the server's rule again
            runBlocking { store.saveActiveProfile(RUN) }
            compose.waitUntil(5_000) { shown(SYNCED) && !shown(CONTROLS) }
        }
    }
}
