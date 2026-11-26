package com.csqtt.client.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.ConnectionState
import com.csqtt.client.Disc
import com.csqtt.client.HomeNotice
import com.csqtt.client.ProfileEntry
import com.csqtt.client.ProfileKind
import com.csqtt.client.ProfileOrder
import com.csqtt.client.ProfileSort
import com.csqtt.client.R
import com.csqtt.client.Tap
import com.csqtt.client.TunnelManager
import com.csqtt.client.WifiCopy
import com.csqtt.client.formatBytes
import com.csqtt.client.pickProfile
import com.csqtt.client.showsTraffic
import com.csqtt.client.ui.components.Btn
import com.csqtt.client.ui.components.BtnKind
import com.csqtt.client.ui.components.Card
import com.csqtt.client.ui.components.Cell
import com.csqtt.client.ui.components.IconBtn
import com.csqtt.client.ui.components.TextBtn
import com.csqtt.client.ui.design.Csq
import com.csqtt.client.ui.design.Icons
import com.csqtt.client.ui.design.Shape
import com.csqtt.client.ui.design.Type
import com.csqtt.client.ui.legacy.rememberWifiNameAccess
import com.csqtt.client.ui.legacy.utils.parseCsqttLink
import com.csqtt.client.ui.shell.LocalNav
import com.csqtt.client.ui.shell.LocalOwners
import com.csqtt.client.ui.shell.LocalStore
import com.csqtt.client.ui.shell.Tab
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val Activity = Type.buttonSmall.copy(fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum")
private val Timer = Type.body.copy(fontFeatureSettings = "tnum")
private val Hint = Type.label.copy(fontWeight = FontWeight.Normal, lineHeight = 18.sp)

/**
 * «Список профилей», Home's other layout: the C in its disc, the status with what a start is doing under it, the timer,
 * the figures, what the app tells or asks as cards, and the profiles to pick from.
 */
@Composable
internal fun HomeList(view: HomeView) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // what is lost without notifications is said before anything else
            view.notices.filter { it is HomeNotice.NotificationsOff }.forEach { ListNotice(it, view) }
            StateBlock(view)
            view.notices.filterNot { it is HomeNotice.NotificationsOff }.forEach { ListNotice(it, view) }
            Profiles()
        }
    }
}

