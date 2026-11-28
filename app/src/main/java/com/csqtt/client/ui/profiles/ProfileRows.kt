package com.csqtt.client.ui.profiles

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.csqtt.client.DeployManager
import com.csqtt.client.ProfileAction
import com.csqtt.client.ProfileEntry
import com.csqtt.client.ProfileKind
import com.csqtt.client.ProfileMenu
import com.csqtt.client.ProfileSort
import com.csqtt.client.R
import com.csqtt.client.TunnelManager
import com.csqtt.client.pickProfile
import com.csqtt.client.ui.components.Cell
import com.csqtt.client.ui.components.IconBtn
import com.csqtt.client.ui.components.Menu
import com.csqtt.client.ui.design.Csq
import com.csqtt.client.ui.design.Icons
import com.csqtt.client.ui.design.Type
import com.csqtt.client.ui.design.pluralText
import com.csqtt.client.ui.legacy.utils.parseCsqttLink
import com.csqtt.client.ui.shell.LocalNav
import com.csqtt.client.ui.shell.LocalOwners
import com.csqtt.client.ui.shell.LocalStore
import com.csqtt.client.ui.shell.Routes
import com.csqtt.client.ui.shell.Tab
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/*
 * A profile's row, as «Главная» and the profiles sheet both draw it (the canvas, F-HomeSwipe, F-ProfileActions): a tap
 * picks it, a swipe left shows its actions in its place, the sheet's rows also have them as a menu under ⋮, and
 * TalkBack has them as the row's own actions.
 */

/** What a list's rows do: [pick] on a tap, [run] for an action of the swipe, the menu or TalkBack. */
internal class ProfileActs(val pick: (ProfileEntry) -> Unit, val run: (ProfileAction, ProfileEntry) -> Unit)

/**
 * The rows' actions for a list. [active] is the open profile. [afterPick] runs once a pick is stored: the sheet closes
 * itself there, in the same coroutine, so the pick is never cut short by its own sheet going (M-97).
 */
@Composable
internal fun profileActs(entries: List<ProfileEntry>, active: Int, afterPick: (ProfileEntry) -> Unit = {}): ProfileActs {
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalContext.current.applicationContext
    val messages = LocalOwners.current.messages
    // on the app's scope, whatever becomes of the screen: a reconnect asked for by a pick goes out, a hide is stored
    val scope = TunnelManager.scope
    return ProfileActs(
        pick = { entry ->
            scope.launch(Dispatchers.Main) {
                pickProfile(context, store, entry.index, active)
                afterPick(entry)
            }
        },
        run = { action, entry ->
            when (action) {
                ProfileAction.Rename -> nav.open(Routes.profileRename(entry.index))
                ProfileAction.Pin, ProfileAction.Unpin -> scope.launch { store.setProfilePinned(entry.index, action == ProfileAction.Pin) }
                ProfileAction.Show -> scope.launch { store.setProfileHidden(entry.index, false) }
                ProfileAction.Hide -> scope.launch(Dispatchers.Main) {
                    if (store.setProfileHidden(entry.index, true)) {
                        messages.show(context.getString(R.string.profile_hidden_done, entry.name), context.getString(R.string.action_undo)) {
                            scope.launch { store.setProfileHidden(entry.index, false) }
                        }
                    }
                }
                ProfileAction.OpenTunnel -> {
                    // the run stays where it is, and says so: opening a profile's settings is not a pick
                    val runProfile = TunnelManager.runTargetProfile()
                    if (TunnelManager.connectionState.value.powerIsOn && runProfile != entry.index) {
                        entries.firstOrNull { it.index == runProfile }?.let { messages.show(context.getString(R.string.home_run_stays, it.name)) }
                    }
                    scope.launch(Dispatchers.Main) {
                        if (entry.index != active) store.saveActiveProfile(entry.index)
                        nav.closeRoutes(Routes.PROFILES)
                        nav.select(Tab.Tunnel)
                    }
                }
                ProfileAction.Delete -> when {
                    // the profile the tunnel is on is not deleted from under it
                    TunnelManager.connectionState.value.powerIsOn && TunnelManager.runTargetProfile() == entry.index ->
                        messages.show(context.getString(R.string.profile_delete_running))
                    DeployManager.isDeploying.value -> messages.show(context.getString(R.string.profile_delete_deploying))
                    else -> nav.open(Routes.profileDelete(entry.index))
                }
            }
        },
    )
}

/** [on]: the action takes back what is on now — a pinned profile's pin — and the swiped row's icon says so by its colour. */
private class ActionLook(val icon: ImageVector, val label: Int, val sub: Int?, val danger: Boolean = false, val on: Boolean = false)

