package com.csqtt.client.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.csqtt.client.CsqttConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignTokensTest {
    private fun contrast(a: Color, b: Color): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }

    /** APP-PLAN §8.2's contrast fixes hold in the tokens, in both themes (board F-A11y). */
    @Test
    fun `text, the faint text and the filled grounds keep their contrast`() {
        for (palette in listOf(LightPalette, DarkPalette)) {
            val name = if (palette.isDark) "dark" else "light"
            for (ground in listOf(palette.bg, palette.surface, palette.raised)) {
                assertTrue("$name text", contrast(palette.text, ground) >= 4.5f)
                assertTrue("$name muted", contrast(palette.muted, ground) >= 4.5f)
                assertTrue("$name faint: ${contrast(palette.faint, ground)}", contrast(palette.faint, ground) >= 4.5f)
            }
            assertTrue("$name on accent", contrast(palette.onAccent, palette.accent) >= 4.5f)
            assertTrue("$name on warn: ${contrast(palette.onWarn, palette.warn)}", contrast(palette.onWarn, palette.warn) >= 4.5f)
            assertTrue("$name on danger: ${contrast(palette.onDanger, palette.danger)}", contrast(palette.onDanger, palette.danger) >= 4.5f)
            assertTrue("$name on ok: ${contrast(palette.onOk, palette.ok)}", contrast(palette.onOk, palette.ok) >= 4.5f)
            // the grey roundel's C is a glyph, not text: 3:1
            assertTrue("$name C on off: ${contrast(Color.White, palette.track)}", contrast(Color.White, palette.track) >= 3f)
            assertTrue("$name accent text on its tint", contrast(palette.accentText, palette.accentSoft) >= 4.5f)
            // a switch that is off is still seen: its track against the card it sits on
            assertTrue("$name track", contrast(palette.track, palette.surface) >= 3f)
        }
    }

    @Test
    fun `every icon builds`() {
        // each is built on first use: a mistyped path would throw when its screen opens
        assertEquals(15, Icons.all.size)
        for (icon in Icons.all) assertTrue(icon.root.size > 0)
    }

    @Test
    fun `the theme is the chosen one, the system's only while it says so`() {
        for (systemDark in listOf(false, true)) {
            assertEquals(true, isDarkMode(CsqttConstants.Theme.MODE_DARK, systemDark))
            assertEquals(false, isDarkMode(CsqttConstants.Theme.MODE_LIGHT, systemDark))
            assertEquals(systemDark, isDarkMode(CsqttConstants.Theme.MODE_SYSTEM, systemDark))
            assertEquals("an unknown stored mode follows the system", systemDark, isDarkMode("sepia", systemDark))
        }
    }
}
