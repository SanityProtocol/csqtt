package com.csqtt.client.ui.tunnel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.CsqttConstants
import com.csqtt.client.DpiSplitMode
import com.csqtt.client.R
import com.csqtt.client.RunKey
import com.csqtt.client.StreamZone
import com.csqtt.client.StreamsPlan
import com.csqtt.client.StreamsSlider
import com.csqtt.client.TunnelManager
import com.csqtt.client.TurnSubnetRegistry
import com.csqtt.client.VpnMtu
import com.csqtt.client.shouldConfirmAutoJsMode
import com.csqtt.client.ui.components.Card
import com.csqtt.client.ui.components.Field
import com.csqtt.client.ui.components.IconBtn
import com.csqtt.client.ui.components.NowLine
import com.csqtt.client.ui.components.Seg
import com.csqtt.client.ui.components.Select
import com.csqtt.client.ui.components.SettingRow
import com.csqtt.client.ui.components.SwitchRow
import com.csqtt.client.ui.design.Csq
import com.csqtt.client.ui.design.Icons
import com.csqtt.client.ui.design.Shape
import com.csqtt.client.ui.design.Type
import com.csqtt.client.ui.design.pluralText
import com.csqtt.client.ui.shell.LocalNav
import com.csqtt.client.ui.shell.Routes
import kotlinx.coroutines.launch

private const val MTU_CUSTOM = "custom"
private val Fingerprints = listOf("chrome" to "Chrome", "safari" to "Safari", "firefox" to "Firefox", "edge" to "Edge", "opera" to "Opera")
private val Inset = RoundedCornerShape(14.dp)

/**
 * «Дополнительно» (the canvas, F-TunnelMore, F-TunnelMoreTcp): how the tunnel is made — where the access to VK's
 * servers comes from, the transport, the streams, the MTU. Folded until asked for; open, it stays open on every
 * profile. Nothing is held while the tunnel runs (the operator, 2026-11-27). All but one of the rows are read when a run
 * starts, so a change is for the next connection: the row then says what the tunnel uses now, under its choice, and the
 * bar over the tabs offers the reconnect (card p3-apply-bar; the operator, 2026-11-28). The MTU is the VPN
 * interface's, which is made again while the tunnel runs: it is changed at once, and says so.
 */
@Composable
internal fun MoreCard(view: TunnelView, open: Boolean, onOpen: (Boolean) -> Unit) {
    val colors = Csq.colors
    Card(gap = 2.dp) {
        val label = stringResource(if (open) R.string.tunnel_more_fold else R.string.tunnel_more_unfold)
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 28.dp)
                .clickable(role = Role.Button, onClickLabel = label) { onOpen(!open) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.tunnel_more), style = Type.cardTitle, color = colors.text,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Icon(if (open) Icons.chevronUp else Icons.chevronDown, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.muted)
        }
        if (open) {
            More(view)
        } else {
            Text(
                stringResource(if (view.linkMode) R.string.tunnel_more_sub else R.string.tunnel_more_sub_owner),
                style = Type.sub, color = colors.muted,
            )
        }
    }
}

