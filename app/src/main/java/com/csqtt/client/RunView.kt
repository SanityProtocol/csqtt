package com.csqtt.client

import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * What the run uses of the settings a screen shows, to be said beside a choice it has not taken («Сейчас: TCP»).
 * Nothing here is a secret: a password, a link and a token are compared, never shown.
 */
internal data class RunUses(
    val address: String,
    val port: Int,
    val hashMode: String,
    /** The creds mode the run is in. */
    val authMode: String,
    val captchaMode: String,
    val captchaSolve: String,
    val srtp: String,
    val transport: String,
    /** The DPI level the run splits with: off over UDP, whatever the profile held. */
    val dpi: DpiSplitMode,
    val streams: Int,
    val fingerprint: String,
    val turnSubnet: String,
    val turnLimit: String,
    val acceptServerHashes: Boolean,
    /** The run has a VK account, and its id among the signed-in ones ("" for a token pasted by hand). */
    val hasAccount: Boolean,
    val accountId: String,
) {
    companion object {
        fun of(taken: RunTaken, accountId: String): RunUses {
            val read = taken.read
            val settings = read.settings
            return RunUses(
                address = read.source.address,
                port = read.source.port,
                hashMode = read.hashMode,
                authMode = taken.authMode,
                captchaMode = settings.captchaMode,
                captchaSolve = settings.captchaSolveMethod,
                srtp = settings.srtpMode,
                transport = settings.turnTransport,
                dpi = RunPending.dpiInUse(settings.turnTransport, settings.dpiSplit),
                streams = settings.workersPerHash,
                fingerprint = settings.fingerprint,
                turnSubnet = read.turnSubnet,
                turnLimit = read.turnLimit,
                acceptServerHashes = read.source.acceptServerHashes,
                hasAccount = taken.account.isNotBlank(),
                accountId = accountId,
            )
        }
    }
}

/**
 * The run as the screens see it (card p3-apply-bar): the profile it is on, what of its settings waits for a reconnect
 * ([keys]) and what it uses of them ([uses]). One state for the bar over the tabs, for «Сейчас: …» under a changed
 * control and for whatever asks «is the tunnel on for this profile» — read from the manager's published run, so it
 * follows the run's profile whenever that changes, not only when the tunnel turns on or starts.
 */
internal data class RunView(
    val profile: Int,
    val keys: Set<RunKey>,
    val uses: RunUses,
    /** The run is on: not stopping, not paused on Wi-Fi (its resume reads the store by itself). */
    val on: Boolean,
    /** «Переподключить» was tapped; the next connection has not taken over yet. */
    val reconnecting: Boolean,
) {
    /** The bar is up: something waits for a reconnect that can be offered. */
    val offered: Boolean get() = on && keys.isNotEmpty()

    /** [key] waits for a reconnect on [profile]'s screen. */
    fun waits(profile: Int, key: RunKey): Boolean = profile == this.profile && key in keys
}

/** What a run that is on or not, with [keys] waiting, shows: [RunView.offered] in one place for its tests. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun runViewFlow(context: Context): Flow<RunView?> {
    val app = context.applicationContext
    val stored = TunnelManager.taken.flatMapLatest { taken ->
        if (taken == null) {
            flowOf(null)
        } else {
            val store = SettingsStore(app, ProfileScope.Fixed(taken.read.profile))
            store.runReadNow().map { (now, account) ->
                val keys = RunPending.of(taken, now, account)
                // the account's id is looked up only where a screen will say it
                Triple(taken, keys, if (RunKey.ACCOUNT in keys) store.vkAccountIdOf(taken.account) else "")
            }
        }
    }
    val runOn = combine(
        TunnelManager.running, TunnelManager.starting, TunnelManager.stopping, TunnelManager.autoPausedForWifi,
    ) { running, starting, stopping, paused -> ProfileSwitch.runIsOn(running, starting, stopping, paused) }
    return combine(stored, runOn, TunnelManager.reconnecting) { side, on, reconnecting ->
        side?.let { (taken, keys, accountId) -> RunView(taken.read.profile, keys, RunUses.of(taken, accountId), on, reconnecting) }
    }.distinctUntilChanged()
}

/** What a tap on «Переподключить» came to. */
internal sealed class ReconnectTap {
    /** The run reconnects with what its profile holds. */
    data object Reconnecting : ReconnectTap()

