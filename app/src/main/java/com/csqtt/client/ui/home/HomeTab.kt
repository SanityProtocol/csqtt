package com.csqtt.client.ui.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.csqtt.client.ConnectionStartBlocker
import com.csqtt.client.ConnectionStartPolicy
import com.csqtt.client.ConnectionState
import com.csqtt.client.ConnectionVisuals
import com.csqtt.client.CsqttConstants
import com.csqtt.client.HomeLayout
import com.csqtt.client.HomeNotice
import com.csqtt.client.ManlCaptchaWebViewManager
import com.csqtt.client.R
import com.csqtt.client.SettingsStore
import com.csqtt.client.StartStage
import com.csqtt.client.StoredSecretState
import com.csqtt.client.Tap
import com.csqtt.client.TunnelAuthSnapshot
import com.csqtt.client.TunnelManager
import com.csqtt.client.TunnelStart
import com.csqtt.client.VkHashValidationCodec
import com.csqtt.client.WifiRule
import com.csqtt.client.connectionVisuals
import com.csqtt.client.homeNotices
import com.csqtt.client.peerIsLocal
import com.csqtt.client.startStage
import com.csqtt.client.ui.components.IconBtn
import com.csqtt.client.ui.design.Icons
import com.csqtt.client.ui.legacy.utils.parseCsqttLink
import com.csqtt.client.ui.shell.LocalNav
import com.csqtt.client.ui.shell.LocalOwners
import com.csqtt.client.ui.shell.LocalStore
import com.csqtt.client.ui.shell.Routes
import com.csqtt.client.ui.shell.Tab
import com.csqtt.client.ui.tunnel.openVkSignIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Set by HomeLayoutSurvivesTest only: told the layout of every composition of Home, the first one included. */
@VisibleForTesting
internal var homeLayoutProbe: ((HomeLayout) -> Unit)? = null

/** What Home's two layouts are given: the same state, the same notices and the same actions (P3-PLAN §1.3a). */
internal class HomeView(
    val state: ConnectionState,
    val visuals: ConnectionVisuals,
    val stage: StartStage?,
    val notices: List<HomeNotice>,
    val actions: HomeActions,
    /**
     * Home shows a change as motion (HomeMotion); false, it is drawn at once. True once Home was drawn from what the
     * store holds — its first frames are drawn from values not read yet, no hint and no cards, and what those turn into
     * is no event, so nothing glides in each time Home is opened (the review's F4) — and never on a phone that is low on
     * memory ([HomeMotion.wanted]).
     */
    val moves: Boolean,
)

internal class HomeActions(
    val tap: () -> Unit,
    val openCaptcha: () -> Unit,
    val openLogs: () -> Unit,
    val openTunnel: () -> Unit,
    val addProfile: () -> Unit,
    val reviewCertificate: (String) -> Unit,
    val vkSignIn: () -> Unit,
    val appSettings: () -> Unit,
    val notificationSettings: () -> Unit,
    val wifiLater: (String) -> Unit,
    val wifiRemember: (String, WifiRule) -> Unit,
)

/**
 * «Главная»: the connection, in the layout the user chose. Everything the two layouts show comes from here, so they
 * differ in how they draw and in nothing else.
 */