@Composable
private fun More(view: TunnelView) {
    val colors = Csq.colors
    val nav = LocalNav.current
    val store = view.store
    val scope = TunnelManager.scope
    // null until the store has answered: a choice is drawn as stored, never as a default that the next frame corrects
    // (§4.2 rule 3)
    val storedTransport by store.turnTransport.collectAsStateWithLifecycle(initialValue = null)
    val storedDpi by store.dpiSplit.collectAsStateWithLifecycle(initialValue = null)
    val storedSrtp by store.srtpMode.collectAsStateWithLifecycle(initialValue = null)
    val storedFingerprint by store.selectedFingerprint.collectAsStateWithLifecycle(initialValue = null)
    val storedSubnet by store.selectedTurnSubnet.collectAsStateWithLifecycle(initialValue = null)
    val storedLimit by store.turnSubnetIpLimit.collectAsStateWithLifecycle(initialValue = null)
    val transport = storedTransport ?: return
    val dpi = storedDpi ?: return
    val srtp = storedSrtp ?: return
    val fingerprint = storedFingerprint ?: return
    val subnet = storedSubnet ?: return
    val ipLimit = storedLimit ?: return

    SettingRow(stringResource(R.string.tunnel_work_mode), onInfo = { nav.open(Routes.info(InfoTopic.WORK_MODE)) }) {
        Select(
            selected = view.authMode,
            options = listOf(
                CsqttConstants.VkAuth.MODE_CAPTCHA to stringResource(R.string.tunnel_work_captcha),
                CsqttConstants.VkAuth.MODE_CALLS to stringResource(R.string.tunnel_work_auto),
                CsqttConstants.VkAuth.MODE_AUTO_JS to stringResource(R.string.tunnel_work_auto_vk),
                // a link's hashes are its owner's calls: «Авто ВК», which makes a call of its own, has no place there
            ).filterNot { (mode, _) -> view.linkMode && view.ownerHashes.isNotEmpty() && mode == CsqttConstants.VkAuth.MODE_AUTO_JS },
            onSelect = { mode ->
                if (shouldConfirmAutoJsMode(view.storedAuthMode, mode, view.riskAcknowledged)) {
                    nav.open(Routes.risk(view.profile, RiskKind.WORK_MODE, mode))
                } else {
                    scope.launch { store.saveVkAuthMode(mode) }
                }
            },
            description = stringResource(R.string.tunnel_work_mode),
        )
    }
    // the mode the run is in, where the mode in force here is another
    view.now(RunKey.AUTH_MODE) { it.authMode }?.takeIf { it != view.authMode }?.let { NowLine(authModeName(it), end = true) }
    if (view.authMode == CsqttConstants.VkAuth.MODE_CAPTCHA) Captcha(view)

    SettingRow(stringResource(R.string.tunnel_srtp), onInfo = { nav.open(Routes.info(InfoTopic.SRTP)) }) {
        Select(
            selected = srtp,
            options = listOf(
                CsqttConstants.SrtpMode.AUTO to stringResource(R.string.tunnel_work_auto),
                CsqttConstants.SrtpMode.WRAP to SRTP_WRAP, CsqttConstants.SrtpMode.DTLS to SRTP_DTLS,
            ),
            onSelect = { scope.launch { store.saveSrtpMode(it) } },
            description = stringResource(R.string.tunnel_srtp),
        )
    }
    view.now(RunKey.SRTP) { it.srtp }?.let { NowLine(srtpName(it), end = true) }

    SettingRow(stringResource(R.string.tunnel_transport), onInfo = { nav.open(Routes.info(InfoTopic.TRANSPORT)) }) {
        Select(
            selected = transport,
            options = listOf(CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT to UDP, CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS to TCP),
            onSelect = { picked -> scope.launch { store.saveTurnTransport(picked) } },
            description = stringResource(R.string.tunnel_transport),
        )
    }
    view.now(RunKey.TRANSPORT) { it.transport }?.let { NowLine(if (DpiSplitMode.isAvailable(it)) TCP else UDP, end = true) }
    if (DpiSplitMode.isAvailable(transport)) {
        Column(
            Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp).background(colors.raised, Inset).padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.tunnel_dpi), style = Type.label, color = colors.muted)
            Seg(DpiSplitMode.entries.map { stringResource(it.text()) }, DpiSplitMode.entries.indexOf(dpi), { index ->
                scope.launch { store.saveDpiSplit(DpiSplitMode.entries[index]) }
            })
            // the level the run splits with — none while it is on UDP
            view.now(RunKey.DPI) { it.dpi }?.let { NowLine(stringResource(it.text())) }
            // what the picked level does, and what it costs: the levels' names alone say neither
            Text(stringResource(dpi.says()), style = Type.sub, color = colors.muted)
        }
    }

    Streams(view)
    Mtu(view)

    SettingRow(stringResource(R.string.tunnel_fingerprint)) {
        Select(fingerprint, Fingerprints, { scope.launch { store.saveFingerprint(it) } }, stringResource(R.string.tunnel_fingerprint))
    }
    view.now(RunKey.FINGERPRINT) { it.fingerprint }?.let { used -> NowLine(Fingerprints.firstOrNull { it.first == used }?.second ?: used, end = true) }
    // which of VK's servers carry the tunnel is the choice of whoever runs the server
    if (!view.linkMode) {
        NavRow(
            stringResource(R.string.tunnel_turn),
            turnName(subnet, ipLimit),
            now = view.now(RunKey.TURN) { it }?.let { turnName(it.turnSubnet, it.turnLimit) },
        ) { nav.open(Routes.turnSubnets(view.profile)) }
    }
}

