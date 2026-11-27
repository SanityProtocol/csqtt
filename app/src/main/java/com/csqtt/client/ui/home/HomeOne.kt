package com.csqtt.client.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.ConnectionState
import com.csqtt.client.HomeNotice
import com.csqtt.client.HomePick
import com.csqtt.client.R
import com.csqtt.client.StartStage
import com.csqtt.client.StatusTone
import com.csqtt.client.Tap
import com.csqtt.client.TunnelManager
import com.csqtt.client.TunnelProbe
import com.csqtt.client.WifiCopy
import com.csqtt.client.formatTraffic
import com.csqtt.client.homePick
import com.csqtt.client.probeLine
import com.csqtt.client.ui.components.Btn
import com.csqtt.client.ui.components.BtnKind
import com.csqtt.client.ui.components.IconBtn
import com.csqtt.client.ui.components.TextBtn
import com.csqtt.client.ui.design.Csq
import com.csqtt.client.ui.design.Icons
import com.csqtt.client.ui.design.Type
import com.csqtt.client.ui.legacy.rememberWifiNameAccess
import com.csqtt.client.ui.shell.LocalNav
import com.csqtt.client.ui.shell.LocalStore
import com.csqtt.client.ui.shell.Routes

// Home's sizes (HomeText), which are this card's own
private val Status = HomeText.Status
private val Timer = HomeText.Timer
private val Line = HomeText.Line
private val CardShape = RoundedCornerShape(34.dp)

// Between the lines of the card. Under the logo there is none: the picture's own margin is the room between it and the
// status — a bit smaller than it was, and still more than between the lines (the operator, 2026-11-27).
private val LineGap = 10.dp

/**
 * The card's height at rest — the logo, the status and the time, at the default text size — with the room above and
 * under it. The card is placed where that is centred; what it says more opens downward ([HomeOne]).
 */
private val Rest = 292.dp

/**
 * And its height with all a connection says: the speeds, the streams while they are news, the ping. On a screen too low
 * to hold that under the centred place, the card at rest sits that much higher, so that a connection still does not
 * move the button.
 */
private val Full = 386.dp

/**
 * «Одно подключение», Home's default layout (the operator, 2026-11-26): the screen the app had, as it was — the logo
 * button in its card, centred between the header and the bar, the status in its state's colour, the timer, the traffic,
 * the streams while they are news, the ping through the tunnel. What it tells or asks is said in the card, as lines.
 *
 * How it moves (the operator, 2026-11-27, HomeMotion): no room is kept for a line that is not said. The card is placed
 * where it is centred at rest and stays there, so the button is under the finger through a connection; a line opens its
 * room downward and closes it, and the card's lower edge glides with it. The logo and the colours of the state turn
 * into the next one's.
 */
