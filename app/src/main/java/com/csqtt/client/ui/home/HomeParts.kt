package com.csqtt.client.ui.home

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.node.Ref
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.csqtt.client.ConnectionState
import com.csqtt.client.R
import com.csqtt.client.StreamsLine
import com.csqtt.client.Tap
import com.csqtt.client.TunnelManager
import com.csqtt.client.showsTraffic
import com.csqtt.client.ui.design.Type
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Home's text sizes, one set for both layouts (the operator, 2026-11-27: «Look at the fonts sizes - they're different.
 * Make them same - I like the smaller single connection UI sizes»): the classic panel's own (P3-PLAN §1.3a).
 */
internal object HomeText {
    val Status = Type.body.copy(fontWeight = FontWeight.Bold)
    val Timer = Type.body.copy(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")

    /**
     * A line under the time. Its box is its line height whatever it holds: the arrows of the speeds come from another
     * font, a little higher, and would make their line higher than the one it takes the place of.
     */
    val Line = Type.sub.copy(
        lineHeight = 16.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum",
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
}

/**
 * How Home moves (the operator, 2026-11-27: «I don't mind moving, just make it smoother, not "flickering" and
 * "jumping"», and «We must not solve "not moving" by adding fixed useless space»). No room is kept for what is not
 * said: a line's room opens and closes over [MOVE_MS], so what is under it glides; a line that takes another's place
 * fades into it; and the colours of the state turn instead of switching. Nothing of it runs while nothing changes, or
 * while Home is not on screen.
 */
internal object HomeMotion {
    const val MOVE_MS = 200
    const val FADE_OUT_MS = 80
    const val FADE_IN_MS = 160

    /** How a size or a colour gets to its next value where Home moves ([HomeView.moves]): over [MOVE_MS]. */
    fun <T> spec(): FiniteAnimationSpec<T> = tween(MOVE_MS, easing = FastOutSlowInEasing)

    /**
     * A phone Android marks as low on memory is the kind that drops frames: there Home changes at once, as it did
     * before it moved, and motion costs nothing (the operator, 2026-11-27: smooth and fast on the worst phones). Measured
     * on the bench, a connect and a disconnect draw about 25 frames more in the list and 75 more in «Одно подключение»
     * with motion than without.
     */
    fun wanted(context: Context): Boolean = context.getSystemService(ActivityManager::class.java)?.isLowRamDevice != true
}

/**
 * A line of Home that is not always there: [value] is what it says, null when it says nothing. What it says changes in
 * place; a line of another [kind] fades into its place; its room opens and closes as [HomeMotion] says. [moves] false:
 * the change is drawn whole in the frame it comes in, the line that went not drawn under it ([HomeView.moves]). The
 * line gives itself its distance from what is above it, inside [content], so a line that says nothing takes no room at
 * all.
 *
 * Its cost (the operator, 2026-11-27: smooth and fast on the worst phones): nothing while nothing changes; a change is
 * laid out and drawn for a quarter of a second, with nothing composed again for a frame of it.
 */
@Composable
internal fun <T : Any> HomeLine(
    value: T?,
    modifier: Modifier = Modifier,
    kind: (T) -> Any = { Unit },
    moves: Boolean = true,
    content: @Composable (T) -> Unit,
) {
    // read as states by the lines on screen: one that is fading out or in is not composed again by its caller
    val now by rememberUpdatedState(value)
    val kindOf by rememberUpdatedState(kind)
    val draw by rememberUpdatedState(content)
    val movesNow by rememberUpdatedState(moves)
    AnimatedContent(
        targetState = value?.let(kind),
        modifier = modifier.fillMaxWidth(),
        transitionSpec = {
            if (!movesNow) {
                // a fade that snaps, not «no transition»: with none the line that went is still drawn, at full strength,
                // under the one that came, for the frame the change takes
                (fadeIn(snap()) togetherWith fadeOut(snap())).using(SizeTransform(clip = false) { _, _ -> snap() })
            } else {
                val opens = initialState == null
                (fadeIn(tween(HomeMotion.FADE_IN_MS, delayMillis = if (opens) 0 else HomeMotion.FADE_OUT_MS)) togetherWith
                    fadeOut(tween(HomeMotion.FADE_OUT_MS)))
                    .using(SizeTransform(clip = true) { _, _ -> tween(HomeMotion.MOVE_MS, easing = FastOutSlowInEasing) })
            }
        },
        contentAlignment = Alignment.TopCenter,
        label = "home-line",
    ) { shown ->
        // as wide as its place when it says nothing too: only its height opens and closes
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            if (shown != null) {
                // what it says now; once another line took its place, what it said last
                val last = remember { Ref<T>() }
                now?.takeIf { kindOf(it) == shown }?.let { last.value = it }
                last.value?.let { draw(it) }
            }
        }
    }
}

/**
 * A colour, a length or a number of Home's as it is shown: turning into its next value where Home moves, and that value
 * itself where it does not — an animation told to snap still lands a frame after the word it goes with.
 */
@Composable
internal fun turningColor(target: Color, moves: Boolean, label: String): State<Color> =
    if (moves) animateColorAsState(target, HomeMotion.spec(), label = label) else rememberUpdatedState(target)

@Composable
internal fun turningDp(target: Dp, moves: Boolean, label: String): State<Dp> =
    if (moves) animateDpAsState(target, HomeMotion.spec(), label = label) else rememberUpdatedState(target)

@Composable
internal fun turningFloat(target: Float, moves: Boolean, label: String): State<Float> =
    if (moves) animateFloatAsState(target, HomeMotion.spec(), label = label) else rememberUpdatedState(target)

/**
 * The speeds have something to show: in a state that shows them ([showsTraffic]), once the run has reported. It changes
 * with that only, not on every report.
 */
@Composable
internal fun rememberShowsTraffic(state: ConnectionState): Boolean {
    val reported by remember { TunnelManager.traffic.map { it != null }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = TunnelManager.traffic.value != null)
    return showsTraffic(state) && reported
}

/** The state's word as Home says it. */
internal fun stateLabel(view: HomeView): String = view.visuals.label

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
