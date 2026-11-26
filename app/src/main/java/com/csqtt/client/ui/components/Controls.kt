package com.csqtt.client.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.csqtt.client.R
import com.csqtt.client.ui.design.Csq
import com.csqtt.client.ui.design.Icons
import com.csqtt.client.ui.design.Shape
import com.csqtt.client.ui.design.Type
import com.csqtt.client.ui.shell.SecretDrafts

/** The switch itself; [SwitchRow] is the control. A stored value shows as it is, only a change slides. */
@Composable
private fun Toggle(checked: Boolean, enabled: Boolean) {
    val colors = Csq.colors
    val at by animateDpAsState(if (checked) 21.dp else 3.dp, label = "toggle")
    Box(
        Modifier.alpha(if (enabled) 1f else 0.45f).size(46.dp, 28.dp)
            .background(if (checked) colors.accent else colors.track, Shape.pill),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.offset(x = at).size(22.dp).shadow(1.dp, CircleShape).background(Color.White, CircleShape))
    }
}

/** A setting that is on or off. The whole row is the switch: one target, read as «название, включено». */
@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    sub: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body, color = Csq.colors.text)
            if (sub != null) Text(sub, style = Type.sub, color = Csq.colors.muted)
        }
        Toggle(checked, enabled)
    }
}

/**
 * A text field with its label above. An [error] is said in words under the field, never by the border alone. The caller
 * keeps [value] (rememberSaveable for a draft); a password never goes through here: see [SecretField].
 */
@Composable
internal fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    error: String? = null,
    hint: String? = null,
    mono: Boolean = false,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    visual: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = Csq.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = (if (mono) Type.mono else Type.body).copy(color = colors.text),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = keyboard,
            visualTransformation = visual,
            modifier = Modifier.fillMaxWidth().semantics { if (error != null) error(error) },
            // the label is drawn inside the field's own node: TalkBack reads it beside what was typed, where a
            // description on the field was read in place of it
            decorationBox = { text ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(label, style = Type.label, color = colors.muted, modifier = Modifier.padding(start = 2.dp))
                    Row(
                        Modifier.fillMaxWidth().defaultMinSize(minHeight = 46.dp).clip(Shape.field).background(colors.raised)
                            .border(1.dp, if (error != null) colors.danger else colors.line, Shape.field)
                            .padding(start = 14.dp, end = if (trailing != null) 4.dp else 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            if (value.isEmpty()) Text(placeholder, style = Type.body, color = colors.faint, maxLines = 1)
                            text()
                        }
                        trailing?.invoke()
                    }
                }
            },
        )
        if (error != null) Text(error, style = Type.sub, color = colors.danger, modifier = Modifier.padding(start = 2.dp))
        if (hint != null) Text(hint, style = Type.sub, color = colors.muted, modifier = Modifier.padding(start = 2.dp))
    }
}

/**
 * A password being typed (M-174). Its text lives in [SecretDrafts] under [draftKey], in memory only: it is never in the
 * saved-instance Bundle (§4.2 rule 2). It is masked; the eye shows it until the screen is recreated, and no longer.
 */
@Composable
internal fun SecretField(
    label: String,
    draftKey: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    error: String? = null,
    hint: String? = null,
) {
    var shown by remember { mutableStateOf(false) }
    Field(
        label = label,
        value = SecretDrafts[draftKey],
        onValueChange = { SecretDrafts[draftKey] = it },
        modifier = modifier,
        placeholder = placeholder,
        error = error,
        hint = hint,
        mono = shown,
        keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        visual = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        trailing = {
            IconBtn(
                icon = if (shown) Icons.eyeOff else Icons.eye,
                description = stringResource(if (shown) R.string.secret_hide else R.string.secret_show),
                onClick = { shown = !shown },
                small = true,
                tint = Csq.colors.muted,
            )
        },
    )
}

/** One of a few, all in sight: «Экспорт | Импорт». */
@Composable
internal fun Seg(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = Csq.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.raised, Shape.segment)
            .border(1.dp, colors.lineSoft, Shape.segment)
            .padding(3.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { index, option ->
            val on = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 38.dp)
                    .then(if (on) Modifier.shadow(1.dp, Shape.segmentItem).background(colors.surface, Shape.segmentItem) else Modifier)
                    .clip(Shape.segmentItem)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(index) })
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(option, style = Type.buttonSmall, color = if (on) colors.text else colors.muted, textAlign = TextAlign.Center)
            }
        }
    }
}