/** A choice of TURN subnets in words: all of VK's, or one subnet and how many of its addresses. */
@Composable
private fun turnName(subnet: String, ipLimit: String): String {
    val title = TurnSubnetRegistry.getSubnet(subnet)?.title
    return when {
        title == null -> stringResource(R.string.tunnel_turn_all)
        ipLimit.toIntOrNull() == null -> stringResource(R.string.tunnel_turn_all_ips, title)
        else -> stringResource(R.string.tunnel_turn_ips, title, ipLimit)
    }
}

/** A creds mode's name, as its row's choices name it. */
@Composable
private fun authModeName(mode: String): String = stringResource(
    when (mode) {
        CsqttConstants.VkAuth.MODE_CAPTCHA -> R.string.tunnel_work_captcha
        CsqttConstants.VkAuth.MODE_AUTO_JS -> R.string.tunnel_work_auto_vk
        else -> R.string.tunnel_work_auto
    },
)

@Composable
private fun srtpName(mode: String): String = when (mode) {
    CsqttConstants.SrtpMode.WRAP -> SRTP_WRAP
    CsqttConstants.SrtpMode.DTLS -> SRTP_DTLS
    else -> stringResource(R.string.tunnel_work_auto)
}

private fun DpiSplitMode.says(): Int = when (this) {
    DpiSplitMode.OFF -> R.string.tunnel_dpi_off_says
    DpiSplitMode.LIGHT -> R.string.tunnel_dpi_light_says
    DpiSplitMode.MEDIUM -> R.string.tunnel_dpi_medium_says
    DpiSplitMode.HEAVY -> R.string.tunnel_dpi_heavy_says
}

private fun DpiSplitMode.text(): Int = when (this) {
    DpiSplitMode.OFF -> R.string.tunnel_dpi_off
    DpiSplitMode.LIGHT -> R.string.tunnel_dpi_light
    DpiSplitMode.MEDIUM -> R.string.tunnel_dpi_medium
    DpiSplitMode.HEAVY -> R.string.tunnel_dpi_heavy
}

// what these are called everywhere, in every language
private const val UDP = "UDP"
private const val TCP = "TCP"
private const val SRTP_WRAP = "WRAP-SRTP"
private const val SRTP_DTLS = "DTLS-SRTP"
private const val WBV = "WBV"
private const val RJS = "RJS"