@Composable
internal fun HomeTab(
    auth: TunnelAuthSnapshot,
    layout: HomeLayout,
    onInvalidConfiguration: () -> Unit,
) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val state by TunnelManager.connectionState.collectAsStateWithLifecycle()
    val cooldown = rememberWaitAfterStop(state)
    val progress by TunnelManager.startProgress.collectAsStateWithLifecycle()
    val tunconf by TunnelManager.tunconfHere.collectAsStateWithLifecycle()
    val wifi by TunnelManager.wifiStatus.collectAsStateWithLifecycle()
    val subscription by store.subscriptionNotice.collectAsStateWithLifecycle(initialValue = null)
    val handOver by store.handOverNotice.collectAsStateWithLifecycle(initialValue = null)
    val start = rememberHomeStart(auth, onInvalidConfiguration)
    val notifications = rememberNotificationsEnabled()
    SideEffect { homeLayoutProbe?.invoke(layout) }
    // true from the frame after the one that was drawn from the stored values
    var settled by remember { mutableStateOf(false) }
    if (start.loaded && !settled) SideEffect { settled = true }
    val motion = remember(context) { HomeMotion.wanted(context) }

    val view = HomeView(
        state = state,
        visuals = connectionVisuals(state, cooldown, configurationValid = start.blocker == null),
        stage = startStage(state, progress, tunconf),
        notices = homeNotices(state, notifications, wifi, start.linkMissing, subscription, handOver),
        actions = HomeActions(
            tap = start.tap,
            openCaptcha = { openCaptcha(context) },
            openLogs = { nav.select(Tab.Logs) },
            openTunnel = { nav.select(Tab.Tunnel) },
            addProfile = { nav.open(Routes.addProfile()) },
            reviewCertificate = { url -> nav.open(Routes.subCert(url)) },
            vkSignIn = { openVkSignIn(nav, auth.profile) },
            appSettings = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                    )
                }
            },
            notificationSettings = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
            // on the app's scope: the answer is stored even if the screen goes meanwhile (§4.2 rule 8)
            wifiLater = { name -> TunnelManager.scope.launch { store.dismissWifiNetwork(name) } },
            wifiRemember = { name, rule -> TunnelManager.scope.launch { store.rememberWifiNetwork(name, rule) } },
        ),
        moves = settled && motion,
    )
    when (layout) {
        HomeLayout.One -> HomeOne(view)
        HomeLayout.List -> HomeList(view)
    }
}

private fun openCaptcha(context: Context) {
    runCatching { context.startActivity(ManlCaptchaWebViewManager.promptIntent(context)) }
}

/** How long a run that was seen to end counts as waiting when no wait is seen to follow it ([rememberWaitAfterStop]). */
private const val WAIT_BRIDGE_MS = 150L

/**
 * The short wait after a stop, as Home shows it ([TunnelManager.cooldownActive], which the shell starts from an effect).
 * That effect runs a frame after the run was seen to end, and the frame between read «Отключено», with its own disc and
 * hint, between two «Остановка…»: a flicker on every disconnect. A run seen to end counts as waiting from that very
 * frame, until the wait is seen or [WAIT_BRIDGE_MS] have passed — a run that ends with no wait after it (given up before
 * its process started) reads «Остановка…» that much longer.
 */
@Composable
private fun rememberWaitAfterStop(state: ConnectionState): Boolean {
    val waiting by TunnelManager.cooldownActive.collectAsStateWithLifecycle()
    // plain holders, not states: they are read and written in this one composition, which a change of [state] or of
    // [waiting] brings about anyway
    val before = remember { Ref<ConnectionState>() }
    val bridge = remember { booleanArrayOf(false) }
    var bridgeOver by remember { mutableIntStateOf(0) }
    val was = before.value
    before.value = state
    val idle = state is ConnectionState.Idle
    if (idle && was != null && (was.powerIsOn || was is ConnectionState.Stopping) && was !is ConnectionState.PausedOnWifi) bridge[0] = true
    if (waiting || !idle) bridge[0] = false
    val bridging = bridge[0]
    LaunchedEffect(bridging) {
        if (bridging) {
            delay(WAIT_BRIDGE_MS)
            bridge[0] = false
            bridgeOver++
        }
    }
    // read, so that the bridge ends by a composition of its own when nothing else changes
    bridgeOver
    return waiting || bridging
}

/** Notifications off: the status and the captcha prompt cannot show (L-29). Asked again on each return to the screen. */
@Composable
private fun rememberNotificationsEnabled(): Boolean {
    val context = LocalContext.current
    // asked for the first frame too: started as «on», the card of a user who has them off came in on every opening
    var enabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }
    return enabled
}

