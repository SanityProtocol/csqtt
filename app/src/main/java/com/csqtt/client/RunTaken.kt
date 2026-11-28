package com.csqtt.client

import android.content.Intent

/*
 * Card p3-apply-bar (the operator, 2026-11-28: «Bar with «Переподключить»», then «That bar - for every change that
 * require a reconnect»). A run takes its settings when it starts and keeps them: the core's command line is made once,
 * and the restarts the run makes itself start from the same values (RunKeeps). So a setting changed while the tunnel
 * is on is for the next connection — and a control that changes and shows nothing is read as applied. For the screens
 * to say so at the moment of the change, whichever screen made it, a run keeps what its start read of the user's
 * stored values (RunRead), and what is stored now is compared with it (RunPending).
 *
 * The one run that does not keep them all: a run on an old link's own VK token. Its call recovery reads the link, the
 * stored hash mode and the stream count again (RunKeeps.recoveryBasis, TunnelService.recoverUnavailableVkCall), and
 * the record does not follow it: after such a recovery the bar can still stand for a value the recovery has applied,
 * until the next reconnect. No link is issued with a token any more.
 *
 * What the app or the server writes into a running profile is no change of the user's and never counts: the hashes the
 * server sends and what a hash check found are not compared at all; a token VK refused is told from a picked one
 * (RunPending.of); a start's own correction of the creds mode and a subscription's refresh of the node the tunnel is on
 * move the record with them (RunRead.withModes, RunRead.following).
 */

/** A profile's connection as its user set it, before anything is resolved from it. */
internal data class SourceRead(
    val linkMode: Boolean = false,
    /** The own server: its address and port as the two fields hold them, and its connection password. */
    val address: String = "",
    val port: Int = 0,
    val password: String = "",
    /** A participant's profile: the link, which holds the server and the password. */
    val link: String = "",
    /** The profile's own hashes, each once, in the order they are kept. */
    val ownHashes: List<String> = emptyList(),
    /** The owner's hashes the participant took out. */
    val removedOwner: Set<String> = emptySet(),
    val acceptServerHashes: Boolean = true,
)

/**
 * What a start read of its profile: the user's own values, as stored. One reading fills it at a start
 * (SettingsStore.runRead) and the same reading, made now, is what it is compared with — so the two sides are told
 * apart by a change of the store alone, never by how each was read.
 */
internal data class RunRead(
    val profile: Int,
    val source: SourceRead,
    val settings: TunnelStartSettings,
    /** «Режим хешей» as stored. */
    val hashMode: String,
    /** The TURN subnet picked and how many of its addresses, for the row that says them; [settings] holds the addresses. */
    val turnSubnet: String = "",
    val turnLimit: String = "",
) {
    /**
     * The record after a start's own correction of the creds mode: a start that runs a link's hashes or token in
     * another mode than the stored one writes that mode into the profile, so the store and the record say the same.
     */
    fun withModes(authMode: String, hashMode: String): RunRead = copy(settings = settings.copy(vkAuthMode = authMode), hashMode = hashMode)

    /**
     * The record after a subscription's refresh rewrote the node the tunnel is on ([before], [now]: the profile as the
     * store held it before the refresh and holds it after). What the refresh wrote is the subscription's doing, not a
     * change the user made, so the record takes it: the connection — the link as served, and for a slot of an older
     * build made a link's, all of it — and the streams the node's power allows; a refresh writes nothing else a run
     * keeps (SettingsStore.writeSubscriptionNode). Value by value, and only where nothing of the user's waited: one
     * the user had changed (stored before the refresh, and not what the run took) still waits after it, whatever the
     * refresh made of it.
     */
    fun following(before: RunRead, now: RunRead): RunRead {
        fun <T> moved(mine: T, was: T, becomes: T): T = if (mine == was) becomes else mine
        val a = source
        val b = before.source
        val c = now.source
        return copy(
            source = a.copy(
                linkMode = moved(a.linkMode, b.linkMode, c.linkMode),
                address = moved(a.address, b.address, c.address),
                port = moved(a.port, b.port, c.port),
                password = moved(a.password, b.password, c.password),
                link = moved(a.link, b.link, c.link),
                ownHashes = moved(a.ownHashes, b.ownHashes, c.ownHashes),
                removedOwner = moved(a.removedOwner, b.removedOwner, c.removedOwner),
            ),
            settings = settings.copy(
                workersPerHash = moved(settings.workersPerHash, before.settings.workersPerHash, now.settings.workersPerHash),
            ),
        )
    }
}

