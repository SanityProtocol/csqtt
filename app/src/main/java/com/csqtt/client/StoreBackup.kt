package com.csqtt.client

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.csqtt.client.ui.legacy.utils.isValidTunnelPassword
import org.json.JSONArray
import org.json.JSONObject

/**
 * M-42: one file with every profile, every VPS record and the app settings, to move to another phone (B5b2).
 *
 * Generic over the store's declarations: a profile or record is its index plus every declared key it holds, typed. A restore
 * takes only declared keys of the matching type, so an edited or newer file can only skip a value, never mistype one.
 *
 * Secrets: a keystore ciphertext is never written (another phone cannot read it). The connection password is part of the
 * profile (every link carries it) and always goes in, decrypted. The servers' secrets (SSH password, key, passphrase,
 * certificate, the panel's and the tunnel's server passwords) go in decrypted only with [encode]'s `serverSecrets`. The VK
 * access token and its user id never do: a VK account credential that a sign-in renews (#6's rule).
 */
internal object StoreBackup {
    const val FORMAT = "csqtt-backup"
    const val VERSION = 1

    private const val ENCRYPTED = "_encrypted"
    private const val CONNECTION_PASSWORD = "connection_password"
    private const val CONNECTION_PASSWORD_LOCAL = "connection_password_local"
    private const val VK_TOKEN = "vk_access_token"
    private const val VK_TOKEN_USER = "vk_access_token_user_id"
    private val indices = CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX

    private val declarations get() = SettingsStore.perProfileDeclarations

    /** The plain twin of every encrypted key: `deploy_password` for `deploy_password_encrypted`. */
    private val secretNames: Set<String> by lazy {
        declarations.keys.filter { it.endsWith(ENCRYPTED) }.mapTo(HashSet()) { it.removeSuffix(ENCRYPTED) }
    }

    /** Keys the generic copy skips: ciphertexts, their plain twins and the token's user id (each handled by name). */
    private val notCopied: Set<String> by lazy {
        declarations.keys.filterTo(HashSet()) { it.endsWith(ENCRYPTED) || it in secretNames } +
            setOf(CONNECTION_PASSWORD_LOCAL, VK_TOKEN_USER)
    }

    fun isBackup(json: String): Boolean =
        runCatching { JSONObject(json).optString("format") == FORMAT }.getOrDefault(false)