@Composable
private fun StateBlock(view: HomeView) {
    val colors = Csq.colors
    val state = view.state
    val streams = rememberStreams(state)
    val pausedOn = view.notices.firstNotNullOfOrNull { it as? HomeNotice.PausedOn }
    Column(
        Modifier.fillMaxWidth().padding(top = if (view.notices.any { it is HomeNotice.NotificationsOff }) 6.dp else 18.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CDisc(view.visuals.disc, tapDescription(view), enabled = view.visuals.tap != Tap.Nothing, onClick = view.actions.tap)
        Text(
            stateLabel(view), style = Type.status, color = colors.text, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        // the stage of a start and the streams, one line, there only while it has something to say
        val activity = activityLine(view.stage?.let { stringResource(it.text) }, streams)
        AnimatedVisibility(visible = activity != null) {
            Text(activity ?: AnnotatedString(""), style = Activity, color = colors.muted, textAlign = TextAlign.Center)
        }
        when {
            pausedOn != null -> Text(stringResource(R.string.home_wifi_network, pausedOn.network), style = Timer, color = colors.muted)
            state !is ConnectionState.CaptchaNeeded -> Uptime(Timer, colors.muted)
        }
        view.visuals.hint?.let {
            Text(stringResource(it), style = Hint, color = colors.muted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 300.dp))
        }
        if (showsTraffic(state)) Figures()
        if (state is ConnectionState.CaptchaNeeded) {
            Btn(
                stringResource(R.string.home_captcha_solve), view.actions.openCaptcha, kind = BtnKind.Primary, icon = Icons.shield,
                modifier = Modifier.padding(top = 10.dp).widthIn(min = 190.dp),
            )
        }
    }
}

/** «Соединение с сервером… · потоки 3 / 10», «Потоки: 19 / 20», the stage alone, or nothing. */
@Composable
private fun activityLine(stage: String?, streams: Pair<Int, Int>?): AnnotatedString? {
    if (stage == null && streams == null) return null
    val strong = SpanStyle(fontWeight = FontWeight.SemiBold, color = Csq.colors.text)
    val figure = streams?.let { (up, running) -> stringResource(R.string.home_streams_figure, up, running) }
    val word = stringResource(if (stage != null) R.string.home_streams_in_stage else R.string.home_streams_label)
    return buildAnnotatedString {
        if (stage != null) append(stage)
        if (figure != null) {
            if (stage != null) append(" · ")
            append(word)
            append(' ')
            withStyle(strong) { append(figure) }
        }
    }
}

/** The speeds and the total, read only while shown; in a layer of its own: it changes on every report while bytes move. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Figures() {
    val traffic by TunnelManager.traffic.collectAsStateWithLifecycle()
    val now = traffic ?: return
    val strong = SpanStyle(fontWeight = FontWeight.SemiBold, color = Csq.colors.text)
    fun part(label: String, value: String) = buildAnnotatedString {
        append(label)
        append(' ')
        withStyle(strong) { append(value) }
    }
    val parts = buildList {
        if (now.hasSpeed) {
            add(part("↓", stringResource(R.string.home_rate, formatBytes(now.downBytesPerSecond))))
            add(part("↑", stringResource(R.string.home_rate, formatBytes(now.upBytesPerSecond))))
        }
        add(part(stringResource(R.string.home_total), formatBytes(now.totalDown + now.totalUp)))
    }
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 6.dp).graphicsLayer(),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
    ) {
        for (text in parts) Text(text, style = Activity, color = Csq.colors.muted)
    }
}

/** The C of the list, 168 dp: its ground and its ring say the state with its colour, the status under it in a word. */
@Composable
private fun CDisc(disc: Disc, description: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = Csq.colors
    val ground = when (disc) {
        Disc.On -> colors.accent
        Disc.Busy -> colors.accentSoft
        Disc.Warn -> colors.warnSoft
        Disc.Off, Disc.Dim -> colors.raised
    }
    val ring = when (disc) {
        Disc.On -> null
        Disc.Busy -> 2.dp to colors.accent
        Disc.Warn -> 2.dp to colors.warn
        Disc.Off, Disc.Dim -> 1.dp to colors.line
    }
    Box(
        Modifier
            .size(168.dp)
            .then(if (disc == Disc.On) Modifier.shadow(14.dp, CircleShape, ambientColor = colors.accent, spotColor = colors.accent) else Modifier)
            .clip(CircleShape)
            .background(ground)
            .then(if (ring != null) Modifier.border(ring.first, ring.second, CircleShape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        when (disc) {
            Disc.On -> Icon(Icons.c, contentDescription = null, modifier = Modifier.size(118.dp), tint = Color.White)
            Disc.Dim -> Icon(Icons.c, contentDescription = null, modifier = Modifier.size(118.dp), tint = colors.faint)
            // the letter in the logo's own gradient
            Disc.Off, Disc.Busy, Disc.Warn -> Icon(
                Icons.c, contentDescription = null, tint = Color.White,
                modifier = Modifier.size(118.dp).graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithCache {
                    val brush = Brush.linearGradient(
                        listOf(Color(0xFF4FA3FF), Color(0xFF0474FB)),
                        start = Offset(size.width * 28f / 108f, size.height * 36f / 108f),
                        end = Offset(size.width * 78f / 108f, size.height * 72f / 108f),
                    )
                    onDrawWithContent {
                        drawContent()
                        drawRect(brush, blendMode = BlendMode.SrcIn)
                    }
                },
            )
        }
    }
}

/** One notice as the list draws it. Every kind has its branch: a kind added later does not compile until it is drawn. */
@Composable
private fun ListNotice(notice: HomeNotice, view: HomeView) {
    val colors = Csq.colors
    val actions = view.actions
    when (notice) {
        HomeNotice.NotificationsOff -> Row(
            Modifier.fillMaxWidth().clip(Shape.card).background(colors.warnSoft).padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.bellOff, contentDescription = null, modifier = Modifier.size(22.dp), tint = colors.warn)
            Text(stringResource(R.string.home_notifications_off), style = Type.fact, color = colors.text, modifier = Modifier.weight(1f))
            Btn(stringResource(R.string.home_notifications_enable), actions.notificationSettings, small = true)
        }
        is HomeNotice.Stopped -> NoticeCard(Icons.alert, colors.danger, notice.cause, stringResource(R.string.home_stopped_itself), edge = colors.danger) {
            Btn(stringResource(R.string.home_stopped_logs), actions.openLogs, small = true)
            if (notice.vkSignIn) Btn(stringResource(R.string.home_stopped_vk), actions.vkSignIn, kind = BtnKind.Primary, small = true)
            if (notice.appSettings) Btn(stringResource(R.string.home_app_settings), actions.appSettings, kind = BtnKind.Primary, small = true)
        }
        is HomeNotice.WifiOffer -> NoticeCard(Icons.wifi, colors.accentText, WifiCopy.offer(notice.network), notice.rule.rememberEffect, ground = colors.accentSoft) {
            TextBtn(stringResource(R.string.home_wifi_later), { actions.wifiLater(notice.network) })
            Btn(stringResource(R.string.home_wifi_remember), { actions.wifiRemember(notice.network, notice.rule) }, kind = BtnKind.Primary, small = true)
        }
        HomeNotice.WifiNameUnknown -> {
            // a run started in the background needs nothing from the user: the app's return re-reads the name
            val access = rememberWifiNameAccess()
            NoticeCard(Icons.wifi, colors.warn, WifiCopy.NAME_UNKNOWN, null, ground = colors.warnSoft) {
                access.fixLabel?.let { Btn(it, access.fix, kind = BtnKind.Primary, small = true) }
            }
        }
        HomeNotice.NeedLink -> NoticeCard(
            Icons.info, colors.warn, stringResource(R.string.home_need_link_title), stringResource(R.string.home_need_link_text),
            ground = colors.warnSoft,
        ) {
            Btn(stringResource(R.string.home_open_tunnel), actions.openTunnel, kind = BtnKind.Primary, small = true)
        }
        is HomeNotice.Outcome -> {
            val tone = when {
                notice.bad -> colors.danger
                notice.warn -> colors.warn
                else -> colors.line
            }
            Row(
                Modifier.fillMaxWidth().clip(Shape.card).background(colors.surface).border(1.dp, tone, Shape.card)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (notice.bad || notice.warn) Icons.alert else Icons.check, contentDescription = null, modifier = Modifier.size(22.dp),
                    tint = if (notice.bad || notice.warn) tone else colors.ok)
                Text(notice.text, style = Type.fact, color = colors.text, modifier = Modifier.weight(1f))
            }
        }
        // drawn in the state's own block: the button under the status, the network in the timer's place
        HomeNotice.Captcha, is HomeNotice.PausedOn -> Unit
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoticeCard(
    icon: ImageVector,
    tint: Color,
    title: String,
    text: String?,
    ground: Color = Csq.colors.surface,
    edge: Color = Color.Transparent,
    actions: @Composable () -> Unit,
) {
    val colors = Csq.colors
    Column(
        Modifier.fillMaxWidth().clip(Shape.card).background(ground).border(1.dp, edge, Shape.card).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = tint)
            Column(Modifier.weight(1f)) {
                Text(title, style = Type.bodyStrong, color = colors.text)
                if (text != null) Text(text, style = Type.sub, color = colors.text)
            }
        }
        // the buttons end the card on the right, and go under one another where they do not fit side by side
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { actions() }
    }
}

/** «Профили»: how they are ordered, and the profiles in that order; a tap picks one, a swipe left shows its actions. */
@Composable
private fun Profiles() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val messages = LocalOwners.current.messages
    val entries by store.profileEntries.collectAsStateWithLifecycle(initialValue = emptyList())
    val sort by store.profileSort.collectAsStateWithLifecycle(initialValue = ProfileSort.Custom)
    val order by store.profileOrder.collectAsStateWithLifecycle(initialValue = emptyList())
    val active by store.activeProfile.collectAsStateWithLifecycle(initialValue = -1)
    val rows = remember(entries, sort, order) { ProfileOrder.shown(entries, sort, order) }
    if (rows.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.padding(start = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.home_profiles), style = Type.section, color = Csq.colors.muted)
            SortMenu(sort) { picked -> scope.launch { store.saveProfileSort(picked) } }
        }
        Card(flush = true) {
            rows.forEachIndexed { index, entry ->
                ProfileRow(
                    entry = entry,
                    selected = entry.index == active,
                    divided = index > 0,
                    // on the app's scope: the reconnect a pick asks for goes out whatever becomes of this screen
                    onPick = { TunnelManager.scope.launch(Dispatchers.Main) { pickProfile(context, store, entry.index, active) } },
                    onPin = { scope.launch { store.setProfilePinned(entry.index, !entry.pinned) } },
                    onOpenTunnel = {
                        // the run stays where it is, and says so: opening a profile's settings is not a pick
                        val runOn = TunnelManager.connectionState.value.powerIsOn
                        val runProfile = TunnelManager.runTargetProfile()
                        if (runOn && runProfile != entry.index) {
                            rows.firstOrNull { it.index == runProfile }?.let { messages.show(context.getString(R.string.home_run_stays, it.name)) }
                        }
                        TunnelManager.scope.launch(Dispatchers.Main) {
                            if (entry.index != active) store.saveActiveProfile(entry.index)
                            nav.select(Tab.Tunnel)
                        }
                    },
                )
            }
        }
    }
}