/** A run and what it took: [read] of its profile, and of its parameters what a comparison and a screen need. */
internal data class RunTaken(
    val read: RunRead,
    /** The creds mode the run is in: a link's hashes or token can make it another than the stored one. */
    val authMode: String,
    /** The run makes its VK calls with an account («Авто API», «Авто ВК»). */
    val usesAccount: Boolean,
    /** The VK account the run is on NOW, "" for none: RunKeeps moves it when VK refuses one, a recovery when it had none. */
    val account: String,
    /** The account is the user's own, not a link's token. */
    val accountOwn: Boolean,
) {
    companion object {
        fun of(read: RunRead, params: TunnelParams): RunTaken = RunTaken(
            read = read,
            authMode = coreAuthMode(params),
            usesAccount = params.vkHashMode != CsqttConstants.VkAutoHash.MODE_MANUAL,
            account = params.vkAccessToken,
            accountOwn = !params.vkTokenFromLink,
        )
    }
}

/** What of a run's settings can wait for a reconnect: one for each control that says «Сейчас: …», and the rest. */
internal enum class RunKey {
    ADDRESS, PORT, PASSWORD, LINK, OWN_HASHES, OWNER_HASHES, ACCEPT_HASHES, HASH_MODE, ACCOUNT,
    AUTH_MODE, CAPTCHA_MODE, CAPTCHA_SOLVE, SRTP, TRANSPORT, DPI, STREAMS, FINGERPRINT, TURN,
    /** A value the core takes that no control sets (a config import can): the bar alone. */
    OTHER,
}

internal object RunPending {
    /**
     * What is stored [now] for the run's profile that the run did not take: each is for the next connection. Compared
     * as stored, value by value. Not compared, on purpose: the second hash, the SNI and the protocol (a start carries
     * them and nothing reads them); the hashes the server sent and what a check found of a hash (the app's and the
     * server's own writes).
     *
     * The VK account ([nowAccount]: the profile's token now) waits only when the run makes its calls with an account
     * of the user's and another one is stored: a blank one is a token VK refused and the app erased, the same one is
     * what the run already has — also after it moved to it by itself (RunKeeps).
     */
    fun of(taken: RunTaken, now: RunRead, nowAccount: String): Set<RunKey> {
        val was = taken.read
        val keys = mutableSetOf<RunKey>()
        fun differs(key: RunKey, differs: Boolean) {
            if (differs) keys += key
        }
        val a = was.source
        val b = now.source
        if (a.linkMode != b.linkMode) {
            // the profile became the other kind (a config import): the connection as a whole is another
            keys += if (b.linkMode) RunKey.LINK else RunKey.ADDRESS
        } else if (b.linkMode) {
            differs(RunKey.LINK, a.link != b.link)
            differs(RunKey.OWNER_HASHES, a.removedOwner != b.removedOwner)
            differs(RunKey.ACCEPT_HASHES, a.acceptServerHashes != b.acceptServerHashes)
        } else {
            differs(RunKey.ADDRESS, a.address != b.address)
            differs(RunKey.PORT, a.port != b.port)
            differs(RunKey.PASSWORD, a.password != b.password)
        }
        differs(RunKey.OWN_HASHES, a.ownHashes != b.ownHashes)
        differs(RunKey.HASH_MODE, was.hashMode != now.hashMode)

        val s = was.settings
        val t = now.settings
        differs(RunKey.AUTH_MODE, s.vkAuthMode != t.vkAuthMode)
        differs(RunKey.CAPTCHA_MODE, s.captchaMode != t.captchaMode)
        differs(RunKey.CAPTCHA_SOLVE, s.captchaSolveMethod != t.captchaSolveMethod)
        differs(RunKey.SRTP, s.srtpMode != t.srtpMode)
        differs(RunKey.TRANSPORT, s.turnTransport != t.turnTransport)
        differs(RunKey.DPI, dpiInUse(s.turnTransport, s.dpiSplit) != dpiInUse(t.turnTransport, t.dpiSplit))
        differs(RunKey.STREAMS, s.workersPerHash != t.workersPerHash)
        differs(RunKey.FINGERPRINT, s.fingerprint != t.fingerprint)
        differs(RunKey.TURN, s.customTurnIps != t.customTurnIps)
        differs(RunKey.OTHER, s.obfsMode != t.obfsMode || s.clientIds != t.clientIds)

        val nowUsesAccount = now.hashMode != CsqttConstants.VkAutoHash.MODE_MANUAL || t.vkAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS
        differs(
            RunKey.ACCOUNT,
            taken.usesAccount && taken.accountOwn && nowUsesAccount && nowAccount.isNotBlank() && nowAccount != taken.account,
        )
        return keys
    }

    /** The DPI level a transport runs with: the split is TCP's, so over UDP whatever is stored is off. */
    fun dpiInUse(transport: String, dpi: DpiSplitMode): DpiSplitMode = if (DpiSplitMode.isAvailable(transport)) dpi else DpiSplitMode.OFF
}

/** The creds mode the core is started in: «Авто ВК» needs its own hash mode, without it the calls are the plain ones. */
internal fun coreAuthMode(params: TunnelParams): String {
    val jsHashMode = params.vkHashMode == CsqttConstants.VkAutoHash.MODE_AUTO_JS
    val jsAuthMode = params.vkAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS
    return if (jsAuthMode && !jsHashMode) CsqttConstants.VkAuth.MODE_CALLS else params.vkAuthMode
}

