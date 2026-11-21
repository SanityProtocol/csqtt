package com.csqtt.client.ui.legacy

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.csqtt.client.ui.legacy.design.CsqttShapes
import com.csqtt.client.ui.legacy.design.CsqttSpacing
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.luminance

@Immutable
internal data class AppItem(
    val name: String,
    val packageName: String,
    val icon: ImageBitmap?,
    val isSystem: Boolean,
)

@Composable
internal fun AppRow(
    app: AppItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.22f

    val surfaceColor = if (isSelected) {
        colors.primaryContainer.copy(alpha = if (isDark) 0.65f else 0.85f)
    } else {
        colors.surface.copy(alpha = if (isDark) 0.50f else 0.65f)
    }

    val borderColor = if (isSelected) {
        colors.primary.copy(alpha = 0.50f)
    } else {
        colors.outlineVariant.copy(alpha = if (isDark) 0.28f else 0.35f)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .toggleable(
                value = isSelected,
                role = Role.Checkbox,
                onValueChange = { onClick() },
            ),
        shape = CsqttShapes.Card,
        color = surfaceColor,
        contentColor = if (isSelected) colors.onPrimaryContainer else colors.onSurface,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = CsqttSpacing.Md, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(CsqttSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (app.icon != null) {
                Image(
                    bitmap = app.icon,
                    contentDescription = null,
                    modifier = Modifier.size(42.dp).clip(CsqttShapes.Small),
                )
            } else {
                Box(modifier = Modifier.size(42.dp).background(colors.surfaceVariant, CsqttShapes.Small))
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Checkbox(
                checked = isSelected,
                onCheckedChange = null,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}

/**
 * M-62 / D24: the installed VK apps, locked out of every list — the tunnel itself runs through VK, so they always go
 * directly. One row, not selectable, with the reason.
 */
@Composable
internal fun VkDirectRow(names: List<String>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.22f
    val title = "Приложения VK всегда напрямую"
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$title: ${names.joinToString()}" },
        shape = CsqttShapes.Card,
        color = colors.surfaceVariant.copy(alpha = if (isDark) 0.35f else 0.55f),
        contentColor = colors.onSurfaceVariant,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = if (isDark) 0.28f else 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = CsqttSpacing.Md, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(CsqttSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(42.dp).background(colors.surfaceVariant, CsqttShapes.Small),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(22.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface,
                )
                Text(
                    text = names.joinToString(", ") + ". Туннель идёт через VK, поэтому они работают мимо него.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
        }
    }
}
