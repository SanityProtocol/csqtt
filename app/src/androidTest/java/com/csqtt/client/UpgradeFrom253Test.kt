package com.csqtt.client

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B5b1: a store file as 2.5.3 wrote it, through the real serializer, is migrated before the first read of a DataStore
 * that carries the store's migrations (an install of the release 2.5.3 cannot be seeded without root).
 */
@RunWith(AndroidJUnit4::class)
class UpgradeFrom253Test {
    private val hashA = "A".repeat(20)
    private val hashC = "C".repeat(20)

    @Test
    fun theFirstReadSeesTheMigratedStore() = runBlocking {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File(dir, "upgrade-253-${System.nanoTime()}.preferences_pb")
        try {
            val oldScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val old = PreferenceDataStoreFactory.create(scope = oldScope) { file }
            old.edit { prefs ->
                prefs[stringPreferencesKey("peer")] = "203.0.113.5"
                prefs[intPreferencesKey("server_peer_port")] = 46100
                prefs[stringPreferencesKey("deploy_ip")] = "203.0.113.5"
                prefs[stringPreferencesKey("deploy_ip_7")] = "203.0.113.7"
                prefs[intPreferencesKey("workers_per_hash")] = 18
                prefs[stringPreferencesKey("vk_hash_mode")] = "auto"
                prefs[stringPreferencesKey("ui_design")] = "v2"
                prefs[booleanPreferencesKey("default_role_decided")] = true
                prefs[booleanPreferencesKey("csqtt_link_mode_3")] = true
                prefs[stringPreferencesKey("csqtt_link_3")] =
                    "csqtt://connect?v=2&host=198.51.100.9&peer=46000&password=p&hashes=$hashA"
                prefs[stringPreferencesKey("vk_hashes_3")] = "$hashA,$hashC"
                // a 2.5.3 subscription slot in owner mode, holding the node's hashes as its own (B5c1, M-170)
                prefs[stringPreferencesKey("peer_8")] = "192.0.2.80"
                prefs[stringPreferencesKey("subscription_url_8")] = "https://sub.example.invalid/s"
                prefs[stringPreferencesKey("connection_password_local_8")] = "subpw"
                prefs[stringPreferencesKey("vk_hashes_8")] = hashA
            }
            oldScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()

            val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val upgraded = PreferenceDataStoreFactory.create(
                migrations = listOf(SettingsStore.storeMigrations),
                scope = newScope,
            ) { file }
            val prefs = upgraded.data.first()
            newScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()

            assertNull(prefs[stringPreferencesKey("ui_design")])
            assertEquals(10, prefs[intPreferencesKey("workers_per_hash")])
            assertEquals(CsqttConstants.VkAutoHash.MODE_AUTO_API, prefs[stringPreferencesKey("vk_hash_mode")])
            assertEquals(46100, prefs[intPreferencesKey("server_peer_port")])
            assertNull(prefs[booleanPreferencesKey("csqtt_link_mode")])
            assertEquals(hashC, prefs[stringPreferencesKey("vk_hashes_3")])
            assertEquals(SettingsStore.STORE_SCHEMA, prefs[intPreferencesKey("store_schema_version")])
            // B5b2: each slot's deploy data is a VPS record its profile links, a VPS-only slot too
            assertEquals(0, prefs[intPreferencesKey("vps_ref")])
            // B5c1: the subscription slot is the participant link it came from; the owner's hash is no longer its own
            assertEquals(true, prefs[booleanPreferencesKey("csqtt_link_mode_8")])
            assertNull(prefs[stringPreferencesKey("vk_hashes_8")])
            val subscriptionLink = com.csqtt.client.ui.legacy.utils.parseCsqttLink(prefs[stringPreferencesKey("csqtt_link_8")]!!)!!
            assertEquals(listOf(hashA), subscriptionLink.hashes)
            assertEquals("subpw", subscriptionLink.password)
            assertEquals(7, prefs[intPreferencesKey("vps_ref_7")])
            assertNull(prefs[intPreferencesKey("vps_ref_3")])
            // the roles batch: the owner role the switch showed became «Показывать деплой»; the role key is gone
            assertEquals(true, prefs[booleanPreferencesKey("show_deploy_tab")])
            assertNull(prefs[booleanPreferencesKey("ui_owner_mode")])
        } finally {
            file.delete()
        }
    }
}
