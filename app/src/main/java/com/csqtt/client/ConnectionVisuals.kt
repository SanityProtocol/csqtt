package com.csqtt.client

import androidx.annotation.StringRes

/** How the C of Home's list is drawn (the boards' F-HomeStates): off, at work, on, waiting with nothing to do, asking. */
internal enum class Disc { Off, Busy, On, Dim, Warn }

/** The colour of the status in «Одно подключение», the classic panel's own (P3-PLAN §1.3a). */
internal enum class StatusTone { Accent, Warn, Danger, Muted }

/** What a tap on the C does. */
internal enum class Tap { Start, Stop, Captcha, Nothing }

/**
 * How Home says and shows a connection state (APP-PLAN §4.3, P3-PLAN §1.3): the word, the C of both layouts, the hint
 * under the status and what a tap does. One mapping, [connectionVisuals], for both layouts; the word is the state's own
 * [ConnectionState.label], which the notification, the tile and the widget show too.
 */
internal data class ConnectionVisuals(
    val label: String,
    val disc: Disc,
    val status: StatusTone,
    @StringRes val hint: Int?,
    val tap: Tap,
)

/** The word of the short wait after a stop, before the next start is taken. */
internal const val LABEL_COOLDOWN = "Ожидание"

/**
 * [cooldown]: the short wait after a stop is on. [configurationValid]: the profile has what a start needs; when it has
 * not, a tap leads to the settings, so it is never refused.
 */
internal fun connectionVisuals(
    state: ConnectionState,
    cooldown: Boolean = false,
    configurationValid: Boolean = true,
): ConnectionVisuals {
    // a start is refused only during the wait, and only when it would be a start
    val start = if (cooldown && configurationValid) Tap.Nothing else Tap.Start
    return when (state) {
        ConnectionState.Idle -> if (cooldown) {
            ConnectionVisuals(LABEL_COOLDOWN, Disc.Dim, StatusTone.Warn, null, start)
        } else {
            // the hint only where a tap connects: with settings missing, the card under the button says what to do
            ConnectionVisuals(state.label, Disc.Off, StatusTone.Muted, R.string.home_hint_start.takeIf { configurationValid }, start)
        }
        is ConnectionState.Failed -> ConnectionVisuals(state.label, Disc.Off, StatusTone.Danger, null, start)
        ConnectionState.Preparing, ConnectionState.Connecting ->
            ConnectionVisuals(state.label, Disc.Busy, StatusTone.Accent, null, Tap.Stop)
        is ConnectionState.Connected -> ConnectionVisuals(state.label, Disc.On, StatusTone.Accent, null, Tap.Stop)
        ConnectionState.Reconnecting -> ConnectionVisuals(state.label, Disc.Busy, StatusTone.Warn, null, Tap.Stop)
        ConnectionState.ServerNotResponding -> ConnectionVisuals(state.label, Disc.Busy, StatusTone.Danger, null, Tap.Stop)
        ConnectionState.WaitingForNetwork -> ConnectionVisuals(state.label, Disc.Dim, StatusTone.Warn, null, Tap.Stop)
        ConnectionState.PausedOnWifi ->
            ConnectionVisuals(state.label, Disc.Warn, StatusTone.Warn, R.string.home_hint_paused, Tap.Stop)
        ConnectionState.Stopping -> ConnectionVisuals(state.label, Disc.Dim, StatusTone.Warn, null, Tap.Nothing)
        // the C opens the captcha, it never stops the run that waits for it: «Отключить» is in the notification
        ConnectionState.CaptchaNeeded ->
            ConnectionVisuals(state.label, Disc.Warn, StatusTone.Danger, R.string.home_hint_captcha, Tap.Captcha)
    }
}

/**
 * The count of streams, said only while it is news (the operator, 2026-11-26): while not every stream the core runs is
 * up, and for [HOLD_MS] after the last one came. Never for a tunnel at rest, whose count is no news (§1.9).
 */
internal object StreamsLine {
    const val HOLD_MS = 2_000L

    /**
     * The states in which streams are being made or carry traffic. Not while a captcha is waited for: no stream can
     * come before it is solved, and the state says why.
     */
    fun applies(state: ConnectionState): Boolean = when (state) {
        ConnectionState.Preparing, ConnectionState.Connecting, is ConnectionState.Connected, ConnectionState.Reconnecting,
        ConnectionState.ServerNotResponding -> true
        else -> false
    }

    /** The count the core runs: the profile's own, or the server's limit when that is lower. */
    fun running(target: Int, serverLimit: Int?): Int =
        if (serverLimit != null && serverLimit in 1 until target) serverLimit else target

    /**
     * When the count last became full, on the caller's clock: [previous] while it stays full, [nowMs] the moment it
     * becomes full, null while a stream is missing.
     */
    fun fullSince(previous: Long?, active: Int, running: Int, nowMs: Long): Long? =
        if (running <= 0 || active < running) null else previous ?: nowMs

    fun shown(state: ConnectionState, dormant: Boolean, active: Int, running: Int, fullSince: Long?, nowMs: Long): Boolean =
        holdLeft(state, dormant, active, running, fullSince, nowMs) != null

    /**
     * Null: not shown. 0: shown until a stream comes or the run ends. Above 0: every stream is up and the line goes after
     * that many milliseconds.
     */
    fun holdLeft(state: ConnectionState, dormant: Boolean, active: Int, running: Int, fullSince: Long?, nowMs: Long): Long? {
        if (!applies(state) || dormant || running <= 0) return null
        if (active < running) return 0L
        val left = (fullSince ?: return null) + HOLD_MS - nowMs
        return left.takeIf { it > 0 }
    }
}

/** The count Home says: the core's last report and the [readySince] streams ready after it, never above what it runs. */
internal fun streamsShown(active: Int, readySince: Int, running: Int): Int =
    if (running > 0) minOf(active + readySince, running) else active

/** What a start is doing, as Home says it under «Подключение…» (the operator, 2026-11-26). */
internal enum class StartStage(@StringRes val text: Int) {
    VkAccess(R.string.home_stage_vk),
    Server(R.string.home_stage_server),
    Vpn(R.string.home_stage_vpn),
}

/**
 * The stage of the start [state] is in, null when it is not a start. [progress] is the running process's
 * ([TunnelManager.startProgress]; none yet while the service prepares), [tunconf] whether it sent its TUNCONF: with a
 * stream ready or the TUNCONF here, only the VPN interface is left.
 */
internal fun startStage(state: ConnectionState, progress: TunnelStartupStage?, tunconf: Boolean): StartStage? = when {
    state != ConnectionState.Preparing && state != ConnectionState.Connecting -> null
    progress == TunnelStartupStage.READY || tunconf -> StartStage.Vpn
    progress == TunnelStartupStage.WAITING_FOR_TURN_OR_PEER -> StartStage.Server
    else -> StartStage.VkAccess
}