/**
 * The header's switch between Home's two layouts, on «Главная» only, before the gear: its icon shows the layout a tap
 * gives (the operator, 2026-11-26).
 */
@Composable
internal fun HomeLayoutSwitch(layout: HomeLayout) {
    val store = LocalStore.current
    IconBtn(
        icon = if (layout == HomeLayout.One) Icons.viewList else Icons.viewOne,
        description = stringResource(if (layout == HomeLayout.One) R.string.home_layout_to_list else R.string.home_layout_to_one),
        // on the app's scope: the choice is stored even if the screen goes the same moment
        onClick = { TunnelManager.scope.launch { store.saveHomeLayout(layout.other) } },
    )
}

/**
 * What a tap on the C needs, and what Home says of it: whether the profile can start ([blocker] null), and whether what
 * it lacks is its link ([linkMissing]; a start would name the hashes first, which a missing link leaves it without).
 */
internal class HomeStart(
    val blocker: ConnectionStartBlocker?,
    val linkMissing: Boolean,
    /** The profile's settings were read from the store: [blocker] speaks of them, not of values not given yet. */
    val loaded: Boolean,
    val tap: () -> Unit,
)

/**
 * The C's tap: stop, the captcha, or a start — the profile's settings checked, Android's notifications and VPN questions
 * asked, then the service started. A start asked for before the VPN question is still wanted when the screen was made
 * again while Android asked (U4): it is saved state, not a field of this composition.
 */
