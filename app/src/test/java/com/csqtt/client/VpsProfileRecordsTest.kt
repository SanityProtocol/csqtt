package com.csqtt.client

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.csqtt.client.Store253Snapshots.asMap
import com.csqtt.client.Store253Snapshots.load
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B5b2: VPS records and profiles are separate (M-41, M-104): deleting either deletes only its own data; a deploy-form write
 * creates a record; a deploy's end writes nothing into a profile deleted or relinked meanwhile; drafts never move the port
 * the tunnel dials (B5b1 backlog B1).
 */
class VpsProfileRecordsTest {
    private fun key(name: String, index: Int) = stringPreferencesKey(SettingsStore.slotKeyName(name, index))
    private fun intKey(name: String, index: Int) = intPreferencesKey(SettingsStore.slotKeyName(name, index))

    /** Profiles 2 (owner, record 2) and 5 (participant link); record 2 also linked from profile 6. */
    private fun twoProfilesOneVps(): MutablePreferences = load(
        """{"strings": {"peer_2": "203.0.113.2", "connection_password_local_2": "pw2", "vk_hashes_2": "${"H".repeat(20)}",
                        "deploy_ip_2": "203.0.113.2", "deploy_login_2": "root", "deploy_password_encrypted_2": "c",
                        "csqtt_link_5": "${Store253Snapshots.participantLink}", "peer_6": "203.0.113.2"},
            "ints": {"server_peer_port_2": 46100, "server_web_port_2": 46102, "vps_ref_2": 2, "vps_ref_6": 2,
                     "server_peer_port_6": 46000, "active_profile": 2},
            "booleans": {"manual_ports_enabled_2": true, "csqtt_link_mode_5": true}}""",
    )

    private fun groupKeys(index: Int, group: ProfileKeyGroup) = SettingsStore.perProfileKeyGroups
        .filterValues { it == group }.keys.map { SettingsStore.slotKeyName(it, index) }.toSet()

    @Test
    fun `deleting a profile keeps its VPS record and every other profile, and moves the active one`() {
        val prefs = twoProfilesOneVps()
        val before = asMap(prefs)
        SettingsStore.deleteProfileKeys(prefs, 2)
        val after = asMap(prefs)
        val vpsKeys = groupKeys(2, ProfileKeyGroup.VPS)
        assertEquals(before.filterKeys { it in vpsKeys }, after.filterKeys { it in vpsKeys })
        assertTrue(after.keys.none { it in groupKeys(2, ProfileKeyGroup.CONNECTION) })
        assertEquals(5, after["active_profile"])
        for (name in listOf("csqtt_link_5", "peer_6", "vps_ref_6", "server_peer_port_6")) assertEquals(before[name], after[name])
    }

    @Test
    fun `deleting a VPS keeps every profile that linked it connecting`() {
        val prefs = twoProfilesOneVps()
        val before = asMap(prefs)
        SettingsStore.deleteVpsKeys(prefs, 2)
        val after = asMap(prefs)
        assertTrue(after.keys.none { it in groupKeys(2, ProfileKeyGroup.VPS) })
        assertNull(after["vps_ref_2"])
        assertNull(after["vps_ref_6"])
        assertEquals(before - groupKeys(2, ProfileKeyGroup.VPS) - setOf("vps_ref_2", "vps_ref_6"), after)
        for (name in listOf("peer_2", "connection_password_local_2", "server_peer_port_2", "server_web_port_2", "vk_hashes_2")) {
            assertEquals(name, before[name], after[name])
        }
    }

    @Test
    fun `classic's delete takes the record only when no other profile links it`() {
        val shared = twoProfilesOneVps()
        assertFalse(SettingsStore.isOnlyLinkToVps(shared, 2))
        SettingsStore.deleteProfileWithLoneVps(shared, 2)
        assertEquals("203.0.113.2", shared[key("deploy_ip", 2)])
        assertTrue(SettingsStore.isOnlyLinkToVps(shared, 6))
        SettingsStore.deleteProfileWithLoneVps(shared, 6)
        assertNull(shared[key("deploy_ip", 2)])
        assertNull(shared[key("deploy_password_encrypted", 2)])
        assertEquals(Store253Snapshots.participantLink, shared[key("csqtt_link", 5)])
    }

