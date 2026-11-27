package com.csqtt.client.ui.home

import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.ConnectionState
import com.csqtt.client.Disc
import com.csqtt.client.HomeNotice
import com.csqtt.client.ProfileKind
import com.csqtt.client.ProfileOrder
import com.csqtt.client.ProfileSort
import com.csqtt.client.R
import com.csqtt.client.StartStage
import com.csqtt.client.Tap
import com.csqtt.client.TrafficSnapshot
import com.csqtt.client.TunnelManager
import com.csqtt.client.WifiCopy
import com.csqtt.client.formatBytes
import com.csqtt.client.ui.components.Btn
import com.csqtt.client.ui.components.BtnKind
import com.csqtt.client.ui.components.Card
import com.csqtt.client.ui.components.TextBtn
import com.csqtt.client.ui.design.Csq
import com.csqtt.client.ui.design.Icons
import com.csqtt.client.ui.design.Shape
import com.csqtt.client.ui.design.Type
import com.csqtt.client.ui.legacy.rememberWifiNameAccess
import com.csqtt.client.ui.profiles.ProfileRow
import com.csqtt.client.ui.profiles.SortMenu
import com.csqtt.client.ui.profiles.profileActs
import com.csqtt.client.ui.profiles.profileLine
import com.csqtt.client.ui.shell.LocalNav
import com.csqtt.client.ui.shell.LocalStore
import com.csqtt.client.ui.shell.Routes
import kotlinx.coroutines.launch

// The list's own distances; its text sizes are Home's (HomeText). More room above the C than under the last line: what
// is tapped and read sits low in its place, nearer the thumb (the operator, 2026-11-27: «The content of that area must
// always be closer to the bottom than to the top»).
private val AboveDisc = 30.dp
private val UnderDisc = 14.dp
private val LineGap = 6.dp
private val BesideCards = 12.dp

/**
 * «Список профилей», Home's other layout: the C in its disc, the status, the timer, the line of the moment under them,
 * what the app tells or asks as cards, and the profiles to pick from. Nothing has room kept for it: what comes and goes
 * opens and closes its own (HomeMotion), and the profiles glide with it.
 */
@Composable
internal fun HomeList(view: HomeView) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            // what is lost without notifications is said before anything else
            val (first, rest) = view.notices.partition { it is HomeNotice.NotificationsOff }
            Notices(first, view, above = true)
            StateBlock(view, top = if (first.isEmpty()) AboveDisc else 6.dp)
            // the captcha's button and the paused Wi-Fi are drawn in the state's own block
            Notices(rest.filterNot { it is HomeNotice.Captcha || it is HomeNotice.PausedOn }, view)
            Profiles()
        }
    }
}

/** The cards of one place, with their distance from what is beside them; their room opens and closes as Home moves. */
@Composable
private fun Notices(notices: List<HomeNotice>, view: HomeView, above: Boolean = false) {
    Column(Modifier.fillMaxWidth().animateContentSize(HomeMotion.move())) {
        for (notice in notices) {
            Box(Modifier.padding(top = if (above) 0.dp else BesideCards, bottom = if (above) BesideCards else 0.dp)) { ListNotice(notice, view) }
        }
    }
}

/** What the line under the time says: one of these at a time, each fading into the other's place. */
private sealed interface Moment {
    val kind: Any

    data object Speeds : Moment {
        override val kind: Any get() = Speeds
    }

    /** What a start is doing: the next stage's words take the last one's place as they are. */
    data class Stage(val stage: StartStage) : Moment {
        override val kind: Any get() = Stage::class
    }

    data class Hint(@StringRes val text: Int) : Moment {
        override val kind: Any get() = text
    }
}

