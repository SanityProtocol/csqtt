package com.csqtt.client

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.Toast
import kotlinx.coroutines.flow.first

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
    return Intent(context, TunnelService::class.java).apply {
        action = QuickTunnelAction.START
        putExtra("peer", source.peer)
        putExtra("vk_hashes", source.hashes)
        putExtra("vk_hashes_from_link", source.hashesFromLink)
        putExtra("vk_access_token", source.vkAccessToken)
        putExtra("vk_token_from_link", source.tokenFromLink)
        putExtra("connection_password", source.password)
        putTunnelSettings(store.tunnelStartSettings())
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

internal suspend fun toggleTunnelFromQuickAction(context: Context, openMain: () -> Unit) {
    val state = TunnelManager.currentConnectionState()
    if (state is ConnectionState.Stopping || TunnelManager.stopping.value) return
    if (state.powerIsOn) {
        context.startService(Intent(context, TunnelService::class.java).apply { action = QuickTunnelAction.STOP })
        return
    }
    if (VpnService.prepare(context) != null) {
        context.showRaisedToast("Откройте CSQTT и выдайте VPN-разрешение", Toast.LENGTH_LONG)
        openMain()
        return
    }
    val startIntent = buildTunnelStartIntent(context)
    if (startIntent == null) {
        context.showRaisedToast("Заполните настройки подключения в CSQTT", Toast.LENGTH_LONG)
        openMain()
        return
    }
    context.startForegroundService(startIntent)
}