    fun encode(prefs: Preferences, serverSecrets: Boolean, decrypt: (String?) -> String?, exportedAt: Long): String {
        val values = prefs.asMap()
        fun value(name: String, index: Int): Any? =
            declarations[name]?.let { values[it.make(SettingsStore.slotKeyName(name, index))] }

        fun record(index: Int, group: ProfileKeyGroup): JSONObject {
            val entry = JSONObject().put("index", index)
            for ((name, key) in declarations) {
                if (key.group != group || name in notCopied) continue
                val stored = value(name, index) ?: continue
                if (kindOf(stored) == key.kind) entry.put(name, stored)
            }
            for (name in secretNames) {
                if (declarations[name]?.group != group) continue
                val plain = value(name, index) as? String
                val encrypted = value(name + ENCRYPTED, index) as? String
                when (name) {
                    CONNECTION_PASSWORD -> resolveTunnelPasswordStorage(
                        value(CONNECTION_PASSWORD_LOCAL, index) as? String, plain, encrypted, decrypt,
                    ).value.takeIf { it.isNotBlank() }?.let { entry.put(CONNECTION_PASSWORD_LOCAL, it) }
                    VK_TOKEN -> Unit
                    else -> if (serverSecrets) {
                        resolveStoredSecret(encrypted, plain, decrypt).value.takeIf { it.isNotBlank() }?.let { entry.put(name, it) }
                    }
                }
            }
            return entry
        }

        val active = prefs[intPreferencesKey("active_profile")] ?: 0
        val profiles = JSONArray()
        indices.filter { it == active || SettingsStore.profileInUse(prefs, it) }
            .forEach { profiles.put(record(it, ProfileKeyGroup.CONNECTION)) }
        val linked = indices.mapNotNullTo(HashSet()) { SettingsStore.linkedVps(prefs, it) }
        val records = JSONArray()
        indices.filter { it in linked || SettingsStore.vpsRecordExists(prefs, it) }
            .forEach { records.put(record(it, ProfileKeyGroup.VPS)) }
        val settings = JSONObject()
        for ((name, kind) in SettingsStore.backupGlobalKeys) {
            val stored = values[stringPreferencesKey(name)] ?: continue
            if (kindOf(stored) == kind) settings.put(name, stored)
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exported_at", exportedAt)
            .put("server_secrets", serverSecrets)
            .put("profiles", profiles)
            .put("vps", records)
            .put("settings", settings)
            .toString(2)
    }

    /**
     * Replaces every profile, VPS record and backed-up setting with the file's. Throws on a file that is not a backup, before
     * anything is changed. Secrets are encrypted again with this phone's key.
     */
    fun restore(prefs: MutablePreferences, json: String, encrypt: (String) -> String, decrypt: (String?) -> String?) {
        val root = JSONObject(json)
        require(root.optString("format") == FORMAT) { "not a csqtt backup" }
        require(root.optInt("version", 0) in 1..VERSION) { "backup version ${root.opt("version")}" }
        val profiles = root.optJSONArray("profiles") ?: JSONArray()
        val records = root.optJSONArray("vps") ?: JSONArray()
        val settings = root.optJSONObject("settings") ?: JSONObject()

        for (index in indices) {
            SettingsStore.clearProfileKeys(prefs, index, ProfileKeyGroup.CONNECTION)
            SettingsStore.clearProfileKeys(prefs, index, ProfileKeyGroup.VPS)
        }
        for (name in SettingsStore.backupGlobalKeys.keys) prefs.remove(stringPreferencesKey(name))

        fun write(entry: JSONObject, group: ProfileKeyGroup): Int? {
            val index = entry.opt("index") as? Int ?: return null
            if (index !in indices) return null
            for (name in entry.keys()) {
                val raw = entry.get(name)
                fun key(keyName: String) = stringPreferencesKey(SettingsStore.slotKeyName(keyName, index))
                when {
                    name == "index" -> Unit
                    name == CONNECTION_PASSWORD_LOCAL -> if (group == ProfileKeyGroup.CONNECTION &&
                        raw is String && raw.isNotBlank() && isValidTunnelPassword(raw)
                    ) {
                        prefs[key(name)] = raw
                    }
                    name in secretNames -> if (name != CONNECTION_PASSWORD && name != VK_TOKEN &&
                        declarations[name]?.group == group && raw is String
                    ) {
                        prefs.putSecretValue(key(name + ENCRYPTED), key(name), raw, encrypt, decrypt)
                    }
                    name in notCopied -> Unit
                    else -> declarations[name]?.takeIf { it.group == group }?.let { declared ->
                        putTyped(prefs, declared.make(SettingsStore.slotKeyName(name, index)), declared.kind, raw)
                    }
                }
            }
            return index
        }

        val restoredRecords = (0 until records.length()).mapNotNullTo(HashSet()) { i ->
            records.optJSONObject(i)?.let { write(it, ProfileKeyGroup.VPS) }
        }
        for (i in 0 until profiles.length()) profiles.optJSONObject(i)?.let { write(it, ProfileKeyGroup.CONNECTION) }
        // a link to a record the file does not hold would be a VPS made of nothing
        val vpsRef = declarations.getValue("vps_ref")
        for (index in indices) {
            val key = vpsRef.make(SettingsStore.slotKeyName("vps_ref", index))
            if (prefs.asMap()[key] != null && SettingsStore.linkedVps(prefs, index) !in restoredRecords) prefs.remove(key)
        }
        for ((name, kind) in SettingsStore.backupGlobalKeys) {
            if (!settings.has(name)) continue
            val key: Preferences.Key<*> = when (kind) {
                StoredKind.STRING -> stringPreferencesKey(name)
                StoredKind.INT -> intPreferencesKey(name)
                StoredKind.BOOLEAN -> booleanPreferencesKey(name)
                StoredKind.LONG -> longPreferencesKey(name)
            }
            putTyped(prefs, key, kind, settings.get(name))
        }
        SettingsStore.bumpProfileEpoch(prefs)
        val activeKey = intPreferencesKey("active_profile")
        prefs[activeKey]?.let { if (it !in indices) prefs[activeKey] = CsqttConstants.Profiles.MIN_INDEX }
    }

    private fun kindOf(value: Any): StoredKind? = when (value) {
        is String -> StoredKind.STRING
        is Int -> StoredKind.INT
        is Boolean -> StoredKind.BOOLEAN
        is Long -> StoredKind.LONG
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    private fun putTyped(prefs: MutablePreferences, key: Preferences.Key<*>, kind: StoredKind, raw: Any) {
        val value: Any = when (kind) {
            StoredKind.STRING -> raw as? String
            StoredKind.BOOLEAN -> raw as? Boolean
            StoredKind.INT -> when (raw) {
                is Int -> raw
                is Long -> raw.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
                else -> null
            }
            StoredKind.LONG -> (raw as? Int)?.toLong() ?: raw as? Long
        } ?: return
        prefs[key as Preferences.Key<Any>] = value
    }
}
