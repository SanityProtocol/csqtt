package com.csqtt.client

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.Toast
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest

internal object QuickTunnelAction {
    const val START = "START"
    const val STOP = "STOP"
}

/**
 * The START intent, built from one read of the store: Home, the tile and the widget start with the same values, and a
 * tap that comes before a screen's collected values arrive does not start with their defaults (B4c backlog B2).
 */
internal suspend fun buildTunnelStartIntent(
    context: Context,
    store: SettingsStore = SettingsStore(context.applicationContext),
    generationId: Long = 0L,
    sessionSalt: String = "",
): Intent? {
    val source = resolveConnectionSource(store) ?: return null
    // what the start takes of the profile is what the run keeps of it (RunRead): one reading for both
    val read = store.runRead()
    return Intent(context, TunnelService::class.java).apply {
        action = QuickTunnelAction.START
        putExtra("peer", source.peer)
        putExtra("vk_hashes", source.hashes)
        putExtra("vk_hashes_from_link", source.hashesFromLink)
        putExtra("vk_access_token", source.vkAccessToken)
        putExtra("vk_token_from_link", source.tokenFromLink)
        putExtra("connection_password", source.password)
        putRunRead(read)
        putExtra("port", 0)
        if (generationId > 0L) putExtra("generation_id", generationId)
        if (sessionSalt.isNotEmpty()) putExtra("session_salt", sessionSalt)
    }
}

/** The connection settings a start carries, read from the store in one place. */
internal data class TunnelStartSettings(
    val secondaryVkHash: String,
    val workersPerHash: Int,
    val sni: String,
    val protocol: String,
    val vkAuthMode: String,
    val captchaMode: String,
    val captchaSolveMethod: String,
    val fingerprint: String,
    val clientIds: String,
    val obfsMode: String,
    val turnTransport: String,
    val dpiSplit: DpiSplitMode,
    val srtpMode: String,
    val customTurnIps: String,
)

internal suspend fun SettingsStore.tunnelStartSettings(): TunnelStartSettings = TunnelStartSettings(
    secondaryVkHash = secondaryVkHash.first(),
    workersPerHash = workersPerHash.first(),
    sni = sni.first(),
    protocol = protocol.first(),
    vkAuthMode = vkAuthMode.first(),
    captchaMode = captchaMode.first(),
    captchaSolveMethod = captchaSolveMethod.first(),
    fingerprint = selectedFingerprint.first(),
    clientIds = activeClientIds.first(),
    obfsMode = obfsMode.first(),
    turnTransport = turnTransport.first(),
    dpiSplit = dpiSplit.first(),
    srtpMode = srtpMode.first(),
    customTurnIps = customTurnIps.first(),
)

/**
 * A profile as a start reads it (RunRead): its connection as the user set it, the settings, the hash mode. The same
 * reading is made by a START, by a restore, and — made now — by the screens that say what waits for a reconnect.
 */
internal suspend fun SettingsStore.runRead(): RunRead = RunRead(
    profile = selectedProfileIndex.first(),
    source = SourceRead(
        linkMode = csqttLinkMode.first(),
        address = peer.first(),
        port = serverPeerPort.first(),
        password = connectionPassword.first(),
        link = csqttLink.first(),
        ownHashes = vkHashes.first().split(Regex("[,\\s\\n]+")).map(String::trim).filter(String::isNotEmpty).distinct(),
        removedOwner = linkHashesRemoved.first(),
        acceptServerHashes = acceptServerHashes.first(),
    ),
    settings = tunnelStartSettings(),
    hashMode = vkHashMode.first(),
    turnSubnet = selectedTurnSubnet.first(),
    turnLimit = turnSubnetIpLimit.first(),
)

