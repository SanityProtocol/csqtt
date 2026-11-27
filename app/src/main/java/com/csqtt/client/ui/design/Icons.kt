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
 * stroke and round ends. Each is built on first use; DesignTokensTest builds them all, so a mistyped path fails in CI.
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
    // «Главная» (B1): the layout's switch, the profiles' order, a pin, and the marks of its cards
    val viewList by stroke("M4 6h.01", "M4 12h.01", "M4 18h.01", "M9 6h11", "M9 12h11", "M9 18h11")
    val viewOne by stroke(
        "M8.5 4h7a4.5 4.5 0 0 1 4.5 4.5v7a4.5 4.5 0 0 1-4.5 4.5h-7A4.5 4.5 0 0 1 4 15.5v-7A4.5 4.5 0 0 1 8.5 4z",
        "M10.4 9.7 12.5 8.3v7.4",
    )
    val sort by stroke("m3 16 4 4 4-4", "M7 20V4", "M11 4h10", "M11 8h7", "M11 12h4")
    val pin by stroke(
        "M12 17v5",
        "M5 17h14v-1.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V6h1a2 2 0 0 0 0-4H8a2 2 0 0 0 0 4h1v4.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24Z",
    )
    val bellOff by stroke(
        "M8.7 3A6 6 0 0 1 18 8a21.3 21.3 0 0 0 .6 5", "M17 17H3s3-2 3-9a4.67 4.67 0 0 1 .3-1.7",
        "M10.3 21a1.94 1.94 0 0 0 3.4 0", "m2 2 20 20",
    )
    val wifi by stroke("M12 20h.01", "M2 8.82a15 15 0 0 1 20 0", "M5 12.859a10 10 0 0 1 14 0", "M8.5 16.429a5 5 0 0 1 7 0")
    val alert by stroke(circle(12f, 12f, 10f), "M12 8v4", "M12 16h.01")
    val shield by stroke(
        "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z",
        "m9 12 2 2 4-4",
    )

    private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"

    // P3 B2: the profiles sheet, the ways to add a profile, the subscription's sheets
    val pencil by stroke(
        "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z",
        "m15 5 4 4",
    )
    val trash by stroke("M3 6h18", "M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6", "M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2")
    val plus by stroke("M5 12h14", "M12 5v14")
    val qr by stroke(
        rect(3f, 3f, 5f, 5f, 1f), rect(16f, 3f, 5f, 5f, 1f), rect(3f, 16f, 5f, 5f, 1f), "M21 16h-3a2 2 0 0 0-2 2v3", "M21 21v.01",
        "M12 7v3a2 2 0 0 1-2 2H7", "M3 12h.01", "M12 3h.01", "M12 16v.01", "M16 12h1", "M21 12v.01", "M12 21v-1",
    )
    val clipboard by stroke(rect(8f, 2f, 8f, 4f, 1f), "M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2")
    val server by stroke(rect(2f, 2f, 20f, 8f, 2f), rect(2f, 14f, 20f, 8f, 2f), "M6 6h.01", "M6 18h.01")
    val image by stroke(rect(3f, 3f, 18f, 18f, 2f), circle(9f, 9f, 2f), "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21")
    val refresh by stroke(
        "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8", "M21 3v5h-5",
        "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16", "M8 16H3v5",
    )
    val copy by stroke(rect(8f, 8f, 14f, 14f, 2f), "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2")
    val chevronUp by stroke("m18 15-6-6-6 6")
    val chevronDown by stroke("m6 9 6 6 6-6")
    val lock by stroke(rect(3f, 11f, 18f, 11f, 2f), "M7 11V7a5 5 0 0 1 10 0v4")

    // «Одно подключение»'s way to another connection (the operator, 2026-11-27): the two arrows set apart, each 2 of
    // 24 off the middle
    val switch by stroke("M7 3 3 7l4 4", "M3 7h14", "m17 21 4-4-4-4", "M21 17H7")
    val link by stroke(
        "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71",
        "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71",
    )
    val chevrons by stroke("m7 15 5 5 5-5", "m7 9 5-5 5 5")
    val help by stroke(circle(12f, 12f, 10f), "M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3", "M12 17h.01")
    val phone by stroke(rect(5f, 2f, 14f, 20f, 2f), "M12 18h.01")
    val user by stroke("M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2", circle(12f, 7f, 4f))
    val activity by stroke("M22 12h-4l-3 9L9 3l-3 9H2")
    val checkCircle by stroke("M22 11.08V12a10 10 0 1 1-5.93-9.14", "m9 11 3 3L22 4")

    /** The C of the wordmark on the launcher icon's 108 grid: the app's own glyph everywhere, coloured by state. */
    val c: ImageVector by lazy {
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 108f, viewportHeight = 108f)
            .addPath(
                pathData = addPathNodes("M78.6 36.8A30 30 0 1 0 78.6 71.2L69.6 64.9A19 19 0 1 1 69.6 43.1Z"),
                fill = SolidColor(Color.Black),
            ).build()
    }

    val all: List<ImageVector>
        get() = listOf(
            key, cloud, terminal, list, arrowLeft, close, check, loader, warning, info, chevronRight, eye, eyeOff, more,
            viewList, viewOne, sort, pin, bellOff, wifi, alert, shield, c,
            pencil, trash, plus, qr, clipboard, server, image, refresh, copy, chevronUp, chevronDown, lock,
            switch, link, chevrons, help, phone, user, activity, checkCircle,
        )
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
