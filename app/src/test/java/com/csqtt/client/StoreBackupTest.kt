package com.csqtt.client

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.csqtt.client.Store253Snapshots.asMap
import com.csqtt.client.Store253Snapshots.load
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** B5b2, M-42: the full backup (BackupRoundTripTest and BackupWithoutSecretsTest of the plan, in one class). */
class StoreBackupTest {
    // a fake keystore: "enc:<value>"; another phone's key cannot read this one's ("other:")
    private val encrypt = { value: String -> "enc:$value" }
    private val decrypt = { stored: String? -> stored?.takeIf { it.startsWith("enc:") }?.removePrefix("enc:") }
    private val hash = "H".repeat(20)

    /** 3 profiles (an owner with a VPS, a participant, a subscription node), 2 VPS records (one VPS-only), settings. */
    private fun phone(): MutablePreferences = load(
        """{"strings": {"peer": "203.0.113.5", "connection_password_encrypted": "enc:owner-pass", "vk_hashes": "$hash",
                        "vk_access_token_encrypted": "enc:vk-token", "vk_access_token_user_id": "42",
                        "deploy_ip": "203.0.113.5", "deploy_login": "root", "deploy_password_encrypted": "enc:ssh-pass",
                        "ssh_private_key_encrypted": "enc:KEY", "deploy_main_password_encrypted": "enc:owner-pass",
                        "deploy_web_password_encrypted": "enc:web-pass", "deploy_server_name": "Main",
                        "csqtt_link_3": "${Store253Snapshots.participantLink}", "profile_name_3": "Друг",
                        "peer_5": "192.0.2.10", "subscription_url_5": "https://sub.example.invalid/s",
                        "connection_password_local_5": "sub-pass",
                        "deploy_ip_9": "198.51.100.9", "deploy_server_name_9": "Запас",
                        "theme_mode": "dark", "subscription_meta_v1": "{}",
                        "update_latest_version": "2.6.0", "client_id_check_results": "{}"},
            "ints": {"server_peer_port": 46100, "server_web_port": 46102, "vps_ref": 0, "vps_ref_9": 9,
                     "workers_per_hash_3": 30, "vpn_mtu_3": 1280, "active_profile": 3, "update_check_interval_hours": 12, "store_schema_version": 2},
            "booleans": {"manual_ports_enabled": true, "csqtt_link_mode_3": true, "auto_pause_on_wifi": true,
                         "default_role_decided": true},
            "longs": {"deploy_last_inspection_at": 1761000000000, "connection_generation": 7}}""",
    )

    /** Another phone with data of its own, which a restore replaces. */
    private fun otherPhone(): MutablePreferences = load(
        """{"strings": {"peer_12": "192.0.2.99", "vk_hashes_12": "${"X".repeat(20)}", "deploy_ip_20": "h",
                        "theme_mode": "light", "update_latest_version": "2.5.3"},
            "ints": {"active_profile": 12, "vps_ref_12": 20, "store_schema_version": 2},
            "booleans": {"default_role_decided": true}}""",
    )