    /** The profile's settings would refuse a start: the tunnel is left as it is, and [profile]'s form says what is missing. */
    data class Blocked(val profile: Int, val blocker: ConnectionStartBlocker) : ReconnectTap()

    /** No run to reconnect any more. */
    data object Nothing : ReconnectTap()
}

/**
 * «Переподключить» (the operator, 2026-11-28: «Keep the tunnel, show what is missing»): the tap first asks what a start
 * asks of the profile's settings. With a start that would be refused the running tunnel is not touched — a reconnect
 * onto settings that cannot start ends with the tunnel off. [blocker] reads the run's profile; [reconnect] is
 * [reconnectRun].
 */
internal suspend fun reconnectTap(
    taken: RunTaken?,
    blocker: suspend (profile: Int) -> ConnectionStartBlocker?,
    reconnect: () -> Boolean,
): ReconnectTap {
    val profile = taken?.read?.profile ?: return ReconnectTap.Nothing
    val refused = blocker(profile)
    if (refused != null) return ReconnectTap.Blocked(profile, refused)
    return if (reconnect()) ReconnectTap.Reconnecting else ReconnectTap.Nothing
}

/**
 * What a start checks of a profile's settings, as stored: Home's tap and «Переподключить» ask the same question
 * ([startBlocker]).
 */
internal data class StartCheck(
    val linkMode: Boolean,
    val link: String,
    val peer: String,
    val manualPorts: Boolean,
    val peerPort: Int,
    val password: String,
    val passwordReadable: Boolean,
    val ownHashes: String,
    val hashChecks: String,
    val authMode: String,
    val hashMode: String,
    val tokenPresent: Boolean,
    val removedOwner: Set<String>,
)

/** Why a start of a profile that holds [check] would be refused; null when it would not. */
internal fun startBlocker(check: StartCheck): ConnectionStartBlocker? {
    val link = com.csqtt.client.ui.legacy.utils.parseCsqttLink(check.link)
    // the owner's hashes still in use: the ones the participant took out do not count
    val linkHashes = link?.hashes.orEmpty().filterNot { it in check.removedOwner }
    val typedHashes = check.ownHashes.split(Regex("[,\\s\\n]+")).map(String::trim).filter { it.length >= 16 }.distinct()
    val liveHashes = VkHashValidationCodec.active(typedHashes, VkHashValidationCodec.decode(check.hashChecks))
    val autoHashes = check.authMode == CsqttConstants.VkAuth.MODE_AUTO_JS || check.hashMode != CsqttConstants.VkAutoHash.MODE_MANUAL
    val hashesReady = ConnectionStartPolicy.hasReadyVkCredentials(
        linkMode = check.linkMode,
        linkHashCount = linkHashes.size,
        linkTokenPresent = check.linkMode && link?.vkAccessToken?.isNotEmpty() == true,
        autoHashMode = autoHashes,
        storedTokenPresent = check.tokenPresent,
        manualHashCount = liveHashes.ifEmpty { typedHashes }.size,
    )
    val peer = check.peer.trim()
    return ConnectionStartPolicy.blocker(
        linkMode = check.linkMode,
        linkValid = link != null,
        peerValid = peer.isNotBlank() && !peer.contains(":") && !peerIsLocal(peer),
        peerPortValid = !check.manualPorts || check.peerPort in 1..65535,
        connectionPasswordSet = check.password.isNotBlank(),
        connectionPasswordReadable = check.passwordReadable,
        hashesReady = hashesReady,
    )
}

/** [startBlocker] of the store's profile, read now. */
internal suspend fun SettingsStore.startBlocker(): ConnectionStartBlocker? {
    val password = connectionPasswordState.first()
    return startBlocker(
        StartCheck(
            linkMode = csqttLinkMode.first(),
            link = csqttLink.first(),
            peer = peer.first(),
            manualPorts = manualPortsEnabled.first(),
            peerPort = serverPeerPort.first(),
            password = password.value,
            passwordReadable = password.state != StoredSecretState.Unreadable,
            ownHashes = vkHashes.first(),
            hashChecks = vkHashCheckResults.first(),
            authMode = vkAuthMode.first(),
            hashMode = vkHashMode.first(),
            tokenPresent = vkAccessToken.first().isNotBlank(),
            removedOwner = linkHashesRemoved.first(),
        ),
    )
}