@Composable
private fun StateBlock(view: HomeView, top: Dp) {
    val colors = Csq.colors
    val state = view.state
    val streams = rememberStreams(state)
    val pausedOn = view.notices.firstNotNullOfOrNull { it as? HomeNotice.PausedOn }
    val captcha = state is ConnectionState.CaptchaNeeded
    Column(Modifier.fillMaxWidth().padding(top = top), horizontalAlignment = Alignment.CenterHorizontally) {
        CDisc(view.visuals.disc, tapDescription(view), enabled = view.visuals.tap != Tap.Nothing, onClick = view.actions.tap)
        Text(
            stateLabel(view), style = HomeText.Status, color = colors.text, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = UnderDisc).semantics { liveRegion = LiveRegionMode.Polite },
        )
        // The time, right under the state wherever there is one, so it never changes rows; the Wi-Fi a pause is on takes
        // its place, and a captcha's question has neither.
        HomeLine(if (captcha) null else pausedOn ?: Unit, kind = { it::class }) { now ->
            if (now is HomeNotice.PausedOn) {
                Text(
                    stringResource(R.string.home_wifi_network, now.network), style = HomeText.Timer, color = colors.muted,
                    modifier = Modifier.padding(top = LineGap),
                )
            } else {
                Uptime(HomeText.Timer, colors.muted, Modifier.padding(top = LineGap))
            }
        }
        // The line of the moment, one place for the three: the speeds while connected, else what a start is doing, else
        // what a tap does. One takes the other's place, so nothing under it moves for that; and through a stop the place
        // waits for the hint that follows.
        val stage = view.stage
        val traffic = rememberShowsTraffic(state)
        val moment: Moment? = when {
            traffic -> Moment.Speeds
            stage != null -> Moment.Stage(stage)
            else -> view.visuals.hint?.let { Moment.Hint(it) }
        }
        HomeLine(moment, kind = { it.kind }, lingers = true) { now ->
            when (now) {
                Moment.Speeds -> Figures(Modifier.padding(top = LineGap))
                is Moment.Stage -> Text(
                    stringResource(now.stage.text), style = HomeText.Line, color = colors.muted, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = LineGap),
                )
                is Moment.Hint -> Text(
                    stringResource(now.text), style = HomeText.Line, color = colors.muted, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = LineGap).widthIn(max = 300.dp),
                )
            }
        }
        // The streams on a line of their own (the operator, 2026-11-27: «Move "Потоки" to next line, use capital "П"»),
        // while their count is news: the room is theirs for that time only.
        HomeLine(streams) { (up, running) ->
            Text(streamsLine(up, running), style = HomeText.Line, color = colors.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = LineGap))
        }
        HomeLine(Unit.takeIf { captcha }) {
            Btn(
                stringResource(R.string.home_captcha_solve), view.actions.openCaptcha, kind = BtnKind.Primary, icon = Icons.shield,
                modifier = Modifier.padding(top = 16.dp).widthIn(min = 190.dp),
            )
        }
    }
}

/** «Потоки: 19 / 20», the count in the text's own colour. */
@Composable
private fun streamsLine(up: Int, running: Int): AnnotatedString {
    val strong = SpanStyle(fontWeight = FontWeight.SemiBold, color = Csq.colors.text)
    val label = stringResource(R.string.home_streams_label)
    val figure = stringResource(R.string.home_streams_figure, up, running)
    return buildAnnotatedString {
        append(label)
        append(' ')
        withStyle(strong) { append(figure) }
    }
}

/** The speeds and the total, read only while shown; in a layer of its own: it changes on every report while bytes move. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Figures(modifier: Modifier = Modifier) {
    val traffic by TunnelManager.traffic.collectAsStateWithLifecycle()
    // the last report stays while the line fades out of a run that ended
    val last = remember { Ref<TrafficSnapshot>() }
    traffic?.let { last.value = it }
    val now = last.value ?: return
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
    // Each part in a line's own height: an arrow reaches a pixel past its line, and Compose would make the row that much
    // higher than the line it takes the place of — the list under it would move by it.
    val line = with(LocalDensity.current) { HomeText.Line.lineHeight.toDp() }
    FlowRow(
        modifier.fillMaxWidth().graphicsLayer(),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
    ) {
        for (text in parts) Text(text, style = HomeText.Line, color = Csq.colors.muted, modifier = Modifier.requiredHeight(line))
    }
}

/** The letter of the disc: white on the accent, faint while there is nothing to tap, else in the logo's own gradient. */
private enum class Letter { White, Faint, Logo }

/**
 * The C of the list, 168 dp: its ground and its ring say the state with its colour, the status under it in a word. The
 * colours of a state turn into the next one's (HomeMotion): the disc is the largest thing that changes on this screen.
 */
