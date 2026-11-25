package com.csqtt.client.ui.shell

import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.BuildConfig
import com.csqtt.client.CsqttConstants
import com.csqtt.client.LogBook
import com.csqtt.client.QuestionPlace
import com.csqtt.client.R
import com.csqtt.client.TunnelManager
import com.csqtt.client.ui.components.BarItem
import com.csqtt.client.ui.components.BottomBar
import com.csqtt.client.ui.components.Header
import com.csqtt.client.ui.components.MessageHost
import com.csqtt.client.ui.components.TabBadge
import com.csqtt.client.ui.design.Csq
import com.csqtt.client.ui.design.Icons
import com.csqtt.client.ui.design.pluralText
import kotlin.math.abs

/**
 * What the first frame needs before the store has answered (M-94): the theme mode and whether «Сервер» is in the bar, as
 * last shown. The settings are in DataStore, whose first read is asynchronous; drawing its defaults until it came was
 * the flash of the wrong theme. A missing mirror (the first start after the update) gives the defaults, once.
 */
internal class FirstFrame(context: Context) {
    private val prefs = context.getSharedPreferences("first_frame", Context.MODE_PRIVATE)

    val themeMode: String get() = prefs.getString(THEME, null) ?: CsqttConstants.Theme.MODE_SYSTEM
    val hasServer: Boolean get() = prefs.getBoolean(SERVER, false)

    fun keep(themeMode: String, hasServer: Boolean) {
        if (themeMode == this.themeMode && hasServer == this.hasServer) return
        prefs.edit().putString(THEME, themeMode).putBoolean(SERVER, hasServer).apply()
    }

    private companion object {
        const val THEME = "theme_mode"
        const val SERVER = "has_server"
    }
}

internal fun navigationSwipeProgress(totalDrag: Float, containerWidth: Int): Float =
    if (containerWidth <= 0) 0f else (abs(totalDrag) / (containerWidth.toFloat() * 0.25f)).coerceIn(0f, 1f)

internal fun shouldCommitNavigationSwipe(progress: Float): Boolean = progress > 0.4f

/** The tab a horizontal drag of [totalDrag] over a page [width] wide ends on: a neighbour past a tenth of the width, or null. */
internal fun swipeTarget(tabs: List<Tab>, open: Tab, totalDrag: Float, width: Int): Tab? {
    if (!shouldCommitNavigationSwipe(navigationSwipeProgress(totalDrag, width))) return null
    return tabs.getOrNull(tabs.indexOf(open) + if (totalDrag < 0f) 1 else -1)
}

private fun QuestionPlace.tab(): Tab = when (this) {
    QuestionPlace.Home -> Tab.Home
    QuestionPlace.Tunnel -> Tab.Tunnel
    QuestionPlace.Server -> Tab.Server
}

/**
 * The app's one shell: the header, the open tab and the bar; over them the open screen; over everything the sheets, the
 * dialogs and the short messages. Those are composed here once, outside the tabs (U6), from what [NavModel] and the
 * owners hold, so nothing the user must see or answer depends on a tab staying on screen (§4.2 rule 11).
 */
@Composable
internal fun Shell(
    hasServer: Boolean,
    headerActions: @Composable RowScope.() -> Unit,
    tabContent: @Composable (Tab) -> Unit,
) {
    val nav = LocalNav.current
    val owners = LocalOwners.current
    val density = LocalDensity.current
    val holder = rememberSaveableStateHolder()
    val screens = nav.stack.filter { it.layer == Layer.Screen }
    val screen = screens.lastOrNull()
    var barHeight by remember { mutableIntStateOf(0) }

    // a screen that was closed starts fresh the next time it is opened
    val drawn = remember { mutableSetOf<String>() }
    SideEffect {
        val open = screens.mapTo(HashSet()) { it.stateKey }
        (drawn - open).forEach { holder.removeState(it) }
        drawn.clear()
        drawn.addAll(open)
    }
    // an overlay this build has no route for (its state was saved by another build) is dropped
    nav.stack.firstOrNull { Routes.find(it.route) == null }?.let { stale -> LaunchedEffect(stale) { nav.close(stale) } }

    BackHandler(enabled = nav.canGoBack) { nav.back() }

    Box(Modifier.fillMaxSize().background(Csq.colors.bg)) {
        if (screen == null) {
            holder.SaveableStateProvider(TABS) { TabsPage(hasServer, headerActions, tabContent, barHeight) { barHeight = it } }
        } else {
            holder.SaveableStateProvider(screen.stateKey) { Routes.find(screen.route)?.invoke(screen) { nav.close(screen) } }
        }
        // 16 dp over the bar, or over a keyboard taller than it; over a screen, which has no bar, over the system's edge
        val bar = with(density) { (if (screen == null) barHeight else 0).toDp() }
        MessageHost(
            owners.messages,
            Modifier.align(Alignment.BottomCenter)
                .padding(bottom = bar)
                .consumeWindowInsets(PaddingValues(bottom = bar))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(bottom = 16.dp),
        )
        // each sheet and dialog is its own keyed group, so one stays itself, with its drafts, when the stack under or
        // around it changes (a key inside an `if` would be matched by position and rebuilt), and after a process death
        // (its key is strings: Dest.stateKey)
        for (dest in nav.stack.filter { it.layer != Layer.Screen }) {
            key(dest.stateKey) { Routes.find(dest.route)?.invoke(dest) { nav.close(dest) } }
        }
    }
}