@Composable
internal fun HomeOne(view: HomeView) {
    val colors = Csq.colors
    val state = view.state
    val on = state.powerIsOn || state is ConnectionState.Stopping
    val canTap = view.visuals.tap != Tap.Nothing
    val description = tapDescription(view)
    val streams = rememberStreams(state)

    Box(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentAlignment = Alignment.TopCenter) {
      Box(
          Modifier
              .widthIn(max = 720.dp)
              // where the card at rest is centred — higher on a screen too low for a connected card under that place; a
              // card with still more to say goes up as far as it must, and then scrolls
              .layout { measurable, constraints ->
                  val card = measurable.measure(constraints.copy(minHeight = 0))
                  if (!constraints.hasBoundedHeight) return@layout layout(card.width, card.height) { card.place(0, 0) }
                  val room = constraints.maxHeight
                  val top = ((room - Rest.roundToPx()) / 2).coerceAtMost(room - maxOf(card.height, Full.roundToPx())).coerceAtLeast(0)
                  layout(card.width, room) { card.place(0, top) }
              }
              .verticalScroll(rememberScrollState())
              .padding(top = 10.dp, bottom = 26.dp),
      ) {
        Column(
            modifier = Modifier
                .shadow(if (colors.isDark) 4.dp else 10.dp, CardShape)
                .clip(CardShape)
                .background(colors.surface)
                .border(1.dp, colors.lineSoft, CardShape)
                .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 22.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Logo(on, canTap, description, view.moves, view.actions.tap)
            StatusWord(stateLabel(view), view.visuals.status, view.moves)
            // every kind is drawn here: the list does the same in its own way, and neither can leave one out
            Notes(view.moves) {
                for (notice in view.notices) {
                    // these two close the card, after the figures: here they take no room
                    if (notice !is HomeNotice.NotificationsOff && notice !is HomeNotice.Outcome) {
                        Column(
                            Modifier.padding(top = LineGap),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(LineGap),
                        ) {
                            when (notice) {
                                is HomeNotice.PausedOn -> NoteLine(stringResource(R.string.home_wifi_network, notice.network), colors.muted)
                                HomeNotice.WifiNameUnknown -> {
                                    NoteLine(WifiCopy.NAME_UNKNOWN, colors.warn)
                                    // a run started in the background needs nothing from the user: the app's return re-reads the name
                                    val access = rememberWifiNameAccess()
                                    access.fixLabel?.let { TextBtn(it, access.fix) }
                                }
                                is HomeNotice.WifiOffer -> {
                                    Text(WifiCopy.offer(notice.network), style = Type.bodyStrong, color = colors.text, textAlign = TextAlign.Center)
                                    NoteLine(notice.rule.rememberEffect, colors.muted)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextBtn(stringResource(R.string.home_wifi_later), { view.actions.wifiLater(notice.network) })
                                        TextBtn(stringResource(R.string.home_wifi_remember), { view.actions.wifiRemember(notice.network, notice.rule) })
                                    }
                                }
                                is HomeNotice.Stopped -> {
                                    NoteLine(notice.cause, colors.danger)
                                    if (notice.vkSignIn) TextBtn(stringResource(R.string.home_stopped_vk), view.actions.vkSignIn)
                                    // the cause asks for a setting, so it carries the way there
                                    if (notice.appSettings) TextBtn(stringResource(R.string.home_app_settings), view.actions.appSettings)
                                }
                                HomeNotice.Captcha -> Btn(
                                    stringResource(R.string.home_captcha_solve), view.actions.openCaptcha, kind = BtnKind.Primary, small = true,
                                )
                                HomeNotice.NeedLink -> {
                                    NoteLine(stringResource(R.string.home_need_link_title), colors.warn)
                                    TextBtn(stringResource(R.string.home_add_link), view.actions.addProfile)
                                    TextBtn(stringResource(R.string.home_open_tunnel), view.actions.openTunnel)
                                }
                                HomeNotice.NotificationsOff, is HomeNotice.Outcome -> Unit
                            }
                        }
                    }
                }
            }

            Clock(on, view.moves)
            // Under the time, one place for what a start is doing and for the speeds that follow it, then the streams
            // while their count is news, then the ping while connected: the state and the time above them never move.
            val traffic = rememberShowsTraffic(state)
            HomeLine(if (traffic) Unit else view.stage, kind = { it::class }, moves = view.moves) { now ->
                if (now is StartStage) {
                    Text(
                        stringResource(now.text), style = Line, color = colors.muted, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = LineGap),
                    )
                } else {
                    Traffic(Modifier.padding(top = LineGap))
                }
            }
            HomeLine(streams, moves = view.moves) { (up, running) ->
                NoteLine(stringResource(R.string.home_streams, up, running), colors.muted, Modifier.padding(top = LineGap))
            }
            HomeLine(Unit.takeIf { state is ConnectionState.Connected }, moves = view.moves) { ProbeLine(Modifier.padding(top = LineGap)) }

            Notes(view.moves) {
                for (notice in view.notices) {
                    when (notice) {
                        HomeNotice.NotificationsOff -> Text(
                            stringResource(R.string.home_notifications_off), style = Line, color = colors.danger,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = LineGap).clickable(role = Role.Button, onClick = view.actions.notificationSettings),
                        )
                        // a failure in the error colour; skipped nodes of a refresh that worked as a warning
                        is HomeNotice.Outcome -> {
                            NoteLine(
                                notice.text,
                                when {
                                    notice.bad -> colors.danger
                                    notice.warn -> colors.warn
                                    else -> colors.muted
                                },
                                Modifier.padding(top = LineGap),
                            )
                            // the certificate changed: the question about it is one tap from where it is said
                            notice.certOf?.let { url ->
                                TextBtn(stringResource(R.string.sub_cert_review), { view.actions.reviewCertificate(url) }, Modifier.padding(top = LineGap))
                            }
                        }
                        is HomeNotice.PausedOn, HomeNotice.WifiNameUnknown, is HomeNotice.WifiOffer, is HomeNotice.Stopped,
                        HomeNotice.Captcha, HomeNotice.NeedLink -> Unit
                    }
                }
            }
        }
        // in the card's corner and beside the clipped card, not inside it; 8 dp from its edges, as on the canvas
        CornerPick(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp))
      }
    }
}

/**
 * The logo button. Its colour, its size and its strength turn with the state here, in a piece of their own: a frame of
 * that composes this picture again and nothing of the card around it (the operator, 2026-11-27: smooth and fast on the
 * worst phones). The size and the strength are read where they are drawn.
 */