/** The profile's reading and its VK account, now and after every write of the store: what a run's record is compared with. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun SettingsStore.runReadNow(): Flow<Pair<RunRead, String>> =
    writes.conflate().mapLatest { runRead() to vkAccessToken.first() }.distinctUntilChanged()

internal fun Intent.putTunnelSettings(settings: TunnelStartSettings): Intent = apply {
    putExtra("secondary_vk_hash", settings.secondaryVkHash)
    putExtra("workers_per_hash", settings.workersPerHash)
    putExtra("sni", settings.sni)
    putExtra("protocol", settings.protocol)
    putExtra("vk_auth_mode", settings.vkAuthMode)
    putExtra("captcha_mode", settings.captchaMode)
    putExtra("captcha_solve_method", settings.captchaSolveMethod)
    putExtra("fingerprint", settings.fingerprint)
    putExtra("client_ids", settings.clientIds)
    putExtra("obfs_mode", settings.obfsMode)
    putExtra("turn_transport", settings.turnTransport)
    putExtra(DpiSplitMode.EXTRA, settings.dpiSplit.storedValue)
    putExtra("srtp_mode", settings.srtpMode)
    putExtra("custom_turn_ips", settings.customTurnIps)
}

/** The settings a START carries, as [putTunnelSettings] put them; the store's own defaults for one that carries none. */
internal fun Intent.tunnelSettings(): TunnelStartSettings = TunnelStartSettings(
    secondaryVkHash = getStringExtra("secondary_vk_hash").orEmpty(),
    workersPerHash = getIntExtra("workers_per_hash", CsqttConstants.Tunnel.DEFAULT_WORKERS),
    sni = getStringExtra("sni").orEmpty(),
    protocol = getStringExtra("protocol") ?: "udp",
    vkAuthMode = getStringExtra("vk_auth_mode") ?: CsqttConstants.VkAuth.MODE_CALLS,
    captchaMode = getStringExtra("captcha_mode") ?: "auto",
    captchaSolveMethod = getStringExtra("captcha_solve_method") ?: "auto",
    fingerprint = getStringExtra("fingerprint") ?: CsqttConstants.Tunnel.DEFAULT_FINGERPRINT,
    clientIds = getStringExtra("client_ids") ?: CsqttConstants.Tunnel.DEFAULT_CLIENT_IDS,
    obfsMode = getStringExtra("obfs_mode") ?: CsqttConstants.Tunnel.DEFAULT_OBFS_MODE,
    turnTransport = getStringExtra("turn_transport") ?: CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT,
    dpiSplit = DpiSplitMode.fromStored(getStringExtra(DpiSplitMode.EXTRA)),
    srtpMode = getStringExtra("srtp_mode") ?: CsqttConstants.SrtpMode.DEFAULT,
    customTurnIps = getStringExtra("custom_turn_ips").orEmpty(),
)

/** What a tap on the tile or the widget does (M-44). */
internal enum class QuickTap { IGNORE, STOP, OPEN_CAPTCHA, START }

/** B5h2 (B5): «Нужна капча» opens the captcha instead of stopping; the app and the notification still stop. */
internal fun quickTapFor(state: ConnectionState, stopping: Boolean): QuickTap = when {
    state is ConnectionState.Stopping || stopping -> QuickTap.IGNORE
    state is ConnectionState.CaptchaNeeded -> QuickTap.OPEN_CAPTCHA
    state.powerIsOn -> QuickTap.STOP
    else -> QuickTap.START
}

/** [open] starts an activity the way its caller may: the tile collapses the shade, the widget sends a PendingIntent. */
internal suspend fun toggleTunnelFromQuickAction(context: Context, open: (Intent) -> Unit) {
    when (quickTapFor(TunnelManager.currentConnectionState(), TunnelManager.stopping.value)) {
        QuickTap.IGNORE -> return
        QuickTap.OPEN_CAPTCHA -> {
            open(ManlCaptchaWebViewManager.promptIntent(context))
            return
        }
        QuickTap.STOP -> {
            context.startService(Intent(context, TunnelService::class.java).apply { action = QuickTunnelAction.STOP })
            return
        }
        QuickTap.START -> Unit
    }
    val openMain = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (VpnService.prepare(context) != null) {
        context.showRaisedToast("Откройте CSQTT и выдайте VPN-разрешение", Toast.LENGTH_LONG)
        open(openMain)
        return
    }
    val startIntent = buildTunnelStartIntent(context)
    if (startIntent == null) {
        context.showRaisedToast("Заполните настройки подключения в CSQTT", Toast.LENGTH_LONG)
        open(openMain)
        return
    }
    context.startForegroundService(startIntent)
}