/**
 * How each action is drawn. The line under a menu item is one short line (26 characters at most: whole at a 1.3× font on
 * a 360 dp screen) and never wraps; «Удалить» has none (the operator, 2026-11-26).
 */
private fun look(action: ProfileAction): ActionLook = when (action) {
    ProfileAction.Show -> ActionLook(Icons.eye, R.string.profile_show, R.string.profile_show_sub)
    ProfileAction.Rename -> ActionLook(Icons.pencil, R.string.profile_rename, R.string.profile_rename_sub)
    ProfileAction.Pin -> ActionLook(Icons.pin, R.string.profile_pin, R.string.profile_pin_sub)
    ProfileAction.Unpin -> ActionLook(Icons.pin, R.string.profile_unpin, R.string.profile_unpin_sub, on = true)
    ProfileAction.Hide -> ActionLook(Icons.eyeOff, R.string.profile_hide, R.string.profile_hide_sub)
    ProfileAction.OpenTunnel -> ActionLook(Icons.key, R.string.profile_open_tunnel, R.string.profile_open_tunnel_sub)
    ProfileAction.Delete -> ActionLook(Icons.trash, R.string.action_delete, null, danger = true)
}

private val ActionWidth = 44.dp

/**
 * One profile's row. [menu] adds ⋮ with the same actions (the sheet's rows; Home's have the swipe alone). [more] are
 * further actions TalkBack is given: moving the row, where a list can be reordered. [dimmed] is a hidden profile's look.
 * [swipe] false holds the swipe back.
 */