@Composable
private fun Logo(on: Boolean, canTap: Boolean, description: String, moves: Boolean, onClick: () -> Unit) {
    val lit = turningFloat(if (on) 1f else 0f, moves, "logo-lit")
    val strength = turningFloat(
        when {
            on -> 1f
            canTap -> 0.66f
            else -> 0.34f
        },
        moves, "logo-alpha",
    )
    val colour = lit.value
    Image(
        painter = painterResource(R.drawable.ic_c_logo),
        contentDescription = null,
        modifier = Modifier
            .size(172.dp)
            .clip(CircleShape)
            .clickable(enabled = canTap, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .graphicsLayer {
                val size = lerp(0.96f, 1.06f, lit.value)
                scaleX = size
                scaleY = size
                alpha = strength.value
            },
        colorFilter = if (colour == 1f) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(colour) }),
    )
}

/** The status in its state's colour, which turns into the next one's in this piece alone. */
@Composable
private fun StatusWord(word: String, tone: StatusTone, moves: Boolean) {
    val colors = Csq.colors
    val color by turningColor(
        when (tone) {
            StatusTone.Accent -> colors.accentText
            StatusTone.Warn -> colors.warn
            StatusTone.Danger -> colors.danger
            StatusTone.Muted -> colors.muted
        },
        moves, "status",
    )
    Text(word, style = Status, textAlign = TextAlign.Center, color = color, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

/** The run's time, in the accent while the power is on; its colour turns in this piece alone. */
@Composable
private fun Clock(on: Boolean, moves: Boolean) {
    val colors = Csq.colors
    val color by turningColor(if (on) colors.accentText else colors.faint, moves, "timer")
    Uptime(Timer, color, Modifier.padding(top = LineGap))
}

/**
 * The card's notice lines of one place. Their room opens and closes as Home moves ([HomeView.moves]); only its
 * height is an event — on a turn of the phone the lines take their new width at once.
 */
@Composable
private fun Notes(moves: Boolean, content: @Composable ColumnScope.() -> Unit) {
    key(LocalConfiguration.current.screenWidthDp) {
        Column(
            Modifier.fillMaxWidth().then(if (moves) Modifier.animateContentSize(HomeMotion.spec()) else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally, content = content,
        )
    }
}

/**
 * The way to another connection (the operator, 2026-11-27): this layout has no list, so one bare icon in the card's
 * corner, as the header's are, opens «Профили» — or, with a single connection, «Добавить профиль». Never the
 * connection's name: it can be long. Nothing is drawn until the store has said how many connections there are.
 */
@Composable
private fun CornerPick(modifier: Modifier) {
    val nav = LocalNav.current
    val entries by LocalStore.current.profileEntries.collectAsStateWithLifecycle(initialValue = null)
    when (homePick(entries ?: return)) {
        HomePick.Switch -> IconBtn(Icons.switch, stringResource(R.string.home_pick_switch), { nav.open(Routes.profiles()) }, modifier)
        HomePick.Add -> IconBtn(Icons.plus, stringResource(R.string.home_pick_add), { nav.open(Routes.addProfile()) }, modifier)
    }
}

@Composable
private fun NoteLine(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(text, style = Line, color = color, textAlign = TextAlign.Center, modifier = modifier)
}

/** Live traffic (M-89), read only while shown; in a layer of its own: while bytes move it changes on every report. */
@Composable
private fun Traffic(modifier: Modifier = Modifier) {
    val traffic by TunnelManager.traffic.collectAsStateWithLifecycle()
    // the last report stays while the line fades out of a run that ended
    val last = remember { Ref<String>() }
    formatTraffic(traffic).takeIf { it.isNotEmpty() }?.let { last.value = it }
    val text = last.value ?: return
    Box(modifier.fillMaxWidth().graphicsLayer(), contentAlignment = Alignment.TopCenter) {
        Text(text, style = Line, color = Csq.colors.muted, textAlign = TextAlign.Center)
    }
}

/** M-86: the tunnel's own ping, on demand; its answer stays until the run ends. */
@Composable
private fun ProbeLine(modifier: Modifier = Modifier) {
    val probe by TunnelManager.probeState.collectAsStateWithLifecycle()
    val running = probe is TunnelProbe.Running
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        (probe as? TunnelProbe.Done)?.let { done ->
            Text(
                probeLine(done), style = Line, textAlign = TextAlign.Center,
                color = if (done.failed) Csq.colors.danger else Csq.colors.muted,
                // a long answer wraps instead of pushing «Пинг» off the card
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Text(
            stringResource(if (running) R.string.home_ping_running else R.string.home_ping),
            style = Line.copy(fontWeight = FontWeight.SemiBold),
            color = if (running) Csq.colors.faint else Csq.colors.accentText,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(enabled = !running, role = Role.Button, onClick = TunnelManager::probe)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}
