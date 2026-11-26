package com.csqtt.client

import android.content.Context
import android.content.Intent

/**
 * What Home tells or asks beside the state itself (APP-PLAN §4.2 rule 9, P3-PLAN §1.3a). One list for both layouts: each
 * draws every kind in its own way, and neither can leave one out, as each handles the kinds by an exhaustive `when`.
 */
internal sealed interface HomeNotice {
    /** Notifications are off: the status and the captcha prompt cannot show (L-29). */
    data object NotificationsOff : HomeNotice

    /**
     * The run ended by itself, with its [cause]. [vkSignIn]: a new sign-in to VK is the way out. [appSettings]: the phone
     * ended it, and its reasons are on the app's page of Android's settings.
     */
    data class Stopped(val cause: String, val vkSignIn: Boolean, val appSettings: Boolean) : HomeNotice

    /** VK asks for a check before the run can go on. */
    data object Captcha : HomeNotice

    /** The Wi-Fi the run is paused on, by its name. */
    data class PausedOn(val network: String) : HomeNotice

    /** A list rule on a Wi-Fi whose name Android does not give: the pause cannot act. */
    data object WifiNameUnknown : HomeNotice

    /** A network the lists do not know yet: remember it? */
    data class WifiOffer(val network: String, val rule: WifiRule) : HomeNotice

    /** A profile that waits for its csqtt:// link (D28): nothing to connect to yet. */
    data object NeedLink : HomeNotice

    /** The last outcome of a background operation the user relies on (B5h2): [bad] failed, [warn] skipped something. */
    data class Outcome(val text: String, val bad: Boolean, val warn: Boolean) : HomeNotice
}

internal fun homeNotices(
    state: ConnectionState,
    notificationsEnabled: Boolean,
    wifi: WifiStatus?,
    /** The open profile takes a csqtt:// link and has none that reads. */
    linkMissing: Boolean,
    subscription: SubscriptionMeta?,
    handOver: HandOverResult?,
): List<HomeNotice> = buildList {
    if (!notificationsEnabled) add(HomeNotice.NotificationsOff)
    if (state is ConnectionState.Failed) {
        add(
            HomeNotice.Stopped(
                cause = state.reason,
                vkSignIn = state.reason == StopCause.VK_SIGN_IN_AGAIN,
                appSettings = state.reason == StopCause.SYSTEM_KILLED || state.reason == StopCause.STOPPED_OUTSIDE,
            ),
        )
    }
    if (state is ConnectionState.CaptchaNeeded) add(HomeNotice.Captcha)
    if (wifi != null) {
        if (state is ConnectionState.PausedOnWifi) wifi.name?.let { add(HomeNotice.PausedOn(it)) }
        if (wifi.nameUnknown && state.powerIsOn) add(HomeNotice.WifiNameUnknown)
        wifi.offer?.let { add(HomeNotice.WifiOffer(it, wifi.rule)) }
    }
    // only while nothing runs: a run on another profile is not this profile's missing link
    if (linkMissing && !state.powerIsOn && state !is ConnectionState.Stopping) add(HomeNotice.NeedLink)
    subscription?.let { meta ->
        subscriptionNoticeLine(meta)?.let { add(HomeNotice.Outcome(it, bad = meta.lastError.isNotEmpty(), warn = meta.lastError.isEmpty())) }
    }
    handOver?.let { add(HomeNotice.Outcome(handOverNoticeLine(it), bad = !it.ok, warn = false)) }
}

/** A tap's start and stop of the tunnel service (Home's C); what refused a start goes to the log. */
internal object TunnelStart {
    enum class Result { Started, NothingToStart, Refused }

    suspend fun start(context: Context, store: SettingsStore): Result {
        val intent = buildTunnelStartIntent(context, store, System.currentTimeMillis() / 1000L, newTunnelSessionId())
            ?: return Result.NothingToStart
        return runCatching { context.startForegroundService(intent) }.fold(
            onSuccess = { Result.Started },
            onFailure = { error ->
                TunnelManager.updateLog(
                    "foreground_request_error",
                    "Android заблокировал запуск VPN: ${error.message ?: error.javaClass.simpleName}",
                    99,
                    true,
                )
                Result.Refused
            },
        )
    }

    fun stop(context: Context) {
        context.startService(Intent(context, TunnelService::class.java).apply { action = "STOP" })
    }

    fun logBlocked(blocker: ConnectionStartBlocker) {
        TunnelManager.updateLog(
            "connection_configuration_${blocker.name}",
            "[ПОДКЛЮЧЕНИЕ] Запуск не начат: ${blocker.message}",
            99,
            true,
        )
    }
}
