package com.csqtt.client.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.csqtt.client.ui.design.Csq
import com.csqtt.client.ui.design.Icons
import com.csqtt.client.ui.design.Ink
import com.csqtt.client.ui.design.Shape
import com.csqtt.client.ui.design.Type

/** A card: the surface for one subject. [flush] holds a list of cells edge to edge. */
@Composable
internal fun Card(
    modifier: Modifier = Modifier,
    flush: Boolean = false,
    gap: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(Shape.card)
            .background(Csq.colors.surface)
            .border(1.dp, Csq.colors.line, Shape.card)
            .then(if (flush) Modifier else Modifier.padding(horizontal = 16.dp, vertical = 14.dp)),
        verticalArrangement = if (flush) Arrangement.Top else Arrangement.spacedBy(gap),
        content = content,
    )
}

/**
 * A row of a list: a title, a quieter line under it, and what stands at its ends. [selected] is for a list one row of which
 * is the choice (the profiles): the row is tinted, its title bold, and TalkBack says «выбран»; null for a plain row.
 * [divided] draws the hairline to the row above.
 */
@Composable
internal fun Cell(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    selected: Boolean? = null,
    divided: Boolean = false,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = Csq.colors
    val press = when {
        onClick == null -> Modifier
        selected != null -> Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        else -> Modifier.clickable(role = Role.Button, onClick = onClick)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .background(if (selected == true) colors.accentSoft else Color.Transparent)
            .drawBehind {
                if (divided) drawLine(colors.lineSoft, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
            }
            .then(press)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = if (selected == true) Type.bodyStrong else Type.body, color = colors.text)
            if (sub != null) Text(sub, style = Type.sub, color = colors.muted)
        }
        trailing?.invoke(this)
    }
}

internal enum class ChipKind { Ok, Warn, Danger, Accent, Muted }

/** A state in a word: the colour never says it alone. [dot] marks a state that is live (a running server). */
@Composable
internal fun Chip(text: String, modifier: Modifier = Modifier, kind: ChipKind = ChipKind.Muted, dot: Boolean = false, small: Boolean = false) {
    val colors = Csq.colors
    val (ground, ink) = when (kind) {
        ChipKind.Ok -> colors.okSoft to colors.ok
        ChipKind.Warn -> colors.warnSoft to colors.warn
        ChipKind.Danger -> colors.dangerSoft to colors.danger
        ChipKind.Accent -> colors.accentSoft to colors.accentText
        ChipKind.Muted -> colors.raised to colors.muted
    }
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = if (small) 22.dp else 28.dp)
            .background(ground, Shape.pill)
            .padding(horizontal = if (small) 8.dp else 10.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) Box(Modifier.size(8.dp).background(ink, CircleShape))
        Text(text, style = if (small) Type.chipSmall else Type.chip, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal enum class FactKind { Gone, Kept, Info }

/** What a confirmation changes, one line each: what goes, what stays, what to know. */
@Composable
internal fun Facts(items: List<Pair<FactKind, String>>, modifier: Modifier = Modifier) {
    val colors = Csq.colors
    Column(modifier.padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((kind, text) in items) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val (icon, tint) = when (kind) {
                    FactKind.Gone -> Icons.close to colors.danger
                    FactKind.Kept -> Icons.check to colors.ok
                    FactKind.Info -> Icons.info to colors.accentText
                }
                Icon(icon, contentDescription = null, modifier = Modifier.padding(top = 1.dp).size(18.dp), tint = tint)
                Text(text, style = Type.fact, color = colors.text)
            }
        }
    }
}

internal enum class StepState { Done, Active, Pending, Failed, Warn }

internal class Step(val text: String, val state: StepState, val sub: String? = null)

/** The steps of a check or an install: done, the one that runs, the ones to come, and where it failed. */
@Composable
internal fun Steps(steps: List<Step>, modifier: Modifier = Modifier) {
    val colors = Csq.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (step in steps) {
            Row(
                Modifier.defaultMinSize(minHeight = 30.dp).padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StepMark(step.state)
                Column(Modifier.weight(1f)) {
                    Text(
                        step.text,
                        style = if (step.state == StepState.Active) Type.bodyStrong else Type.body,
                        color = if (step.state == StepState.Pending) colors.muted else colors.text,
                    )
                    if (step.sub != null) Text(step.sub, style = Type.sub, color = colors.muted)
                }
            }
        }
    }
}

@Composable
private fun StepMark(state: StepState) {
    val colors = Csq.colors
    val (ground, ink) = when (state) {
        StepState.Done -> colors.okSoft to colors.ok
        StepState.Active -> colors.accent to colors.onAccent
        StepState.Pending -> colors.raised to colors.faint
        StepState.Failed -> colors.dangerSoft to colors.danger
        StepState.Warn -> colors.warnSoft to colors.warn
    }
    val pending = state == StepState.Pending
    Box(
        Modifier.size(22.dp).background(ground, CircleShape)
            .then(if (pending) Modifier.border(1.dp, colors.line, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            StepState.Done -> Icon(Icons.check, null, Modifier.size(16.dp), tint = ink)
            StepState.Failed -> Icon(Icons.close, null, Modifier.size(16.dp), tint = ink)
            StepState.Warn -> Icon(Icons.warning, null, Modifier.size(16.dp), tint = ink)
            StepState.Active -> {
                // the one moving thing on the screen: the step that runs
                val turn by rememberInfiniteTransition(label = "step").animateFloat(
                    0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "turn",
                )
                Icon(Icons.loader, null, Modifier.size(16.dp).rotate(turn), tint = ink)
            }
            StepState.Pending -> Unit
        }
    }
}

/** How far a run is, [fraction] of 1. */
@Composable
internal fun Prog(fraction: Float, modifier: Modifier = Modifier) {
    val done = fraction.coerceIn(0f, 1f)
    Box(
        modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Csq.colors.raised)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(done, 0f..1f) },
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(done).background(Csq.colors.accent, RoundedCornerShape(3.dp)))
    }
}

internal enum class TermKind { Plain, Muted, Ok, Error, Warn, Info }

/** The terminal's ground for a log: «Логи», an install's live log. The lines are [TermLine]s. */
@Composable
internal fun Term(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(Shape.context)
            .background(Ink.termBg)
            .border(1.dp, Csq.colors.line, Shape.context)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        content = content,
    )
}

/** One line of a [Term]: when, what, and how many times in a row it came. */
@Composable
internal fun TermLine(text: String, modifier: Modifier = Modifier, kind: TermKind = TermKind.Plain, time: String? = null, repeats: Int = 1) {
    val ink = when (kind) {
        TermKind.Plain -> Ink.termText
        TermKind.Muted -> Ink.termMuted
        TermKind.Ok -> Ink.termOk
        TermKind.Error -> Ink.termError
        TermKind.Warn -> Ink.termWarn
        TermKind.Info -> Ink.termInfo
    }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (time != null) Text(time, style = Type.term, color = Ink.termMuted)
        Text(text, style = Type.term, color = ink, modifier = Modifier.weight(1f))
        if (repeats > 1) {
            Text(
                "×$repeats", style = Type.tag, color = Ink.termCount,
                modifier = Modifier.background(Ink.termCountBg, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp),
            )
        }
    }
}