/** «Капча»'s own choices: solved by the app, or by which method and by whom. A choice with no effect is not drawn. */
@Composable
private fun Captcha(view: TunnelView) {
    val colors = Csq.colors
    val store = view.store
    val scope = TunnelManager.scope
    val storedMode by store.captchaMode.collectAsStateWithLifecycle(initialValue = null)
    val storedMethod by store.captchaWbvSolveMethod.collectAsStateWithLifecycle(initialValue = null)
    val mode = storedMode ?: return
    val wbvMethod = storedMethod ?: return
    val auto = mode == "auto"
    val webView = mode != "rjs"
    val wbvManual = wbvMethod != "auto"
    // the run's own choices, where one of them is not the profile's any more: said under the control it differs in
    val used = view.run?.takeIf { RunKey.CAPTCHA_MODE in it.keys || RunKey.CAPTCHA_SOLVE in it.keys }?.uses
    val usedAuto = used?.let { it.captchaMode == "auto" }
    Column(
        Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp).background(colors.raised, Inset)
            .padding(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SwitchRow(
            stringResource(R.string.tunnel_captcha_auto), auto,
            { on ->
                scope.launch {
                    if (on) {
                        store.saveCaptchaMode("auto")
                        store.saveCaptchaSolveMethod("auto")
                    } else {
                        store.saveCaptchaMode("wv")
                        store.saveCaptchaSolveMethod(if (wbvManual) "manual" else "auto")
                    }
                }
            },
        )
        if (usedAuto != null && usedAuto != auto) {
            NowLine(stringResource(if (usedAuto) R.string.now_captcha_on else R.string.now_captcha_off))
        }
        if (!auto) {
            Text(stringResource(R.string.tunnel_captcha_method), style = Type.label, color = colors.muted)
            Seg(listOf(WBV, RJS), if (webView) 0 else 1, { index ->
                scope.launch {
                    if (index == 0) {
                        store.saveCaptchaMode("wv")
                        store.saveCaptchaSolveMethod(if (wbvManual) "manual" else "auto")
                    } else {
                        store.saveCaptchaMode("rjs")
                        store.saveCaptchaSolveMethod("auto")
                    }
                }
            })
            // a run that solves by itself («Авто капча») is on neither method: the switch above says so
            if (used != null && usedAuto == false && (used.captchaMode != "rjs") != webView) NowLine(if (used.captchaMode == "rjs") RJS else WBV)
            // RJS solves by itself: only the web view has a manual mode
            if (webView) {
                Text(stringResource(R.string.tunnel_captcha_mode), style = Type.label, color = colors.muted)
                Seg(
                    listOf(stringResource(R.string.tunnel_captcha_manual), stringResource(R.string.tunnel_work_auto)), if (wbvManual) 0 else 1,
                    { index -> scope.launch { store.saveWbvCaptchaSolveMethod(if (index == 0) "manual" else "auto") } },
                )
                if (used != null && (used.captchaSolve != "auto") != wbvManual) {
                    NowLine(stringResource(if (used.captchaSolve != "auto") R.string.tunnel_captcha_manual else R.string.tunnel_work_auto))
                }
            }
        }
    }
}

/** A zone's colour, and the ground of a pill in it: as a chip of the same kind pairs them. */
@Composable
private fun StreamZone.colors(): Pair<Color, Color> {
    val colors = Csq.colors
    return when (this) {
        StreamZone.Green -> colors.ok to colors.okSoft
        StreamZone.Blue -> colors.accentText to colors.accentSoft
        StreamZone.Orange -> colors.warn to colors.warnSoft
        StreamZone.Red -> colors.danger to colors.dangerSoft
    }
}