private const val TABS = "tabs"

@Composable
private fun TabsPage(
    hasServer: Boolean,
    headerActions: @Composable RowScope.() -> Unit,
    tabContent: @Composable (Tab) -> Unit,
    barHeight: Int,
    onBarHeight: (Int) -> Unit,
) {
    val nav = LocalNav.current
    val view = LocalView.current
    val density = LocalDensity.current
    val tabs = tabsFor(hasServer, nav.tab)
    val badges = tabBadges()
    val select = { tab: Tab ->
        if (tab != nav.tab) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            nav.select(tab)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                // the bar holds the bottom of the screen: only a keyboard taller than it lifts the page (M-95)
                .consumeWindowInsets(PaddingValues(bottom = with(density) { barHeight.toDp() }))
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Header(
                version = BuildConfig.VERSION_NAME,
                modifier = Modifier.widthIn(max = 720.dp).padding(horizontal = 16.dp),
                actions = headerActions,
            )
            Box(
                Modifier.weight(1f).fillMaxWidth().pointerInput(tabs, nav.tab) {
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = { swipeTarget(tabs, nav.tab, total, size.width)?.let(select) },
                    ) { change, amount ->
                        change.consume()
                        total += amount
                    }
                },
            ) {
                tabContent(nav.tab)
            }
        }
        BottomBar(
            items = tabs.map { tab -> BarItem(tabLabel(tab), tabIcon(tab), badges[tab]) },
            selected = tabs.indexOf(nav.tab),
            onSelect = { index -> select(tabs[index]) },
            modifier = Modifier.onSizeChanged { onBarHeight(it.height) },
        )
    }
}

@Composable
private fun tabLabel(tab: Tab): String = stringResource(
    when (tab) {
        Tab.Home -> R.string.tab_home
        Tab.Tunnel -> R.string.tab_tunnel
        Tab.Server -> R.string.tab_server
        Tab.Logs -> R.string.tab_logs
        Tab.Routes -> R.string.tab_routes
    },
)

private fun tabIcon(tab: Tab) = when (tab) {
    Tab.Home -> Icons.c
    Tab.Tunnel -> Icons.key
    Tab.Server -> Icons.cloud
    Tab.Logs -> Icons.terminal
    Tab.Routes -> Icons.list
}

/**
 * The tabs' badges: a question waiting on a tab for an answer (rule 9) comes first; «Логи» counts the errors not yet
 * seen, in the accent while the tunnel runs and as a warning when it is off.
 */
@Composable
private fun tabBadges(): Map<Tab, TabBadge> {
    val questions by LocalOwners.current.questions.pending.collectAsStateWithLifecycle()
    val unread by LogBook.app.unreadErrors.collectAsStateWithLifecycle()
    val running by TunnelManager.running.collectAsStateWithLifecycle()
    val badges = HashMap<Tab, TabBadge>()
    if (unread > 0) {
        val spoken = pluralText(R.plurals.tab_logs_new_errors, unread)
        // the pill is as wide as the tab's corner lets it be: past 99 it says so, TalkBack still reads the count
        badges[Tab.Logs] = TabBadge(if (unread > 99) MANY else unread.toString(), warn = !running, description = spoken)
    }
    for (question in questions.asReversed()) {
        badges[question.place.tab()] = TabBadge(QUESTION_MARK, warn = true, description = question.title)
    }
    return badges
}

private const val QUESTION_MARK = "!"
private const val MANY = "99+"
