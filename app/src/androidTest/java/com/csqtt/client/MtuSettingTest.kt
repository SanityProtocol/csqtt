package com.csqtt.client

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P1 Bt part 2: the profile's MTU on «Туннель», under «Дополнительно». A preset is stored as picked; «Своё значение» stores a
 * typed value only inside 576–1500; another profile shows its own value, never the last one's draft or choice.
 */
@RunWith(AndroidJUnit4::class)
class MtuSettingTest {
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

        private const val SLOT = 14
        private const val OTHER = 15
        private const val THIRD = 17
        private const val FIELD_LABEL = "MTU, 576–1500"
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store by lazy { SettingsStore(context) }

    @After
    fun restore() = runBlocking {
        store.deleteProfile(SLOT)
        store.deleteProfile(OTHER)
        store.deleteProfile(THIRD)
    }

    private fun shown(text: String): Boolean = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun fieldShown(): Boolean =
        compose.onAllNodes(hasSetTextAction() and hasText(FIELD_LABEL, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun field() = compose.onNode(hasSetTextAction() and hasText(FIELD_LABEL, substring = true))

    private fun fieldText(): String? = compose.onAllNodes(hasSetTextAction() and hasText(FIELD_LABEL, substring = true))
        .fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(SemanticsProperties.EditableText)?.text

    private fun stored(profile: Int): Int = runBlocking { SettingsStore(context, ProfileScope.Fixed(profile)).vpnMtu.first() }

    /** Opens the dropdown (its button shows [current]) and picks [label]. */
    private fun pick(current: String, label: String) {
        compose.onNodeWithText(current).performScrollTo().performClick()
        compose.waitUntil(5_000) { shown(label) && (label != current || compose.onAllNodes(hasText(label)).fetchSemanticsNodes().size > 1) }
        compose.onAllNodes(hasText(label)).fetchSemanticsNodes().size.let { count ->
            // the menu item is the last node with the label (the button, when it shows the same label, comes first)
            compose.onAllNodes(hasText(label))[count - 1].performClick()
        }
    }

    @Test
    fun aPresetIsStoredAsPickedAndACustomValueOnlyInsideTheRange() {
        runBlocking {
            store.clearProfile(OTHER)
            store.clearProfile(THIRD)
            store.clearProfile(SLOT)
            store.saveActiveProfile(SLOT)
            store.saveCsqttLinkMode(false)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { shown("Туннель") }
            compose.onNodeWithText("Туннель").performClick()
            compose.waitUntil(10_000) { shown("Дополнительно") }
            compose.onNodeWithText("Дополнительно").performScrollTo().performClick()
            compose.waitUntil(10_000) { shown("MTU") }
            compose.onNodeWithText("MTU").performScrollTo()
            // a new profile runs at the default, and a preset is stored as picked
            assertEquals(1500, stored(SLOT))
            pick("1500", "1280")
            compose.waitUntil(5_000) { stored(SLOT) == 1280 }

            // «Своё значение» opens the field with the profile's value; only a value inside 576–1500 is stored
            pick("1280", "Своё значение")
            compose.waitUntil(5_000) { fieldShown() }
            field().performScrollTo().assertTextContains("1280")
            field().performTextReplacement("1400")
            compose.waitUntil(5_000) { stored(SLOT) == 1400 }
            for (outside in listOf("575", "9000", "")) {
                field().performTextReplacement(outside)
                compose.waitForIdle()
                assertEquals(outside, 1400, stored(SLOT))
            }
            field().performTextReplacement("600")
            compose.waitUntil(5_000) { stored(SLOT) == 600 }
            // an invalid draft is left in the field: only a profile's own value replaces it (review F1)
            field().performTextReplacement("60")
            compose.waitForIdle()
            assertEquals(600, stored(SLOT))

            // another profile with the same custom MTU opens with its own value in the field, not SLOT's draft;
            // «Дополнительно» stays open from one profile to the next
            runBlocking {
                SettingsStore(context, ProfileScope.Fixed(OTHER)).saveVpnMtu(600)
                store.saveActiveProfile(OTHER)
            }
            compose.waitUntil(5_000) { fieldText() == "600" }
            // a profile at a preset shows it with the field closed: «Своё значение» picked on SLOT stays SLOT's
            runBlocking {
                SettingsStore(context, ProfileScope.Fixed(THIRD)).saveVpnMtu(1280)
                store.saveActiveProfile(THIRD)
            }
            compose.waitUntil(5_000) { !fieldShown() && shown("1280") }
            assertEquals(1280, stored(THIRD))
        }
    }

    @Test
    fun anExportCarriesTheProfilesMtuOrNullForTheDefault() = runBlocking {
        store.clearProfile(SLOT)
        store.saveActiveProfile(SLOT)
        suspend fun export() = store.exportConfigJson(true, false, false, false)
        suspend fun import(json: String) = assertTrue(store.importConfigJson(json, true, false, false, false))
        val atDefault = export()
        assertTrue(JSONObject(atDefault).getJSONObject("tunnel").isNull("vpn_mtu"))
        store.saveVpnMtu(1280)
        assertEquals(1280, JSONObject(export()).getJSONObject("tunnel").getInt("vpn_mtu"))
        // a default profile's file brings the profile back to the default (review F2)
        import(atDefault)
        assertEquals(1500, stored(SLOT))
        // a file from before 2.6.0 (no key) leaves the profile's own value
        store.saveVpnMtu(1280)
        import(JSONObject(atDefault).apply { getJSONObject("tunnel").remove("vpn_mtu") }.toString())
        assertEquals(1280, stored(SLOT))
    }
}
