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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.csqtt.client.StreamsPlan
import com.csqtt.client.StreamsSlider
import com.csqtt.client.TunnelManager
import com.csqtt.client.TurnSubnetRegistry
import com.csqtt.client.VpnMtu
import com.csqtt.client.shouldConfirmAutoJsMode
import com.csqtt.client.shouldConfirmTcpTransport
import com.csqtt.client.ui.components.Card
import com.csqtt.client.ui.components.Field
import com.csqtt.client.ui.components.IconBtn
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
 * profile. What a running tunnel cannot take is held until it is off, and says so.
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
    if (view.authMode == CsqttConstants.VkAuth.MODE_CAPTCHA) Captcha(view)

    SettingRow(stringResource(R.string.tunnel_srtp)) {
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

    SettingRow(stringResource(R.string.tunnel_transport), onInfo = { nav.open(Routes.info(InfoTopic.TRANSPORT)) }) {
        Select(
            selected = transport,
            options = listOf(CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT to UDP, CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS to TCP),
            onSelect = { picked ->
                if (shouldConfirmTcpTransport(transport, picked, view.tcpAcknowledged)) {
                    nav.open(Routes.risk(view.profile, RiskKind.TCP, picked))
                } else {
                    scope.launch { store.saveTurnTransport(picked) }
                }
            },
            description = stringResource(R.string.tunnel_transport),
        )
    }
    if (DpiSplitMode.isAvailable(transport)) {
        Column(
            Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp).background(colors.raised, Inset).padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.tunnel_dpi), style = Type.label, color = colors.muted)
            Seg(DpiSplitMode.entries.map { stringResource(it.text()) }, DpiSplitMode.entries.indexOf(dpi), { index ->
                scope.launch { store.saveDpiSplit(DpiSplitMode.entries[index]) }
            })
            Text(stringResource(R.string.tunnel_dpi_sub), style = Type.sub, color = colors.muted)
        }
    }

    Streams(view)
    Mtu(view)

    SettingRow(stringResource(R.string.tunnel_fingerprint)) {
        Select(fingerprint, Fingerprints, { scope.launch { store.saveFingerprint(it) } }, stringResource(R.string.tunnel_fingerprint))
    }
    // which of VK's servers carry the tunnel is the choice of whoever runs the server
    if (!view.linkMode) {
        val title = TurnSubnetRegistry.getSubnet(subnet)?.title
        NavRow(
            stringResource(R.string.tunnel_turn),
            when {
                title == null -> stringResource(R.string.tunnel_turn_all)
                ipLimit.toIntOrNull() == null -> stringResource(R.string.tunnel_turn_all_ips, title)
                else -> stringResource(R.string.tunnel_turn_ips, title, ipLimit)
            },
        ) { nav.open(Routes.turnSubnets(view.profile)) }
    }
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
    Column(
        Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp).alpha(if (view.running) 0.5f else 1f).background(colors.raised, Inset)
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
            sub = if (view.running) stringResource(R.string.tunnel_run_locked) else null,
            enabled = !view.running,
        )
        // while the tunnel runs the choices stay in sight, and are held
        if (!auto) {
            Text(stringResource(R.string.tunnel_captcha_method), style = Type.label, color = colors.muted)
            Seg(listOf(WBV, RJS), if (webView) 0 else 1, { index ->
                if (!view.running) scope.launch {
                    if (index == 0) {
                        store.saveCaptchaMode("wv")
                        store.saveCaptchaSolveMethod(if (wbvManual) "manual" else "auto")
                    } else {
                        store.saveCaptchaMode("rjs")
                        store.saveCaptchaSolveMethod("auto")
                    }
                }
            })
            // RJS solves by itself: only the web view has a manual mode
            if (webView) {
                Text(stringResource(R.string.tunnel_captcha_mode), style = Type.label, color = colors.muted)
                Seg(
                    listOf(stringResource(R.string.tunnel_captcha_manual), stringResource(R.string.tunnel_work_auto)), if (wbvManual) 0 else 1,
                    { index -> if (!view.running) scope.launch { store.saveWbvCaptchaSolveMethod(if (index == 0) "manual" else "auto") } },
                )
            }
        }
    }
}

