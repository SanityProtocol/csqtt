package com.csqtt.client.ui.profiles

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.ProfileEntry
import com.csqtt.client.ProfileKind
import com.csqtt.client.ProfileOrder
import com.csqtt.client.ProfileSort
import com.csqtt.client.R
import com.csqtt.client.SubscriptionMeta
import com.csqtt.client.SubscriptionSync
import com.csqtt.client.TunnelManager
import com.csqtt.client.noticeTime
import com.csqtt.client.ui.components.Btn
import com.csqtt.client.ui.components.Card
import com.csqtt.client.ui.components.IconBtn
import com.csqtt.client.ui.components.Sheet
import com.csqtt.client.ui.components.TextBtn
import com.csqtt.client.ui.design.Csq
import com.csqtt.client.ui.design.Icons
import com.csqtt.client.ui.design.Type
import com.csqtt.client.ui.shell.LocalNav
import com.csqtt.client.ui.shell.LocalOwners
import com.csqtt.client.ui.shell.LocalStore
import com.csqtt.client.ui.shell.Routes
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * «Профили» (the canvas, F-Profiles): every profile the user has — their own, each subscription's nodes under its
 * heading, the hidden ones folded at the end — to pick from, to put in order and to act on; and the way to add one.
 */
@Composable
internal fun ProfilesSheet(close: () -> Unit) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val entries by store.profileEntries.collectAsStateWithLifecycle(initialValue = emptyList())
    val sort by store.profileSort.collectAsStateWithLifecycle(initialValue = ProfileSort.Custom)
    val order by store.profileOrder.collectAsStateWithLifecycle(initialValue = emptyList())
    val active by store.activeProfile.collectAsStateWithLifecycle(initialValue = -1)
    val metas by store.subscriptionMeta.collectAsStateWithLifecycle(initialValue = emptyMap())
    val shown = remember(entries, sort, order) { ProfileOrder.shown(entries, sort, order) }
    val acts = profileActs(entries, active, afterPick = close)

    val own = shown.filter { it.kind != ProfileKind.Subscription }
    // a subscription's nodes, the hidden ones among them: its heading counts both
    val subscriptions = remember(entries) { entries.filter { it.kind == ProfileKind.Subscription }.groupBy { it.subscriptionUrl } }
    val hidden = entries.filter { it.hidden }
    val sortControl: @Composable () -> Unit = { SortMenu(sort) { picked -> TunnelManager.scope.launch { store.saveProfileSort(picked) } } }

    Sheet(onDismiss = close, title = stringResource(R.string.profiles_title), messages = LocalOwners.current.messages) {
        if (own.isNotEmpty()) {
            Heading(stringResource(R.string.profiles_mine), sortControl)
            Group(own, shown, order, sort, active, acts) { profileSheetLine(it) }
        }
        var first = own.isEmpty()
        for ((url, nodes) in subscriptions) {
            SubscriptionHeading(url, nodes, metas[url], if (first) sortControl else null)
            first = false
            val rows = shown.filter { it.kind == ProfileKind.Subscription && it.subscriptionUrl == url }
            if (rows.isNotEmpty()) Group(rows, shown, order, sort, active, acts) { profileSheetLine(it) }
        }
        if (hidden.isNotEmpty()) Hidden(hidden, acts)
        Btn(
            stringResource(R.string.profiles_add),
            {
                // «Добавить профиль» takes this sheet's place, as on the board: one sheet at a time on the way in
                nav.closeRoutes(Routes.PROFILES)
                nav.open(Routes.addProfile())
            },
            icon = Icons.plus, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        Text(
            stringResource(R.string.profiles_foot), style = Type.sub, color = Csq.colors.muted, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Heading(text: String, control: (@Composable () -> Unit)? = null, actions: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = Type.section, color = Csq.colors.muted, modifier = Modifier.semantics { heading() })
        control?.invoke()
        if (actions != null) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) { actions() }
        }
    }
}

/**
 * One card of rows that can be put in order: a long press lifts a row, and it is dropped among the rows of its own card
 * (the canvas, F-ProfilesReorder). A move makes the order the user's own, and says so with the way back.
 */