    @Test
    fun `a backup with server secrets restores every profile, record and setting on another phone`() {
        val source = phone()
        val json = StoreBackup.encode(source, serverSecrets = true, decrypt = decrypt, exportedAt = 1L)
        assertTrue(StoreBackup.isBackup(json))
        val target = otherPhone()
        StoreBackup.restore(target, json, encrypt, decrypt)
        val restored = asMap(target)

        // nothing of the other phone's profiles or settings is left; its own state keys stay
        assertNull(restored["peer_12"])
        assertNull(restored["vk_hashes_12"])
        assertNull(restored["deploy_ip_20"])
        assertEquals("2.5.3", restored["update_latest_version"])
        assertEquals(2, restored["store_schema_version"])

        val sourceMap = asMap(source)
        for (name in listOf("peer", "vk_hashes", "deploy_ip", "deploy_login", "deploy_server_name", "server_peer_port",
            "server_web_port", "vps_ref", "manual_ports_enabled", "deploy_last_inspection_at", "csqtt_link_3",
            "profile_name_3", "workers_per_hash_3", "vpn_mtu_3", "csqtt_link_mode_3", "peer_5", "subscription_url_5",
            "connection_password_local_5", "deploy_ip_9", "vps_ref_9", "theme_mode", "update_check_interval_hours",
            "auto_pause_on_wifi", "active_profile", "subscription_meta_v1")) {
            assertEquals(name, sourceMap[name], restored[name])
        }
        // secrets come back encrypted with this phone's key; the connection password as a local value
        assertEquals("owner-pass", restored["connection_password_local"])
        assertEquals("enc:ssh-pass", restored["deploy_password_encrypted"])
        assertEquals("enc:KEY", restored["ssh_private_key_encrypted"])
        assertEquals("enc:web-pass", restored["deploy_web_password_encrypted"])
        // never: the VK token, its user id, this device's state
        assertNull(restored["vk_access_token_encrypted"])
        assertNull(restored["vk_access_token_user_id"])
        assertNull(restored["connection_generation"])
        assertNull(restored["client_id_check_results"])
        assertEquals(setOf(0, 3, 5, 9), (0..31).filter { SettingsStore.profileInUse(target, it) }.toSet())
    }

    @Test
    fun `a file from before the store's step 9 restores a count of streams as it ran`() {
        // P3 B3: «Экстра потоки» went with the limit of 70 it lifted, and the numbered steps do not run on a restore
        val source = phone()
        source[intPreferencesKey("workers_per_hash_3")] = 100
        source[intPreferencesKey("workers_per_hash_5")] = 90
        source[intPreferencesKey("workers_per_hash_9")] = 18
        source[stringPreferencesKey("peer_9")] = "192.0.2.9"
        val file = JSONObject(StoreBackup.encode(source, serverSecrets = false, decrypt = decrypt, exportedAt = 1L))
        assertEquals(SettingsStore.STORE_SCHEMA, file.getInt("store_schema"))
        // this build's own file: the counts are the user's own, and a profile that holds none starts as a new one does
        val same = otherPhone().also { StoreBackup.restore(it, file.toString(), encrypt, decrypt) }
        assertEquals(100, same[intPreferencesKey("workers_per_hash_3")])
        assertEquals(90, same[intPreferencesKey("workers_per_hash_5")])
        assertNull(same[intPreferencesKey("workers_per_hash")])

        // an older build's file names no schema, and the switch where it was ever touched
        file.remove("store_schema")
        val profiles = file.getJSONArray("profiles")
        for (i in 0 until profiles.length()) profiles.getJSONObject(i).let { if (it.getInt("index") == 5) it.put("extra_workers", true) }
        val old = otherPhone().also { StoreBackup.restore(it, file.toString(), encrypt, decrypt) }
        assertEquals("the switch never touched: the count ran as 70", 70, old[intPreferencesKey("workers_per_hash_3")])
        assertEquals("the switch on: the count ran as stored", 90, old[intPreferencesKey("workers_per_hash_5")])
        assertEquals("no count in the file: the profile ran 10", 10, old[intPreferencesKey("workers_per_hash")])
        assertEquals("2.5.3's default of 18 ran as 10, and would now run as 15", 10, old[intPreferencesKey("workers_per_hash_9")])
        assertEquals("this build's own file keeps a count as it is held", 18, same[intPreferencesKey("workers_per_hash_9")])
        assertNull("the switch itself is not restored", asMap(old)["extra_workers_5"])
    }

