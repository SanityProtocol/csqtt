package com.csqtt.client

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.csqtt.client.Store253Snapshots.asMap
import com.csqtt.client.Store253Snapshots.changedKeys
import com.csqtt.client.Store253Snapshots.load
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3 B3 (the operator, 2026-11-27): a subscription the store holds under two spellings of its link becomes one, by the
 * store's step 9, with nothing asked. No profile is removed, and a profile changes in nothing but the subscription it
 * points to.
 */
class SubscriptionTwinsMergeTest {
    private val https = "https://sub.example.invalid/s/abc"
    private val http = "http://sub.example.invalid/s/abc"
    private val leafOld = "aa".repeat(32)
    private val leafNew = "bb".repeat(32)

    private class Record(
        val url: String,
        val title: String = "",
        val minutes: Int = 0,
        val updatedAt: Long = 0L,
        val attemptAt: Long = 0L,
        val allowHttp: Boolean = false,
        val leaf: String = "",
        val refused: String = "",
        val error: String = "",
    )

    private fun metaJson(vararg records: Record): String = JSONObject().apply {
        for (record in records) {
            put(
                record.url,
                JSONObject().put("url", record.url).put("title", record.title).put("autoUpdateMinutes", record.minutes)
                    .put("lastUpdatedAt", record.updatedAt).put("lastAttemptAt", record.attemptAt).put("allowHttp", record.allowHttp)
                    .apply {
                        if (record.leaf.isNotEmpty()) put("certSha256", record.leaf).put("certNotAfter", 9L).put("certKind", "self").put("certNames", "sub.example.invalid")
                        if (record.refused.isNotEmpty()) put("refusedSha256", record.refused).put("refusedNotAfter", 8L).put("refusedKind", "self").put("refusedNames", "x")
                        if (record.error.isNotEmpty()) put("lastError", record.error)
                    },
            )
        }
    }.toString()

    /** A store at schema 8 with [records] and one node per entry of [nodes]: the profile's slot to the key it is under. */
    private fun store(records: String, nodes: Map<Int, String>): MutablePreferences = mutablePreferencesOf().also { prefs ->
        prefs[intPreferencesKey("store_schema_version")] = 8
        prefs[stringPreferencesKey("subscription_meta_v1")] = records
        for ((slot, url) in nodes) {
            prefs[stringPreferencesKey("subscription_url_$slot")] = url
            prefs[stringPreferencesKey("csqtt_link_$slot")] = "csqtt://connect?v=2&host=203.0.113.$slot&peer=46000&password=p$slot"
            prefs[booleanPreferencesKey("csqtt_link_mode_$slot")] = true
            prefs[stringPreferencesKey("subscription_title_$slot")] = "Sub"
            prefs[stringPreferencesKey("subscription_node_$slot")] = "node $slot"
            prefs[stringPreferencesKey("profile_name_$slot")] = "node $slot"
        }
    }

    private fun metas(prefs: MutablePreferences) = SettingsStore.subscriptionMetaOf(prefs)

    private fun keyOf(prefs: MutablePreferences, slot: Int) = prefs[stringPreferencesKey("subscription_url_$slot")]

    @Test
    fun `2_5_3's pair of one address becomes one marked record with every profile on it`() {
        // 2.5.3: the link added plainly, and again with «Скачивать по HTTP»
        val prefs = store(
            metaJson(Record(https, title = "Sub", minutes = 60, updatedAt = 2_000L), Record(http, title = "Sub", updatedAt = 1_000L, allowHttp = true, leaf = leafOld)),
            mapOf(3 to https, 4 to https, 5 to http),
        )
        SettingsStore.migrateStore(prefs)

        val merged = metas(prefs)
        assertEquals(setOf(http), merged.keys)
        val kept = merged.getValue(http)
        assertTrue("the mark is never lost", kept.allowHttp)
        assertEquals(leafOld, kept.certSha256)
        assertEquals("the interval it lacked comes from the twin", 60, kept.autoUpdateMinutes)
        assertEquals(2_000L, kept.lastUpdatedAt)
        assertEquals(listOf(http, http, http), listOf(3, 4, 5).map { keyOf(prefs, it) })
        assertEquals(SettingsStore.STORE_SCHEMA, prefs[intPreferencesKey("store_schema_version")])
    }