// «По пингу» comes with the profiles' own ping (B9): before it no profile has one to be ordered by
private val SortChoices = listOf(
    ProfileSort.Custom to R.string.home_sort_custom, ProfileSort.Name to R.string.home_sort_name,
    ProfileSort.Recent to R.string.home_sort_recent, ProfileSort.Added to R.string.home_sort_added,
)

@Composable
private fun SortMenu(sort: ProfileSort, onPick: (ProfileSort) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        IconBtn(Icons.sort, stringResource(R.string.home_sort), { open = true }, small = true, tint = Csq.colors.muted)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
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

private val ActionWidth = 44.dp

/**
 * A profile's row. A swipe left slides it and shows its actions in its place (the operator, 2026-11-25); TalkBack has
 * them as the row's own actions.
 */
@Composable
private fun ProfileRow(
    entry: ProfileEntry,
    selected: Boolean,
    divided: Boolean,
    onPick: () -> Unit,
    onPin: () -> Unit,
    onOpenTunnel: () -> Unit,
) {
    val colors = Csq.colors
    val scope = rememberCoroutineScope()
    val open = with(LocalDensity.current) { -(ActionWidth * 2).toPx() }
    val slide = remember { Animatable(0f) }
    val close = { scope.launch { slide.animateTo(0f) } }
    val pinLabel = stringResource(if (entry.pinned) R.string.profile_unpin else R.string.profile_pin)
    val tunnelLabel = stringResource(R.string.profile_open_tunnel)
    val pinnedLabel = stringResource(R.string.profile_pinned)

    Box(
        Modifier.fillMaxWidth().draggable(
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
            sub = profileLine(entry),
            selected = selected,
            divided = divided,
            onClick = { if (slide.value < 0f) close() else onPick() },
            modifier = Modifier.semantics {
                customActions = listOf(
                    CustomAccessibilityAction(pinLabel) { onPin(); true },
                    CustomAccessibilityAction(tunnelLabel) { onOpenTunnel(); true },
                )
            },
            trailing = {
                if (entry.pinned) {
                    Icon(Icons.pin, contentDescription = pinnedLabel, modifier = Modifier.size(16.dp), tint = colors.accentText)
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
                    IconBtn(Icons.pin, pinLabel, { close(); onPin() })
                    IconBtn(Icons.key, tunnelLabel, { close(); onOpenTunnel() })
                }
            }
        }
    }
}

/** Where a profile connects: its own server, a link's server, or a subscription. */
@Composable
private fun profileLine(entry: ProfileEntry): String = when (entry.kind) {
    ProfileKind.OwnServer ->
        if (entry.peer.isEmpty()) stringResource(R.string.profile_own_server) else stringResource(R.string.profile_own_server_at, entry.peer)
    ProfileKind.Link -> {
        val host = remember(entry.link) { parseCsqttLink(entry.link)?.host }
        if (host.isNullOrEmpty()) stringResource(R.string.profile_link_missing) else stringResource(R.string.profile_link, host)
    }
    ProfileKind.Subscription ->
        if (entry.subscriptionTitle.isEmpty()) stringResource(R.string.profile_subscription)
        else stringResource(R.string.profile_subscription_named, entry.subscriptionTitle)
}
