package com.csqtt.client

import com.csqtt.client.ui.legacy.utils.isValidTunnelPassword

/*
 * What the «Туннель» tab decides, apart from how it draws it (P3 B3): what an empty profile can still become, what a
 * field's text is short of, where the streams slider's mark stands, what a link lacks. Pure, so each has its unit test.
 */

/** The roles batch: the Туннель tab's one way to change an empty profile's mode. */
internal enum class ProfileModeAction { ENTER_SERVER, USE_LINK }

/**
 * Which text action the Туннель tab offers: only while the profile is empty ([emptyProfileLinkMode], the store's
 * emptyProfileMode: its link mode, or null once it holds a link, a server or a subscription), the field on screen is
 * empty too and the tunnel is idle.
 */
internal fun profileModeAction(emptyProfileLinkMode: Boolean?, field: String, tunnelBusy: Boolean): ProfileModeAction? =
    when {
        emptyProfileLinkMode == null || field.isNotBlank() || tunnelBusy -> null
        emptyProfileLinkMode -> ProfileModeAction.ENTER_SERVER
        else -> ProfileModeAction.USE_LINK
    }

/**
 * An address typed or pasted with its port, «203.0.113.5:46000», as the two it is: the form puts each into its own
 * field instead of asking the user to. Null for anything else, an IPv6 address among it.
 */
internal fun splitHostPort(text: String): Pair<String, Int>? {
    val at = text.lastIndexOf(':')
    if (at <= 0 || text.indexOf(':') != at) return null
    val port = text.substring(at + 1).takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isDigit) }?.toInt()
    return if (port != null && port in 1..65535) text.substring(0, at) to port else null
}

/** An address that is the phone itself: a tunnel to it goes nowhere. */
internal fun peerIsLocal(peer: String): Boolean =
    peer == "0.0.0.0" || peer == "127.0.0.1" || peer.equals("localhost", ignoreCase = true) || peer == "::1"

internal enum class AddressIssue { Missing, HasPort, Local }

internal enum class PasswordIssue { Missing, Rule }

internal enum class LinkIssue { Missing, Invalid }

/** What is wrong with the form, field by field (the canvas, F-TunnelInvalid); nothing set, nothing wrong. */
internal data class TunnelIssues(
    val address: AddressIssue? = null,
    val port: Boolean = false,
    val password: PasswordIssue? = null,
    val link: LinkIssue? = null,
    /** Nothing to connect with: no hash of a call, and no VK account in a mode that makes them. */
    val hashes: Boolean = false,
) {
    val any: Boolean get() = address != null || port || password != null || link != null || hashes
}

/**
 * The form's issues. What a field holds wrongly is always said. What it merely lacks is said only once a start was
 * refused for it ([required]): a form being filled in is not a form full of errors. [password] is what its field
 * holds, the stored one or the one being typed.
 */
internal fun tunnelIssues(
    linkMode: Boolean,
    address: String,
    port: String,
    password: String,
    link: String,
    linkValid: Boolean,
    hashesReady: Boolean,
    required: Boolean,
): TunnelIssues {
    val hashes = required && !hashesReady
    if (linkMode) {
        val linkIssue = when {
            link.isBlank() -> LinkIssue.Missing.takeIf { required }
            !linkValid -> LinkIssue.Invalid
            else -> null
        }
        return TunnelIssues(link = linkIssue, hashes = hashes)
    }
    return TunnelIssues(
        address = when {
            address.isBlank() -> AddressIssue.Missing.takeIf { required }
            address.contains(':') -> AddressIssue.HasPort
            peerIsLocal(address) -> AddressIssue.Local
            else -> null
        },
        port = if (port.isEmpty()) required else port.toIntOrNull()?.let { it !in 1..65535 } ?: true,
        password = when {
            password.isEmpty() -> PasswordIssue.Missing.takeIf { required }
            !isValidTunnelPassword(password) -> PasswordIssue.Rule
            else -> null
        },
        hashes = hashes,
    )
}

/**
 * The «Потоки» slider (the canvas, F-TunnelMore): it stops at whole groups of ten, from one group to the profile's
 * maximum, and the server's limit, when the server named one, is marked on its track (S10's hint).
 */
internal object StreamsSlider {
    private const val STEP = CsqttConstants.Tunnel.WORKERS_PER_GROUP

    /** The counts the slider stops at. */
    fun stops(maximum: Int): List<Int> = (STEP..maximum.coerceAtLeast(STEP) step STEP).toList()

    /** Where [value] stands on the track, from 0 at its start to 1 at its end. */
    fun fraction(value: Int, maximum: Int): Float {
        val last = stops(maximum).last()
        return if (last == STEP) 0f else ((value - STEP).toFloat() / (last - STEP)).coerceIn(0f, 1f)
    }