@Composable
private fun rememberHomeStart(auth: TunnelAuthSnapshot, onInvalidConfiguration: () -> Unit): HomeStart {
    val context = LocalContext.current.applicationContext
    val store = LocalStore.current
    val messages = LocalOwners.current.messages
    val scope = rememberCoroutineScope()
    val invalid by rememberUpdatedState(onInvalidConfiguration)

    // null until the store has emitted: a tap before that starts nothing (§4.2 rule 3)
    val storedPeer by store.peer.collectAsStateWithLifecycle(initialValue = null)
    val vkHashes by store.vkHashes.collectAsStateWithLifecycle(initialValue = "")
    val checkResults by store.vkHashCheckResults.collectAsStateWithLifecycle(initialValue = "{}")
    val manualPorts by store.manualPortsEnabled.collectAsStateWithLifecycle(initialValue = false)
    val peerPort by store.serverPeerPort.collectAsStateWithLifecycle(initialValue = CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT)
    val vkAuthMode by store.vkAuthMode.collectAsStateWithLifecycle(initialValue = CsqttConstants.VkAuth.MODE_CALLS)
    val hashMode by store.vkHashMode.collectAsStateWithLifecycle(initialValue = SettingsStore.cachedVkHashMode)
    val vkToken by store.vkAccessToken.collectAsStateWithLifecycle(initialValue = SettingsStore.cachedVkAccessToken)
    val linkHashesRemoved by store.linkHashesRemoved.collectAsStateWithLifecycle(initialValue = emptySet())

    val link = remember(auth.csqttLink) { parseCsqttLink(auth.csqttLink) }
    // the owner's hashes still in use: the ones the participant took out do not count
    val linkHashes = link?.hashes.orEmpty().filterNot { it in linkHashesRemoved }
    val typedHashes = remember(vkHashes) {
        vkHashes.split(Regex("[,\\s\\n]+")).map(String::trim).filter { it.length >= 16 }.distinct()
    }
    val liveHashes = remember(vkHashes, checkResults) {
        VkHashValidationCodec.active(typedHashes, VkHashValidationCodec.decode(checkResults))
    }
    val hashSettingsLoaded = hashMode != null && vkToken != null
    val autoHashes = vkAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS ||
        (hashMode != null && hashMode != CsqttConstants.VkAutoHash.MODE_MANUAL)
    val hashesReady = hashSettingsLoaded && ConnectionStartPolicy.hasReadyVkCredentials(
        linkMode = auth.csqttLinkMode,
        linkHashCount = linkHashes.size,
        linkTokenPresent = auth.csqttLinkMode && link?.vkAccessToken?.isNotEmpty() == true,
        autoHashMode = autoHashes,
        storedTokenPresent = vkToken?.isNotBlank() == true,
        manualHashCount = liveHashes.ifEmpty { typedHashes }.size,
    )
    val peer = storedPeer.orEmpty().trim()
    val blocker = ConnectionStartPolicy.blocker(
        linkMode = auth.csqttLinkMode,
        linkValid = link != null,
        peerValid = peer.isNotBlank() && !peer.contains(":") && !peerIsLocal(peer),
        peerPortValid = !manualPorts || peerPort in 1..65535,
        connectionPasswordSet = auth.connectionPassword.isNotBlank(),
        connectionPasswordReadable = auth.connectionPasswordState != StoredSecretState.Unreadable,
        hashesReady = hashesReady,
    )
    val loaded = storedPeer != null && hashSettingsLoaded && auth.isLoaded

    val fillSettings = stringResource(R.string.home_fill_settings)
    val startRefused = stringResource(R.string.home_start_refused)
    val vpnDenied = stringResource(R.string.home_vpn_denied)
    val passwordReset = stringResource(R.string.home_password_reset)

    // on the app's scope: a start asked for goes out even if the tab is left the same moment (§4.2 rule 8)
    val startService = {
        TunnelManager.scope.launch(Dispatchers.Main) {
            when (TunnelStart.start(context, store)) {
                TunnelStart.Result.Started -> Unit
                TunnelStart.Result.NothingToStart -> messages.show(fillSettings)
                TunnelStart.Result.Refused -> messages.show(startRefused)
            }
        }
        Unit
    }

    var startAfterVpnConsent by rememberSaveable { mutableStateOf(false) }
    val vpnConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (startAfterVpnConsent) {
            startAfterVpnConsent = false
            if (VpnService.prepare(context) == null) startService() else messages.show(vpnDenied)
        }
    }
    val askVpnAndStart = {
        val consent = VpnService.prepare(context)
        if (consent != null) {
            startAfterVpnConsent = true
            vpnConsent.launch(consent)
        } else {
            startService()
        }
    }
    // P3 §1.9: the one runtime permission is asked by Android itself at a connect, right before the VPN consent, with
    // no dialog of ours before it. Whatever the answer, the start goes on: refused, the screen says what is lost
    val notificationsAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { askVpnAndStart() }

    val tap: () -> Unit = tap@{
        val state = TunnelManager.currentConnectionState()
        if (TunnelManager.stopping.value) return@tap
        when (connectionVisuals(state, TunnelManager.cooldownActive.value, configurationValid = blocker == null).tap) {
            Tap.Nothing -> Unit
            Tap.Captcha -> openCaptcha(context)
            Tap.Stop -> {
                startAfterVpnConsent = false
                TunnelStart.stop(context)
            }
            Tap.Start -> {
                if (!loaded) return@tap
                if (blocker != null) {
                    TunnelStart.logBlocked(blocker)
                    if (blocker == ConnectionStartBlocker.StoredSecret) {
                        scope.launch {
                            if (store.resetUnreadableConnectionPassword()) messages.show(passwordReset)
                            invalid()
                        }
                    } else {
                        invalid()
                    }
                    return@tap
                }
                val notificationsMissing = Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                if (notificationsMissing) notificationsAsk.launch(Manifest.permission.POST_NOTIFICATIONS) else askVpnAndStart()
            }
        }
    }
    return HomeStart(blocker, linkMissing = auth.isLoaded && auth.csqttLinkMode && link == null, loaded = loaded, tap = tap)
}
