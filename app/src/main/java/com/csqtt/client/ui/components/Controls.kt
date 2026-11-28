package com.csqtt.client.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    enabled: Boolean = true,
    onDone: (() -> Unit)? = null,
    labelEnd: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    now: String? = null,
) {
    val colors = Csq.colors
    Column(modifier.alpha(if (enabled) 1f else 0.5f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = (if (mono) Type.mono else Type.body).copy(color = colors.text),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = keyboard,
            keyboardActions = KeyboardActions(onDone = { if (onDone != null) onDone() else defaultKeyboardAction(ImeAction.Done) }),
            visualTransformation = visual,
            modifier = Modifier.fillMaxWidth().semantics { if (error != null) error(error) },
            // the label is drawn inside the field's own node: TalkBack reads it beside what was typed, where a
            // description on the field was read in place of it
            decorationBox = { text ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // what stands at the label's end says something of the field's value (a hash's status)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = Type.label, color = colors.muted, modifier = Modifier.padding(start = 2.dp).weight(1f))
                        labelEnd?.invoke()
                    }
                    Row(
                        Modifier.fillMaxWidth().defaultMinSize(minHeight = 46.dp).clip(Shape.field).background(colors.raised)
                            .border(1.dp, if (error != null) colors.danger else colors.line, Shape.field)
                            .padding(start = 14.dp, end = if (trailing != null) 4.dp else 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            if (value.isEmpty()) {
                                Text(placeholder, style = Type.body, color = colors.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            text()
                        }
                        trailing?.invoke()
                    }
                }
            },
        )
        if (error != null) Text(error, style = Type.sub, color = colors.danger, modifier = Modifier.padding(start = 2.dp))
        if (hint != null) Text(hint, style = Type.sub, color = colors.muted, modifier = Modifier.padding(start = 2.dp))
        if (now != null) NowLine(now, Modifier.padding(start = 2.dp))
    }
}

/**
 * «Сейчас: …» — what the running tunnel uses, under a control whose value it has not taken yet (card p3-apply-bar; the
 * operator, 2026-11-28: the changed row says what the tunnel uses now). In the accent's colour: it is what works now.
 * [end]: under a choice at a row's end.
 */
@Composable
internal fun NowLine(value: String, modifier: Modifier = Modifier, end: Boolean = false) {
    Text(
        stringResource(R.string.now_value, value), style = Type.sub.copy(fontWeight = FontWeight.Medium), color = Csq.colors.accentText,
        textAlign = if (end) TextAlign.End else TextAlign.Start,
        modifier = if (end) modifier.fillMaxWidth() else modifier,
    )
}

/**
 * A password being typed (M-174). Its text lives in [SecretDrafts] under [draftKey], in memory only: it is never in the
 * saved-instance Bundle (§4.2 rule 2). It is masked; the eye shows it until the screen is recreated or the app is
 * left, and no longer. With [stored] the field shows a secret the store already holds until it is edited: from then
 * on it shows what was typed, an emptied field too, until its form drops the draft. [accept] is what the field takes
 * of what is typed or pasted: a secret that can hold no space drops one, since a masked field cannot show it.
 */
@Composable
internal fun SecretField(
    label: String,
    draftKey: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    error: String? = null,
    hint: String? = null,
    stored: String? = null,
    onDone: (() -> Unit)? = null,
    accept: (String) -> String = { it },
    now: String? = null,
) {
    var shown by remember { mutableStateOf(false) }
    // a secret left shown is hidden again when the app is left: it is not on the screen for whoever picks the phone up
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) shown = false }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Field(
        label = label,
        value = if (stored != null) SecretDrafts.typed(draftKey) ?: stored else SecretDrafts[draftKey],
        onValueChange = { typed -> accept(typed).let { if (stored != null) SecretDrafts.type(draftKey, it) else SecretDrafts[draftKey] = it } },
        modifier = modifier,
        placeholder = placeholder,
        error = error,
        hint = hint,
        mono = shown,
        keyboard = KeyboardOptions(
            keyboardType = KeyboardType.Password, autoCorrectEnabled = false,
            imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default,
        ),
        visual = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        onDone = onDone,
        now = now,
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

/**
 * One of several, the others out of sight until asked for: the value in its box, the choices in a menu under it.
 * [description] says what it sets («Режим хешей»): TalkBack reads it with the value.
 */
@Composable
internal fun <T> Select(
    selected: T,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = Csq.colors
    var open by rememberSaveable { mutableStateOf(false) }
    val shown = options.firstOrNull { it.first == selected }?.second.orEmpty()
    Box(modifier) {
        Row(
            Modifier
                .alpha(if (enabled) 1f else 0.5f)
                .defaultMinSize(minWidth = 118.dp, minHeight = 40.dp)
                .clip(Shape.field)
                .background(colors.raised)
                .border(1.dp, colors.line, Shape.field)
                .clickable(enabled = enabled, role = Role.DropdownList, onClickLabel = description) { open = true }
                .padding(start = 12.dp, end = 8.dp)
                .semantics(mergeDescendants = true) { contentDescription = description },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(shown, style = Type.fact, color = colors.text, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.chevronDown, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.muted)
        }
        Menu(open, { open = false }) {
            for ((value, label) in options) {
                DropdownMenuItem(
                    // TalkBack says which one is the chosen one: the check mark alone is a picture
                    modifier = Modifier.semantics { this.selected = value == selected },
                    text = { Text(label, style = Type.body) },
                    leadingIcon = {
                        if (value == selected) Icon(Icons.check, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.muted)
                        else Spacer(Modifier.size(18.dp))
                    },
                    onClick = {
                        open = false
                        if (value != selected) onSelect(value)
                    },
                )
            }
        }
    }
}

/** A setting on one line: what it is, a «?» that explains it when it needs explaining, and its control at the end. */
@Composable
internal fun SettingRow(
    label: String,
    modifier: Modifier = Modifier,
    onInfo: (() -> Unit)? = null,
    control: @Composable () -> Unit,
) {
    val colors = Csq.colors
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Type.body, color = colors.text, modifier = Modifier.weight(1f, fill = false))
            if (onInfo != null) {
                IconBtn(Icons.help, stringResource(R.string.setting_about, label), onInfo, small = true, tint = colors.faint)
            }
        }
        control()
    }
}

/** The mark of a list one row of which is the choice: filled for the chosen one. The row is the control, not this. */
@Composable
internal fun RadioMark(selected: Boolean, modifier: Modifier = Modifier) {
    val colors = Csq.colors
    Box(
        modifier.size(20.dp).border(2.dp, if (selected) colors.accent else colors.track, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(10.dp).background(colors.accent, CircleShape))
    }
}

/** A choice that is ticked or not, said in a line: «Не напоминать». The whole row is the control. */
@Composable
internal fun CheckRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = Csq.colors
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = Type.body, color = colors.text, modifier = Modifier.weight(1f))
        Box(
            Modifier.size(20.dp).clip(RoundedCornerShape(6.dp))
                .background(if (checked) colors.accent else Color.Transparent)
                .border(2.dp, if (checked) colors.accent else colors.track, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Icons.check, contentDescription = null, modifier = Modifier.size(14.dp), tint = colors.onAccent)
        }
    }
}