@Composable
private fun CDisc(disc: Disc, description: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = Csq.colors
    val ground by animateColorAsState(
        when (disc) {
            Disc.On -> colors.accent
            Disc.Busy -> colors.accentSoft
            Disc.Warn -> colors.warnSoft
            Disc.Off, Disc.Dim -> colors.raised
        },
        HomeMotion.move(), label = "disc-ground",
    )
    // «on» has no ring: the accent's own is not seen on the accent ground
    val ring by animateColorAsState(
        when (disc) {
            Disc.On, Disc.Busy -> colors.accent
            Disc.Warn -> colors.warn
            Disc.Off, Disc.Dim -> colors.line
        },
        HomeMotion.move(), label = "disc-ring",
    )
    val ringWidth by animateDpAsState(if (disc == Disc.Off || disc == Disc.Dim) 1.dp else 2.dp, HomeMotion.move(), label = "disc-ring-width")
    val glow by animateDpAsState(if (disc == Disc.On) 14.dp else 0.dp, HomeMotion.move(), label = "disc-glow")
    Box(
        Modifier
            .size(168.dp)
            .shadow(glow, CircleShape, ambientColor = colors.accent, spotColor = colors.accent)
            .clip(CircleShape)
            .background(ground)
            .border(ringWidth, ring, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        val letter = when (disc) {
            Disc.On -> Letter.White
            Disc.Dim -> Letter.Faint
            Disc.Off, Disc.Busy, Disc.Warn -> Letter.Logo
        }
        Crossfade(letter, animationSpec = HomeMotion.move(), label = "disc-letter") { shown ->
            when (shown) {
                Letter.White -> Icon(Icons.c, contentDescription = null, modifier = Modifier.size(118.dp), tint = Color.White)
                Letter.Faint -> Icon(Icons.c, contentDescription = null, modifier = Modifier.size(118.dp), tint = colors.faint)
                Letter.Logo -> Icon(
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
            Btn(stringResource(R.string.home_add_link), actions.addProfile, small = true)
            Btn(stringResource(R.string.home_open_tunnel), actions.openTunnel, kind = BtnKind.Primary, small = true)
        }
        is HomeNotice.Outcome -> {
            val tone = when {
                notice.bad -> colors.danger
                notice.warn -> colors.warn
                else -> colors.line
            }
            val certOf = notice.certOf
            Row(
                Modifier.fillMaxWidth().clip(Shape.card).background(colors.surface).border(1.dp, tone, Shape.card)
                    // the certificate changed: the card opens the question about it (the canvas, F-HomeWifi)
                    .then(if (certOf != null) Modifier.clickable(role = Role.Button) { actions.reviewCertificate(certOf) } else Modifier)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (notice.bad || notice.warn) Icons.alert else Icons.check, contentDescription = null, modifier = Modifier.size(22.dp),
                    tint = if (notice.bad || notice.warn) tone else colors.ok)
                Text(notice.text, style = Type.fact, color = colors.text, modifier = Modifier.weight(1f))
                if (certOf != null) Icon(Icons.chevronRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.faint)
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

/**
 * «Профили»: how they are ordered, the first of them in that order, and the way to all of them. A tap picks one, a swipe
 * left shows its actions; «+» adds one.
 */
@Composable
private fun Profiles() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val colors = Csq.colors
    val entries by store.profileEntries.collectAsStateWithLifecycle(initialValue = emptyList())
    val sort by store.profileSort.collectAsStateWithLifecycle(initialValue = ProfileSort.Custom)
    val order by store.profileOrder.collectAsStateWithLifecycle(initialValue = emptyList())
    val active by store.activeProfile.collectAsStateWithLifecycle(initialValue = -1)
    val shown = remember(entries, sort, order) { ProfileOrder.shown(entries, sort, order) }
    val rows = remember(shown, active) { ProfileOrder.homeRows(shown, active) }
    if (rows.isEmpty()) return
    val acts = profileActs(entries, active)
    val hidden = entries.count { it.hidden }

    Column(Modifier.padding(top = BesideCards), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.home_profiles), style = Type.section, color = colors.muted)
            SortMenu(sort) { picked -> TunnelManager.scope.launch { store.saveProfileSort(picked) } }
            Spacer(Modifier.weight(1f))
            // the one filled control of the section (the operator, 2026-11-25): it adds a profile
            val add = stringResource(R.string.profiles_add)
            Box(
                Modifier.minimumInteractiveComponentSize().size(36.dp).clip(CircleShape).background(colors.accent)
                    .clickable(role = Role.Button) { nav.open(Routes.addProfile()) }
                    .semantics { contentDescription = add },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.plus, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.onAccent)
            }
        }
        Card(flush = true) {
            rows.forEachIndexed { index, entry ->
                key(entry.index) {
                    ProfileRow(entry = entry, line = profileLine(entry), selected = entry.index == active, divided = index > 0, acts = acts)
                }
            }
            // every profile and subscription is in the sheet. With one profile it would hold nothing more than this row,
            // unless that one is a subscription's node: its subscription is refreshed, edited and deleted there
            if (entries.size > 1 || entries.any { it.kind == ProfileKind.Subscription }) {
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                        .drawBehind { drawLine(colors.lineSoft, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                        .clickable(role = Role.Button) { nav.open(Routes.profiles()) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.home_all_profiles, shown.size), style = Type.button, color = colors.accentText,
                        modifier = Modifier.weight(1f),
                    )
                    if (hidden > 0) Text(stringResource(R.string.profiles_hidden_count, hidden), style = Type.sub, color = colors.muted)
                    Icon(Icons.chevronRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.faint)
                }
            }
        }
    }
}
