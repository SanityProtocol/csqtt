package com.csqtt.client.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.csqtt.client.R
import com.csqtt.client.RunView
import com.csqtt.client.ui.components.Btn
import com.csqtt.client.ui.components.BtnKind
import com.csqtt.client.ui.design.Csq
import com.csqtt.client.ui.design.Type
import com.csqtt.client.ui.home.HomeMotion

/**
 * The run as the screens see it ([RunView]): null with no run. The shell collects it once and gives it to everything
 * under it — the bar, the sheets, the rows that say «Сейчас: …».
 */
internal val LocalRun = compositionLocalOf<RunView?> { null }

/**
 * Whether the run was read yet: false for the first moments of a screen, before the store has answered. [LocalRun] is
 * null then and also with no run, and what is drawn of a run must not take the one for the other.
 */
internal val LocalRunKnown = compositionLocalOf { false }

/** What a tap on the bar's button does; the shell gives it, a test its own. */
internal val LocalReconnect = compositionLocalOf<() -> Unit> { {} }

/**
 * The apply bar (card p3-apply-bar; the operator, 2026-11-28: «Bar with «Переподключить»», then «That bar - for every
 * change that require a reconnect»). A setting a running tunnel keeps from its start was changed, on whichever screen:
 * from that moment the bar says «Изменения применятся после переподключения» and offers the reconnect, on every tab —
 * docked on the tab bar — and on the foot of a sheet, which covers the tabs. It goes when the tunnel has reconnected
 * or the value is put back. After the tap it reads «Переподключение…» until the next connection takes over.
 *
 * Its room opens and closes as Home's lines do ([HomeMotion]): it changes the room Home's card is centred in. Where
 * Home does not move — a phone low on memory — it is there or not, at once. And so it is the first time it is drawn,
 * as Home's first frames are: a bar that waits when the app is opened, or when a sheet opens over it, is there with no
 * motion; only a change seen on screen moves.
 */
@Composable
internal fun ApplyBar(modifier: Modifier = Modifier, sideInsets: Boolean = false) {
    // nothing before the run was read once: «no run» and «not read yet» look the same, and the second is not a state to draw
    if (!LocalRunKnown.current) return
    val run = LocalRun.current
    val shown = run?.offered == true
    // The wording the bar had while it was shown is the one it leaves with: its content is still composed while its
    // room closes, and a reconnect that has just been made is no offer to make it. Kept beside the composition, not in
    // a state: it is read where it is written
    val wording = remember { BooleanArray(1) }
    if (run != null && shown) wording[0] = run.reconnecting
    val busy = wording[0]
    val context = LocalContext.current
    val moves = remember(context) { HomeMotion.wanted(context) }
    if (moves) {
        // made with what is shown now, so the first drawing is whole; a change after it opens or closes the room
        val visible = remember { MutableTransitionState(shown) }
        visible.targetState = shown
        AnimatedVisibility(
            visibleState = visible, modifier = modifier,
            enter = expandVertically(HomeMotion.spec(), expandFrom = Alignment.Top),
            exit = shrinkVertically(HomeMotion.spec(), shrinkTowards = Alignment.Top),
        ) { ApplyBarBody(busy = busy, sideInsets = sideInsets) }
    } else if (shown) {
        Box(modifier) { ApplyBarBody(busy = busy, sideInsets = sideInsets) }
    }
}

/** Narrower than this at the font's scale, the text and the button do not fit side by side: the button goes under. */
private val SideBySide: Dp = 330.dp

/** [sideInsets]: over the tab bar the bar's ground runs under the system's side bars and the cutout, its content beside them. */
@Composable
private fun ApplyBarBody(busy: Boolean, sideInsets: Boolean) {
    val colors = Csq.colors
    val reconnect = LocalReconnect.current
    val scale = LocalDensity.current.fontScale
    val sides = if (sideInsets) {
        Modifier.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal))
    } else {
        Modifier
    }
    BoxWithConstraints(
        Modifier.fillMaxWidth().background(colors.surface)
            .drawBehind { drawLine(colors.line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .then(sides),
        contentAlignment = Alignment.Center,
    ) {
        val stacked = maxWidth / scale < SideBySide
        // the bar's own type: a button's size and weight, so the line reads as one with its button
        val text = @Composable { weight: Modifier ->
            Text(
                stringResource(if (busy) R.string.apply_bar_busy else R.string.apply_bar_text),
                style = if (busy) Type.buttonSmall.copy(fontWeight = FontWeight.SemiBold) else Type.buttonSmall,
                color = if (busy) colors.accentText else colors.text,
                // TalkBack says it when it comes, and again when the reconnect begins
                modifier = weight.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        val button = @Composable {
            if (!busy) Btn(stringResource(R.string.apply_bar_action), reconnect, kind = BtnKind.Primary, small = true)
        }
        if (stacked) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().defaultMinSize(minHeight = 60.dp)
                    .padding(start = 16.dp, top = 10.dp, end = 12.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                text(Modifier.fillMaxWidth())
                Box(Modifier.align(Alignment.End)) { button() }
            }
        } else {
            Row(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().defaultMinSize(minHeight = 60.dp)
                    .padding(start = 16.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                text(Modifier.weight(1f))
                button()
            }
        }
    }
}
