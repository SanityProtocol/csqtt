package com.csqtt.client

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.csqtt.client.Store253Snapshots.asMap
import com.csqtt.client.Store253Snapshots.load
import com.csqtt.client.ui.legacy.utils.parseCsqttLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B5c1, M-170 / M-169: a subscription node is the owner's link in link mode — its hashes stay the owner's (masked, never the
 * profile's own), a token stays in the link, and 2.5.3's owner-mode subscription slots lose the hashes they held as their own.
 */
class ImportKeepsParticipantTest {
    private val ownerHashes = listOf("A".repeat(20), "B".repeat(20))
    private val nodeLink = "csqtt://connect?v=2&host=203.0.113.50&peer=46100&password=pw&hashes=${ownerHashes.joinToString("+")}"
    private val tokenLink = "csqtt://connect?v=2&host=203.0.113.51&peer=46000&password=pw&token=vk1.a.owner"

    private fun write(profile: Int, link: String) = SubscriptionProfileWrite(
        profile = profile, link = link, power = 40, nodeName = "Node", title = "Sub", url = "https://sub.example/s", subUuid = "u1",
    )

    private fun s(name: String, p: Int) = stringPreferencesKey(SettingsStore.slotKeyName(name, p))
    private fun b(name: String, p: Int) = booleanPreferencesKey(SettingsStore.slotKeyName(name, p))

    @Test
    fun `a node in a fresh slot is the owner's link, with no hashes of its own`() {
        val prefs = mutablePreferencesOf()
        SettingsStore.writeSubscriptionNode(prefs, write(3, nodeLink))
        assertEquals(nodeLink, prefs[s("csqtt_link", 3)])
        assertEquals(true, prefs[b("csqtt_link_mode", 3)])
        assertNull(prefs[s("vk_hashes", 3)])
        assertNull(prefs[s("peer", 3)])
        assertEquals("https://sub.example/s", prefs[s("subscription_url", 3)])
        assertEquals("Node", prefs[s("profile_name", 3)])
        assertEquals(ownerHashes, parseCsqttLink(prefs[s("csqtt_link", 3)]!!)!!.hashes)
    }

    @Test
    fun `a 2_5_3 owner-mode subscription slot converts and drops the owner's hashes it held as its own`() {
        val prefs = load(
            """{"strings": {"peer_2": "203.0.113.50", "connection_password_local_2": "pw", "vk_hashes_2": "${ownerHashes.joinToString(",")}",
                            "subscription_url_2": "https://sub.example/s", "link_hashes_removed_2": "x"},
                "ints": {"server_peer_port_2": 46100}}""",
        )
        SettingsStore.writeSubscriptionNode(prefs, write(2, nodeLink))
        for (gone in listOf("vk_hashes", "peer", "connection_password_local", "link_hashes_removed")) {
            assertNull(gone, prefs[s(gone, 2)])
        }
        assertEquals(nodeLink, prefs[s("csqtt_link", 2)])
        assertEquals(true, prefs[b("csqtt_link_mode", 2)])
    }

    @Test
    fun `a slot already in link mode keeps the participant's own hashes`() {
        val own = "C".repeat(20)
        val prefs = load("""{"strings": {"csqtt_link_4": "$nodeLink", "vk_hashes_4": "$own"}, "booleans": {"csqtt_link_mode_4": true}}""")
        SettingsStore.writeSubscriptionNode(prefs, write(4, nodeLink))
        assertEquals(own, prefs[s("vk_hashes", 4)])
    }

    @Test
    fun `a token link keeps the token in the link and writes no token of the profile's own`() {
        val prefs = mutablePreferencesOf()
        SettingsStore.writeSubscriptionNode(prefs, write(6, tokenLink))
        assertEquals("vk1.a.owner", parseCsqttLink(prefs[s("csqtt_link", 6)]!!)!!.vkAccessToken)
        assertTrue(asMap(prefs).keys.none { it.contains("vk_access_token") })
    }

    @Test
    fun `a link-mode profile is matched by its link's server`() {
        val snapshot = ProfileConnectionSnapshot(csqttLink = nodeLink, csqttLinkMode = true)
        val key = with(SubscriptionImporter) { snapshot.endpointKey() }
        assertEquals("203.0.113.50|46100", key)
        val ownerMode = ProfileConnectionSnapshot(peer = "203.0.113.9", serverPeerPort = 46000)
        assertEquals("203.0.113.9|46000", with(SubscriptionImporter) { ownerMode.endpointKey() })
    }
}