/**
 * «Потоки» (the operator, 2026-11-27): the count the profile asks for, in fives, said as the total and, with more
 * than one hash, the most one of them carries. The line has four zones, counted per hash — green to 20, blue to 50,
 * orange to 70, red above — so with more hashes they stretch along it. A thumb let go in the orange or the red zone,
 * from a zone below it, is asked about before the count is stored; nothing caps a count. The server's limit, when the
 * server named one, is marked on the track (S10).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Streams(view: TunnelView) {
    val colors = Csq.colors
    val nav = LocalNav.current
    val store = view.store
    val stored by store.workersPerHash.collectAsStateWithLifecycle(initialValue = null)
    val storedMaximum by store.workersMaximum.collectAsStateWithLifecycle(initialValue = null)
    val serverWorkers by TunnelManager.serverAssignedWorkers.collectAsStateWithLifecycle()
    // the server's count is the run's: another profile opened during the run has no limit to show
    val limit = serverWorkers?.takeIf { view.running && view.profile == TunnelManager.runTargetProfile() }
    val saved = stored ?: return
    val maximum = storedMaximum ?: return
    // while a finger moves the thumb the count follows it; the store gets the one it is let go on
    var dragged by remember(saved, maximum) { mutableStateOf<Int?>(null) }
    val value = dragged ?: saved
    val autoVk = view.authMode == CsqttConstants.VkAuth.MODE_AUTO_JS || view.hashMode == CsqttConstants.VkAutoHash.MODE_AUTO_JS
    // a link's own hashes are what its streams go over, whatever the mode says
    val autoApi = view.hashMode == CsqttConstants.VkAutoHash.MODE_AUTO_API && view.ownerHashes.isEmpty()
    // the hashes a start would connect with: one a check found dead is left out there, and so here
    val manual = view.startHashes
    val calls = StreamsPlan.calls(autoVk, autoApi, manual)
    val perHashSaid = StreamsPlan.saidPerHash(autoVk, autoApi, manual)
    val zone = StreamsPlan.zone(value, calls)
    val (zoneColor, zoneGround) = zone.colors()

    // the count is long («90 (50 на хеш)»): where it does not fit beside the label — a narrow screen, a large
    // font — it goes under the label, whole, and the label is never broken inside its word
    val name = stringResource(R.string.tunnel_streams)
    FlowRow(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.Center,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = Type.body, color = colors.text)
            IconBtn(Icons.help, stringResource(R.string.setting_about, name), { nav.open(Routes.info(InfoTopic.STREAMS)) }, small = true, tint = colors.faint)
        }
        // the count in its pill, as the canvas has it, in its zone's colours: the total, which the thumb sets, and
        // after it what one hash carries of it — with one call that is the same number, and is not said twice
        Text(
            if (perHashSaid && calls > 1) stringResource(R.string.tunnel_streams_per_hash, StreamsPlan.perHash(value, calls), value) else value.toString(),
            style = Type.figure, color = zoneColor, softWrap = false,
            modifier = Modifier.background(zoneGround, Shape.pill).padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
    StreamsTrack(
        value = value, maximum = maximum, calls = calls, mark = StreamsSlider.mark(limit, maximum),
        onChange = { dragged = it },
        onDone = {
            val picked = dragged
            val into = picked?.let { StreamsPlan.zone(it, calls) }
            when {
                picked == null || into == null || picked == saved -> dragged = null
                // into the orange or the red zone from a zone below it: asked about first, and stored only on the
                // answer (the thumb goes back meanwhile)
                into >= StreamZone.Orange && into > StreamsPlan.zone(saved, calls) -> {
                    dragged = null
                    // with what one hash would carry, and whether more hashes would help («Авто ВК» takes none)
                    nav.open(Routes.risk(view.profile, RiskKind.STREAMS, "$picked:${StreamsPlan.perHash(picked, calls)}:${if (autoVk) 0 else 1}"))
                }
                else -> TunnelManager.scope.launch { store.saveWorkersPerHash(picked) }
            }
        },
    )
    // the count the run asked for, while the one picked here waits for a reconnect
    view.now(RunKey.STREAMS) { it.streams }?.let { NowLine(it.toString()) }
    // what is recommended, said while the line has a part above it; in that part, and above it, what the count is
    val recommended = StreamsPlan.totalWithin(StreamsPlan.RECOMMENDED_PER_HASH, calls, maximum)
    when {
        zone == StreamZone.Red -> Text(
            stringResource(if (perHashSaid) R.string.tunnel_streams_danger else R.string.tunnel_streams_danger_plain, StreamsPlan.ORANGE_PER_HASH),
            style = Type.sub, color = colors.danger,
        )
        zone == StreamZone.Orange -> Text(
            stringResource(if (perHashSaid) R.string.tunnel_streams_over else R.string.tunnel_streams_over_plain, StreamsPlan.RECOMMENDED_PER_HASH),
            style = Type.sub, color = colors.warn,
        )
        recommended < StreamsSlider.stops(maximum).last() -> Text(
            stringResource(
                if (perHashSaid) R.string.tunnel_streams_recommended else R.string.tunnel_streams_recommended_plain,
                StreamsPlan.RECOMMENDED_PER_HASH,
            ),
            style = Type.sub, color = colors.muted,
        )
    }
    if (limit != null) Text(stringResource(R.string.tunnel_streams_limit, limit), style = Type.sub, color = colors.muted)
}

/** The track itself. A gesture outlives the composition it began in: what it reads, it reads through state. */
@Composable
private fun StreamsTrack(
    value: Int,
    maximum: Int,
    calls: Int,
    mark: Float?,
    onChange: (Int) -> Unit,
    onDone: () -> Unit,
) {
    val colors = Csq.colors
    val top by rememberUpdatedState(maximum)
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    val stops = StreamsSlider.stops(maximum)
    val zone = StreamsPlan.zone(value, calls)
    val count = pluralText(R.plurals.profile_streams, value)
    // the zone is said, not only coloured
    val spoken = when (zone) {
        StreamZone.Orange -> stringResource(R.string.tunnel_streams_state_over, count)
        StreamZone.Red -> stringResource(R.string.tunnel_streams_state_danger, count)
        else -> count
    }
    val name = stringResource(R.string.tunnel_streams)
    // where each zone ends on the line: the largest total that keeps every hash within the zone's count
    val ends = listOf(StreamsPlan.GREEN_PER_HASH, StreamsPlan.RECOMMENDED_PER_HASH, StreamsPlan.ORANGE_PER_HASH)
        .map { StreamsSlider.fraction(StreamsPlan.totalWithin(it, calls, stops.last()), maximum) } + 1f
    val zoneColors = listOf(colors.ok, colors.accent, colors.warn, colors.danger)
    Canvas(
        Modifier.fillMaxWidth().height(44.dp)
            .semantics {
                contentDescription = name
                stateDescription = spoken
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), stops.first().toFloat()..stops.last().toFloat(), (stops.size - 2).coerceAtLeast(0))
                setProgress { wanted ->
                    val picked = stops.minBy { kotlin.math.abs(it - wanted) }
                    change(picked)
                    done()
                    true
                }
            }
            .focusable()
            .pointerInput(Unit) {
                val inset = 10.dp.toPx()
                fun at(x: Float) = StreamsSlider.valueAt((x - inset) / (size.width - 2 * inset).coerceAtLeast(1f), top)
                detectTapGestures { offset ->
                    change(at(offset.x))
                    done()
                }
            }
            .pointerInput(Unit) {
                val inset = 10.dp.toPx()
                fun at(x: Float) = StreamsSlider.valueAt((x - inset) / (size.width - 2 * inset).coerceAtLeast(1f), top)
                detectHorizontalDragGestures(onDragEnd = { done() }, onDragCancel = { done() }) { touch, _ ->
                    touch.consume()
                    change(at(touch.position.x))
                }
            },
    ) {
        val inset = 10.dp.toPx()
        val y = size.height / 2
        val width = size.width - 2 * inset
        val thumb = StreamsSlider.fraction(value, maximum)
        val stroke = 4.dp.toPx()
        // the four zones along the whole line, pale; and up to the thumb in full colour
        var from = 0f
        ends.forEachIndexed { index, end ->
            if (end > from) {
                val color = zoneColors[index]
                drawLine(color.copy(alpha = 0.3f), Offset(inset + width * from, y), Offset(inset + width * end, y), stroke, StrokeCap.Butt)
                val filled = minOf(end, thumb)
                if (filled > from) drawLine(color, Offset(inset + width * from, y), Offset(inset + width * filled, y), stroke, StrokeCap.Butt)
                from = end
            }
        }
        // where one zone ends and the next begins: a notch in the line, so that the edge is seen without the colours
        // too (two pale zones side by side are near one colour)
        ends.dropLast(1).filter { it > 0f && it < 1f }.forEach { edge ->
            drawRect(colors.surface, Offset(inset + width * edge - 1.dp.toPx(), y - stroke), Size(2.dp.toPx(), stroke * 2))
        }
        // the server's limit: a tick across the track, taller than the track, so that it is read without its colour too
        if (mark != null) {
            drawRect(colors.text, Offset(inset + width * mark - 1.dp.toPx(), y - 7.dp.toPx()), Size(2.dp.toPx(), 14.dp.toPx()))
        }
        val at = inset + width * thumb
        drawCircle(colors.surface, 10.dp.toPx(), Offset(at, y))
        drawCircle(zoneColors[zone.ordinal], 7.dp.toPx(), Offset(at, y))
    }
}

