package com.csqtt.client.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.csqtt.client.R
import com.csqtt.client.ui.design.Csq
import com.csqtt.client.ui.design.Shape
import com.csqtt.client.ui.design.Type

/**
 * The app's header (§8.4): the wordmark with the running version beside it, «сборка SanityProtocol» under them, and the
 * screen's few actions on the right.
 */
@Composable
internal fun Header(version: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit) {
    val colors = Csq.colors
    Row(
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                Image(
                    painterResource(R.drawable.ic_csqtt_logo), contentDescription = stringResource(R.string.brand_name),
                    modifier = Modifier.size(91.dp, 22.dp),
                )
                Text(
                    stringResource(R.string.brand_version, version), style = Type.version, color = colors.accentText,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 1.dp),
                )
            }
            Text(stringResource(R.string.brand_build), style = Type.caption, color = colors.muted, modifier = Modifier.padding(start = 3.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** What a tab's badge shows and what TalkBack says for it («2 новые ошибки», «нужен ваш выбор»). */
internal class TabBadge(val text: String, val warn: Boolean, val description: String)

internal class BarItem(val label: String, val icon: ImageVector, val badge: TabBadge? = null)

/**
 * The tab bar: docked, labelled, the open tab tinted whole. Its labels follow the system font size up to 1.3×: five
 * of them share the width (F-Home360x2). On a phone held on its side the bar is lower (F-HomeLandscape).
 */
@Composable
internal fun BottomBar(items: List<BarItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = Csq.colors
    val low = LocalConfiguration.current.screenHeightDp < 480
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(1.3f))) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(colors.surface)
                .drawBehind { drawLine(colors.line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(horizontal = 6.dp, vertical = if (low) 6.dp else 8.dp)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                val on = index == selected
                val ink = if (on) colors.accentText else colors.muted
                // with a badge the tab is read as «Логи, 2 новые ошибки», not as «Логи 2»
                val spoken = item.badge?.let { "${item.label}, ${it.description}" }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 3.dp)
                        .defaultMinSize(minHeight = if (low) 52.dp else 64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (on) colors.accentSoft else Color.Transparent)
                        .selectable(selected = on, role = Role.Tab, onClick = { onSelect(index) })
                        .then(if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier)
                        .padding(vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(56.dp, 24.dp), contentAlignment = Alignment.Center) {
                            Icon(item.icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = ink)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(item.label, style = Type.tab, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    item.badge?.let { badge ->
                        Text(
                            badge.text, style = Type.badge, color = if (badge.warn) colors.onWarn else colors.onAccent,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                // its left edge 6 dp right of the tab's middle, over the icon's corner
                                .layout { measurable, constraints ->
                                    val placed = measurable.measure(constraints)
                                    layout(placed.width, placed.height) { placed.place(placed.width / 2 + 6.dp.roundToPx(), 0) }
                                }
                                .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                                .background(if (badge.warn) colors.warn else colors.accent, Shape.pill)
                                .padding(horizontal = 5.dp),
                        )
                    }
                }
            }
        }
    }
}
