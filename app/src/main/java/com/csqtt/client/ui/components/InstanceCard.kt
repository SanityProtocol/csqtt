package com.csqtt.client.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.csqtt.client.R
import com.csqtt.client.ui.design.Csq
import com.csqtt.client.ui.design.Icons
import com.csqtt.client.ui.design.Type

internal class InstanceAction(val text: String, val primary: Boolean = false, val onClick: () -> Unit)

/**
 * One installation found on a VPS (§6.13): what it is, how it runs, its state in one chip and at most one [qualifier],
 * and only the actions that fit it, the recommended one first. The row of actions never wraps; the rest are behind
 * [onMore], and «Подробнее» sits on the right.
 */
@Composable
internal fun InstanceCard(
    title: String,
    sub: String,
    state: Pair<String, ChipKind>,
    modifier: Modifier = Modifier,
    name: String? = null,
    stateLive: Boolean = false,
    qualifier: Pair<String, ChipKind>? = null,
    note: String? = null,
    actions: List<InstanceAction> = emptyList(),
    onMore: (() -> Unit)? = null,
    onDetails: (() -> Unit)? = null,
) {
    val colors = Csq.colors
    Card(modifier, gap = 10.dp) {
        Column {
            Text(title, style = Type.bodyStrong, color = colors.text)
            Text(sub, style = Type.sub, color = colors.muted)
            if (name != null) Text(name, style = Type.mono, color = colors.muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(state.first, kind = state.second, dot = stateLive)
            if (qualifier != null) Chip(qualifier.first, kind = qualifier.second)
        }
        if (note != null) Text(note, style = Type.sub, color = colors.text)
        if (actions.isNotEmpty() || onMore != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                for (action in actions) {
                    Btn(
                        action.text, action.onClick, Modifier.weight(1f).defaultMinSize(minHeight = 40.dp),
                        kind = if (action.primary) BtnKind.Primary else BtnKind.Plain, small = true,
                    )
                }
                if (onMore != null) IconBtn(Icons.more, stringResource(R.string.instance_more), onMore, outlined = true)
            }
        }
        if (onDetails != null) TextBtn(stringResource(R.string.instance_details), onDetails, Modifier.align(Alignment.End))
    }
}