/**
 * «Логи», under each «Подключение #N»: what the connection started with — the transport, the DPI level with TCP, the
 * [streams] the core is asked for, SRTP and the creds mode. The log of a phone could not say which transport a
 * connection ran on (the operator's second log, 2026-11-28).
 */
internal fun runStartLine(params: TunnelParams, streams: Int): String {
    val tcp = DpiSplitMode.isAvailable(params.turnTransport)
    val parts = mutableListOf(if (tcp) "TCP" else "UDP")
    if (tcp) {
        parts += "обход DPI: " + when (params.dpiSplit) {
            DpiSplitMode.OFF -> "выкл"
            DpiSplitMode.LIGHT -> "лёгкая"
            DpiSplitMode.MEDIUM -> "средняя"
            DpiSplitMode.HEAVY -> "тяжёлая"
        }
    }
    parts += "потоки: $streams"
    parts += "SRTP: " + when (params.srtpMode) {
        CsqttConstants.SrtpMode.WRAP -> "WRAP-SRTP"
        CsqttConstants.SrtpMode.DTLS -> "DTLS-SRTP"
        else -> "авто"
    }
    parts += "креды: " + when (coreAuthMode(params)) {
        CsqttConstants.VkAuth.MODE_CAPTCHA -> "капча"
        CsqttConstants.VkAuth.MODE_AUTO_JS -> "Авто ВК"
        else -> "авто"
    }
    return "[ЗАПУСК] " + parts.joinToString(" · ")
}

/** The part of a start's record its intent carries beside the settings ([putTunnelSettings]) and the run's profile. */
private const val EXTRA_READ = "run_read"
private const val EXTRA_HASH_MODE = "run_hash_mode"
private const val EXTRA_LINK_MODE = "run_link_mode"
private const val EXTRA_ADDRESS = "run_address"
private const val EXTRA_PORT = "run_port"
private const val EXTRA_PASSWORD = "run_password"
private const val EXTRA_LINK = "run_link"
private const val EXTRA_OWN_HASHES = "run_own_hashes"
private const val EXTRA_REMOVED = "run_removed_hashes"
private const val EXTRA_ACCEPT = "run_accept_hashes"
private const val EXTRA_TURN_SUBNET = "run_turn_subnet"
private const val EXTRA_TURN_LIMIT = "run_turn_limit"

/** A START carries what it read: the service starts with these values and keeps them as the run's record. */
internal fun Intent.putRunRead(read: RunRead): Intent = apply {
    putTunnelSettings(read.settings)
    putExtra(CsqttConstants.General.EXTRA_RUN_PROFILE, read.profile)
    putExtra(EXTRA_READ, true)
    putExtra(EXTRA_HASH_MODE, read.hashMode)
    putExtra(EXTRA_LINK_MODE, read.source.linkMode)
    putExtra(EXTRA_ADDRESS, read.source.address)
    putExtra(EXTRA_PORT, read.source.port)
    putExtra(EXTRA_PASSWORD, read.source.password)
    putExtra(EXTRA_LINK, read.source.link)
    putExtra(EXTRA_OWN_HASHES, ArrayList(read.source.ownHashes))
    putExtra(EXTRA_REMOVED, ArrayList(read.source.removedOwner))
    putExtra(EXTRA_ACCEPT, read.source.acceptServerHashes)
    putExtra(EXTRA_TURN_SUBNET, read.turnSubnet)
    putExtra(EXTRA_TURN_LIMIT, read.turnLimit)
}

/** The record a START carries; null for a START that carries none (one built by another build of the app). */
internal fun Intent.runRead(): RunRead? {
    if (!getBooleanExtra(EXTRA_READ, false)) return null
    return RunRead(
        profile = getIntExtra(CsqttConstants.General.EXTRA_RUN_PROFILE, -1),
        source = SourceRead(
            linkMode = getBooleanExtra(EXTRA_LINK_MODE, false),
            address = getStringExtra(EXTRA_ADDRESS).orEmpty(),
            port = getIntExtra(EXTRA_PORT, 0),
            password = getStringExtra(EXTRA_PASSWORD).orEmpty(),
            link = getStringExtra(EXTRA_LINK).orEmpty(),
            ownHashes = getStringArrayListExtra(EXTRA_OWN_HASHES).orEmpty(),
            removedOwner = getStringArrayListExtra(EXTRA_REMOVED).orEmpty().toSet(),
            acceptServerHashes = getBooleanExtra(EXTRA_ACCEPT, true),
        ),
        settings = tunnelSettings(),
        hashMode = getStringExtra(EXTRA_HASH_MODE) ?: CsqttConstants.VkAutoHash.MODE_MANUAL,
        turnSubnet = getStringExtra(EXTRA_TURN_SUBNET).orEmpty(),
        turnLimit = getStringExtra(EXTRA_TURN_LIMIT).orEmpty(),
    )
}