/**
 * «Потоки»: the count the profile asks for, said as the most one hash carries and the total (the operator,
 * 2026-11-27). The part of the track above what is recommended — [StreamsPlan.RECOMMENDED_PER_HASH] on a hash — is
 * red, and a thumb let go there is asked about before it is stored. The server's limit, when the server named one, is
 * marked on the track (S10).
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
    val hashes = StreamsPlan.hashes(
        autoVk = view.authMode == CsqttConstants.VkAuth.MODE_AUTO_JS || view.hashMode == CsqttConstants.VkAutoHash.MODE_AUTO_JS,
        // a link's own hashes are what its streams go over, whatever the mode says
        autoApi = view.hashMode == CsqttConstants.VkAutoHash.MODE_AUTO_API && view.ownerHashes.isEmpty(),
        manual = view.ownerInUse + view.ownCount,
    )
    val recommended = hashes?.let { StreamsPlan.recommended(it, maximum) } ?: maximum
    val over = value > recommended

    // the count is long («50 на хеш (всего 90)»): where it does not fit beside the label — a narrow screen, a large
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
        // the count in its pill, as the canvas has it; in the danger colours once it is above what is recommended
        Text(
            if (hashes == null) value.toString() else stringResource(R.string.tunnel_streams_per_hash, StreamsPlan.perHash(value, hashes), value),
            style = Type.figure, color = if (over) colors.danger else colors.accentText, softWrap = false,
            modifier = Modifier.background(if (over) colors.dangerSoft else colors.accentSoft, Shape.pill).padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
    StreamsTrack(
        value = value, maximum = maximum, recommended = recommended, mark = StreamsSlider.mark(limit, maximum), enabled = !view.running,
        onChange = { dragged = it },
        onDone = {
            val picked = dragged
            when {
                picked == null || picked == saved -> dragged = null
                // into the red part: asked about first, and stored only on the answer (the thumb goes back meanwhile)
                picked > recommended && saved <= recommended -> {
                    dragged = null
                    nav.open(Routes.risk(view.profile, RiskKind.STREAMS, "$picked:$recommended"))
                }
                else -> TunnelManager.scope.launch { store.saveWorkersPerHash(picked) }
            }
        },
    )
    if (recommended < maximum) {
        Text(
            stringResource(R.string.tunnel_streams_recommended, recommended, StreamsPlan.RECOMMENDED_PER_HASH),
            style = Type.sub, color = if (over) colors.danger else colors.muted,
        )
    }
    when {
        limit != null -> Text(stringResource(R.string.tunnel_streams_limit, limit), style = Type.sub, color = colors.muted)
        view.running -> Text(stringResource(R.string.tunnel_streams_locked), style = Type.sub, color = colors.muted)
    }
}

/** The track itself. A gesture outlives the composition it began in: what it reads, it reads through state. */
@Composable
private fun StreamsTrack(
    value: Int,
    maximum: Int,
    recommended: Int,
    mark: Float?,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    onDone: () -> Unit,
) {
    val colors = Csq.colors
    val top by rememberUpdatedState(maximum)
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    val spoken = pluralText(R.plurals.profile_streams, value)
    val name = stringResource(R.string.tunnel_streams)
    val stops = StreamsSlider.stops(maximum)
    Canvas(
        Modifier.fillMaxWidth().height(44.dp).alpha(if (enabled) 1f else 0.5f)
            .semantics {
                contentDescription = name
                stateDescription = spoken
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), stops.first().toFloat()..stops.last().toFloat(), (stops.size - 2).coerceAtLeast(0))
                if (enabled) {
                    setProgress { wanted ->
                        val picked = stops.minBy { kotlin.math.abs(it - wanted) }
                        change(picked)
                        done()
                        true
                    }
                }
            }
            .focusable(enabled)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val inset = 10.dp.toPx()
                fun at(x: Float) = StreamsSlider.valueAt((x - inset) / (size.width - 2 * inset).coerceAtLeast(1f), top)
                detectTapGestures { offset ->
                    change(at(offset.x))
                    done()
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
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
        val at = inset + width * StreamsSlider.fraction(value, maximum)
        val edge = inset + width * StreamsSlider.fraction(recommended, maximum)
        val stroke = 4.dp.toPx()
        // the track, and red where a count is above what is recommended
        drawLine(colors.line, Offset(inset, y), Offset(edge, y), stroke, StrokeCap.Round)
        if (recommended < maximum) drawLine(colors.danger.copy(alpha = 0.45f), Offset(edge, y), Offset(inset + width, y), stroke, StrokeCap.Round)
        // what is chosen: in the accent up to the edge, in red past it
        drawLine(colors.accent, Offset(inset, y), Offset(minOf(at, edge), y), stroke, StrokeCap.Round)
        if (at > edge) drawLine(colors.danger, Offset(edge, y), Offset(at, y), stroke, StrokeCap.Round)
        // the server's limit: a tick across the track, in the warning colour and taller than the track, so that it is
        // read without the colour too
        if (mark != null) {
            drawRect(colors.warn, Offset(inset + width * mark - 1.dp.toPx(), y - 6.dp.toPx()), Size(2.dp.toPx(), 12.dp.toPx()))
        }
        drawCircle(colors.surface, 10.dp.toPx(), Offset(at, y))
        drawCircle(if (at > edge) colors.danger else colors.accent, 7.dp.toPx(), Offset(at, y))
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
    SettingRow(stringResource(R.string.tunnel_mtu)) {
        Select(
            selected = if (custom) MTU_CUSTOM else shown.toString(),
            options = VpnMtu.PRESETS.map { it.toString() to it.toString() } + (MTU_CUSTOM to stringResource(R.string.tunnel_mtu_custom)),
            onSelect = { key ->
                val preset = key.toIntOrNull()
                customPicked = preset == null
                if (preset != null && preset != stored) scope.launch { store.saveVpnMtu(preset) }
            },
            description = stringResource(R.string.tunnel_mtu),
            enabled = !view.running,
        )
    }
    if (custom) {
        Field(
            stringResource(R.string.tunnel_mtu_field, VpnMtu.RANGE.first, VpnMtu.RANGE.last), typed,
            { next ->
                typed = next.filter(Char::isDigit).take(4)
                // every valid MTU of four digits has an invalid prefix of three, so typing stores no value on the way
                VpnMtu.parse(typed)?.takeIf { it != stored }?.let { mtu -> scope.launch { store.saveVpnMtu(mtu) } }
            },
            error = if (VpnMtu.parse(typed) == null) stringResource(R.string.tunnel_port_wrong_range, VpnMtu.RANGE.first, VpnMtu.RANGE.last) else null,
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !view.running,
        )
    }
    Text(
        if (view.running) stringResource(R.string.tunnel_run_locked)
        else stringResource(R.string.tunnel_mtu_hint, VpnMtu.RANGE.first, VpnMtu.RANGE.last),
        style = Type.sub, color = colors.muted, modifier = Modifier.padding(bottom = 4.dp),
    )
}