    @Test
    fun `a pair that differs by the host's letter case is one, on the one more profiles are on`() {
        val upper = "https://Sub.Example.invalid/s/abc"
        val prefs = store(metaJson(Record(upper, title = "Мой"), Record(https, minutes = 30)), mapOf(1 to upper, 2 to https, 3 to https))
        SettingsStore.migrateStore(prefs)

        val kept = metas(prefs).values.single()
        assertEquals(https, kept.url)
        assertEquals("the name it lacked comes from the twin", "Мой", kept.title)
        assertEquals(30, kept.autoUpdateMinutes)
        assertEquals(listOf(https, https, https), listOf(1, 2, 3).map { keyOf(prefs, it) })
    }

    @Test
    fun `three spellings are one, and another subscription beside them is left as it is`() {
        val bare = "https://SUB.example.invalid:443/s/abc"
        val other = "https://sub.example.invalid/s/other"
        val prefs = store(
            metaJson(Record(https, updatedAt = 5L), Record(bare, updatedAt = 7L), Record(http, allowHttp = true), Record(other, title = "Другая", minutes = 15)),
            mapOf(1 to https, 2 to bare, 3 to http, 4 to other),
        )
        SettingsStore.migrateStore(prefs)

        val merged = metas(prefs)
        assertEquals(setOf(http, other), merged.keys)
        assertEquals(7L, merged.getValue(http).lastUpdatedAt)
        assertEquals(SubscriptionMeta(url = other, title = "Другая", autoUpdateMinutes = 15), merged.getValue(other))
        assertEquals(listOf(http, http, http, other), listOf(1, 2, 3, 4).map { keyOf(prefs, it) })
    }

    @Test
    fun `of two different certificates the older twin's is kept, so the newer one is asked about`() {
        // the https:// twin took the server's new certificate by a first use; the marked one still trusts the old one
        val newer = Record(https, updatedAt = 9_000L, allowHttp = true, leaf = leafNew)
        val older = Record(http, updatedAt = 1_000L, allowHttp = true, leaf = leafOld)
        for (records in listOf(metaJson(newer, older), metaJson(older, newer))) {
            // whichever of them stays: here the one more profiles are on, the newer one
            val prefs = store(records, mapOf(1 to https, 2 to https, 3 to http))
            SettingsStore.migrateStore(prefs)
            val kept = metas(prefs).values.single()
            assertEquals(https, kept.url)
            assertEquals(leafOld, kept.certSha256)
            assertEquals("no question is answered for the user", "", kept.refusedSha256)
        }
    }

    @Test
    fun `the question the one that stays already asks is kept`() {
        val asking = Record(http, updatedAt = 1_000L, allowHttp = true, leaf = leafOld, refused = leafNew, error = "Сертификат сервера подписки изменился: обновление отклонено")
        val prefs = store(metaJson(asking, Record(https, updatedAt = 9_000L, leaf = leafNew)), mapOf(1 to http, 2 to https))
        SettingsStore.migrateStore(prefs)

        val kept = metas(prefs).values.single()
        assertEquals(http, kept.url)
        assertEquals(leafOld, kept.certSha256)
        assertEquals(leafNew, kept.refusedSha256)
        assertTrue(kept.lastError.isNotEmpty())
    }

    @Test
    fun `one remembered certificate among the twins is the one kept`() {
        val prefs = store(metaJson(Record(http, allowHttp = true), Record(https, allowHttp = true, leaf = leafOld, updatedAt = 3L)), mapOf(1 to http, 2 to http, 3 to https))
        SettingsStore.migrateStore(prefs)
        assertEquals(leafOld, metas(prefs).getValue(http).certSha256)
    }

    @Test
    fun `a key only profiles name, with no record of its own, is a twin all the same`() {
        val prefs = store(metaJson(Record(https, title = "Sub", minutes = 60)), mapOf(1 to https, 2 to http))
        SettingsStore.migrateStore(prefs)

        assertEquals("an http:// key is a marked one", setOf(http), metas(prefs).keys)
        assertEquals(60, metas(prefs).getValue(http).autoUpdateMinutes)
        assertEquals(listOf(http, http), listOf(1, 2).map { keyOf(prefs, it) })
    }

