package com.csqtt.client.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.ConnectionState
import com.csqtt.client.HomeNotice
import com.csqtt.client.R
import com.csqtt.client.StatusTone
import com.csqtt.client.Tap
import com.csqtt.client.TunnelManager
import com.csqtt.client.TunnelProbe
import com.csqtt.client.WifiCopy
import com.csqtt.client.formatTraffic
import com.csqtt.client.probeLine
import com.csqtt.client.showsTraffic
import com.csqtt.client.ui.components.Btn
import com.csqtt.client.ui.components.BtnKind
import com.csqtt.client.ui.components.TextBtn
import com.csqtt.client.ui.design.Csq
import com.csqtt.client.ui.design.Type
import com.csqtt.client.ui.legacy.rememberWifiNameAccess

// the classic panel's own sizes, kept as they are (P3-PLAN §1.3a)
private val Status = Type.body.copy(fontWeight = FontWeight.Bold)
private val Timer = Type.body.copy(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
private val Line = Type.sub.copy(lineHeight = 16.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
private val CardShape = RoundedCornerShape(34.dp)

/**
 * «Одно подключение», Home's default layout (the operator, 2026-11-26): the screen the app had, as it was — the logo
 * button in its card, centred between the header and the bar, the status in its state's colour, the timer, the traffic,
 * the streams while they are news, the ping through the tunnel. What it tells or asks is said in the card, as lines.
 */
@Composable
internal fun HomeOne(view: HomeView) {
    val colors = Csq.colors
    val state = view.state
    val on = state.powerIsOn || state is ConnectionState.Stopping
    val canTap = view.visuals.tap != Tap.Nothing
    val description = tapDescription(view)
    val streams = rememberStreams(state)

    Box(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
                .padding(top = 10.dp, bottom = 26.dp)
                .shadow(if (colors.isDark) 4.dp else 10.dp, CardShape)
                .clip(CardShape)
                .background(colors.surface)
                .border(1.dp, colors.lineSoft, CardShape)
                .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 22.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_c_logo),
                contentDescription = null,
                modifier = Modifier
                    .size(172.dp)
                    .clip(CircleShape)
                    .clickable(enabled = canTap, role = Role.Button, onClick = view.actions.tap)
                    .semantics { contentDescription = description }
                    .scale(if (on) 1.06f else 0.96f),
                colorFilter = if (on) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
                alpha = when {
                    on -> 1f
                    canTap -> 0.66f
                    else -> 0.34f
                },
            )
            Text(
                view.visuals.label, style = Status, textAlign = TextAlign.Center,
                color = when (view.visuals.status) {
                    StatusTone.Accent -> colors.accentText
                    StatusTone.Warn -> colors.warn
                    StatusTone.Danger -> colors.danger
                    StatusTone.Muted -> colors.muted
                },
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            view.stage?.let { Text(stringResource(it.text), style = Line, color = colors.muted, textAlign = TextAlign.Center) }

            // every kind is drawn here: the list does the same in its own way, and neither can leave one out
            for (notice in view.notices) {
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
                        TextBtn(stringResource(R.string.home_open_tunnel), view.actions.openTunnel)
                    }
                    // these two close the card, after the figures
                    HomeNotice.NotificationsOff, is HomeNotice.Outcome -> Unit
                }
            }

            Uptime(Timer, if (on) colors.accentText else colors.faint)
            if (showsTraffic(state)) Traffic()
            streams?.let { (up, running) -> NoteLine(stringResource(R.string.home_streams, up, running), colors.muted) }
            if (state is ConnectionState.Connected) ProbeLine()

            for (notice in view.notices) {
                when (notice) {
                    HomeNotice.NotificationsOff -> Text(
                        stringResource(R.string.home_notifications_off), style = Line, color = colors.danger,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.clickable(role = Role.Button, onClick = view.actions.notificationSettings),
                    )
                    // a failure in the error colour; skipped nodes of a refresh that worked as a warning
                    is HomeNotice.Outcome -> NoteLine(
                        notice.text,
                        when {
                            notice.bad -> colors.danger
                            notice.warn -> colors.warn
                            else -> colors.muted
                        },
                    )
                    is HomeNotice.PausedOn, HomeNotice.WifiNameUnknown, is HomeNotice.WifiOffer, is HomeNotice.Stopped,
                    HomeNotice.Captcha, HomeNotice.NeedLink -> Unit
                }
            }
        }
    }
}

@Composable
private fun NoteLine(text: String, color: Color) {
    Text(text, style = Line, color = color, textAlign = TextAlign.Center)
}

/** Live traffic (M-89), read only while shown; in a layer of its own: while bytes move it changes on every report. */
@Composable
private fun Traffic() {
    val traffic by TunnelManager.traffic.collectAsStateWithLifecycle()
    val text = formatTraffic(traffic)
    if (text.isEmpty()) return
    Box(Modifier.fillMaxWidth().graphicsLayer(), contentAlignment = Alignment.TopCenter) {
        Text(text, style = Line, color = Csq.colors.muted, textAlign = TextAlign.Center)
    }
}

/** M-86: the tunnel's own ping, on demand; its answer stays until the run ends. */
@Composable
private fun ProbeLine() {
    val probe by TunnelManager.probeState.collectAsStateWithLifecycle()
    val running = probe is TunnelProbe.Running
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
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
