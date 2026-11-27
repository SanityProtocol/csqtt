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

internal enum class HashesIssue { Missing, Duplicates }

/** What is wrong with the form, field by field (the canvas, F-TunnelInvalid); nothing set, nothing wrong. */
internal data class TunnelIssues(
    val address: AddressIssue? = null,
    val port: Boolean = false,
    val password: PasswordIssue? = null,
    val link: LinkIssue? = null,
    val hashes: HashesIssue? = null,
) {
    val any: Boolean get() = address != null || port || password != null || link != null || hashes != null
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
    hashDuplicates: Boolean,
    required: Boolean,
): TunnelIssues {
    val hashes = when {
        hashDuplicates -> HashesIssue.Duplicates
        required && !hashesReady -> HashesIssue.Missing
        else -> null
    }
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
