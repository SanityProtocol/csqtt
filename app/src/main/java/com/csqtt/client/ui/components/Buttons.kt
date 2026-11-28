package com.csqtt.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.csqtt.client.ui.design.Csq
import com.csqtt.client.ui.design.Shape
import com.csqtt.client.ui.design.Type

/** [Danger] is an action that removes, among others; [Destroy] is the one button of a dialog that confirms the removal. */
internal enum class BtnKind { Plain, Primary, Tonal, Danger, Destroy }

/**
 * A pill button. One primary per surface; [small] for a card's own actions, drawn at 36 dp: Compose widens a smaller
 * target's touch area to 48 dp by itself, so the rows stay as tight as they are drawn.
 */
@Composable
internal fun Btn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: BtnKind = BtnKind.Plain,
    small: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val colors = Csq.colors
    val ground = when (kind) {
        BtnKind.Primary -> colors.accent
        BtnKind.Tonal -> colors.accentSoft
        BtnKind.Destroy -> colors.danger
        BtnKind.Plain, BtnKind.Danger -> colors.surface
    }
    val ink = when (kind) {
        BtnKind.Primary -> colors.onAccent
        BtnKind.Tonal -> colors.accentText
        BtnKind.Danger -> colors.danger
        BtnKind.Destroy -> colors.onDanger
        BtnKind.Plain -> colors.text
    }
    val edge = when (kind) {
        BtnKind.Primary, BtnKind.Tonal, BtnKind.Destroy -> Color.Transparent
        BtnKind.Plain, BtnKind.Danger -> colors.line
    }
    Row(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.5f)
            .defaultMinSize(minHeight = if (small) 36.dp else 48.dp)
            .clip(Shape.pill)
            .background(ground)
            .border(1.dp, edge, Shape.pill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (small) 12.dp else 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = ink)
        Text(
            text, style = if (small) Type.buttonSmall else Type.button, color = ink, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A text action: «Отмена» beside a dialog's button, «Подробнее» on a card. */
@Composable
internal fun TextBtn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false) {
    Text(
        text, style = Type.button, color = if (danger) Csq.colors.danger else Csq.colors.accentText, maxLines = 1,
        // a label too long for its place ends in «…»: it is not cut mid-word with nothing to say so
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(Shape.pill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/**
 * An icon alone always says what it does: [description] is read by TalkBack. [outlined] is the icon beside a row of
 * buttons, drawn as one of them (an instance's «ещё»). [glyph] draws the icon at another size in the same target: a
 * row's actions at the size of the row's own marks.
 */
@Composable
internal fun IconBtn(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    outlined: Boolean = false,
    tint: Color = Csq.colors.text,
    glyph: Dp = if (small) 18.dp else 22.dp,
) {
    val frame = when {
        outlined -> Modifier.size(44.dp, 40.dp).clip(Shape.pill).border(1.dp, Csq.colors.line, Shape.pill)
        else -> Modifier.minimumInteractiveComponentSize().size(if (small) 36.dp else 44.dp).clip(CircleShape)
    }
    Box(
        modifier = modifier
            .then(frame)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(glyph), tint = tint)
    }
}