    @Test
    fun `a store without twins comes out equal but for the schema's number`() {
        for (json in listOf(Store253Snapshots.subscriptionAndToken, Store253Snapshots.owner, Store253Snapshots.participant)) {
            val prefs = load(json)
            prefs[intPreferencesKey("store_schema_version")] = 8
            val before = asMap(prefs)
            SettingsStore.migrateStore(prefs)
            assertEquals(setOf("store_schema_version"), changedKeys(before, asMap(prefs)))
        }
        val two = store(metaJson(Record(https, minutes = 60), Record("https://other.example.invalid/s/abc")), mapOf(1 to https, 2 to "https://other.example.invalid/s/abc"))
        val before = asMap(two)
        SettingsStore.migrateStore(two)
        assertEquals(setOf("store_schema_version"), changedKeys(before, asMap(two)))
    }

    @Test
    fun `a profile changes in nothing but the subscription it points to, and none is removed`() {
        val prefs = store(metaJson(Record(https), Record(http, allowHttp = true)), mapOf(1 to https, 2 to http, 6 to https))
        prefs[intPreferencesKey("active_profile")] = 6
        prefs[intPreferencesKey("run_profile")] = 1
        prefs[booleanPreferencesKey("profile_hidden_1")] = true
        prefs[booleanPreferencesKey("profile_pinned_6")] = true
        prefs[stringPreferencesKey("profile_name_override_6")] = "Дом"
        prefs[stringPreferencesKey("profile_order")] = "6,1,2"
        val before = asMap(prefs)
        SettingsStore.migrateStore(prefs)

        assertEquals(
            setOf("store_schema_version", "subscription_meta_v1", "subscription_url_1", "subscription_url_6"),
            changedKeys(before, asMap(prefs)),
        )
        assertEquals(6, prefs[intPreferencesKey("active_profile")])
        assertEquals(1, prefs[intPreferencesKey("run_profile")])
        assertEquals(3, SettingsStore.profileEntries(prefs).size)
    }

    @Test
    fun `the merge runs once, and a second run finds nothing to do`() {
        val prefs = store(metaJson(Record(https), Record(http, allowHttp = true)), mapOf(1 to https, 2 to http))
        SettingsStore.migrateStore(prefs)
        val once = asMap(prefs)
        SettingsStore.mergeSubscriptionTwins(prefs)
        assertEquals(once, asMap(prefs))
    }

    @Test
    fun `a link with no host is nobody's twin`() {
        val prefs = store(metaJson(Record("not a link"), Record("also not")), mapOf(1 to "not a link"))
        val before = asMap(prefs)
        SettingsStore.mergeSubscriptionTwins(prefs)
        assertEquals(before, asMap(prefs))
    }

    @Test
    fun `a restored backup that holds a subscription twice is merged too`() {
        val backup = JSONObject().put("format", StoreBackup.FORMAT).put("version", StoreBackup.VERSION).put("vps", org.json.JSONArray())
            .put(
                "profiles",
                org.json.JSONArray()
                    .put(JSONObject().put("index", 1).put("csqtt_link_mode", true).put("csqtt_link", "csqtt://connect?v=2&host=203.0.113.1&peer=46000&password=a").put("subscription_url", https))
                    .put(JSONObject().put("index", 2).put("csqtt_link_mode", true).put("csqtt_link", "csqtt://connect?v=2&host=203.0.113.2&peer=46000&password=b").put("subscription_url", http)),
            )
            .put("settings", JSONObject().put("active_profile", 1).put("subscription_meta_v1", metaJson(Record(https, minutes = 60), Record(http, allowHttp = true))))
        val prefs = mutablePreferencesOf()
        StoreBackup.restore(prefs, backup.toString(), encrypt = { it }, decrypt = { it })

        assertEquals(setOf(http), metas(prefs).keys)
        assertEquals(listOf(http, http), listOf(1, 2).map { keyOf(prefs, it) })
        assertFalse(metas(prefs).getValue(http).certSha256.isNotEmpty())
        assertNull(prefs[stringPreferencesKey("subscription_url_3")])
    }
}
