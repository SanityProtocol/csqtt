package com.csqtt.client.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The one design's colours («Classic 3, flat», the canvas's f3.css), light and dark. The contrast fixes of APP-PLAN §8.2
 * are in the values: [faint] is at least 4.5:1 on every ground, [track] at least 3:1, white on [accent] at least 4.5:1.
 */
@Immutable
class Palette(
    val bg: Color, val surface: Color, val raised: Color, val line: Color, val lineSoft: Color,
    val text: Color, val muted: Color, val faint: Color, val track: Color,
    val accent: Color, val onAccent: Color, val accentText: Color, val accentSoft: Color,
    val ok: Color, val okSoft: Color, val warn: Color, val warnSoft: Color, val danger: Color, val dangerSoft: Color,
    val scrim: Color, val isDark: Boolean,
) {
    // What is drawn on a ground filled with a state's colour (a badge, the C's roundel, the button that deletes): white
    // on the light theme's deep colours, and the state's own dark tint on the dark theme's pale ones, where white is 1.8:1
    val onWarn: Color get() = if (isDark) warnSoft else Color.White
    val onDanger: Color get() = if (isDark) dangerSoft else Color.White
    val onOk: Color get() = if (isDark) okSoft else Color.White
}

internal val LightPalette = Palette(
    bg = Color(0xFFF5F6F8), surface = Color(0xFFFFFFFF), raised = Color(0xFFEEF1F5), line = Color(0xFFDCE0E7),
    lineSoft = Color(0xFFE8EBF0), text = Color(0xFF1C2230), muted = Color(0xFF5E6878), faint = Color(0xFF626C7C),
    track = Color(0xFF7A8494), accent = Color(0xFF1A73E8), onAccent = Color(0xFFFFFFFF), accentText = Color(0xFF1663CC),
    accentSoft = Color(0xFFE6F0FD), ok = Color(0xFF187C42), okSoft = Color(0xFFE3F4EA), warn = Color(0xFFA54C06),
    warnSoft = Color(0xFFFFF1E5), danger = Color(0xFFC62828), dangerSoft = Color(0xFFFDECEC),
    scrim = Color(0x6B101216), isDark = false,
)

internal val DarkPalette = Palette(
    bg = Color(0xFF101216), surface = Color(0xFF191C22), raised = Color(0xFF242830), line = Color(0xFF2E333C),
    lineSoft = Color(0xFF262A31), text = Color(0xFFF4F5F7), muted = Color(0xFFAEB3BD), faint = Color(0xFF8A909B),
    track = Color(0xFF6B7382), accent = Color(0xFF1F6FE0), onAccent = Color(0xFFFFFFFF), accentText = Color(0xFF7DB1FF),
    accentSoft = Color(0xFF16294A), ok = Color(0xFF73D497), okSoft = Color(0xFF173326), warn = Color(0xFFFFAD68),
    warnSoft = Color(0xFF3A2716), danger = Color(0xFFFF8A80), dangerSoft = Color(0xFF3A1C1C),
    scrim = Color(0x99000000), isDark = true,
)

internal val LocalPalette = staticCompositionLocalOf { LightPalette }

/** The terminal (logs, the deploy log) and the in-app message keep their own ground in both themes. */
internal object Ink {
    val termBg = Color(0xFF0E1013)
    val termText = Color(0xFFDDE1E8)
    val termMuted = Color(0xFF9DA3AE)
    val termOk = Color(0xFF9BD4AF)
    val termError = Color(0xFFFF8A80)
    val termWarn = Color(0xFFFFAD68)
    val termInfo = Color(0xFF7DB1FF)
    val termCountBg = Color(0xFF2A3140)
    val termCount = Color(0xFFC7CFDC)
    val messageBg = Color(0xFF2A2F38)
    val messageAction = Color(0xFF9CC4FF)
}

/** Text sizes by purpose, in sp: the system font scale is followed (§4.2 rule 6), and heights come from the text. */
internal object Type {
    private fun style(size: Double, line: Int, weight: Int = 400, spacing: Double = 0.0) = TextStyle(
        fontFamily = FontFamily.Default, fontSize = size.sp, lineHeight = line.sp, fontWeight = FontWeight(weight),
        letterSpacing = spacing.sp,
    )

    val title = style(26.0, 32, 600, -0.2)
    val dialogTitle = style(19.0, 26, 600)
    val cardTitle = style(16.0, 22, 600)
    val body = style(15.0, 20)
    val fact = style(14.0, 20)
    val message = style(14.0, 19)
    val messageAction = style(14.0, 19, 600)
    val bodyStrong = style(15.0, 20, 600)
    val button = style(14.5, 20, 500)
    val buttonSmall = style(13.5, 18, 500)
    val version = style(14.0, 14, 600, 0.1)
    val figure = style(14.0, 16, 600).copy(fontFeatureSettings = "tnum")
    val unit = style(11.0, 13)
    val section = style(13.0, 18, 600)
    val label = style(13.0, 16, 500)
    val sub = style(12.5, 17)
    val chip = style(12.5, 16, 500)
    val chipSmall = style(11.0, 14, 500)
    val tab = style(12.0, 14, 500)
    val caption = style(11.5, 14)
    val badge = style(11.0, 18, 600)
    val tag = style(10.5, 14, 600)
    val mono = style(13.0, 18).copy(fontFamily = FontFamily.Monospace)
    val term = style(12.5, 19).copy(fontFamily = FontFamily.Monospace)
}

internal object Shape {
    val card = RoundedCornerShape(20.dp)
    val context = RoundedCornerShape(16.dp)
    val field = RoundedCornerShape(12.dp)
    val segment = RoundedCornerShape(14.dp)
    val segmentItem = RoundedCornerShape(11.dp)
    val sheet = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val dialog = RoundedCornerShape(24.dp)
    val message = RoundedCornerShape(14.dp)
    val pill = RoundedCornerShape(percent = 50)
}

/** `Csq.colors.accent` in a composable. */
internal object Csq {
    val colors: Palette
        @Composable @ReadOnlyComposable get() = LocalPalette.current
}
