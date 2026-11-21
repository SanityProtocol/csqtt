package com.csqtt.client

import com.csqtt.client.ui.legacy.utils.parseCsqttLink
import com.csqtt.client.ui.legacy.utils.peerAddress
import kotlinx.coroutines.flow.first

data class ConnectionSource(
    val peer: String,
    val password: String,
    val hashes: String,
    val hashesFromLink: Boolean,
    val vkAccessToken: String,
    val tokenFromLink: Boolean,
)

/**
 * S8: the owner's hashes the server sent for this password (`{"version":1,"hashes":[…]}`), checked like a link's: 1 to
 * 16 distinct entries of 16–128 characters with no whitespace or separator. Null for anything else, so an empty or
 * broken set never replaces a working one.
 */
internal object ReceivedHashes {
    fun parse(json: String): List<String>? = runCatching {
        val root = org.json.JSONObject(json)
        if (root.optInt("version", 0) != 1) return null
        val array = root.getJSONArray("hashes")
        val hashes = (0 until array.length()).map { array.getString(it).trim() }
        hashes.takeIf { list ->
            list.size in 1..16 && list.distinct().size == list.size &&
                list.all { h -> h.length in 16..128 && h.none { it.isWhitespace() || it == ',' || it == '+' } }
        }
    }.getOrNull()

    /** The owner's set a participant connects with — and the screen shows (review F4): what the server last sent, else the link's own. */
    fun ownerSet(linkHashes: List<String>, received: List<String>): List<String> = received.ifEmpty { linkHashes }
}

/** A participant's hashes: the owner's from the link minus the ones taken out, then the participant's own. */
internal fun participantHashes(linkHashes: List<String>, removed: Set<String>, ownHashes: String): String =
    (linkHashes.filterNot { it in removed } + ownHashes.split(Regex("[,\\s\\n]+")))
        .map(String::trim).filter(String::isNotEmpty).distinct().joinToString(",")

suspend fun resolveConnectionSource(store: SettingsStore): ConnectionSource? {
    val invalidHashes = VkHashValidationCodec.decode(store.vkHashCheckResults.first())
    fun activeHashes(raw: String): String {
        val rawList = raw.split(Regex("[,\\s\\n]+")).map(String::trim).filter { it.length >= 16 }.distinct()
        val filtered = VkHashValidationCodec.active(rawList, invalidHashes)
        return (if (filtered.isNotEmpty()) filtered else rawList).joinToString(",")
    }

    // S8: hashes the server sends during this run belong to the profile it started from, whatever is active by then
    TunnelManager.sourceProfile = store.selectedProfileIndex.first()
    TunnelManager.sourceLink = null
    if (store.csqttLinkMode.first()) {
        val rawLink = store.csqttLink.first()
        val link = parseCsqttLink(rawLink) ?: return null
        TunnelManager.sourceLink = rawLink
        val received = store.receivedHashes.first()
        val ownerSet = ReceivedHashes.ownerSet(link.hashes, received)
        TunnelManager.ownerHashes = (link.hashes + received).distinct()
        val removed = store.linkHashesRemoved.first()
        // hashes the server sent replace an old link's token too (S1: no link carries the owner's token any more)
        val tokenFromLink = link.vkAccessToken.isNotEmpty() && received.isEmpty()
        return ConnectionSource(
            peer = link.peerAddress(),
            password = link.password,
            hashes = if (tokenFromLink) "" else activeHashes(participantHashes(ownerSet, removed, store.vkHashes.first())),
            // a link with hashes hides the hash mode, so the participant's list (the owner's kept + their own) is used as
            // is — even with every owner hash taken out — never through a stored hash mode they cannot see
            hashesFromLink = ownerSet.isNotEmpty(),
            vkAccessToken = if (tokenFromLink) link.vkAccessToken else "",
            tokenFromLink = tokenFromLink,
        )
    }

    TunnelManager.ownerHashes = emptyList()
    val basePeer = store.peer.first()
    val passwordState = store.connectionPasswordState.first()
    val password = passwordState.value
    if (basePeer.isBlank()) return null
    val serverPeerPort = store.serverPeerPort.first().takeIf { it in 1..65535 }
        ?: CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT
    val peer = if (basePeer.contains(':')) basePeer else "$basePeer:$serverPeerPort"
    return ConnectionSource(
        peer = peer,
        password = password,
        hashes = activeHashes(store.vkHashes.first()),
        hashesFromLink = false,
        vkAccessToken = "",
        tokenFromLink = false,
    )
}
