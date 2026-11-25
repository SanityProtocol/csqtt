package com.csqtt.client.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The outline icons of the canvas: Lucide's glyphs (ISC, assets/licenses/Lucide-LICENSE.txt) on a 24 grid with a 1.8
 * stroke and round ends. Each is built on first use; IconsTest builds them all, so a mistyped path fails in CI.
 */
internal object Icons {
    private fun stroke(vararg paths: String): Lazy<ImageVector> = lazy {
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            for (path in paths) {
                addPath(
                    pathData = addPathNodes(path), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
    }

    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    val key by stroke(circle(7.5f, 15.5f, 5.5f), "m21 2-9.6 9.6", "m15.5 7.5 3 3L22 7l-3-3")
    val cloud by stroke("M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z")
    val terminal by stroke("m4 17 6-6-6-6", "M12 19h8")
    val list by stroke("M3 6h18", "M7 12h10", "M10 18h4")
    val arrowLeft by stroke("m12 19-7-7 7-7", "M19 12H5")
    val close by stroke("M18 6 6 18", "m6 6 12 12")
    val check by stroke("M20 6 9 17l-5-5")
    val loader by stroke("M21 12a9 9 0 1 1-6.22-8.56")
    val warning by stroke(
        "M12 9v4", "M12 17h.01",
        "M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z",
    )
    val info by stroke(circle(12f, 12f, 10f), "M12 16v-4", "M12 8h.01")
    val chevronRight by stroke("m9 18 6-6-6-6")
    val eye by stroke("M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z", circle(12f, 12f, 3f))
    val eyeOff by stroke(
        "M9.88 9.88a3 3 0 1 0 4.24 4.24", "M10.73 5.08A10.43 10.43 0 0 1 12 5c7 0 10 7 10 7a13.16 13.16 0 0 1-1.67 2.68",
        "M6.61 6.61A13.526 13.526 0 0 0 2 12s3 7 10 7a9.74 9.74 0 0 0 5.39-1.61", "m2 2 20 20",
    )
    val more by stroke(circle(12f, 5f, 1f), circle(12f, 12f, 1f), circle(12f, 19f, 1f))

    /** The C of the wordmark on the launcher icon's 108 grid: the app's own glyph everywhere, coloured by state. */
    val c: ImageVector by lazy {
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 108f, viewportHeight = 108f)
            .addPath(
                pathData = addPathNodes("M78.6 36.8A30 30 0 1 0 78.6 71.2L69.6 64.9A19 19 0 1 1 69.6 43.1Z"),
                fill = SolidColor(Color.Black),
            ).build()
    }

    val all: List<ImageVector>
        get() = listOf(key, cloud, terminal, list, arrowLeft, close, check, loader, warning, info, chevronRight, eye, eyeOff, more, c)
}

@Composable
internal fun CGlyph(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Icon(Icons.c, contentDescription = null, modifier = modifier.size(size), tint = color)
}

/**
 * What the C's colour says wherever it stands for a state (a notification's roundel, a card, a step): blue works, grey
 * is off, orange needs you, red failed, green finished. Which state has which tone is its owner's one mapping.
 */
internal enum class Tone { Working, Off, NeedsYou, Failed, Done }

/** The C in a circle of its tone's colour. */
@Composable
internal fun CRoundel(tone: Tone, size: Dp, modifier: Modifier = Modifier) {
    val colors = Csq.colors
    val (ground, ink) = when (tone) {
        Tone.Working -> colors.accent to colors.onAccent
        Tone.Off -> colors.track to Color.White
        Tone.NeedsYou -> colors.warn to colors.onWarn
        Tone.Failed -> colors.danger to colors.onDanger
        Tone.Done -> colors.ok to colors.onOk
    }
    Box(modifier.size(size).background(ground, CircleShape), contentAlignment = Alignment.Center) {
        CGlyph(size * 0.62f, ink)
    }
}