@Composable
private fun Group(
    rows: List<ProfileEntry>,
    shown: List<ProfileEntry>,
    order: List<Int>,
    sort: ProfileSort,
    active: Int,
    acts: ProfileActs,
    line: @Composable (ProfileEntry) -> String,
) {
    val store = LocalStore.current
    val context = LocalContext.current.applicationContext
    val messages = LocalOwners.current.messages
    val view = LocalView.current
    val colors = Csq.colors
    val moveUp = stringResource(R.string.profile_move_up)
    val moveDown = stringResource(R.string.profile_move_down)

    // the rows as a drop left them, until the store answers — with its rows, or with its order and sort when the rows
    // it shows did not change: no jump back in between, and nothing left drawn that the store does not hold
    val stored = rows.map { it.index }
    var dropped by remember { mutableStateOf<List<Int>?>(null) }
    LaunchedEffect(stored, order, sort) { dropped = null }
    val drawn = dropped?.mapNotNull { index -> rows.firstOrNull { it.index == index } }?.takeIf { it.size == rows.size } ?: rows

    var lifted by remember { mutableStateOf<Int?>(null) }
    var dragged by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<Int, Int>() }
    // a gesture outlives the composition it began in: what it reads is read through state, as it is now
    val group by rememberUpdatedState(drawn.map { it.index })
    val pinned by rememberUpdatedState(drawn.filter { it.pinned }.mapTo(HashSet()) { it.index })
    val shownNow by rememberUpdatedState(shown)
    val orderNow by rememberUpdatedState(order)
    // a long press that lifts a row and is let go in place ends as the row's own tap: it picks nothing while a row is
    // lifted (read at the tap, which comes before the gesture's end)
    val guarded = ProfileActs(pick = { if (lifted == null) acts.pick(it) }, run = acts.run)

    /** Where the lifted row would land: past each neighbour whose middle it has crossed; -1 with none lifted. */
    fun landing(): Int {
        val from = group.indexOf(lifted)
        if (from < 0) return -1
        var to = from
        var passed = 0f
        val step = if (dragged > 0) 1 else -1
        var next = from + step
        // pinned rows stay first whatever the order: a row is not moved past a neighbour whose pin differs from its own
        while (next in group.indices && (group[next] in pinned) == (lifted in pinned)) {
            val height = heights[group[next]] ?: break
            if (abs(dragged) <= passed + height / 2f) break
            passed += height
            to = next
            next += step
        }
        return to
    }

    fun move(profile: Int, to: Int) {
        val at = group.indexOf(profile)
        if (at < 0 || to == at || to !in group.indices || (group[to] in pinned) != (profile in pinned)) return
        val next = ProfileOrder.movedInGroup(shownNow, orderNow, group, profile, to) ?: return
        dropped = group.toMutableList().apply { remove(profile); add(to, profile) }
        // on the app's scope: the order is stored even if the sheet goes the same moment
        TunnelManager.scope.launch(Dispatchers.Main) {
            val before = store.saveProfileMove(next)
            messages.show(context.getString(R.string.profiles_order_own), context.getString(R.string.action_undo)) {
                TunnelManager.scope.launch { store.restoreProfileOrder(before) }
            }
        }
    }

    // the rows are drawn again when the landing place changes, not at every pixel of the drag
    val from = group.indexOf(lifted)
    val to by remember { derivedStateOf { landing() } }
    Card(flush = true) {
        drawn.forEachIndexed { position, entry ->
            key(entry.index) {
                val isLifted = entry.index == lifted
                val room = lifted?.let { heights[it] } ?: 0
                // the rows between where the lifted one was and where it would land make room for it
                val shift by animateIntAsState(
                    when {
                        from < 0 || isLifted -> 0
                        position in (from + 1)..to -> -room
                        position in to until from -> room
                        else -> 0
                    },
                    label = "shift",
                )
                ProfileRow(
                    entry = entry,
                    line = line(entry),
                    selected = entry.index == active,
                    divided = position > 0,
                    acts = guarded,
                    menu = true,
                    swipe = lifted == null,
                    // offered where the move does something: a neighbour of the same pin
                    more = listOfNotNull(
                        if (drawn.getOrNull(position - 1)?.pinned == entry.pinned) CustomAccessibilityAction(moveUp) { move(entry.index, position - 1); true } else null,
                        if (drawn.getOrNull(position + 1)?.pinned == entry.pinned) CustomAccessibilityAction(moveDown) { move(entry.index, position + 1); true } else null,
                    ),
                    modifier = Modifier
                        .onSizeChanged { heights[entry.index] = it.height }
                        .zIndex(if (isLifted) 1f else 0f)
                        // the neighbours make room only while a row is lifted: at the drop the rows are in their new
                        // places already, and a shift still running out would throw them a row too far
                        .offset {
                            val up = lifted
                            IntOffset(0, if (up == entry.index) dragged.roundToInt() else if (up != null) shift else 0)
                        }
                        .then(if (isLifted) Modifier.shadow(8.dp).background(colors.raised) else Modifier)
                        .pointerInput(entry.index) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    dragged = 0f
                                    lifted = entry.index
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                },
                                onDragEnd = {
                                    val target = landing()
                                    lifted = null
                                    move(entry.index, target)
                                },
                                onDragCancel = { lifted = null },
                            ) { change, amount ->
                                change.consume()
                                // not past the ends of its own card
                                val at = group.indexOf(entry.index)
                                val above = group.take(at).sumOf { heights[it] ?: 0 }
                                val below = group.drop(at + 1).sumOf { heights[it] ?: 0 }
                                dragged = (dragged + amount.y).coerceIn(-above.toFloat(), below.toFloat())
                            }
                        },
                )
            }
        }
    }
}