    @Test
    fun `a backup without server secrets carries no keystore secret, ciphertext or VK token`() {
        val json = StoreBackup.encode(phone(), serverSecrets = false, decrypt = decrypt, exportedAt = 1L)
        for (secret in listOf("ssh-pass", "KEY", "web-pass", "vk-token", "enc:")) assertFalse(secret, secret in json)
        val root = JSONObject(json)
        val names = listOf("profiles", "vps").flatMap { section ->
            root.getJSONArray(section).let { a -> (0 until a.length()).flatMap { a.getJSONObject(it).keys().asSequence().toList() } }
        }
        assertTrue(names.none { it.endsWith("_encrypted") })
        for (name in listOf("deploy_password", "ssh_private_key", "vk_access_token", "vk_access_token_user_id",
            "deploy_web_password", "deploy_main_password")) {
            assertFalse(name, name in names)
        }
        // the connection data are the profile: always in
        assertTrue("owner-pass" in json)
        assertTrue("sub-pass" in json)
        assertTrue(Store253Snapshots.participantLink.substringAfter("password=").substringBefore('&') in json)

        val target = otherPhone()
        StoreBackup.restore(target, json, encrypt, decrypt)
        assertNull(target[stringPreferencesKey("deploy_password_encrypted")])
        assertNull(target[stringPreferencesKey("ssh_private_key_encrypted")])
        assertEquals("root", target[stringPreferencesKey("deploy_login")])
        assertEquals("owner-pass", target[stringPreferencesKey("connection_password_local")])
    }

    @Test
    fun `an edited file can only skip a value, and a file that is not a backup changes nothing`() {
        val root = JSONObject(StoreBackup.encode(phone(), serverSecrets = false, decrypt = decrypt, exportedAt = 1L))
        val owner = root.getJSONArray("profiles").let { a -> (0 until a.length()).map(a::getJSONObject).first { it.getInt("index") == 0 } }
        owner.put("server_peer_port", "46100")            // a string for an Int key
        owner.put("unknown_key", "x")
        owner.put("connection_password_local", "bad|pass") // breaks the password rule
        owner.put("vps_ref", 17)                           // no record 17 in the file
        root.getJSONObject("settings").put("update_check_interval_hours", true)
        // P3 B0s: a backup an earlier build made still names «Экономия батареи»: it is not a setting any more
        root.getJSONObject("settings").put("battery_saver_mode", "below").put("battery_saver_percent", 30)
        val target = otherPhone()
        StoreBackup.restore(target, root.toString(), encrypt, decrypt)
        assertNull(target[intPreferencesKey("server_peer_port")])
        assertNull(target[stringPreferencesKey("unknown_key")])
        assertNull(target[stringPreferencesKey("connection_password_local")])
        assertNull(target[intPreferencesKey("vps_ref")])
        assertNull(target[intPreferencesKey("update_check_interval_hours")])
        assertNull(target[stringPreferencesKey("battery_saver_mode")])
        assertNull(target[intPreferencesKey("battery_saver_percent")])
        assertEquals("203.0.113.5", target[stringPreferencesKey("peer")])
        assertEquals(1761000000000L, target[longPreferencesKey("deploy_last_inspection_at")])
        assertEquals(true, target[booleanPreferencesKey("manual_ports_enabled")])

        val untouched = otherPhone()
        val before = asMap(untouched)
        assertFalse(StoreBackup.isBackup("""{"version": 1, "tunnel": {}}"""))
        assertThrows(IllegalArgumentException::class.java) { StoreBackup.restore(untouched, """{"tunnel": {}}""", encrypt, decrypt) }
        assertThrows(IllegalArgumentException::class.java) {
            StoreBackup.restore(untouched, """{"format": "csqtt-backup", "version": 99}""", encrypt, decrypt)
        }
        assertEquals(before, asMap(untouched))
    }

    @Test
    fun `every app setting is either backed up or listed as left out`() {
        val source = listOf(
            File("app/src/main/java/com/csqtt/client/SettingsStore.kt"),
            File("src/main/java/com/csqtt/client/SettingsStore.kt"),
        ).first(File::isFile).readText()
        val globals = Regex("""private val [A-Z_]+ =\s*(?:string|int|boolean|long)PreferencesKey\("([a-z0-9_]+)"\)""")
            .findAll(source).map { it.groupValues[1] }.toSet()
        assertTrue("no global key found", globals.size > 30)
        assertEquals(emptySet<String>(), globals - SettingsStore.backupGlobalKeys.keys - SettingsStore.notBackedUpGlobalKeys)
        assertEquals(emptySet<String>(), SettingsStore.backupGlobalKeys.keys intersect SettingsStore.notBackedUpGlobalKeys)
    }
}
