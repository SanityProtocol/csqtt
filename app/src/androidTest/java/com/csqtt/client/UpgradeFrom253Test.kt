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
            assertEquals(7, prefs[intPreferencesKey("vps_ref_7")])
            assertNull(prefs[intPreferencesKey("vps_ref_3")])
        } finally {
            file.delete()
        }
    }
}