/** The profile's MTU (P1 Bt): a preset as picked, or a value of the user's own, stored only when it is one the tunnel can run. */
@Composable
private fun Mtu(view: TunnelView) {
    val colors = Csq.colors
    val store = view.store
    val scope = TunnelManager.scope
    // null until the store has answered: a profile left at the default stores nothing, and follows the default
    val stored by store.vpnMtu.collectAsStateWithLifecycle(initialValue = null)
    var customPicked by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(stored) {
        val value = stored ?: return@LaunchedEffect
        // an invalid draft is left in the field: only the profile's own value replaces it
        if (VpnMtu.parse(typed) != value) typed = value.toString()
    }
    val shown = stored ?: CsqttConstants.Vpn.DEFAULT_MTU
    val custom = customPicked || shown !in VpnMtu.PRESETS

    /**
     * The MTU is the VPN interface's, not the core's: with the tunnel on for this profile the interface is made again
     * with it, as it is for a change of «Маршруты» (the run reads its own profile). Stored first, since the service
     * reads the store.
     */
    fun save(mtu: Int) {
        scope.launch {
            store.saveVpnMtu(mtu)
            if (view.profile == TunnelManager.runTargetProfile()) TunnelManager.reloadVpn()
        }
    }

    SettingRow(stringResource(R.string.tunnel_mtu)) {
        Select(
            selected = if (custom) MTU_CUSTOM else shown.toString(),
            options = VpnMtu.PRESETS.map { it.toString() to it.toString() } + (MTU_CUSTOM to stringResource(R.string.tunnel_mtu_custom)),
            onSelect = { key ->
                val preset = key.toIntOrNull()
                customPicked = preset == null
                if (preset != null && preset != stored) save(preset)
            },
            description = stringResource(R.string.tunnel_mtu),
        )
    }
    if (custom) {
        Field(
            stringResource(R.string.tunnel_mtu_field, VpnMtu.RANGE.first, VpnMtu.RANGE.last), typed,
            { next ->
                typed = next.filter(Char::isDigit).take(4)
                // every valid MTU of four digits has an invalid prefix of three, so typing stores no value on the way
                VpnMtu.parse(typed)?.takeIf { it != stored }?.let(::save)
            },
            error = if (VpnMtu.parse(typed) == null) stringResource(R.string.tunnel_port_wrong_range, VpnMtu.RANGE.first, VpnMtu.RANGE.last) else null,
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
    Text(
        stringResource(R.string.tunnel_mtu_hint, VpnMtu.RANGE.first, VpnMtu.RANGE.last),
        style = Type.sub, color = colors.muted, modifier = Modifier.padding(bottom = if (view.runsHere) 0.dp else 4.dp),
    )
    if (view.runsHere) {
        Text(stringResource(R.string.tunnel_mtu_now), style = Type.sub, color = colors.muted, modifier = Modifier.padding(bottom = 4.dp))
    }
}