    @Test
    fun `the first deploy write creates a record, the profile's own index, else the first free one, leftovers cleared`() {
        val prefs = load("""{"strings": {"peer_3": "198.51.100.3", "deploy_secret_path_uuid_3": "old"}, "ints": {}}""")
        assertNull(SettingsStore.linkedVps(prefs, 3))
        assertEquals(3, SettingsStore.vpsForWrite(prefs, 3))
        assertEquals(3, SettingsStore.linkedVps(prefs, 3))
        assertNotEquals("old", prefs[key("deploy_secret_path_uuid", 3)])
        assertEquals(3, SettingsStore.vpsForWrite(prefs, 3))

        // profile 0 links record 3, so profile 3's next write needs another index: record 0 holds only a secret path
        val taken = load(
            """{"strings": {"deploy_ip_3": "203.0.113.3", "deploy_secret_path_uuid": "old0", "deploy_ip_1": "x"},
                "ints": {"vps_ref": 3}}""",
        )
        assertEquals(0, SettingsStore.vpsForWrite(taken, 3))
        assertNotEquals("old0", taken[key("deploy_secret_path_uuid", 0)])
        assertEquals(3, SettingsStore.linkedVps(taken, 0))

        val full = load("""{"strings": {}, "ints": {}}""")
        for (i in 0..31) full[key("deploy_ip", i)] = "h$i"
        assertNull(SettingsStore.vpsForWrite(full, 4))
        assertNull(SettingsStore.linkedVps(full, 4))
    }

    @Test
    fun `drafts never move the port the tunnel dials, a deploy that succeeds does`() {
        val prefs = twoProfilesOneVps()
        SettingsStore.putDeployDraftPorts(prefs, 2, 46200, 46202, "2222")
        assertEquals(46100, prefs[intKey("server_peer_port", 2)])
        assertEquals(46102, prefs[intKey("server_web_port", 2)])
        assertEquals(46200, prefs[intKey("deploy_peer_port", 2)])

        assertTrue(SettingsStore.applyDeployedServer(prefs, 2, 2, "203.0.113.9", 46200, 46202, "new-pass"))
        assertEquals(46200, prefs[intKey("server_peer_port", 2)])
        assertEquals(46202, prefs[intKey("server_web_port", 2)])
        assertEquals("203.0.113.2", prefs[key("peer", 2)])
        assertEquals("new-pass", prefs[key("connection_password_local", 2)])
        assertNull(prefs[intKey("deploy_peer_port", 2)])
        assertNull(prefs[intKey("deploy_web_port", 2)])
        assertEquals(true, prefs[booleanPreferencesKey("manual_ports_enabled_2")])

        // a password the tunnel cannot carry is not synced
        SettingsStore.applyDeployedServer(prefs, 2, 2, "", 46200, 46202, "bad|pass")
        assertEquals("new-pass", prefs[key("connection_password_local", 2)])
    }

    @Test
    fun `a deploy's end writes nothing into a profile deleted or relinked meanwhile`() {
        val deleted = twoProfilesOneVps()
        SettingsStore.deleteProfileKeys(deleted, 2)
        val before = asMap(deleted)
        assertFalse(SettingsStore.applyDeployedServer(deleted, 2, 2, "203.0.113.2", 46000, 46002, "pw"))
        assertEquals(before, asMap(deleted))

        val relinked = twoProfilesOneVps()
        relinked[intKey("vps_ref", 2)] = 7
        val relinkedBefore = asMap(relinked)
        assertFalse(SettingsStore.applyDeployedServer(relinked, 2, 2, "203.0.113.2", 46000, 46002, "pw"))
        assertEquals(relinkedBefore, asMap(relinked))
    }
}