    /** The stop nearest to a touch at [fraction] of the track. */
    fun valueAt(fraction: Float, maximum: Int): Int {
        val stops = stops(maximum)
        val wanted = STEP + fraction.coerceIn(0f, 1f) * (stops.last() - STEP)
        return stops.minBy { kotlin.math.abs(it - wanted) }
    }

    /**
     * The server's limit as a place on the track; null when the server named none. A limit the track has no place for
     * (fewer than one group, more than the profile's maximum) is marked at the end it lies beyond.
     */
    fun mark(serverLimit: Int?, maximum: Int): Float? = serverLimit?.let { fraction(it, maximum) }
}

/**
 * How a profile's streams land on its calls (the operator, 2026-11-27: the count is said per hash, and what is
 * recommended is a count per hash, not a total). The core deals the streams out in tens — each ten on one VK
 * credential, which is one more guest in the call — a ten to a hash in turn (`group_hash_index`), so with fewer hashes
 * than tens some calls carry several.
 */
internal object StreamsPlan {
    private const val STEP = CsqttConstants.Tunnel.WORKERS_PER_GROUP

    /**
     * The most streams recommended on one hash: three credentials on a call. The 2.1 line enforced the same three (27
     * streams, in its groups of nine) and 2.0 ran two by default; 2.5 enforces nothing, and a total of 70 on a single
     * hash was seven guests in one call. One constant: what the red part of the slider and its warning go by.
     */
    const val RECOMMENDED_PER_HASH = 30

    /** The most streams any one hash carries when [total] streams go over [hashes] calls. */
    fun perHash(total: Int, hashes: Int): Int {
        val tens = (total + STEP - 1) / STEP
        return (tens + hashes - 1) / hashes.coerceAtLeast(1) * STEP
    }

    /** The largest total that keeps every one of [hashes] calls within [RECOMMENDED_PER_HASH]; never above [maximum]. */
    fun recommended(hashes: Int, maximum: Int): Int = (hashes.coerceAtLeast(1) * RECOMMENDED_PER_HASH).coerceAtMost(maximum)

    /**
     * Over how many calls a profile's streams go. With the user's own hashes or a link's, the ones in use ([manual]),
     * no more than the tunnel takes. In «Авто API» the app makes a call for every ten streams, up to that same count.
     * Null where the count says nothing: «Авто ВК», whose one call is the account's own and is served by the account's
     * sessions, and a profile that has no hash yet.
     */
    fun hashes(autoVk: Boolean, autoApi: Boolean, manual: Int): Int? = when {
        autoVk -> null
        autoApi -> CsqttConstants.Tunnel.MAX_VK_HASHES
        manual <= 0 -> null
        else -> manual.coerceAtMost(CsqttConstants.Tunnel.MAX_VK_HASHES)
    }
}

/**
 * How many of a participant's own hash fields the sheet shows: every one that is filled, and room up to the count the
 * tunnel takes with the owner's hashes that are in use; one at least, so that there is always somewhere to paste.
 */
internal fun ownHashRows(ownerInUse: Int, own: List<String>): Int {
    val filled = own.indexOfLast { it.isNotBlank() } + 1
    return maxOf(filled, CsqttConstants.Tunnel.MAX_VK_HASHES - ownerInUse, 1).coerceAtMost(own.size)
}

/** What a participant's link is short of, said on a card of its own (the canvas, F-TunnelLinkStates). */
internal enum class LinkNote { OwnerToken, NeedsHashes, Outdated }

/**
 * The note for a link that parses. [ownerHashes] is the owner's set the profile connects with (what the server last
 * sent, else the link's own), [removed] the ones the participant took out, [results] the last check's. [ownReady]: the
 * participant has hashes of their own, or a VK account in a mode that makes them.
 */
internal fun linkNote(
    ownerHashes: List<String>,
    removed: Set<String>,
    results: Map<String, VkHashValidationStatus>,
    linkToken: Boolean,
    ownReady: Boolean,
): LinkNote? {
    val inUse = ownerHashes.filterNot { it in removed }
    return when {
        // an owner's token in the link serves by itself; such links are no longer made (S1), which the card says
        ownerHashes.isEmpty() && linkToken -> LinkNote.OwnerToken
        ownReady -> null
        inUse.isEmpty() -> LinkNote.NeedsHashes
        // every hash the owner gave was checked and none answers: only a new link brings working ones
        inUse.all { results[it] == VkHashValidationStatus.Invalid } -> LinkNote.Outdated
        else -> null
    }
}
