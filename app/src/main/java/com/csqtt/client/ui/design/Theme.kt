package com.csqtt.client.ui.design

import android.graphics.Color.TRANSPARENT
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.csqtt.client.CsqttConstants

/** «Оформление»: Тёмная, Светлая, or the system's while «Система». */
internal fun isDarkMode(mode: String, systemDark: Boolean): Boolean = when (mode) {
    CsqttConstants.Theme.MODE_DARK -> true
    CsqttConstants.Theme.MODE_LIGHT -> false
    else -> systemDark
}

/** Edge to edge (M-95): clear system bars over the app's own grounds, their icons dark on a light theme. */
internal fun ComponentActivity.systemBars(dark: Boolean) {
    val style = if (dark) SystemBarStyle.dark(TRANSPARENT) else SystemBarStyle.light(TRANSPARENT, TRANSPARENT)
    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
}

/** Set by ThemeFirstFrameTest only: told the darkness of every composition of the theme, the first one included (M-94). */
@VisibleForTesting
internal var themeProbe: ((dark: Boolean) -> Unit)? = null

/** The one design's theme. Material components placed in it (a menu, a ripple, classic's screens) take the same tokens. */
@Composable
internal fun CsqTheme(mode: String, content: @Composable () -> Unit) {
    val dark = isDarkMode(mode, isSystemInDarkTheme())
    val palette = if (dark) DarkPalette else LightPalette
    SideEffect { themeProbe?.invoke(dark) }
    CompositionLocalProvider(LocalPalette provides palette, LocalContentColor provides palette.text) {
        MaterialTheme(
            colorScheme = remember(palette) { palette.material() },
            typography = MaterialType,
            shapes = MaterialShapes,
            content = content,
        )
    }
}

private fun Palette.material(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent, onPrimary = onAccent, primaryContainer = accentSoft, onPrimaryContainer = accentText,
        secondary = accent, onSecondary = onAccent, secondaryContainer = accentSoft, onSecondaryContainer = accentText,
        tertiary = warn, onTertiary = if (isDark) warnSoft else Color.White, tertiaryContainer = warnSoft,
        onTertiaryContainer = warn,
        background = bg, onBackground = text, surface = surface, onSurface = text, surfaceVariant = raised,
        onSurfaceVariant = muted, surfaceTint = Color.Transparent, inverseSurface = Ink.messageBg,
        inverseOnSurface = Color.White, inversePrimary = Ink.messageAction,
        error = danger, onError = if (isDark) dangerSoft else Color.White, errorContainer = dangerSoft,
        onErrorContainer = danger, outline = track, outlineVariant = line, scrim = scrim,
        surfaceBright = surface, surfaceDim = bg, surfaceContainerLowest = surface, surfaceContainerLow = surface,
        surfaceContainer = surface, surfaceContainerHigh = raised, surfaceContainerHighest = raised,
    )
}

private val MaterialType = Typography(
    headlineSmall = Type.dialogTitle, titleLarge = Type.title, titleMedium = Type.cardTitle, titleSmall = Type.section,
    bodyLarge = Type.body, bodyMedium = Type.body, bodySmall = Type.sub,
    labelLarge = Type.button, labelMedium = Type.chip, labelSmall = Type.tab,
)

private val MaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = Shape.field, medium = Shape.context, large = Shape.card,
    extraLarge = Shape.dialog,
)
