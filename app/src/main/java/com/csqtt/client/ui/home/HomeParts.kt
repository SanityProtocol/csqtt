package com.csqtt.client.ui.home

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.csqtt.client.ConnectionState
import com.csqtt.client.R
import com.csqtt.client.StreamsLine
import com.csqtt.client.Tap
import com.csqtt.client.TunnelManager
import java.util.Locale
import kotlinx.coroutines.delay

/** The state's word as Home says it. */
@Composable
internal fun stateLabel(view: HomeView): String = view.visuals.homeLabel?.let { stringResource(it) } ?: view.visuals.label

/** What TalkBack says of the C: what a tap will do, or the state when a tap does nothing now. */
@Composable
internal fun tapDescription(view: HomeView): String = when (view.visuals.tap) {
    Tap.Start -> stringResource(R.string.home_vpn_on)
    Tap.Stop -> stringResource(R.string.home_vpn_off)
    Tap.Captcha -> stringResource(R.string.home_captcha_solve)
    Tap.Nothing -> if (view.state is ConnectionState.Stopping) stringResource(R.string.home_vpn_stopping) else stateLabel(view)
}

/**
 * The run's time. It ticks once a second only while it is on a started screen (M-48), and in a layer of its own as wide
 * as its place, so a tick redraws these digits and nothing around them.
 */
@Composable
internal fun Uptime(style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val mark by TunnelManager.uptime.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(mark, lifecycle) {
        if (mark.startedAtMs <= 0L) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = SystemClock.elapsedRealtime()
                delay(1_000L - (now - mark.startedAtMs).mod(1_000L))
            }
        }
    }
    val seconds = mark.frozenSeconds ?: mark.startedAtMs.takeIf { it > 0L }?.let { (now - it).coerceAtLeast(0L) / 1_000L }
    Box(modifier.fillMaxWidth().graphicsLayer(), contentAlignment = Alignment.TopCenter) {
        Text(formatUptime(seconds), style = style, color = color)
    }
}

internal fun formatUptime(seconds: Long?): String {
    val total = seconds?.coerceAtLeast(0L) ?: 0L
    return String.format(Locale.US, "%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
}

/**
 * The streams as they are said (P3-PLAN §1.3a): the count that is up and the count the core runs, or null when there is
 * nothing to say. The two seconds after the last stream end by a timer of this composable, keyed on the moment the count
 * became full: a screen made again inside them shows the rest of them, not two seconds again.
 */
@Composable
internal fun rememberStreams(state: ConnectionState): Pair<Int, Int>? {
    val shown by TunnelManager.shownWorkers.collectAsStateWithLifecycle()
    val target by TunnelManager.targetWorkers.collectAsStateWithLifecycle()
    val limit by TunnelManager.serverAssignedWorkers.collectAsStateWithLifecycle()
    val rest by TunnelManager.atRest.collectAsStateWithLifecycle()
    val fullSince by TunnelManager.streamsFullSince.collectAsStateWithLifecycle()
    val running = StreamsLine.running(target, limit)
    var tick by remember { mutableIntStateOf(0) }
    val left = remember(tick, state, rest, shown, running, fullSince) {
        StreamsLine.holdLeft(state, rest, shown, running, fullSince, SystemClock.elapsedRealtime())
    }
    LaunchedEffect(left, fullSince) {
        if (left != null && left > 0L) {
            delay(left)
            tick++
        }
    }
    return if (left != null) shown to running else null
}