/** A subscription's heading: its name, when it was last refreshed and how often, its nodes; «Обновить» and its settings. */
@Composable
private fun SubscriptionHeading(url: String, nodes: List<ProfileEntry>, meta: SubscriptionMeta?, sortControl: (@Composable () -> Unit)?) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val messages = LocalOwners.current.messages
    val colors = Csq.colors
    val title = meta?.title?.takeIf { it.isNotBlank() } ?: nodes.firstOrNull()?.subscriptionTitle.orEmpty()
    var refreshing by remember(url) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Heading(
            if (title.isEmpty()) stringResource(R.string.profile_subscription) else stringResource(R.string.profile_subscription_named, title),
            sortControl,
        ) {
            IconBtn(
                if (refreshing) Icons.loader else Icons.refresh,
                stringResource(if (refreshing) R.string.sub_refreshing else R.string.sub_refresh),
                {
                    if (!refreshing) {
                        refreshing = true
                        // on the app's scope: a refresh that was asked for ends, and its outcome is kept, whatever
                        // becomes of the sheet
                        TunnelManager.scope.launch(Dispatchers.Main) {
                            val outcome = SubscriptionSync.refresh(store, url)
                            refreshing = false
                            if (outcome.refused != null) nav.open(Routes.subCert(url)) else messages.show(outcome.message)
                        }
                    }
                },
                small = true, tint = colors.muted,
            )
            IconBtn(Icons.pencil, stringResource(R.string.sub_settings), { nav.open(Routes.subEdit(url)) }, small = true, tint = colors.muted)
        }
        val hiddenCount = nodes.count { it.hidden }
        val parts = listOfNotNull(
            subscriptionUpdatedLine(meta),
            subscriptionIntervalLabel(meta?.autoUpdateMinutes ?: 0, inLine = true),
            stringResource(R.string.sub_nodes_count, nodes.size),
            if (hiddenCount > 0) stringResource(R.string.profiles_hidden_count, hiddenCount) else null,
        )
        Text(parts.joinToString(" · "), style = Type.sub, color = colors.muted, modifier = Modifier.padding(horizontal = 4.dp))
        // the last refresh failed: said here, with the way out when it is the certificate that changed
        if (meta != null && meta.lastError.isNotEmpty()) {
            Text(
                if (meta.refused != null) stringResource(R.string.sub_cert_changed_line) else stringResource(R.string.sub_not_refreshed, meta.lastError),
                style = Type.sub, color = colors.danger, modifier = Modifier.padding(horizontal = 4.dp),
            )
            // under the line, not beside it: on a narrow screen with a large font the two do not fit side by side
            if (meta.refused != null) TextBtn(stringResource(R.string.sub_cert_review), { nav.open(Routes.subCert(url)) })
        }
    }
}

/** The hidden profiles, folded: «Скрытые · N». A hidden one is picked, renamed, shown again; it has no place in the order. */
@Composable
private fun Hidden(hidden: List<ProfileEntry>, acts: ProfileActs) {
    var open by rememberSaveable { mutableStateOf(false) }
    Heading(
        stringResource(R.string.profiles_hidden_group, hidden.size),
        control = {
            IconBtn(
                if (open) Icons.chevronUp else Icons.chevronDown,
                stringResource(if (open) R.string.profiles_hidden_fold else R.string.profiles_hidden_unfold),
                { open = !open }, small = true, tint = Csq.colors.muted,
            )
        },
    )
    if (open) {
        Card(flush = true) {
            hidden.forEachIndexed { position, entry ->
                key(entry.index) {
                    ProfileRow(
                        entry = entry, line = profileSheetLine(entry, withSubscription = true), selected = false,
                        divided = position > 0, acts = acts, menu = true, dimmed = true,
                    )
                }
            }
        }
    }
}

/** «Обновлена 20:21», «Обновлена 25.11 20:21», or that it never was. */
@Composable
internal fun subscriptionUpdatedLine(meta: SubscriptionMeta?): String {
    val at = meta?.lastUpdatedAt ?: 0L
    return if (at > 0L) stringResource(R.string.sub_updated_at, remember(at) { noticeTime(at) }) else stringResource(R.string.sub_never_updated)
}

/** The refresh intervals a subscription can be set to (the canvas, F-SubEdit), in minutes; 0 is off. */
internal val SubscriptionIntervals = listOf(15, 30, 60, 180, 360, 720, 1440, 0)

/** How often a subscription refreshes, as its settings name it; [inLine], as its heading's line says it. */
@Composable
internal fun subscriptionIntervalLabel(minutes: Int, inLine: Boolean = false): String = when (minutes) {
    0 -> stringResource(if (inLine) R.string.sub_interval_off_line else R.string.sub_interval_off)
    15 -> stringResource(R.string.sub_interval_15m)
    30 -> stringResource(R.string.sub_interval_30m)
    60 -> stringResource(R.string.sub_interval_1h)
    180 -> stringResource(R.string.sub_interval_3h)
    360 -> stringResource(R.string.sub_interval_6h)
    720 -> stringResource(R.string.sub_interval_12h)
    1440 -> stringResource(R.string.sub_interval_1d)
    // a value another build stored
    else -> stringResource(R.string.sub_interval_minutes, minutes)
}