@Composable
internal fun ProfileRow(
    entry: ProfileEntry,
    line: String,
    selected: Boolean,
    divided: Boolean,
    acts: ProfileActs,
    modifier: Modifier = Modifier,
    menu: Boolean = false,
    dimmed: Boolean = false,
    swipe: Boolean = true,
    more: List<CustomAccessibilityAction> = emptyList(),
) {
    val colors = Csq.colors
    val scope = rememberCoroutineScope()
    val items = remember(entry, selected) { ProfileMenu.items(entry, selected) }
    val labels = items.map { stringResource(look(it).label) }
    val open = with(LocalDensity.current) { -(ActionWidth * items.size).toPx() }
    val slide = remember { Animatable(0f) }
    val close = { scope.launch { slide.animateTo(0f) } }
    val pinnedLabel = stringResource(R.string.profile_pinned)
    val hiddenLabel = stringResource(R.string.profile_hidden)
    var menuOpen by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier.fillMaxWidth().draggable(
            // off while the row is being moved up or down: one gesture at a time
            enabled = swipe,
            orientation = Orientation.Horizontal,
            // at once, not queued: a delta that ran after the lift's settle would cancel it and leave the row half open
            state = rememberDraggableState { delta ->
                scope.launch(start = CoroutineStart.UNDISPATCHED) { slide.snapTo((slide.value + delta).coerceIn(open, 0f)) }
            },
            onDragStopped = { slide.animateTo(if (slide.value < open / 2) open else 0f) },
        ),
    ) {
        Cell(
            title = entry.name,
            sub = line,
            selected = selected,
            divided = divided,
            onClick = { if (slide.value < 0f) close() else acts.pick(entry) },
            modifier = Modifier.alpha(if (dimmed) 0.6f else 1f).semantics {
                customActions = items.mapIndexed { index, action ->
                    CustomAccessibilityAction(labels[index]) { acts.run(action, entry); true }
                } + more
            },
            trailing = {
                if (entry.pinned && !entry.hidden) {
                    Icon(Icons.pin, contentDescription = pinnedLabel, modifier = Modifier.size(16.dp), tint = colors.accentText)
                }
                if (entry.hidden) {
                    Icon(Icons.eyeOff, contentDescription = hiddenLabel, modifier = Modifier.size(16.dp), tint = colors.faint)
                }
                if (menu) {
                    Box {
                        IconBtn(
                            Icons.more, stringResource(R.string.profile_actions, entry.name), { menuOpen = true },
                            small = true, tint = colors.muted,
                        )
                        Menu(menuOpen, { menuOpen = false }) {
                            for (action in items) {
                                ActionItem(look(action)) {
                                    menuOpen = false
                                    acts.run(action, entry)
                                }
                            }
                        }
                    }
                }
            },
        )
        // the row keeps its place and its name; the actions come in from the right, over the end of it
        if (slide.value < 0f) {
            Box(
                Modifier.matchParentSize().offset { IntOffset((slide.value - open).roundToInt(), 0) },
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(
                    Modifier.fillMaxHeight().shadow(4.dp).background(colors.raised),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items.forEachIndexed { index, action ->
                        val drawn = look(action)
                        IconBtn(
                            drawn.icon, labels[index], { close(); acts.run(action, entry) },
                            // a pinned profile's pin is blue here as on the row it covers (the operator, 2026-11-28:
                            // «users need to know it's pinned or not while slided»)
                            tint = when {
                                drawn.danger -> colors.danger
                                drawn.on -> colors.accentText
                                else -> colors.text
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One item of a row's menu. Its own row, not Material's item: that one is never wider than 280 dp, which cuts the line
 * under «Переименовать» at a 1.3× font; this one is as wide as its text.
 */
@Composable
private fun ActionItem(drawn: ActionLook, onClick: () -> Unit) {
    val colors = Csq.colors
    val ink = if (drawn.danger) colors.danger else colors.text
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(drawn.icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (drawn.danger) ink else colors.muted)
        Column {
            Text(stringResource(drawn.label), style = Type.body, color = ink, maxLines = 1)
            if (drawn.sub != null) {
                Text(stringResource(drawn.sub), style = Type.sub, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// «По пингу» comes with the profiles' own ping (B9): before it no profile has one to be ordered by
private val SortChoices = listOf(
    ProfileSort.Custom to R.string.home_sort_custom, ProfileSort.Name to R.string.home_sort_name,
    ProfileSort.Recent to R.string.home_sort_recent, ProfileSort.Added to R.string.home_sort_added,
)

/** The lists' order, beside their heading on «Главная» and in the sheet: one setting for both. */
@Composable
internal fun SortMenu(sort: ProfileSort, onPick: (ProfileSort) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        IconBtn(Icons.sort, stringResource(R.string.home_sort), { open = true }, small = true, tint = Csq.colors.muted)
        Menu(open, { open = false }) {
            for ((choice, label) in SortChoices) {
                DropdownMenuItem(
                    // TalkBack says which order is the chosen one: the check mark alone is a picture
                    modifier = Modifier.semantics { selected = choice == sort },
                    text = { Text(stringResource(label), style = Type.body) },
                    leadingIcon = {
                        if (choice == sort) Icon(Icons.check, contentDescription = null, modifier = Modifier.size(18.dp), tint = Csq.colors.muted)
                        else Spacer(Modifier.size(18.dp))
                    },
                    onClick = {
                        open = false
                        onPick(choice)
                    },
                )
            }
        }
    }
}

/** Where a profile connects, as «Главная» says it: its own server, a link's server, or its subscription. */
@Composable
internal fun profileLine(entry: ProfileEntry): String = when (entry.kind) {
    ProfileKind.OwnServer ->
        if (entry.peer.isEmpty()) stringResource(R.string.profile_own_server) else stringResource(R.string.profile_own_server_at, entry.peer)
    ProfileKind.Link -> linkHost(entry).let { host ->
        if (host.isEmpty()) stringResource(R.string.profile_link_missing) else stringResource(R.string.profile_link, host)
    }
    ProfileKind.Subscription ->
        if (entry.subscriptionTitle.isEmpty()) stringResource(R.string.profile_subscription)
        else stringResource(R.string.profile_subscription_named, entry.subscriptionTitle)
}

/**
 * The same in the sheet, where there is room for more: the own server with its port; a node under its subscription's
 * heading by its server and its streams, and among the hidden ones ([withSubscription]) by its subscription first.
 */
@Composable
internal fun profileSheetLine(entry: ProfileEntry, withSubscription: Boolean = false): String = when (entry.kind) {
    ProfileKind.OwnServer ->
        if (entry.peer.isEmpty()) stringResource(R.string.profile_own_server)
        else stringResource(R.string.profile_own_server_at, "${entry.peer}:${entry.port}")
    ProfileKind.Link -> profileLine(entry)
    ProfileKind.Subscription -> {
        val host = linkHost(entry)
        if (withSubscription) {
            if (host.isEmpty()) profileLine(entry) else stringResource(R.string.profile_line_two, profileLine(entry), host)
        } else {
            val streams = pluralText(R.plurals.profile_streams, entry.workers)
            if (host.isEmpty()) streams else stringResource(R.string.profile_line_two, host, streams)
        }
    }
}

@Composable
private fun linkHost(entry: ProfileEntry): String = remember(entry.link) { parseCsqttLink(entry.link)?.host.orEmpty() }
