package com.csqtt.client.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.csqtt.client.R
import com.csqtt.client.ui.design.Csq
import com.csqtt.client.ui.design.Type

/** How a ping reads: the figure, [bars] of three, and a colour, so the colour is never the only cue (F-A11y). */
internal enum class PingGrade(val bars: Int) { Good(3), Fair(2), Slow(1), Bad(0), NoAnswer(0) }

/** The operator's scale (2026-11-25): 1–79 ms good, 80–139 fair, 140–199 slow, 200 and more bad; null is no answer. */
internal fun pingGrade(ms: Int?): PingGrade = when {
    ms == null || ms < 0 -> PingGrade.NoAnswer
    ms < 80 -> PingGrade.Good
    ms < 140 -> PingGrade.Fair
    ms < 200 -> PingGrade.Slow
    else -> PingGrade.Bad
}

/** A profile's ping: bars, the figure over «мс»; «нет ответа» for none. Read aloud as «38 миллисекунд». */
@Composable
internal fun MsPill(ms: Int?, modifier: Modifier = Modifier) {
    val colors = Csq.colors
    val grade = pingGrade(ms)
    val ink = when (grade) {
        PingGrade.Good -> colors.ok
        PingGrade.Fair -> colors.accentText
        PingGrade.Slow -> colors.warn
        PingGrade.Bad -> colors.danger
        PingGrade.NoAnswer -> colors.faint
    }
    val figure = ms?.takeIf { grade != PingGrade.NoAnswer }
    val spoken = if (figure != null) pluralStringResource(R.plurals.ping_ms_spoken, figure, figure) else stringResource(R.string.ping_no_answer)
    Row(
        modifier.clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(16.dp, 12.dp)) {
            val unit = size.width / 16f
            listOf(Triple(0f, 8f, 4f), Triple(5f, 5f, 7f), Triple(10f, 1f, 11f)).forEachIndexed { index, (x, y, height) ->
                drawRoundRect(
                    color = ink.copy(alpha = if (index < grade.bars) 1f else 0.25f),
                    topLeft = Offset(x * unit, y * unit), size = Size(4f * unit, height * unit), cornerRadius = CornerRadius(unit),
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(figure?.toString() ?: stringResource(R.string.ping_none_figure), style = Type.figure, color = ink)
            Text(stringResource(if (figure != null) R.string.ping_unit else R.string.ping_no_answer), style = Type.unit, color = colors.muted)
        }
    }
}
