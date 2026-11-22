package com.csqtt.client

import android.content.Context
import android.content.pm.PackageManager
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import org.json.JSONObject
import com.csqtt.client.ProfileKeyGroup.CONNECTION
import com.csqtt.client.ProfileKeyGroup.VPS
import com.csqtt.client.ui.legacy.utils.isValidTunnelPassword
import com.csqtt.client.ui.legacy.utils.parseCsqttLink

private fun <T> Flow<T>.onIo(): Flow<T> = flowOn(Dispatchers.IO)

/** What a per-profile key belongs to: the connection, or the VPS the deploy screen manages (M-40; B5b2 splits them). */
internal enum class ProfileKeyGroup { CONNECTION, VPS }

/** How a key's value is stored: a backup (M-42) restores a value only when its type is the declared one. */
internal enum class StoredKind { STRING, INT, BOOLEAN, LONG }

internal class PerProfileKey(val make: (String) -> Preferences.Key<*>, val group: ProfileKeyGroup, val kind: StoredKind)

/**
 * Stores [value] encrypted under [encryptedKey] and drops the legacy plain copy. An unchanged value writes nothing: a fresh
 * ciphertext would rewrite the store and wake every collector, the tunnel service's too (M-37).
 */
internal fun MutablePreferences.putSecretValue(
    encryptedKey: Preferences.Key<String>,
    legacyKey: Preferences.Key<String>,
    value: String,
    encrypt: (String) -> String,
    decrypt: (String?) -> String?,
) {
    if (value.isBlank()) {
        remove(encryptedKey)
        remove(legacyKey)
        return
    }
    val stored = this[encryptedKey]
    if (stored == null || runCatching { decrypt(stored) }.getOrNull() != value) {
        this[encryptedKey] = encrypt(value)
    }
    remove(legacyKey)
}

/** D6 (a1): the server panel an owner's pool is handed to, and the VPS record that keeps the pool (B5e3b). */
internal data class PoolPanel(
    val vps: Int,
    val host: String,
    val webPort: Int,
    val secretPath: String,
    val webPassword: String,
) {
    override fun toString(): String = "PoolPanel(vps=$vps, host=$host, webPort=$webPort)"
}

internal data class DeploySettingsSnapshot(
    val profile: Int = -1,
    /** See PROFILE_EPOCH: the deploy tab's saved form state is keyed on it. */
    val epoch: Int = 0,
    val ip: String = "",
    val serverName: String = "",
    val sshLogin: String = "",
    val sshPassword: String = "",
    val webLogin: String = "",
    val webPassword: String = "",
    val mainPassword: String = "",
    val sshPort: String = CsqttConstants.Network.DEFAULT_SSH_PORT.toString(),
    val manualPortsEnabled: Boolean = false,
    val serverPeerPort: Int = CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT,
    val serverWebPort: Int = CsqttConstants.Network.DEFAULT_SERVER_WEB_PORT,
    /** The form's ports with «Ручные порты» on, kept apart from the ports the profile dials until a deploy succeeds. */
    val draftPeerPort: Int? = null,
    val draftWebPort: Int? = null,
    val sshKeysMode: Boolean = false,
    val dockerInstall: Boolean = false,
    val secretPathEnabled: Boolean = true,
    val secretPathUuid: String = "",
    val serverConfigured: Boolean = false,
    val lastInspectionAtMs: Long = 0L,
    val lastInspectionOk: Boolean = false,
    val lastInspectionArch: String = "",
    val lastInspectionServiceStatus: String = "",
    val lastInspectionVersion: String = "",
    val lastInspectionMessage: String = "",
    val lastPanelAvailable: Boolean = false,
    val sshPrivateKey: String = "",
    val sshKeyPassphrase: String = "",
    val sshCertificate: String = "",
) {
    val isLoaded: Boolean
        get() = profile >= 0
}

internal data class TunnelAuthSnapshot(
    val profile: Int = -1,
    val connectionPassword: String = "",
    val connectionPasswordState: StoredSecretState = StoredSecretState.Missing,
    val csqttLink: String = "",
    val csqttLinkMode: Boolean = false,
) {
    val isLoaded: Boolean
        get() = profile >= 0
}

/** Snapshot of the connection settings of a concrete profile slot (not necessarily active). */
internal data class ProfileConnectionSnapshot(
    val peer: String = "",
    val serverPeerPort: Int = CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT,
    val connectionPassword: String = "",
    val connectionPasswordState: StoredSecretState = StoredSecretState.Missing,
    val workersPerHash: Int = CsqttConstants.Tunnel.DEFAULT_WORKERS,
    val extraWorkers: Boolean = false,
    val csqttLink: String = "",
    val csqttLinkMode: Boolean = false,
    val subscriptionUrl: String = "",
    val subscriptionTitle: String = "",
    val subscriptionPower: Int = 0,
)

internal data class ProfileSubscriptionInfo(
    val url: String,
    val title: String,
    val subUuid: String,
    val nodeName: String,
    val powerLimit: Int,
)

internal data class SubscriptionMeta(
    val url: String = "",
    val title: String = "",
    val autoUpdateMinutes: Int = 0,
    val lastUpdatedAt: Long = 0L,
    val lastAttemptAt: Long = 0L,
    /**
     * V2's «Скачивать по HTTP»: the user accepted this subscription's server certificate unchecked. It now means the
     * server is trusted by its pinned certificate (M-171), never over plain HTTP.
     */
    val allowHttp: Boolean = false,
    /**
     * The last leaf certificate a marked subscription's server was trusted with (SHA-256 hex, and its notAfter in ms):
     * a certificate the platform refuses is accepted only if it is this one or this one has expired (M-171).
     */
    val certSha256: String = "",
    val certNotAfter: Long = 0L,
    /** How that leaf was shown ([CertKind.stored]) and the names it certifies, comma-joined (review V1). */
    val certKind: String = "",
    val certNames: String = "",
    /** B5h2 (U3): the last refresh's failure, masked; "" after a success. Its time is [lastAttemptAt]. */
    val lastError: String = "",
    /** B5h2 (U3): what the last successful refresh skipped, and why, masked; "" when nothing was. */
    val lastSkipped: String = "",
) {
    fun needsUpdate(now: Long): Boolean {
        if (autoUpdateMinutes <= 0 || url.isBlank()) return false
        val last = maxOf(lastUpdatedAt, lastAttemptAt)
        return last == 0L || now - last >= autoUpdateMinutes * 60_000L
    }
}

internal fun defaultProfileName(index: Int): String = "Профиль ${index + 1}"

private fun decodeSubscriptionMeta(raw: String?): Map<String, SubscriptionMeta> {
    if (raw.isNullOrBlank()) return emptyMap()
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
    val result = linkedMapOf<String, SubscriptionMeta>()
    val keys = json.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val entry = json.optJSONObject(key) ?: continue
        val url = entry.optString("url").trim().ifEmpty { key.trim() }
        if (url.isEmpty()) continue
        result[url] = SubscriptionMeta(
            url = url,
            title = entry.optString("title").trim(),
            autoUpdateMinutes = entry.optInt("autoUpdateMinutes", 0).coerceAtLeast(0),
            lastUpdatedAt = entry.optLong("lastUpdatedAt", 0L).coerceAtLeast(0L),
            lastAttemptAt = entry.optLong("lastAttemptAt", 0L).coerceAtLeast(0L),
            allowHttp = entry.optBoolean("allowHttp", false),
            certSha256 = entry.optString("certSha256").trim(),
            certNotAfter = entry.optLong("certNotAfter", 0L).coerceAtLeast(0L),
            certKind = entry.optString("certKind").trim(),
            certNames = entry.optString("certNames").trim(),
            lastError = entry.optString("lastError").trim(),
            lastSkipped = entry.optString("lastSkipped").trim(),
        )
    }
    return result
}

private fun encodeSubscriptionMeta(meta: Map<String, SubscriptionMeta>): String {
    val json = JSONObject()
    for ((key, value) in meta) {
        if (key.isBlank()) continue
        json.put(
            key,
            JSONObject().apply {
                put("url", value.url)
                put("title", value.title)
                put("autoUpdateMinutes", value.autoUpdateMinutes)
                put("lastUpdatedAt", value.lastUpdatedAt)
                put("lastAttemptAt", value.lastAttemptAt)
                put("allowHttp", value.allowHttp)
                if (value.certSha256.isNotBlank()) {
                    put("certSha256", value.certSha256)
                    put("certNotAfter", value.certNotAfter)
                    put("certKind", value.certKind)
                    put("certNames", value.certNames)
                }
                if (value.lastError.isNotEmpty()) put("lastError", value.lastError)
                if (value.lastSkipped.isNotEmpty()) put("lastSkipped", value.lastSkipped)
            },
        )
    }
    return json.toString()
}

/**
 * Which profile a store's per-profile reads and writes use (the run reads its own profile, the operator 2026-11-21):
 * [Active], the profile open in the screens; [Run], the profile the tunnel runs on (run_profile, else the active one) —
 * for the run-time code, so editing another profile never touches the live run; [Fixed], one profile (a switch's target).
 */
internal sealed interface ProfileScope {
    data object Active : ProfileScope
    data object Run : ProfileScope
    data class Fixed(val profile: Int) : ProfileScope
}

class SettingsStore internal constructor(context: Context, private val scope: ProfileScope) {
    constructor(context: Context) : this(context, ProfileScope.Active)

    internal val appContext: Context = context.applicationContext
    companion object {
        // Every per-profile key, declared once with its type and its group (M-37, M-40). It is filled by the
        // profileString/Int/Boolean/Long calls below, so it stays first in the companion.
        private val perProfileKeys = linkedMapOf<String, PerProfileKey>()

        private fun <T> perProfile(
            key: Preferences.Key<T>,
            make: (String) -> Preferences.Key<T>,
            group: ProfileKeyGroup,
            kind: StoredKind,
        ): Preferences.Key<T> {
            check(perProfileKeys.put(key.name, PerProfileKey(make, group, kind)) == null) { "per-profile key ${key.name} twice" }
            return key
        }
        private fun profileString(name: String, group: ProfileKeyGroup = CONNECTION) =
            perProfile(stringPreferencesKey(name), ::stringPreferencesKey, group, StoredKind.STRING)
        private fun profileInt(name: String, group: ProfileKeyGroup = CONNECTION) =
            perProfile(intPreferencesKey(name), ::intPreferencesKey, group, StoredKind.INT)
        private fun profileBoolean(name: String, group: ProfileKeyGroup = CONNECTION) =
            perProfile(booleanPreferencesKey(name), ::booleanPreferencesKey, group, StoredKind.BOOLEAN)
        private fun profileLong(name: String, group: ProfileKeyGroup = CONNECTION) =
            perProfile(longPreferencesKey(name), ::longPreferencesKey, group, StoredKind.LONG)

        private val Context.dataStore by preferencesDataStore(
            "settings",
            produceMigrations = { listOf(StoreMigrations) },
        )
        private val ACTIVE_PROFILE = intPreferencesKey("active_profile")
        private val SHOW_SYSTEM_APPS = booleanPreferencesKey("show_system_apps")
        private val INFO_ACTIONS_EXPANDED = booleanPreferencesKey("info_actions_expanded")
        private val INFO_PROJECT_EXPANDED = booleanPreferencesKey("info_project_expanded")
        private val CSQTT_LINK = profileString("csqtt_link")
        private val CSQTT_LINK_MODE = profileBoolean("csqtt_link_mode")
        // the owner's link hashes a participant took out of the connection (per profile, comma-separated)
        private val LINK_HASHES_REMOVED = profileString("link_hashes_removed")
        // S8: the owner's hashes the server last sent for a link profile; they replace the link's at the next connect
        private val RECEIVED_HASHES = profileString("received_hashes")
        private val SUBSCRIPTION_URL = profileString("subscription_url")
        private val SUBSCRIPTION_TITLE = profileString("subscription_title")
        private val SUBSCRIPTION_UUID = profileString("subscription_uuid")
        private val SUBSCRIPTION_NODE = profileString("subscription_node")
        private val SUBSCRIPTION_POWER = profileInt("subscription_power")
        private val SUBSCRIPTION_META = stringPreferencesKey("subscription_meta_v1")

        private val PEER = profileString("peer")
        private val VK_HASHES = profileString("vk_hashes")
        private val SECONDARY_VK_HASH = profileString("secondary_vk_hash")
        private val WORKERS_PER_HASH = profileInt("workers_per_hash")
        private val PROTOCOL = profileString("protocol")
        private val LISTEN_PORT = profileInt("listen_port")
        private val MANUAL_PORTS_ENABLED = profileBoolean("manual_ports_enabled", VPS)
        private val SERVER_PEER_PORT = profileInt("server_peer_port")
        // the web port of the installation the profile connects to (its panel); the deploy form's draft is DEPLOY_WEB_PORT
        private val SERVER_WEB_PORT = profileInt("server_web_port")
        // the index of the profile's VPS record (B5b2): the VPS keys with that suffix; absent when it has none
        private val VPS_REF = profileInt("vps_ref")
        // set by «Добавить профиль», so a profile with no address or link yet stays listed and is never reused as free
        private val PROFILE_ADDED = profileBoolean("profile_added")
        // changes whenever a slot's profile is replaced (delete, add, restore): screens key their saved form state on it,
        // so a form edited for the old profile never autosaves into the new one
        private val PROFILE_EPOCH = intPreferencesKey("profile_records_epoch")
        private val SNI = profileString("sni")
        private val NO_DNS = profileBoolean("no_dns")
        private val CUSTOM_TURN_IPS = profileString("custom_turn_ips")
        private val SELECTED_TURN_SUBNET = profileString("selected_turn_subnet")
        private val TURN_SUBNET_IP_LIMIT = profileString("turn_subnet_ip_limit")

        private val PROFILE_NAME = profileString("profile_name")

        private val DEPLOY_IP = profileString("deploy_ip", VPS)
        private val DEPLOY_SERVER_NAME = profileString("deploy_server_name", VPS)
        private val DEPLOY_PROFILE_NAMES_CLEANED = booleanPreferencesKey("deploy_profile_names_cleaned_v2")
        private val DEFAULT_ROLE_DECIDED = booleanPreferencesKey("default_role_decided")
        // M-75's role (B5e1, written by store step 4); step 5 turns it into SHOW_DEPLOY_TAB and removes it
        private val UI_OWNER_MODE = booleanPreferencesKey("ui_owner_mode")
        // the roles batch: what the app shows, app-wide — «Показывать логи» (on when absent) and «Показывать деплой» (off
        // when absent: a fresh install is participant-first, D28). How each profile connects follows what it holds
        private val SHOW_LOGS_TAB = booleanPreferencesKey("show_logs_tab")
        private val SHOW_DEPLOY_TAB = booleanPreferencesKey("show_deploy_tab")
        // M-79: VK accounts by user id, each token encrypted (VkAccounts); a device credential, never in a backup
        private val VK_ACCOUNTS = stringPreferencesKey("vk_accounts_v1")
        private val DEPLOY_LOGIN = profileString("deploy_login", VPS)
        private val DEPLOY_PASSWORD = profileString("deploy_password", VPS)
        private val DEPLOY_PASSWORD_ENCRYPTED = profileString("deploy_password_encrypted", VPS)
        private val DEPLOY_SSH_PORT = profileString("deploy_ssh_port", VPS)
        private val SSH_KEYS_MODE = profileBoolean("ssh_keys_mode", VPS)
        private val DOCKER_INSTALL = profileBoolean("docker_install", VPS)
        private val DEPLOY_SECRET_PATH_ENABLED = profileBoolean("deploy_secret_path_enabled", VPS)
        private val DEPLOY_SECRET_PATH_UUID = profileString("deploy_secret_path_uuid", VPS)
        private val DEPLOY_SERVER_CONFIGURED = profileBoolean("deploy_server_configured", VPS)
        private val DEPLOY_LAST_INSPECTION_AT = profileLong("deploy_last_inspection_at", VPS)
        private val DEPLOY_LAST_INSPECTION_OK = profileBoolean("deploy_last_inspection_ok", VPS)
        private val DEPLOY_LAST_INSPECTION_ARCH = profileString("deploy_last_inspection_arch", VPS)
        private val DEPLOY_LAST_INSPECTION_SERVICE = profileString("deploy_last_inspection_service", VPS)
        private val DEPLOY_LAST_INSPECTION_VERSION = profileString("deploy_last_inspection_version", VPS)
        private val DEPLOY_LAST_INSPECTION_MESSAGE = profileString("deploy_last_inspection_message", VPS)
        private val DEPLOY_LAST_INSPECTION_PANEL = profileBoolean("deploy_last_inspection_panel", VPS)
        private val SSH_PRIVATE_KEY = profileString("ssh_private_key", VPS)
        private val SSH_PRIVATE_KEY_ENCRYPTED = profileString("ssh_private_key_encrypted", VPS)
        private val SSH_KEY_PASSPHRASE = profileString("ssh_key_passphrase", VPS)
        private val SSH_KEY_PASSPHRASE_ENCRYPTED = profileString("ssh_key_passphrase_encrypted", VPS)
        private val SSH_CERTIFICATE = profileString("ssh_certificate", VPS)
        private val SSH_CERTIFICATE_ENCRYPTED = profileString("ssh_certificate_encrypted", VPS)
        private val EXCLUDED_APPS = profileString("excluded_apps")
        private val WHITELIST_APPS = profileString("whitelist_apps")


        private val CONNECTION_PASSWORD = profileString("connection_password")
        private val CONNECTION_PASSWORD_ENCRYPTED = profileString("connection_password_encrypted")
        private val CONNECTION_PASSWORD_LOCAL = profileString("connection_password_local")
        private val DEPLOY_MAIN_PASSWORD = profileString("deploy_main_password", VPS)
        private val DEPLOY_MAIN_PASSWORD_ENCRYPTED = profileString("deploy_main_password_encrypted", VPS)
        private val DEPLOY_WEB_LOGIN = profileString("deploy_web_login", VPS)
        private val DEPLOY_WEB_PASSWORD = profileString("deploy_web_password", VPS)
        private val DEPLOY_WEB_PASSWORD_ENCRYPTED = profileString("deploy_web_password_encrypted", VPS)
        // the deploy form's ports with «Ручные порты» on: a draft until a deploy succeeds and writes the profile's ports
        private val DEPLOY_PEER_PORT = profileInt("deploy_peer_port", VPS)
        private val DEPLOY_WEB_PORT = profileInt("deploy_web_port", VPS)
        // D6 (a1): the VK call hashes this app minted for the server, live ones only, comma-separated (B5e3b)
        private val DEPLOY_VK_POOL = profileString("deploy_vk_pool", VPS)
        // the pool as the server's panel last accepted it, and the panel's leaf certificate it was trusted with (JSON)
        private val DEPLOY_VK_POOL_SENT = profileString("deploy_vk_pool_sent", VPS)
        private val DEPLOY_PANEL_CERT = profileString("deploy_panel_cert", VPS)
        // B5h2: the last hand-over's result (HandOverResult JSON), shown on «Подключение»
        private val DEPLOY_VK_POOL_HANDOVER = profileString("deploy_vk_pool_handover", VPS)


        private val VK_AUTH_MODE = profileString("vk_auth_mode")
        private val OBFS_MODE = profileString("obfs_mode")
        private val TURN_TRANSPORT = profileString("turn_transport")
        private val DPI_SPLIT = profileString("dpi_split")
        // P1 Bt part 2: the VPN interface's MTU (VpnMtu); absent = the default, so a later default reaches old profiles
        private val VPN_MTU = profileInt("vpn_mtu")
        private val SRTP_MODE = profileString("srtp_mode")
        private val CAPTCHA_MODE = profileString("captcha_mode")
        private val CAPTCHA_SOLVE_METHOD = profileString("captcha_solve_method")
        private val CAPTCHA_WBV_SOLVE_METHOD = profileString("captcha_wbv_solve_method")

        private val VK_HASH_MODE = profileString("vk_hash_mode")
        private val VK_ACCESS_TOKEN = profileString("vk_access_token")
        private val VK_ACCESS_TOKEN_ENCRYPTED = profileString("vk_access_token_encrypted")
        private val VK_ACCESS_TOKEN_USER_ID = profileString("vk_access_token_user_id")

        private val IS_WHITELIST = profileBoolean("is_whitelist")
        private val SPLIT_TUNNEL_WHITELIST_MIGRATED = booleanPreferencesKey("split_tunnel_whitelist_migrated")
        private val EXTRA_WORKERS = profileBoolean("extra_workers")
        private val AUTO_JS_RISK_ACKNOWLEDGED = booleanPreferencesKey("auto_js_risk_acknowledged")
        private val TCP_TRANSPORT_RISK_ACKNOWLEDGED = booleanPreferencesKey("tcp_transport_risk_acknowledged")
        private val BATTERY_OPTIMIZATION_PROMPT_HANDLED =
            booleanPreferencesKey("battery_optimization_prompt_handled")
        // the one explanation before the first notification and battery prompts was shown (L-29)
        private val PERMISSION_INTRO_SHOWN = booleanPreferencesKey("permission_intro_shown")
        private val AUTO_PAUSE_ON_WIFI = booleanPreferencesKey("auto_pause_on_wifi")
        // B5f2: where the auto-pause acts — #19's keys; the networks the user dismissed («Не сейчас») added
        private val WIFI_RULE = stringPreferencesKey("wifi_network_list_mode")
        private val WIFI_CHECKED = stringPreferencesKey("wifi_network_list_ssids")
        private val WIFI_SAVED = stringPreferencesKey("wifi_saved_ssids")
        private val WIFI_DISMISSED = stringPreferencesKey("wifi_dismissed_ssids")
        private val BATTERY_SAVER_MODE = stringPreferencesKey("battery_saver_mode")
        private val BATTERY_SAVER_PERCENT = intPreferencesKey("battery_saver_percent")
        private val DOMAIN_ROUTING_ENABLED = profileBoolean("domain_routing_enabled")
        private val DOMAIN_IS_WHITELIST = profileBoolean("domain_is_whitelist")
        private val EXCLUDE_RU_ZONES_ENABLED = profileBoolean("exclude_ru_zones_enabled")
        private val TEMPLATE_DOMAINS_ENABLED = profileBoolean("template_domains_enabled")
        private val DIRECT_DOMAINS = profileString("direct_domains")
        private val DIRECT_DOMAINS_BLACK = profileString("direct_domains_black")
        private val DIRECT_DOMAINS_WHITE = profileString("direct_domains_white")

        private val IP_ROUTING_ENABLED = profileBoolean("ip_routing_enabled")
        private val IP_IS_WHITELIST = profileBoolean("ip_is_whitelist")
        private val DIRECT_IPS_BLACK = profileString("direct_ips_black")
        private val DIRECT_IPS_WHITE = profileString("direct_ips_white")
        // B5d2: «Все с VPN» (M-59), LAN bypass (M-64) and the device's DNS (M-67); their controls beyond the apps
        // dialog are P3, the backup carries them
        private val APP_ROUTING_ALL = profileBoolean("app_routing_all")
        private val LAN_BYPASS = profileBoolean("lan_bypass")
        private val DNS_OVERRIDE = profileString("dns_override")

        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val IS_DYNAMIC_COLOR = booleanPreferencesKey("is_dynamic_color")
        private val THEME_PALETTE = stringPreferencesKey("theme_palette")

        private val SELECTED_FINGERPRINT = profileString("selected_fingerprint")
        private val ACTIVE_CLIENT_IDS = profileString("active_client_ids")

        private val UPDATE_LAST_CHECK_AT = longPreferencesKey("update_last_check_at")
        private val UPDATE_LATEST_VERSION = stringPreferencesKey("update_latest_version")
        private val UPDATE_LAST_ERROR = stringPreferencesKey("update_last_error")
        private val UPDATE_CHECK_INTERVAL_HOURS = intPreferencesKey("update_check_interval_hours")
        private val UPDATE_POSTPONE_UNTIL = longPreferencesKey("update_postpone_until")
        private val UPDATE_POSTPONE_VERSION = stringPreferencesKey("update_postpone_version")
        private val UPDATE_DIALOG_LAST_SHOWN_VERSION = stringPreferencesKey("update_dialog_last_shown_version")
        private val UPDATE_DIALOG_LAST_SHOWN_AT = longPreferencesKey("update_dialog_last_shown_at")
        private val UPDATE_DIALOG_LAST_ACTION_VERSION = stringPreferencesKey("update_dialog_last_action_version")
        private val UPDATE_DIALOG_LAST_ACTION = stringPreferencesKey("update_dialog_last_action")
        private val UPDATE_DIALOG_LAST_ACTION_AT = longPreferencesKey("update_dialog_last_action_at")

        private val CLIENT_ID_CHECK_RESULTS = stringPreferencesKey("client_id_check_results")
        private val VK_HASH_CHECK_RESULTS = profileString("vk_hash_check_results")
        private val CONNECTION_GENERATION = longPreferencesKey("connection_generation")
        private val TUNNEL_LAST_DIAGNOSTIC = stringPreferencesKey("tunnel_last_diagnostic")
        private val TUNNEL_LAST_DIAGNOSTIC_AT = longPreferencesKey("tunnel_last_diagnostic_at")
        private val TUNNEL_WAS_RUNNING = booleanPreferencesKey("tunnel_was_running")
        // B5f: the phone's boot count and the app's install time when the flag was set, so a reboot and an update are
        // told from a kill
        private val TUNNEL_WAS_RUNNING_BOOT = intPreferencesKey("tunnel_was_running_boot")
        private val TUNNEL_WAS_RUNNING_UPDATE = longPreferencesKey("tunnel_was_running_update")
        // B5h1: the cause of the last stop the user did not ask for, until the next start (this device's state)
        private val LAST_STOP_CAUSE = stringPreferencesKey("last_stop_cause")
        // the profile the tunnel runs on, from its start (or the switch onto it) to its stop (this device's state)
        private val RUN_PROFILE = intPreferencesKey("run_profile")

        /** The profile [scope] reads and writes in [prefs] (a Run scope with no run is the active profile). */
        internal fun selectedProfileOf(prefs: Preferences, scope: ProfileScope): Int = when (scope) {
            ProfileScope.Active -> prefs[ACTIVE_PROFILE] ?: 0
            ProfileScope.Run -> prefs[RUN_PROFILE] ?: prefs[ACTIVE_PROFILE] ?: 0
            is ProfileScope.Fixed -> scope.profile
        }

        @Volatile
        internal var cachedVkHashMode: String? = null

        @Volatile
        internal var cachedVkAccessToken: String? = null

        @Volatile
        internal var cachedObfsMode: String? = null

        @Volatile
        internal var cachedTurnTransport: String? = null

        @Volatile
        internal var cachedSrtpMode: String? = null

        @Volatile
        internal var cachedCsqttLinkMode: Boolean? = null

        // the tab switches as last read in this process: a recreated screen starts from them, not from the defaults,
        // so its nav bar does not change on the first frames (roles batch review F2)
        @Volatile
        internal var cachedShowDeployTab: Boolean? = null

        @Volatile
        internal var cachedShowLogsTab: Boolean? = null

        @Volatile
        internal var cachedWorkersPerHash: Int? = null

        @Volatile
        internal var cachedManualPortsEnabled: Boolean? = null

        @Volatile
        internal var cachedServerPeerPort: Int? = null

        @Volatile
        internal var cachedCustomTurnIps: String? = null

        @Volatile
        internal var cachedExcludedApps: String? = null

        @Volatile
        internal var cachedWhitelistApps: String? = null

        @Volatile
        internal var cachedIsWhitelist: Boolean? = null

        @Volatile
        internal var cachedDomainRoutingEnabled: Boolean? = null

        @Volatile
        internal var cachedDomainIsWhitelist: Boolean? = null

        @Volatile
        internal var cachedExcludeRuZonesEnabled: Boolean? = null

        @Volatile
        internal var cachedTemplateDomainsEnabled: Boolean? = null

        @Volatile
        internal var cachedDirectDomains: String? = null

        @Volatile
        internal var cachedDirectDomainsBlack: String? = null

        @Volatile
        internal var cachedDirectDomainsWhite: String? = null

        @Volatile
        internal var cachedIpRoutingEnabled: Boolean? = null

        @Volatile
        internal var cachedIpIsWhitelist: Boolean? = null

        @Volatile
        internal var cachedDirectIpsBlack: String? = null

        @Volatile
        internal var cachedDirectIpsWhite: String? = null

        private val uiCacheStarted = java.util.concurrent.atomic.AtomicBoolean(false)

        private val STORE_SCHEMA_VERSION = intPreferencesKey("store_schema_version")
        internal const val STORE_SCHEMA = 6

        /** Runs before the process's first read or write of the store, so nothing sees half-migrated data. */
        private object StoreMigrations : DataMigration<Preferences> {
            override suspend fun shouldMigrate(currentData: Preferences): Boolean =
                (currentData[STORE_SCHEMA_VERSION] ?: 0) < STORE_SCHEMA

            override suspend fun migrate(currentData: Preferences): Preferences =
                currentData.toMutablePreferences().also(::migrateStore).toPreferences()

            override suspend fun cleanUp() = Unit
        }

        /** For the instrumented upgrade test, which opens a DataStore of its own on a 2.5.3 file. */
        internal val storeMigrations: DataMigration<Preferences> get() = StoreMigrations

        /**
         * The numbered store steps (M-38's rules): each runs once, while the stored version is below it, and derives from
         * the data it finds. A fresh install runs every step on an empty store.
         */
        internal fun migrateStore(prefs: MutablePreferences) {
            val from = prefs[STORE_SCHEMA_VERSION] ?: 0
            if (from >= STORE_SCHEMA) return
            // a step that throws would fail every read of the store, so a value it cannot handle only skips that step
            fun step(action: () -> Unit) = runCatching(action)
            if (from < 1) {
                // 2.6.0. «Экстра потоки» needs no step: 2.5.3 wrote false at every start, so no choice is left to recover
                prefs.remove(stringPreferencesKey("ui_design"))
                step { decideDefaultRole(prefs) }
                for (profile in CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX) {
                    step { normalizeVkModes(prefs, profile) }
                    step { dropLinkHashesFromOwn(prefs, profile) }
                    step {
                        // what the core runs: 18 and 5 ran as 10
                        val key = getProfileKey(WORKERS_PER_HASH, profile)
                        prefs[key]?.let { prefs[key] = WorkerCountPolicy.normalize(it) }
                    }
                }
            }
            if (from < 2) {
                // 2.6.0, B5b2: each slot's deploy data becomes VPS record n, linked from profile n (a VPS-only slot too:
                // classic has no VPS list, and a record no profile links could not be reached)
                for (profile in CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX) {
                    step { if (vpsRecordExists(prefs, profile)) prefs[getProfileKey(VPS_REF, profile)] = profile }
                }
            }
            // step 4 records the role the switch showed: read before step 3 turns a 2.5.3 subscription slot (owner mode
            // there) into a link profile (B5e1 review F3)
            val activeLinkModeShown = prefs[getProfileKey(CSQTT_LINK_MODE, prefs[ACTIVE_PROFILE] ?: 0)] == true
            if (from < 3) {
                // 2.6.0, B5c1: a subscription slot is the owner's link, not an owner-mode slot holding the owner's hashes
                // as its own (M-170)
                for (profile in CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX) {
                    step { subscriptionSlotToLink(prefs, profile) }
                }
            }
            if (from < 4) {
                // 2.6.0, B5e1 (M-75): the role the switch showed becomes the user's own, kept when profiles change
                step {
                    if (prefs[UI_OWNER_MODE] == null) prefs[UI_OWNER_MODE] = !activeLinkModeShown
                }
            }
            if (from < 5) {
                // 2.6.0, the roles batch: the role becomes «Показывать деплой», on for an owner or anyone with a server (a
                // VPS record with an address); else nothing is written, so a fresh install shows Логи and not Деплой
                // (D28). Logs are shown to everyone now, so the per-profile participant choice goes
                step {
                    val owner = prefs[UI_OWNER_MODE] ?: !activeLinkModeShown
                    val hasServer = (CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX)
                        .any { !prefs[getProfileKey(DEPLOY_IP, it)].isNullOrBlank() }
                    if (prefs[SHOW_DEPLOY_TAB] == null && (owner || hasServer)) prefs[SHOW_DEPLOY_TAB] = true
                }
                prefs.remove(UI_OWNER_MODE)
                for (profile in CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX) {
                    prefs.remove(booleanPreferencesKey(slotKeyName("participant_show_logs", profile)))
                }
            }
            if (from < 6) {
                // 2.6.0, B5g: the log is always recorded, so «Активное логирование» goes; «detailed_logs» was never read
                prefs.remove(booleanPreferencesKey("logging_enabled"))
                prefs.remove(booleanPreferencesKey("detailed_logs"))
            }
            prefs[STORE_SCHEMA_VERSION] = STORE_SCHEMA
        }

        /**
         * A fresh install's profile waits for a link: most people get a link or a QR code, not a server. Decided once, under
         * its own flag, by what is stored: an install that holds a connection, a link, a subscription, hashes, a deployed
         * server or a chosen mode keeps it (a 2.5.3 install has the flag, so its link modes stay as they are).
         */
        private fun decideDefaultRole(prefs: MutablePreferences) {
            if (prefs[DEFAULT_ROLE_DECIDED] == true) return
            prefs[DEFAULT_ROLE_DECIDED] = true
            val configured = (CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX).any { profile ->
                listOf(PEER, CSQTT_LINK, SUBSCRIPTION_URL, VK_HASHES, DEPLOY_IP, CONNECTION_PASSWORD,
                    CONNECTION_PASSWORD_ENCRYPTED, CONNECTION_PASSWORD_LOCAL)
                    .any { !prefs[getProfileKey(it, profile)].isNullOrBlank() } ||
                    prefs[getProfileKey(CSQTT_LINK_MODE, profile)] != null
            }
            if (!configured) {
                prefs[getProfileKey(CSQTT_LINK_MODE, prefs[ACTIVE_PROFILE] ?: 0)] = true
            }
        }

        /** A stored mode becomes the value its reader already maps it to («auto» was auto_api), so nothing behaves otherwise. */
        private fun normalizeVkModes(prefs: MutablePreferences, profile: Int) {
            val hashKey = getProfileKey(VK_HASH_MODE, profile)
            prefs[hashKey]?.let { mode ->
                prefs[hashKey] = when (mode) {
                    "auto", CsqttConstants.VkAutoHash.MODE_AUTO_API -> CsqttConstants.VkAutoHash.MODE_AUTO_API
                    CsqttConstants.VkAutoHash.MODE_AUTO_JS -> CsqttConstants.VkAutoHash.MODE_AUTO_JS
                    else -> CsqttConstants.VkAutoHash.MODE_MANUAL
                }
            }
            val authKey = getProfileKey(VK_AUTH_MODE, profile)
            prefs[authKey]?.let { mode ->
                prefs[authKey] = when (mode) {
                    CsqttConstants.VkAuth.MODE_CAPTCHA -> CsqttConstants.VkAuth.MODE_CAPTCHA
                    CsqttConstants.VkAuth.MODE_AUTO_JS -> CsqttConstants.VkAuth.MODE_AUTO_JS
                    else -> CsqttConstants.VkAuth.MODE_CALLS
                }
            }
        }

        /**
         * A participant's own hashes never repeat the link's: those come from the link, minus the ones taken out, and a
         * copy in the own list kept a taken-out owner hash in use (older imports stored the owner's hashes as the
         * participant's own, plan §13.2 item 2).
         */
        private fun dropLinkHashesFromOwn(prefs: MutablePreferences, profile: Int) {
            if (prefs[getProfileKey(CSQTT_LINK_MODE, profile)] != true) return
            val linkHashes = parseCsqttLink(prefs[getProfileKey(CSQTT_LINK, profile)].orEmpty())?.hashes.orEmpty()
            if (linkHashes.isEmpty()) return
            val key = getProfileKey(VK_HASHES, profile)
            val own = prefs[key]?.split(Regex("[,\\s\\n]+"))?.map(String::trim)?.filter(String::isNotEmpty) ?: return
            val kept = own.filterNot { it in linkHashes }
            if (kept.size == own.size) return
            if (kept.isEmpty()) prefs.remove(key) else prefs[key] = kept.joinToString(",")
        }

        private fun <T> getProfileKey(baseKey: Preferences.Key<T>, profile: Int): Preferences.Key<T> {
            if (profile == 0) return baseKey
            val newName = "${baseKey.name}_$profile"
            @Suppress("UNCHECKED_CAST")
            val make = perProfileKeys[baseKey.name]?.make as ((String) -> Preferences.Key<T>)?
            // a key not declared per profile is a mistake ProfileDeleteClearsAllKeysTest catches; the string key is 2.5.3's fallback
            @Suppress("UNCHECKED_CAST")
            return make?.invoke(newName) ?: stringPreferencesKey(newName) as Preferences.Key<T>
        }

        /** The per-profile key names and their groups, for the tests. */
        internal val perProfileKeyGroups: Map<String, ProfileKeyGroup>
            get() = perProfileKeys.mapValues { it.value.group }

        /** Every per-profile key, for the backup (M-42). */
        internal val perProfileDeclarations: Map<String, PerProfileKey> get() = perProfileKeys

        /** The app settings a full backup carries (M-42), by name and stored type. */
        internal val backupGlobalKeys: Map<String, StoredKind> by lazy {
            listOf(
                ACTIVE_PROFILE to StoredKind.INT, SHOW_SYSTEM_APPS to StoredKind.BOOLEAN,
                INFO_ACTIONS_EXPANDED to StoredKind.BOOLEAN, INFO_PROJECT_EXPANDED to StoredKind.BOOLEAN,
                SUBSCRIPTION_META to StoredKind.STRING, AUTO_JS_RISK_ACKNOWLEDGED to StoredKind.BOOLEAN,
                TCP_TRANSPORT_RISK_ACKNOWLEDGED to StoredKind.BOOLEAN, AUTO_PAUSE_ON_WIFI to StoredKind.BOOLEAN,
                BATTERY_SAVER_MODE to StoredKind.STRING, BATTERY_SAVER_PERCENT to StoredKind.INT,
                THEME_MODE to StoredKind.STRING, IS_DYNAMIC_COLOR to StoredKind.BOOLEAN, THEME_PALETTE to StoredKind.STRING,
                UPDATE_CHECK_INTERVAL_HOURS to StoredKind.INT, SHOW_LOGS_TAB to StoredKind.BOOLEAN,
                SHOW_DEPLOY_TAB to StoredKind.BOOLEAN,
            ).associate { (key, kind) -> key.name to kind }
        }

        /** App state a backup leaves out: this device's flags, update and tunnel state, check results. */
        internal val notBackedUpGlobalKeys: Set<String> by lazy {
            listOf(
                STORE_SCHEMA_VERSION, DEPLOY_PROFILE_NAMES_CLEANED, DEFAULT_ROLE_DECIDED, SPLIT_TUNNEL_WHITELIST_MIGRATED,
                BATTERY_OPTIMIZATION_PROMPT_HANDLED, PERMISSION_INTRO_SHOWN, UPDATE_LAST_CHECK_AT, UPDATE_LATEST_VERSION, UPDATE_LAST_ERROR,
                UPDATE_POSTPONE_UNTIL, UPDATE_POSTPONE_VERSION, UPDATE_DIALOG_LAST_SHOWN_VERSION, UPDATE_DIALOG_LAST_SHOWN_AT,
                UPDATE_DIALOG_LAST_ACTION_VERSION, UPDATE_DIALOG_LAST_ACTION, UPDATE_DIALOG_LAST_ACTION_AT,
                CLIENT_ID_CHECK_RESULTS, CONNECTION_GENERATION, TUNNEL_LAST_DIAGNOSTIC, TUNNEL_LAST_DIAGNOSTIC_AT,
                TUNNEL_WAS_RUNNING, TUNNEL_WAS_RUNNING_BOOT, TUNNEL_WAS_RUNNING_UPDATE, PROFILE_EPOCH, VK_ACCOUNTS,
                UI_OWNER_MODE, LAST_STOP_CAUSE, RUN_PROFILE,
                // B5f2 (F7): the Wi-Fi rule and the networks' names stay on this phone; a choice to carry them is P3's
                WIFI_RULE, WIFI_CHECKED, WIFI_SAVED, WIFI_DISMISSED,
            ).mapTo(HashSet()) { it.name }
        }

        /** B5f2: the Wi-Fi rules as stored. */
        internal fun readWifiRules(prefs: Preferences): WifiRules {
            val checked = decodeWifiSsids(prefs[WIFI_CHECKED])
            return WifiRules(
                enabled = prefs[AUTO_PAUSE_ON_WIFI] ?: false,
                rule = WifiRule.fromStored(prefs[WIFI_RULE]),
                checked = checked,
                saved = decodeWifiSsids(prefs[WIFI_SAVED]) + checked,
                dismissed = decodeWifiSsids(prefs[WIFI_DISMISSED]),
            )
        }

        /**
         * «Запомнить»: [name] saved and checked — only under the [rule] the offer was made in, with the switch on, so a
         * notice from before a change of either changes nothing.
         */
        internal fun rememberWifi(prefs: MutablePreferences, name: String, rule: WifiRule): Boolean {
            val ssid = normalizeWifiSsid(name) ?: return false
            val current = readWifiRules(prefs)
            if (!current.enabled || current.rule != rule || !rule.usesNames) return false
            prefs[WIFI_SAVED] = encodeWifiSsids(current.saved + ssid)
            prefs[WIFI_CHECKED] = encodeWifiSsids(current.checked + ssid)
            return true
        }

        internal fun checkWifi(prefs: MutablePreferences, name: String, checked: Boolean) {
            val ssid = normalizeWifiSsid(name) ?: return
            val current = readWifiRules(prefs)
            prefs[WIFI_SAVED] = encodeWifiSsids(current.saved + ssid)
            prefs[WIFI_CHECKED] = encodeWifiSsids(if (checked) current.checked + ssid else current.checked - ssid)
        }

        /** «Удалить»: out of the list, and never offered again (as «Не сейчас»). */
        internal fun forgetWifi(prefs: MutablePreferences, name: String) {
            val ssid = normalizeWifiSsid(name) ?: return
            val current = readWifiRules(prefs)
            prefs[WIFI_SAVED] = encodeWifiSsids(current.saved - ssid)
            prefs[WIFI_CHECKED] = encodeWifiSsids(current.checked - ssid)
            dismissWifi(prefs, ssid)
        }

        /** «Не сейчас» (or the offer's notice swiped away): never offered again. */
        internal fun dismissWifi(prefs: MutablePreferences, name: String) {
            val ssid = normalizeWifiSsid(name) ?: return
            prefs[WIFI_DISMISSED] = encodeWifiSsids(decodeWifiSsids(prefs[WIFI_DISMISSED]) + ssid)
        }

        internal fun slotKeyName(name: String, index: Int): String = if (index == 0) name else "${name}_$index"

        /** Removes every key of [group] from [profile]'s slot: profile 0's are the base keys. */
        internal fun clearProfileKeys(prefs: MutablePreferences, profile: Int, group: ProfileKeyGroup) {
            for ((name, key) in perProfileKeys) {
                if (key.group == group) prefs.remove(key.make(slotKeyName(name, profile)))
            }
        }

        private val profileIndices = CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX

        // opening the deploy tab writes the secret-path pair, so the pair alone is not a VPS record
        private val vpsPresenceIgnored = setOf("deploy_secret_path_enabled", "deploy_secret_path_uuid")

        /**
         * B5b2: VPS record n is the VPS keys with suffix n, profile p the CONNECTION keys with suffix p and its `vps_ref`.
         * A record exists when it holds a value the user gave (a blank string is none).
         */
        internal fun vpsRecordExists(prefs: Preferences, index: Int): Boolean {
            val values = prefs.asMap()
            return perProfileKeys.any { (name, key) ->
                key.group == VPS && name !in vpsPresenceIgnored &&
                    when (val value = values[key.make(slotKeyName(name, index))]) {
                        null -> false
                        is String -> value.isNotBlank()
                        else -> true
                    }
            }
        }

        /** The VPS record [profile] links, or null. */
        internal fun linkedVps(prefs: Preferences, profile: Int): Int? =
            prefs[getProfileKey(VPS_REF, profile)]?.takeIf { it in profileIndices }

        /**
         * The VPS record a deploy-form write of [profile] goes to: its own, or on the first write a free one (the profile's
         * own index when free), cleared, linked and given a fresh secret path. Null only when all 32 are taken.
         */
        internal fun vpsForWrite(prefs: MutablePreferences, profile: Int): Int? {
            linkedVps(prefs, profile)?.let { return it }
            val linked = profileIndices.mapNotNullTo(HashSet()) { linkedVps(prefs, it) }
            val index = (sequenceOf(profile) + profileIndices.asSequence())
                .firstOrNull { it !in linked && !vpsRecordExists(prefs, it) } ?: return null
            clearProfileKeys(prefs, index, VPS)
            prefs[getProfileKey(DEPLOY_SECRET_PATH_UUID, index)] = UUID.randomUUID().toString()
            prefs[getProfileKey(VPS_REF, profile)] = index
            return index
        }

        /** A profile the user has: a peer, a link, a subscription, a VPS record or a name they gave it (M-39). */
        internal fun profileInUse(prefs: Preferences, profile: Int): Boolean =
            listOf(PEER, CSQTT_LINK, SUBSCRIPTION_URL, PROFILE_NAME).any { !prefs[getProfileKey(it, profile)].isNullOrBlank() } ||
                linkedVps(prefs, profile) != null || prefs[getProfileKey(PROFILE_ADDED, profile)] == true

        /** A slot's profile was replaced: saved form state of the old one must not reach it (see PROFILE_EPOCH). */
        internal fun bumpProfileEpoch(prefs: MutablePreferences) {
            prefs[PROFILE_EPOCH] = (prefs[PROFILE_EPOCH] ?: 0) + 1
        }

        /**
         * Deletes [profile]: every connection key, its VPS link with them; the VPS record stays (M-41). The active profile
         * moves to another in use; with none left it stays on the emptied slot in the role it had. The last profile of a
         * subscription takes the subscription's metadata with it, so an auto-update does not bring the node back.
         */
        internal fun deleteProfileKeys(prefs: MutablePreferences, profile: Int) {
            val linkMode = prefs[getProfileKey(CSQTT_LINK_MODE, profile)]
            val subscription = prefs[getProfileKey(SUBSCRIPTION_URL, profile)]?.trim().orEmpty()
            clearProfileKeys(prefs, profile, CONNECTION)
            bumpProfileEpoch(prefs)
            // a run whose profile is deleted (allowed while paused on Wi-Fi) follows the open profile from now on
            if (prefs[RUN_PROFILE] == profile) prefs.remove(RUN_PROFILE)
            if ((prefs[ACTIVE_PROFILE] ?: CsqttConstants.Profiles.MIN_INDEX) == profile) {
                val next = profileIndices.firstOrNull { it != profile && profileInUse(prefs, it) } ?: profile
                prefs[ACTIVE_PROFILE] = next
                if (next == profile && linkMode != null) prefs[getProfileKey(CSQTT_LINK_MODE, profile)] = linkMode
            }
            if (subscription.isNotEmpty() &&
                profileIndices.none { prefs[getProfileKey(SUBSCRIPTION_URL, it)]?.trim() == subscription }
            ) {
                val meta = decodeSubscriptionMeta(prefs[SUBSCRIPTION_META]).toMutableMap()
                if (meta.remove(subscription) != null) prefs[SUBSCRIPTION_META] = encodeSubscriptionMeta(meta)
            }
        }

        /** Deletes VPS record [index] and every link to it; the profiles keep their connection (M-41, M-104). */
        internal fun deleteVpsKeys(prefs: MutablePreferences, index: Int) {
            clearProfileKeys(prefs, index, VPS)
            for (profile in profileIndices) {
                if (linkedVps(prefs, profile) == index) prefs.remove(getProfileKey(VPS_REF, profile))
            }
        }

        /**
         * A subscription node into its slot (M-170): the owner's link as served, in link mode, so its hashes stay the owner's
         * («от владельца», masked, never the profile's own) and a token link connects from the link. A slot that held the
         * node in owner mode (2.5.3) loses the peer and password the link now carries; its stored hashes go only when the
         * link carries hashes (2.5.3's rule: a node without hashes left the participant's own hashes there).
         */
        internal fun writeSubscriptionNode(prefs: MutablePreferences, write: SubscriptionProfileWrite) {
            val profile = write.profile
            if (write.link.isBlank()) return
            startCleanIfFree(prefs, profile)
            useParticipantLink(prefs, profile, write.link.trim())

            val power = write.power?.coerceIn(1, CsqttConstants.Tunnel.MAX_WORKERS)
            val storedWorkers = prefs[getProfileKey(WORKERS_PER_HASH, profile)]
            val targetWorkers = when {
                power == null -> storedWorkers ?: 10
                storedWorkers == null -> power
                else -> storedWorkers.coerceAtMost(power)
            }
            prefs[getProfileKey(WORKERS_PER_HASH, profile)] = WorkerCountPolicy.normalize(
                targetWorkers,
                maximum = power ?: CsqttConstants.Tunnel.MAX_WORKERS,
            )

            val title = write.title.trim()
            val nodeName = write.nodeName.trim()
            val previousNode = prefs[getProfileKey(SUBSCRIPTION_NODE, profile)].orEmpty()
            val currentName = prefs[getProfileKey(PROFILE_NAME, profile)]
            val defaultName = defaultProfileName(profile)
            val renameAllowed = currentName.isNullOrBlank() ||
                currentName == defaultName ||
                currentName == previousNode
            if (renameAllowed) {
                if (nodeName.isEmpty() || nodeName == defaultName) {
                    prefs.remove(getProfileKey(PROFILE_NAME, profile))
                } else {
                    prefs[getProfileKey(PROFILE_NAME, profile)] = nodeName
                }
            }

            prefs[getProfileKey(SUBSCRIPTION_URL, profile)] = write.url.trim()
            if (title.isEmpty()) {
                prefs.remove(getProfileKey(SUBSCRIPTION_TITLE, profile))
            } else {
                prefs[getProfileKey(SUBSCRIPTION_TITLE, profile)] = title
            }
            if (write.subUuid.isNullOrBlank()) {
                prefs.remove(getProfileKey(SUBSCRIPTION_UUID, profile))
            } else {
                prefs[getProfileKey(SUBSCRIPTION_UUID, profile)] = write.subUuid
            }
            prefs[getProfileKey(SUBSCRIPTION_NODE, profile)] = nodeName
            if (power == null) {
                prefs.remove(getProfileKey(SUBSCRIPTION_POWER, profile))
            } else {
                prefs[getProfileKey(SUBSCRIPTION_POWER, profile)] = power
            }
        }

        /**
         * [profile] connects through the owner's [link]. Coming from owner mode, the slot's connection fields go (the link
         * carries peer, port and password), and its stored hashes follow 2.5.3's refresh rule (the operator, 2026-11-20):
         * a link with hashes means the stored ones were the node's, so they go (a hash the owner revoked meanwhile must not
         * survive as the participant's own); a link without hashes means they are the participant's own, so they stay.
         */
        private fun useParticipantLink(prefs: MutablePreferences, profile: Int, link: String) {
            if (prefs[getProfileKey(CSQTT_LINK_MODE, profile)] != true) {
                listOf(
                    PEER, SERVER_PEER_PORT, CONNECTION_PASSWORD_LOCAL, CONNECTION_PASSWORD,
                    CONNECTION_PASSWORD_ENCRYPTED, LINK_HASHES_REMOVED,
                ).forEach { prefs.remove(getProfileKey(it, profile)) }
                if (parseCsqttLink(link)?.hashes.orEmpty().isNotEmpty()) prefs.remove(getProfileKey(VK_HASHES, profile))
            }
            writeLinkKeys(prefs, profile, link)
            prefs[getProfileKey(CSQTT_LINK_MODE, profile)] = true
        }

        /**
         * Store step 3 (M-170): a 2.5.3 subscription slot in owner mode becomes the participant link it came from. Built
         * from the stored fields, as no original link was kept; skipped while its password is not readable here (the
         * keystore is not), and then its next refresh converts it.
         */
        internal fun subscriptionSlotToLink(prefs: MutablePreferences, profile: Int) {
            val url = prefs[getProfileKey(SUBSCRIPTION_URL, profile)]?.trim()
            if (url.isNullOrEmpty()) return
            if (prefs[getProfileKey(CSQTT_LINK_MODE, profile)] == true) return
            // a subscription that refreshes is converted by its next refresh, from the link it serves, which tells the
            // owner's hashes from the participant's own; only the ones that never refresh are rebuilt here (review F1)
            if ((decodeSubscriptionMeta(prefs[SUBSCRIPTION_META])[url]?.autoUpdateMinutes ?: 0) > 0) return
            val peer = prefs[getProfileKey(PEER, profile)]?.trim().orEmpty()
            if (peer.isEmpty()) return
            val host = if (peer.startsWith("[")) peer.substringAfter('[').substringBefore(']') else peer
            if (host.isEmpty() || host.contains(':') && !peer.startsWith("[")) return
            val port = prefs[getProfileKey(SERVER_PEER_PORT, profile)] ?: CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT
            val password = (prefs[getProfileKey(CONNECTION_PASSWORD_LOCAL, profile)]
                ?: prefs[getProfileKey(CONNECTION_PASSWORD, profile)])?.takeIf { it.isNotBlank() } ?: return
            val hashes = prefs[getProfileKey(VK_HASHES, profile)].orEmpty()
                .split(Regex("[,\\s]+")).map(String::trim).filter(String::isNotEmpty).distinct()
            val link = com.csqtt.client.ui.legacy.utils.formatCsqttLink(
                host = host,
                peer = port,
                password = password,
                hashes = hashes,
                power = prefs[getProfileKey(SUBSCRIPTION_POWER, profile)],
            )
            // a link the parser refuses (more than 6 hashes, a short one) would leave a profile that cannot connect
            parseCsqttLink(link) ?: return
            useParticipantLink(prefs, profile, link)
        }

        /**
         * A new profile written into a free slot starts clean: no VK keys or lists left by an older profile there (M-40).
         * The active slot's keys are the settings the user is looking at, so they stay.
         */
        internal fun startCleanIfFree(prefs: MutablePreferences, profile: Int) {
            if (!profileInUse(prefs, profile) && profile != (prefs[ACTIVE_PROFILE] ?: CsqttConstants.Profiles.MIN_INDEX)) {
                clearProfileKeys(prefs, profile, CONNECTION)
            }
        }

        /** Whether [profile] links a VPS record no other profile links. */
        internal fun isOnlyLinkToVps(prefs: Preferences, profile: Int): Boolean {
            val vps = linkedVps(prefs, profile) ?: return false
            return profileIndices.none { it != profile && linkedVps(prefs, it) == vps }
        }

        /** Classic's delete: the profile, and its VPS record when nothing else links it (classic has no VPS list). */
        internal fun deleteProfileWithLoneVps(prefs: MutablePreferences, profile: Int) {
            val lone = if (isOnlyLinkToVps(prefs, profile)) linkedVps(prefs, profile) else null
            deleteProfileKeys(prefs, profile)
            lone?.let { deleteVpsKeys(prefs, it) }
        }

        /** The deploy form's ports with «Ручные порты» on, as drafts on [profile]'s VPS record: its own ports stay. */
        internal fun putDeployDraftPorts(prefs: MutablePreferences, profile: Int, peer: Int, web: Int, ssh: String) {
            val vps = vpsForWrite(prefs, profile) ?: return
            prefs[getProfileKey(DEPLOY_PEER_PORT, vps)] = peer
            prefs[getProfileKey(DEPLOY_WEB_PORT, vps)] = web
            prefs[getProfileKey(DEPLOY_SSH_PORT, vps)] = ssh
        }

        /**
         * S8: [hashes] become [profile]'s received set when it is a link profile; a set equal to the link's own is
         * dropped (nothing to replace). True when the stored value changed.
         */
        internal fun receivedHashesKeys(prefs: MutablePreferences, profile: Int, hashes: List<String>, sourceLink: String): Boolean {
            if (profile !in profileIndices || prefs[getProfileKey(CSQTT_LINK_MODE, profile)] != true) return false
            // only while the profile still holds the link the run started with (review F2): a new link takes no old set
            val stored = prefs[getProfileKey(CSQTT_LINK, profile)].orEmpty()
            if (stored != sourceLink) return false
            val link = parseCsqttLink(stored) ?: return false
            val key = getProfileKey(RECEIVED_HASHES, profile)
            val before = prefs[key]
            if (hashes.isEmpty() || hashes == link.hashes) prefs.remove(key) else prefs[key] = hashes.joinToString(",")
            return prefs[key] != before
        }

        /**
         * Every write of a profile's link: S8's hashes received for another link never outlive it (B5e3a review F1 and
         * verify V1 — the subscription refresh, the imports and the settings JSON all write through here).
         */
        internal fun writeLinkKeys(prefs: MutablePreferences, profile: Int, link: String) {
            if (prefs[getProfileKey(CSQTT_LINK, profile)] != link) prefs.remove(getProfileKey(RECEIVED_HASHES, profile))
            prefs[getProfileKey(CSQTT_LINK, profile)] = link
        }

        /**
         * D6 (a1): the VPS record whose pool an owner run of [profile] uses: an owner profile whose linked record has the
         * server's IP, web password and secret path, the fields «Панель управления» opens it with. Null for every other
         * run, which keeps per-connect calls ended at stop (B5e3b).
         */
        internal fun poolPanelOf(prefs: Preferences, profile: Int, webPassword: (Int) -> String): PoolPanel? {
            if (prefs[getProfileKey(CSQTT_LINK_MODE, profile)] == true) return null
            val vps = linkedVps(prefs, profile) ?: return null
            val host = prefs[getProfileKey(DEPLOY_IP, vps)].orEmpty().trim()
            val path = prefs[getProfileKey(DEPLOY_SECRET_PATH_UUID, vps)].orEmpty().trim().trim('/')
            if (host.isEmpty() || path.isEmpty()) return null
            val password = webPassword(vps).takeIf { it.isNotEmpty() } ?: return null
            val port = prefs[getProfileKey(SERVER_WEB_PORT, profile)] ?: CsqttConstants.Network.DEFAULT_SERVER_WEB_PORT
            return PoolPanel(vps, host, port, path, password)
        }

        internal fun vkPoolOf(prefs: Preferences, vps: Int): List<String> =
            prefs[getProfileKey(DEPLOY_VK_POOL, vps)].orEmpty().split(",").map(String::trim).filter(String::isNotEmpty)

        /** The pool of [vps], written only while the record still has its server (a deleted record is not revived). */
        internal fun writeVkPool(prefs: MutablePreferences, vps: Int, pool: List<String>): Boolean {
            if (vps !in profileIndices || prefs[getProfileKey(DEPLOY_IP, vps)].isNullOrBlank()) return false
            if (pool.isEmpty()) prefs.remove(getProfileKey(DEPLOY_VK_POOL, vps))
            else prefs[getProfileKey(DEPLOY_VK_POOL, vps)] = pool.joinToString(",")
            return true
        }

        internal fun encodePanelCert(cert: SubscriptionCertRecord): String = JSONObject()
            .put("sha256", cert.sha256).put("notAfter", cert.notAfterMs).put("kind", cert.kind.stored)
            .put("names", cert.names.joinToString(",")).toString()

        internal fun decodePanelCert(raw: String?): SubscriptionCertRecord? = runCatching {
            val json = JSONObject(raw ?: return null)
            SubscriptionCertRecord(
                sha256 = json.getString("sha256").takeIf { it.isNotBlank() } ?: return null,
                notAfterMs = json.optLong("notAfter", 0L),
                kind = CertKind.fromStored(json.optString("kind")),
                names = json.optString("names").split(",").filter(String::isNotBlank).toSet(),
            )
        }.getOrNull()

        /** What [vps]'s panel last accepted, and the leaf it was trusted with. */
        internal fun handOverStateOf(prefs: Preferences, vps: Int): Pair<List<String>, SubscriptionCertRecord?> =
            prefs[getProfileKey(DEPLOY_VK_POOL_SENT, vps)].orEmpty().split(",").filter(String::isNotBlank) to
                decodePanelCert(prefs[getProfileKey(DEPLOY_PANEL_CERT, vps)])

        /** A hand-over the panel accepted; a CA or first-use leaf becomes the one it is trusted with from now on. */
        internal fun writeHandOver(prefs: MutablePreferences, vps: Int, sent: List<String>, cert: SubscriptionCertRecord?) {
            if (vps !in profileIndices || prefs[getProfileKey(DEPLOY_IP, vps)].isNullOrBlank()) return
            prefs[getProfileKey(DEPLOY_VK_POOL_SENT, vps)] = sent.joinToString(",")
            if (cert != null) prefs[getProfileKey(DEPLOY_PANEL_CERT, vps)] = encodePanelCert(cert)
        }

        /** A deploy's end: a fresh install has no default hashes and may have a new self-signed leaf. */
        internal fun clearHandOver(prefs: MutablePreferences, vps: Int) {
            prefs.remove(getProfileKey(DEPLOY_VK_POOL_SENT, vps))
            prefs.remove(getProfileKey(DEPLOY_PANEL_CERT, vps))
            prefs.remove(getProfileKey(DEPLOY_VK_POOL_HANDOVER, vps))
        }

        /**
         * B5h2: the hand-over's [result] for [vps], kept while the record has its server. True when it is a failure other
         * than the one already stored: rule 10 announces only a new one.
         */
        internal fun writeHandOverResult(prefs: MutablePreferences, vps: Int, result: HandOverResult): Boolean {
            if (vps !in profileIndices || prefs[getProfileKey(DEPLOY_IP, vps)].isNullOrBlank()) return false
            val key = getProfileKey(DEPLOY_VK_POOL_HANDOVER, vps)
            val previous = HandOverResult.decode(prefs[key])
            prefs[key] = result.encode()
            return !result.ok && (previous == null || previous.ok || failureKey(previous.text) != failureKey(result.text))
        }

        /**
         * B5h2: [profile]'s own server's last hand-over; else a failed one of another owner profile — the one its notice
         * may have brought the user here for (review F3).
         */
        internal fun handOverNoticeOf(prefs: Preferences, profile: Int): HandOverResult? =
            handOverOfProfile(prefs, profile)
                ?: profileIndices.firstNotNullOfOrNull { other ->
                    // named, since it is not the open profile's (verify N1)
                    handOverOfProfile(prefs, other)?.takeIf { !it.ok }
                        ?.let { it.copy(text = "«${profileDisplayName(prefs, other)}»: ${it.text}") }
                }

        /** [profile]'s own server, in «Авто API», with a hand-over on record: its last result. */
        private fun handOverOfProfile(prefs: Preferences, profile: Int): HandOverResult? {
            if (prefs[getProfileKey(CSQTT_LINK_MODE, profile)] == true) return null
            val mode = prefs[getProfileKey(VK_HASH_MODE, profile)]
            if (mode != "auto" && mode != CsqttConstants.VkAutoHash.MODE_AUTO_API) return null
            val vps = linkedVps(prefs, profile) ?: return null
            return HandOverResult.decode(prefs[getProfileKey(DEPLOY_VK_POOL_HANDOVER, vps)])
        }

        /** B5h2 (U3): the last refresh's outcome of [url]; a success clears the failure. True for a new failure. */
        internal fun writeSubscriptionOutcome(prefs: MutablePreferences, url: String, error: String, skipped: String): Boolean {
            val key = url.trim()
            if (key.isEmpty()) return false
            val meta = decodeSubscriptionMeta(prefs[SUBSCRIPTION_META]).toMutableMap()
            val current = meta[key] ?: SubscriptionMeta(url = key)
            meta[key] = current.copy(lastError = error.trim(), lastSkipped = skipped.trim())
            prefs[SUBSCRIPTION_META] = encodeSubscriptionMeta(meta)
            return error.isNotBlank() && failureKey(error) != failureKey(current.lastError)
        }

        /**
         * B5h2 (U3): [profile]'s subscription, when its last refresh failed or skipped nodes; else a failure of another
         * subscription in use — the one its notice may have brought the user here for (review F3).
         */
        internal fun subscriptionNoticeOf(prefs: Preferences, profile: Int): SubscriptionMeta? {
            val all = decodeSubscriptionMeta(prefs[SUBSCRIPTION_META])
            val url = prefs[getProfileKey(SUBSCRIPTION_URL, profile)]?.trim().orEmpty()
            all[url]?.takeIf { it.lastError.isNotEmpty() || it.lastSkipped.isNotEmpty() }?.let { return it }
            val inUse = profileIndices.mapNotNullTo(HashSet()) { prefs[getProfileKey(SUBSCRIPTION_URL, it)]?.trim() }
            return all.values.firstOrNull { it.lastError.isNotEmpty() && it.url in inUse }
        }

        /** M-70: what an import can write into, read in one pass. */
        internal fun linkImportState(prefs: Preferences, tunnelRunning: Boolean): LinkImportState {
            val active = prefs[ACTIVE_PROFILE] ?: CsqttConstants.Profiles.MIN_INDEX
            val endpoint = if (prefs[getProfileKey(CSQTT_LINK_MODE, active)] == true) {
                parseCsqttLink(prefs[getProfileKey(CSQTT_LINK, active)].orEmpty())?.let { LinkImport.endpoint(it.host, it.port) }
            } else {
                prefs[getProfileKey(PEER, active)]?.takeIf { it.isNotBlank() }?.let { peer ->
                    LinkImport.endpoint(
                        peer,
                        prefs[getProfileKey(SERVER_PEER_PORT, active)] ?: CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT,
                    )
                }
            }
            return LinkImportState(
                activeName = profileDisplayName(prefs, active),
                activeEmpty = listOf(PEER, CSQTT_LINK, SUBSCRIPTION_URL).all { prefs[getProfileKey(it, active)].isNullOrBlank() },
                activeEndpoint = endpoint,
                freeProfile = profileIndices.any { it != active && !profileInUse(prefs, it) },
                tunnelRunning = tunnelRunning,
            )
        }

        /**
         * M-70: [link] into the active profile or a new one (made active only when [activate]: never while the tunnel
         * runs). One store path for the deep link, the QR scan and the config dialog: a former owner profile's own
         * fields go, the link starts with all its hashes in use, and the profile leaves any subscription it came from
         * (whose next refresh would otherwise put its own node back). The profile written, or null with none free.
         */
        internal fun importParticipantLinkKeys(prefs: MutablePreferences, link: String, intoNew: Boolean, activate: Boolean): Int? {
            val previousActive = prefs[ACTIVE_PROFILE] ?: CsqttConstants.Profiles.MIN_INDEX
            val target = if (intoNew) addProfileKeys(prefs, linkMode = true) ?: return null else previousActive
            if (intoNew && !activate) prefs[ACTIVE_PROFILE] = previousActive
            useParticipantLink(prefs, target, link)
            prefs.remove(getProfileKey(LINK_HASHES_REMOVED, target))
            prefs.remove(getProfileKey(RECEIVED_HASHES, target))   // a new link brings its own set
            listOf(SUBSCRIPTION_URL, SUBSCRIPTION_TITLE, SUBSCRIPTION_UUID, SUBSCRIPTION_NODE).forEach {
                prefs.remove(getProfileKey(it, target))
            }
            prefs.remove(getProfileKey(SUBSCRIPTION_POWER, target))
            return target
        }

        /** «Добавить профиль»: the first free slot but the active one, cleared, in [linkMode], made active; null if none. */
        internal fun addProfileKeys(prefs: MutablePreferences, linkMode: Boolean): Int? {
            val active = prefs[ACTIVE_PROFILE] ?: CsqttConstants.Profiles.MIN_INDEX
            val free = profileIndices.firstOrNull { it != active && !profileInUse(prefs, it) } ?: return null
            clearProfileKeys(prefs, free, CONNECTION)
            prefs[getProfileKey(CSQTT_LINK_MODE, free)] = linkMode
            prefs[getProfileKey(PROFILE_ADDED, free)] = true
            prefs[ACTIVE_PROFILE] = free
            bumpProfileEpoch(prefs)
            return free
        }

        /** «Добавить профиль» (the roles batch): the new profile waits for a server with «Показывать деплой» on, else a link. */
        internal fun newProfileKeys(prefs: MutablePreferences): Int? =
            addProfileKeys(prefs, linkMode = prefs[SHOW_DEPLOY_TAB] != true)

        /**
         * The roles batch: a profile's mode follows what it holds. While it holds nothing for its mode (no link in link
         * mode, no server address otherwise) and no subscription, its mode can still change; after that it is fixed.
         */
        internal fun profileModeSwitchable(prefs: Preferences, profile: Int): Boolean {
            val content = if (prefs[getProfileKey(CSQTT_LINK_MODE, profile)] == true) CSQTT_LINK else PEER
            return listOf(content, SUBSCRIPTION_URL).all { prefs[getProfileKey(it, profile)].isNullOrBlank() }
        }

        /** «Ввести сервер вручную» / «Подключиться по ссылке csqtt://»: an empty [profile] waits for the other kind. */
        internal fun switchEmptyProfileModeKeys(prefs: MutablePreferences, profile: Int, linkMode: Boolean): Boolean {
            val key = getProfileKey(CSQTT_LINK_MODE, profile)
            if ((prefs[key] == true) == linkMode || !profileModeSwitchable(prefs, profile)) return false
            prefs[key] = linkMode
            return true
        }

        /** Classic's dropdown (M-97): the profiles in use and the active one, with their names. */
        internal fun profileList(prefs: Preferences): List<Pair<Int, String>> {
            val active = prefs[ACTIVE_PROFILE] ?: CsqttConstants.Profiles.MIN_INDEX
            return profileIndices.filter { it == active || profileInUse(prefs, it) }.map { it to profileDisplayName(prefs, it) }
        }

        /** The name the user gave, else the linked server's (a deploy-only slot's name, #54), else «Профиль N». */
        internal fun profileDisplayName(prefs: Preferences, profile: Int): String =
            prefs[getProfileKey(PROFILE_NAME, profile)]?.takeIf { it.isNotBlank() }
                ?: linkedVps(prefs, profile)?.let { prefs[getProfileKey(DEPLOY_SERVER_NAME, it)] }?.takeIf { it.isNotBlank() }
                ?: defaultProfileName(profile)

        /**
         * A deploy that succeeded: the profile that started it takes the server (its address when it had none, both ports,
         * the synced [password]) and the record's drafts go, only while [profile] still links [vps]: a profile deleted or
         * relinked during the deploy is not brought back. Ports other than 46000/46002 turn «Ручные порты» on. An empty
         * link profile becomes an own-server profile on this server (an address it kept hidden from an older mode is
         * replaced); a profile holding a link keeps it.
         */
        internal fun applyDeployedServer(
            prefs: MutablePreferences,
            profile: Int,
            vps: Int,
            host: String,
            peerPort: Int,
            webPort: Int,
            password: String?,
        ): Boolean {
            if (linkedVps(prefs, profile) != vps) return false
            val becameOwn = switchEmptyProfileModeKeys(prefs, profile, linkMode = false)
            if ((becameOwn || prefs[getProfileKey(PEER, profile)].isNullOrBlank()) && host.isNotBlank()) {
                prefs[getProfileKey(PEER, profile)] = host
            }
            prefs[getProfileKey(SERVER_PEER_PORT, profile)] = peerPort
            prefs[getProfileKey(SERVER_WEB_PORT, profile)] = webPort
            if (password != null && isValidTunnelPassword(password)) {
                prefs[getProfileKey(CONNECTION_PASSWORD_LOCAL, profile)] = password
                prefs.remove(getProfileKey(CONNECTION_PASSWORD, profile))
                prefs.remove(getProfileKey(CONNECTION_PASSWORD_ENCRYPTED, profile))
            }
            prefs.remove(getProfileKey(DEPLOY_PEER_PORT, vps))
            prefs.remove(getProfileKey(DEPLOY_WEB_PORT, vps))
            if (peerPort != CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT ||
                webPort != CsqttConstants.Network.DEFAULT_SERVER_WEB_PORT
            ) {
                prefs[getProfileKey(MANUAL_PORTS_ENABLED, vps)] = true
            }
            return true
        }

    }

    private val dataStore = appContext.dataStore

    private fun selectedProfile(prefs: Preferences): Int = selectedProfileOf(prefs, scope)

    /** The profile this store reads and writes (see [ProfileScope]). */
    internal val selectedProfileIndex: Flow<Int> = dataStore.data.map { selectedProfile(it) }
    private val secureStore = SecureStringStore(appContext)

    val activeProfile: Flow<Int> = dataStore.data.map {
        (it[ACTIVE_PROFILE] ?: CsqttConstants.Profiles.MIN_INDEX).coerceIn(
            CsqttConstants.Profiles.MIN_INDEX,
            CsqttConstants.Profiles.MAX_INDEX,
        )
    }
    val tunnelLastDiagnostic: Flow<String> = dataStore.data.map {
        it[TUNNEL_LAST_DIAGNOSTIC] ?: ""
    }
    /** B5h1: the cause of the last stop the user did not ask for, kept until the next start; "" when none. */
    val lastStopCause: Flow<String> = dataStore.data.map { it[LAST_STOP_CAUSE] ?: "" }
    val showSystemApps: Flow<Boolean> = dataStore.data.map { it[SHOW_SYSTEM_APPS] ?: false }
    val infoActionsExpanded: Flow<Boolean> = dataStore.data.map { it[INFO_ACTIONS_EXPANDED] ?: false }
    val infoProjectExpanded: Flow<Boolean> = dataStore.data.map { it[INFO_PROJECT_EXPANDED] ?: false }
    val csqttLink: Flow<String> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(CSQTT_LINK, profile)] ?: ""
    }
    val csqttLinkMode: Flow<Boolean> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(CSQTT_LINK_MODE, profile)] ?: false
    }
    /**
     * The roles batch: the active profile's link mode while it is empty (the Туннель tab may offer the other mode), null
     * once it holds a link, a server or a subscription.
     */
    internal val emptyProfileMode: Flow<Boolean?> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        if (profileModeSwitchable(prefs, profile)) prefs[getProfileKey(CSQTT_LINK_MODE, profile)] == true else null
    }

    val peer: Flow<String> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(PEER, profile)] ?: ""
    }
    val vkHashes: Flow<String> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(VK_HASHES, profile)] ?: ""
    }
    val secondaryVkHash: Flow<String> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(SECONDARY_VK_HASH, profile)] ?: ""
    }
    /** The most workers the active profile may use: «Экстра потоки» lifts 70 to 100, a subscription can lower it. */
    private fun workersMaximum(prefs: Preferences): Int {
        val profile = selectedProfile(prefs)
        val isExtra = prefs[getProfileKey(EXTRA_WORKERS, profile)] ?: false
        val defaultMax = WorkerCountPolicy.defaultMaximum(isExtra)
        return prefs[getProfileKey(SUBSCRIPTION_POWER, profile)]
            ?.takeIf { it in 10..100 }
            ?.coerceAtMost(defaultMax)
            ?: defaultMax
    }
    val workersMaximum: Flow<Int> = dataStore.data.map { prefs -> workersMaximum(prefs) }
    val workersPerHash: Flow<Int> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        val current = prefs[getProfileKey(WORKERS_PER_HASH, profile)] ?: CsqttConstants.Tunnel.DEFAULT_WORKERS
        WorkerCountPolicy.normalize(current, maximum = workersMaximum(prefs))
    }
    val protocol: Flow<String> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(PROTOCOL, profile)] ?: "udp"
    }
    val listenPort: Flow<Int> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(LISTEN_PORT, profile)] ?: 0
    }
    /** The deploy form's «Ручные порты», on the active profile's VPS record. */
    val manualPortsEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        linkedVps(prefs, selectedProfile(prefs))?.let { prefs[getProfileKey(MANUAL_PORTS_ENABLED, it)] } ?: false
    }
    val serverPeerPort: Flow<Int> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(SERVER_PEER_PORT, profile)] ?: CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT
    }
    val sni: Flow<String> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(SNI, profile)] ?: ""
    }

    /** The deploy tab's form: the active profile's VPS record (B5b2), its ports, and the defaults when it has none. */
    internal val deploySettingsSnapshot: Flow<DeploySettingsSnapshot> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        val vps = linkedVps(prefs, profile)
        fun <T> record(key: Preferences.Key<T>): T? = vps?.let { prefs[getProfileKey(key, it)] }
        fun secret(encrypted: Preferences.Key<String>, legacy: Preferences.Key<String>) =
            vps?.let { readSecret(prefs, encrypted, legacy, it) } ?: ""
        DeploySettingsSnapshot(
            profile = profile,
            epoch = prefs[PROFILE_EPOCH] ?: 0,
            ip = record(DEPLOY_IP) ?: "",
            serverName = record(DEPLOY_SERVER_NAME) ?: "",
            sshLogin = record(DEPLOY_LOGIN) ?: "",
            sshPassword = secret(DEPLOY_PASSWORD_ENCRYPTED, DEPLOY_PASSWORD),
            webLogin = record(DEPLOY_WEB_LOGIN) ?: "",
            webPassword = secret(DEPLOY_WEB_PASSWORD_ENCRYPTED, DEPLOY_WEB_PASSWORD),
            mainPassword = secret(DEPLOY_MAIN_PASSWORD_ENCRYPTED, DEPLOY_MAIN_PASSWORD),
            sshPort = record(DEPLOY_SSH_PORT)
                ?.ifBlank { CsqttConstants.Network.DEFAULT_SSH_PORT.toString() }
                ?: CsqttConstants.Network.DEFAULT_SSH_PORT.toString(),
            manualPortsEnabled = record(MANUAL_PORTS_ENABLED) ?: false,
            serverPeerPort = prefs[getProfileKey(SERVER_PEER_PORT, profile)]
                ?: CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT,
            serverWebPort = prefs[getProfileKey(SERVER_WEB_PORT, profile)]
                ?: CsqttConstants.Network.DEFAULT_SERVER_WEB_PORT,
            draftPeerPort = record(DEPLOY_PEER_PORT),
            draftWebPort = record(DEPLOY_WEB_PORT),
            sshKeysMode = record(SSH_KEYS_MODE) ?: false,
            dockerInstall = record(DOCKER_INSTALL) ?: false,
            secretPathEnabled = true,
            secretPathUuid = record(DEPLOY_SECRET_PATH_UUID) ?: "",
            serverConfigured = record(DEPLOY_SERVER_CONFIGURED) ?: false,
            lastInspectionAtMs = record(DEPLOY_LAST_INSPECTION_AT) ?: 0L,
            lastInspectionOk = record(DEPLOY_LAST_INSPECTION_OK) ?: false,
            lastInspectionArch = record(DEPLOY_LAST_INSPECTION_ARCH) ?: "",
            lastInspectionServiceStatus = record(DEPLOY_LAST_INSPECTION_SERVICE) ?: "",
            lastInspectionVersion = record(DEPLOY_LAST_INSPECTION_VERSION) ?: "",
            lastInspectionMessage = record(DEPLOY_LAST_INSPECTION_MESSAGE) ?: "",
            lastPanelAvailable = record(DEPLOY_LAST_INSPECTION_PANEL) ?: false,
            sshPrivateKey = secret(SSH_PRIVATE_KEY_ENCRYPTED, SSH_PRIVATE_KEY),
            sshKeyPassphrase = secret(SSH_KEY_PASSPHRASE_ENCRYPTED, SSH_KEY_PASSPHRASE),
            sshCertificate = secret(SSH_CERTIFICATE_ENCRYPTED, SSH_CERTIFICATE),
        )
    }.onIo()
    internal val tunnelAuthSnapshot: Flow<TunnelAuthSnapshot> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        val connectionPassword = readConnectionPasswordState(prefs, profile)
        TunnelAuthSnapshot(
            profile = profile,
            connectionPassword = connectionPassword.value,
            connectionPasswordState = connectionPassword.state,
            csqttLink = prefs[getProfileKey(CSQTT_LINK, profile)] ?: "",
            csqttLinkMode = prefs[getProfileKey(CSQTT_LINK_MODE, profile)] ?: false,
        )
    }.onIo()
    val excludedApps: Flow<String> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(EXCLUDED_APPS, profile)] ?: ""
    }
    val whitelistApps: Flow<String> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(WHITELIST_APPS, profile)] ?: ""
    }

    internal val connectionPasswordState: Flow<StoredSecret> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        readConnectionPasswordState(prefs, profile)
    }.onIo()
    val connectionPassword: Flow<String> = connectionPasswordState.map { it.value }

    val vkAuthMode: Flow<String> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        when (prefs[getProfileKey(VK_AUTH_MODE, profile)]) {
            CsqttConstants.VkAuth.MODE_CAPTCHA -> CsqttConstants.VkAuth.MODE_CAPTCHA
            CsqttConstants.VkAuth.MODE_AUTO_JS -> CsqttConstants.VkAuth.MODE_AUTO_JS
            else -> CsqttConstants.VkAuth.MODE_CALLS
        }
    }
    val obfsMode: Flow<String> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        val mode = prefs[getProfileKey(OBFS_MODE, profile)]
        if (mode == "unified") {
            mode
        } else {
            CsqttConstants.Tunnel.DEFAULT_OBFS_MODE
        }
    }

    val turnTransport: Flow<String> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        when (prefs[getProfileKey(TURN_TRANSPORT, profile)]) {
            CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS -> CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS
            else -> CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT
        }
    }

    /** P1 Bt part 2: the active profile's VPN MTU, as the interface takes it. */
    val vpnMtu: Flow<Int> = dataStore.data.map { prefs ->
        VpnMtu.effective(prefs[getProfileKey(VPN_MTU, selectedProfile(prefs))])
    }

    /** Per profile; the core uses it only with the TCP transport. */
    val dpiSplit: Flow<DpiSplitMode> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        DpiSplitMode.fromStored(prefs[getProfileKey(DPI_SPLIT, profile)])
    }

    val srtpMode: Flow<String> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        when (prefs[getProfileKey(SRTP_MODE, profile)]) {
            CsqttConstants.SrtpMode.WRAP -> CsqttConstants.SrtpMode.WRAP
            CsqttConstants.SrtpMode.DTLS -> CsqttConstants.SrtpMode.DTLS
            else -> CsqttConstants.SrtpMode.DEFAULT
        }
    }

    val customTurnIps: Flow<String> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        val subnetId = prefs[getProfileKey(SELECTED_TURN_SUBNET, profile)] ?: ""
        if (subnetId.isNotBlank()) {
            val stored = prefs[getProfileKey(CUSTOM_TURN_IPS, profile)]
            if (!stored.isNullOrBlank()) {
                stored
            } else {
                TurnSubnetRegistry.getJoinedIpsForSubnet(subnetId)
            }
        } else {
            ""
        }
    }

    val selectedTurnSubnet: Flow<String> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(SELECTED_TURN_SUBNET, profile)] ?: ""
    }

    val turnSubnetIpLimit: Flow<String> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(TURN_SUBNET_IP_LIMIT, profile)] ?: "ALL"
    }

    val captchaMode: Flow<String> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(CAPTCHA_MODE, profile)] ?: "auto"
    }
    val captchaSolveMethod: Flow<String> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(CAPTCHA_SOLVE_METHOD, profile)] ?: "auto"
    }
    val captchaWbvSolveMethod: Flow<String> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(CAPTCHA_WBV_SOLVE_METHOD, profile)] ?: "auto"
    }

    val vkHashMode: Flow<String> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        val mode = prefs[getProfileKey(VK_HASH_MODE, profile)]
        when (mode) {
            "auto", CsqttConstants.VkAutoHash.MODE_AUTO_API ->
                CsqttConstants.VkAutoHash.MODE_AUTO_API
            CsqttConstants.VkAutoHash.MODE_AUTO_JS ->
                CsqttConstants.VkAutoHash.MODE_AUTO_JS
            else -> CsqttConstants.VkAutoHash.MODE_MANUAL
        }
    }
    val vkAccessToken: Flow<String> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        readSecret(prefs, VK_ACCESS_TOKEN_ENCRYPTED, VK_ACCESS_TOKEN, profile)
    }.onIo()
    val vkAccessTokenUserId: Flow<String> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(VK_ACCESS_TOKEN_USER_ID, profile)] ?: ""
    }

    val isWhitelist: Flow<Boolean> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(IS_WHITELIST, profile)] ?: false
    }

    val domainRoutingEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(DOMAIN_ROUTING_ENABLED, profile)] ?: false
    }

    val excludeRuZonesEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(EXCLUDE_RU_ZONES_ENABLED, profile)] ?: false
    }

    val templateDomainsEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(TEMPLATE_DOMAINS_ENABLED, profile)] ?: false
    }

    val domainIsWhitelist: Flow<Boolean> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(DOMAIN_IS_WHITELIST, profile)] ?: false
    }

    val directDomains: Flow<String> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(DIRECT_DOMAINS, profile)] ?: ""
    }

    val directDomainsBlack: Flow<String> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(DIRECT_DOMAINS_BLACK, profile)]
            ?: prefs[getProfileKey(DIRECT_DOMAINS, profile)]
            ?: ""
    }

    val directDomainsWhite: Flow<String> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(DIRECT_DOMAINS_WHITE, profile)] ?: ""
    }

    val ipRoutingEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(IP_ROUTING_ENABLED, profile)] ?: false
    }

    val ipIsWhitelist: Flow<Boolean> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(IP_IS_WHITELIST, profile)] ?: false
    }

    val directIpsBlack: Flow<String> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(DIRECT_IPS_BLACK, profile)] ?: ""
    }

    val directIpsWhite: Flow<String> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(DIRECT_IPS_WHITE, profile)] ?: ""
    }

    /** What the active profile asks of the VPN interface (B5d2), in one read. */
    internal val vpnSettings: Flow<VpnSettings> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        fun apps(key: Preferences.Key<String>) = prefs[getProfileKey(key, profile)].orEmpty().split(',').filter(String::isNotBlank).toSet()
        val ipsWhite = prefs[getProfileKey(IP_IS_WHITELIST, profile)] ?: false
        VpnSettings(
            appsWhitelist = prefs[getProfileKey(IS_WHITELIST, profile)] ?: false,
            whitelistApps = apps(WHITELIST_APPS),
            excludedApps = apps(EXCLUDED_APPS),
            allApps = prefs[getProfileKey(APP_ROUTING_ALL, profile)] ?: false,
            lanBypass = prefs[getProfileKey(LAN_BYPASS, profile)] ?: false,
            dnsOverride = prefs[getProfileKey(DNS_OVERRIDE, profile)].orEmpty(),
            ipsWhitelist = ipsWhite,
            ips = prefs[getProfileKey(if (ipsWhite) DIRECT_IPS_WHITE else DIRECT_IPS_BLACK, profile)].orEmpty(),
            mtu = VpnMtu.effective(prefs[getProfileKey(VPN_MTU, profile)]),
        )
    }.distinctUntilChanged()

    val appRoutingAll: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[getProfileKey(APP_ROUTING_ALL, selectedProfile(prefs))] ?: false
    }

    /** M-59: «Все с VPN» for the active profile; the black and white lists are left as they are. */
    suspend fun saveAppRoutingAll(enabled: Boolean) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(APP_ROUTING_ALL, profile)] = enabled
        }
    }

    /** M-57: the active profile's domain and IP rules in one read (the black domain list falls back to the legacy key). */
    internal val routingSnapshot: Flow<RoutingSnapshot> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        RoutingSnapshot(
            domainIsWhitelist = prefs[getProfileKey(DOMAIN_IS_WHITELIST, profile)] ?: false,
            directDomainsBlack = prefs[getProfileKey(DIRECT_DOMAINS_BLACK, profile)]
                ?: prefs[getProfileKey(DIRECT_DOMAINS, profile)]
                ?: "",
            directDomainsWhite = prefs[getProfileKey(DIRECT_DOMAINS_WHITE, profile)] ?: "",
            excludeRuZonesEnabled = prefs[getProfileKey(EXCLUDE_RU_ZONES_ENABLED, profile)] ?: false,
            templateDomainsEnabled = prefs[getProfileKey(TEMPLATE_DOMAINS_ENABLED, profile)] ?: false,
            ipIsWhitelist = prefs[getProfileKey(IP_IS_WHITELIST, profile)] ?: false,
            directIpsBlack = prefs[getProfileKey(DIRECT_IPS_BLACK, profile)] ?: "",
            directIpsWhite = prefs[getProfileKey(DIRECT_IPS_WHITE, profile)] ?: "",
        )
    }.distinctUntilChanged()

    /** See PROFILE_EPOCH: each tab's saved form state is keyed on the profile and this. */
    val profileEpoch: Flow<Int> = dataStore.data.map { it[PROFILE_EPOCH] ?: 0 }

    /** Classic's «Профиль конфигурации»: the profiles in use and the active one, with their names (M-97). */
    internal val profileList: Flow<List<Pair<Int, String>>> = dataStore.data.map(::profileList)

    /** Profiles the user has (a peer, link, subscription, VPS record or name): a new one never lands on them (M-39). */
    val occupiedProfiles: Flow<Set<Int>> = dataStore.data.map { prefs ->
        (CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX).filter { profileInUse(prefs, it) }.toSet()
    }

    /** Subscription metadata of every slot imported from a subscription. */
    internal val profileSubscriptions: Flow<Map<Int, ProfileSubscriptionInfo>> = dataStore.data.map { prefs ->
        (CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX).mapNotNull { p ->
            val url = prefs[getProfileKey(SUBSCRIPTION_URL, p)]?.trim().orEmpty()
            if (url.isEmpty()) return@mapNotNull null
            p to ProfileSubscriptionInfo(
                url = url,
                title = prefs[getProfileKey(SUBSCRIPTION_TITLE, p)]?.trim().orEmpty(),
                subUuid = prefs[getProfileKey(SUBSCRIPTION_UUID, p)].orEmpty(),
                nodeName = prefs[getProfileKey(SUBSCRIPTION_NODE, p)]?.trim().orEmpty(),
                powerLimit = prefs[getProfileKey(SUBSCRIPTION_POWER, p)] ?: 0,
            )
        }.toMap()
    }

    /** Subscription-level metadata keyed by subscription URL. */
    internal val subscriptionMeta: Flow<Map<String, SubscriptionMeta>> = dataStore.data.map { prefs ->
        decodeSubscriptionMeta(prefs[SUBSCRIPTION_META])
    }

    suspend fun saveProfileName(profile: Int, name: String) {
        dataStore.edit { prefs ->
            val trimmed = name.trim()
            if (trimmed.isEmpty() || trimmed == defaultProfileName(profile)) {
                prefs.remove(getProfileKey(PROFILE_NAME, profile))
            } else {
                prefs[getProfileKey(PROFILE_NAME, profile)] = trimmed
            }
        }
    }

    internal suspend fun readProfileConnectionSettings(profile: Int): ProfileConnectionSnapshot =
        dataStore.data.map { prefs ->
            val secret = readConnectionPasswordState(prefs, profile)
            ProfileConnectionSnapshot(
                peer = prefs[getProfileKey(PEER, profile)] ?: "",
                serverPeerPort = prefs[getProfileKey(SERVER_PEER_PORT, profile)]
                    ?: CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT,
                connectionPassword = secret.value,
                connectionPasswordState = secret.state,
                workersPerHash = WorkerCountPolicy.normalize(
                    prefs[getProfileKey(WORKERS_PER_HASH, profile)] ?: CsqttConstants.Tunnel.DEFAULT_WORKERS,
                ),
                extraWorkers = prefs[getProfileKey(EXTRA_WORKERS, profile)] ?: false,
                csqttLink = prefs[getProfileKey(CSQTT_LINK, profile)] ?: "",
                csqttLinkMode = prefs[getProfileKey(CSQTT_LINK_MODE, profile)] ?: false,
                subscriptionUrl = prefs[getProfileKey(SUBSCRIPTION_URL, profile)] ?: "",
                subscriptionTitle = prefs[getProfileKey(SUBSCRIPTION_TITLE, profile)] ?: "",
                subscriptionPower = prefs[getProfileKey(SUBSCRIPTION_POWER, profile)] ?: 0,
            )
        }.first()

    suspend fun saveProfileConnectionSettings(
        profile: Int,
        peer: String,
        serverPeerPort: Int,
        connectionPassword: String,
        workersPerHash: Int,
    ) {
        dataStore.edit { prefs ->
            prefs[getProfileKey(PEER, profile)] = peer.trim()
            prefs[getProfileKey(SERVER_PEER_PORT, profile)] = serverPeerPort.coerceIn(1, 65535)
            prefs[getProfileKey(WORKERS_PER_HASH, profile)] =
                WorkerCountPolicy.normalize(workersPerHash)
            val localKey = getProfileKey(CONNECTION_PASSWORD_LOCAL, profile)
            val legacyKey = getProfileKey(CONNECTION_PASSWORD, profile)
            val encryptedKey = getProfileKey(CONNECTION_PASSWORD_ENCRYPTED, profile)
            if (connectionPassword.isBlank()) {
                prefs.remove(localKey)
            } else {
                prefs[localKey] = connectionPassword
            }
            prefs.remove(legacyKey)
            prefs.remove(encryptedKey)
        }
    }

    suspend fun saveProfileParticipantLink(profile: Int, link: String) {
        dataStore.edit { prefs ->
            writeLinkKeys(prefs, profile, link.trim())
        }
    }

    suspend fun saveProfileParticipantMode(profile: Int, enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[getProfileKey(CSQTT_LINK_MODE, profile)] = enabled
        }
    }

    suspend fun saveProfileWorkersPerHash(profile: Int, workersPerHash: Int) {
        dataStore.edit { prefs ->
            prefs[getProfileKey(WORKERS_PER_HASH, profile)] =
                WorkerCountPolicy.normalize(workersPerHash, maximum = CsqttConstants.Tunnel.MAX_WORKERS)
        }
    }

    /** Writes subscription nodes into profile slots as the owner's links (M-170); a node's power caps the worker count. */
    internal suspend fun applySubscriptionImport(writes: List<SubscriptionProfileWrite>) {
        if (writes.isEmpty()) return
        dataStore.edit { prefs -> writes.forEach { writeSubscriptionNode(prefs, it) } }
    }

    /** The first slot no profile uses (M-39). */
    internal suspend fun findFirstFreeProfile(): Int? = dataStore.data.map { prefs ->
        (CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX).firstOrNull { !profileInUse(prefs, it) }
    }.first()

    /**
     * Deletes a profile: every connection key (hashes, token, modes, routing and app lists, subscription, its VPS link),
     * making the slot free again; its VPS record stays (M-40, M-41).
     */
    suspend fun clearProfile(profile: Int) {
        dataStore.edit { prefs -> deleteProfileKeys(prefs, profile) }
    }

    /**
     * Classic's «Удалить профиль»: the profile, and its VPS record when no other profile links it — classic has no VPS list
     * to reach it from. The server itself is not touched.
     */
    suspend fun deleteProfileFromClassic(profile: Int) {
        dataStore.edit { prefs -> deleteProfileWithLoneVps(prefs, profile) }
    }

    /** Whether [profile] is the only one linking its VPS record (the delete dialog says the SSH data go too). */
    internal suspend fun profileHoldsOnlyLinkToVps(profile: Int): Boolean =
        dataStore.data.map { prefs -> isOnlyLinkToVps(prefs, profile) }.first()

    /** «Добавить профиль»: a free slot, cleared and made active (newProfileKeys); null when all 32 are in use. */
    suspend fun addProfile(): Int? {
        var added: Int? = null
        dataStore.edit { prefs -> added = newProfileKeys(prefs) }
        return added
    }

    /** Wipes all profile slots associated with the given subscription URL. */
    suspend fun clearSubscription(url: String) {
        if (url.isBlank()) return
        val currentPrefs = dataStore.data.first()
        val targetSlots = (CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX).filter { profile ->
            currentPrefs[getProfileKey(SUBSCRIPTION_URL, profile)].orEmpty() == url
        }
        for (slot in targetSlots) {
            clearProfile(slot)
        }
        deleteSubscriptionMeta(url)
    }

    internal suspend fun recordSubscriptionImport(
        url: String,
        title: String,
        allowHttp: Boolean,
    ) {
        val key = url.trim()
        if (key.isEmpty()) return
        dataStore.edit { prefs ->
            val meta = decodeSubscriptionMeta(prefs[SUBSCRIPTION_META]).toMutableMap()
            val existing = meta[key]
            meta[key] = SubscriptionMeta(
                url = key,
                title = existing?.title?.takeIf { it.isNotBlank() } ?: title.trim(),
                autoUpdateMinutes = existing?.autoUpdateMinutes ?: 0,
                lastUpdatedAt = existing?.lastUpdatedAt ?: 0L,
                lastAttemptAt = existing?.lastAttemptAt ?: 0L,
                allowHttp = allowHttp || existing?.allowHttp == true,
                certSha256 = existing?.certSha256.orEmpty(),
                certNotAfter = existing?.certNotAfter ?: 0L,
                certKind = existing?.certKind.orEmpty(),
                certNames = existing?.certNames.orEmpty(),
            )
            prefs[SUBSCRIPTION_META] = encodeSubscriptionMeta(meta)
        }
    }

    /** The leaf a marked subscription's server was last trusted with, whichever way (M-171; review F2, F3). */
    internal suspend fun recordSubscriptionCertificate(url: String, cert: SubscriptionCertRecord) {
        if (cert.sha256.isBlank()) return
        updateSubscriptionMeta(url) { meta ->
            val kind = cert.kind.stored
            val names = cert.names.sorted().joinToString(",")
            if (meta.certSha256 == cert.sha256 && meta.certNotAfter == cert.notAfterMs && meta.certKind == kind &&
                meta.certNames == names
            ) {
                meta
            } else {
                meta.copy(certSha256 = cert.sha256, certNotAfter = cert.notAfterMs, certKind = kind, certNames = names)
            }
        }
    }

    internal suspend fun markSubscriptionAttempt(
        url: String,
        at: Long = System.currentTimeMillis(),
    ) {
        updateSubscriptionMeta(url) { meta ->
            meta.copy(lastAttemptAt = at)
        }
    }

    internal suspend fun markSubscriptionUpdated(
        url: String,
        title: String? = null,
        at: Long = System.currentTimeMillis(),
    ) {
        updateSubscriptionMeta(url) { meta ->
            val resolvedTitle = meta.title.takeIf { it.isNotBlank() }
                ?: title?.trim().orEmpty()
            meta.copy(
                title = resolvedTitle,
                lastUpdatedAt = at,
                lastAttemptAt = at,
            )
        }
    }

    internal suspend fun saveSubscriptionAutoUpdate(url: String, minutes: Int) {
        updateSubscriptionMeta(url) { meta ->
            meta.copy(autoUpdateMinutes = minutes.coerceAtLeast(0))
        }
    }

    internal suspend fun renameSubscription(url: String, oldTitle: String, newTitle: String) {
        val key = url.trim()
        val title = newTitle.trim()
        if (key.isEmpty()) return
        dataStore.edit { prefs ->
            val meta = decodeSubscriptionMeta(prefs[SUBSCRIPTION_META]).toMutableMap()
            val existing = meta[key] ?: SubscriptionMeta(url = key, title = oldTitle)
            meta[key] = existing.copy(url = key, title = title)
            prefs[SUBSCRIPTION_META] = encodeSubscriptionMeta(meta)

            for (profile in CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX) {
                if (prefs[getProfileKey(SUBSCRIPTION_URL, profile)].orEmpty() != key) continue
                if (title.isEmpty()) {
                    prefs.remove(getProfileKey(SUBSCRIPTION_TITLE, profile))
                } else {
                    prefs[getProfileKey(SUBSCRIPTION_TITLE, profile)] = title
                }
                val profileName = prefs[getProfileKey(PROFILE_NAME, profile)]
                if (profileName == oldTitle) {
                    if (title.isEmpty()) {
                        prefs.remove(getProfileKey(PROFILE_NAME, profile))
                    } else {
                        prefs[getProfileKey(PROFILE_NAME, profile)] = title
                    }
                }
            }
        }
    }

    internal suspend fun changeSubscriptionUrl(oldUrl: String, newUrl: String) {
        val from = oldUrl.trim()
        val to = newUrl.trim()
        if (from.isEmpty() || to.isEmpty() || from == to) return
        dataStore.edit { prefs ->
            val meta = decodeSubscriptionMeta(prefs[SUBSCRIPTION_META]).toMutableMap()
            val moved = meta.remove(from) ?: SubscriptionMeta(url = to)
            meta[to] = moved.copy(url = to)
            prefs[SUBSCRIPTION_META] = encodeSubscriptionMeta(meta)

            for (profile in CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX) {
                if (prefs[getProfileKey(SUBSCRIPTION_URL, profile)].orEmpty() == from) {
                    prefs[getProfileKey(SUBSCRIPTION_URL, profile)] = to
                }
            }
        }
    }

    internal suspend fun deleteSubscriptionMeta(url: String) {
        val key = url.trim()
        if (key.isEmpty()) return
        dataStore.edit { prefs ->
            val meta = decodeSubscriptionMeta(prefs[SUBSCRIPTION_META]).toMutableMap()
            if (meta.remove(key) != null) {
                prefs[SUBSCRIPTION_META] = encodeSubscriptionMeta(meta)
            }
        }
    }

    private suspend fun updateSubscriptionMeta(
        url: String,
        transform: (SubscriptionMeta) -> SubscriptionMeta,
    ) {
        val key = url.trim()
        if (key.isEmpty()) return
        dataStore.edit { prefs ->
            val meta = decodeSubscriptionMeta(prefs[SUBSCRIPTION_META]).toMutableMap()
            meta[key] = transform(meta[key] ?: SubscriptionMeta(url = key))
            prefs[SUBSCRIPTION_META] = encodeSubscriptionMeta(meta)
        }
    }

    val receivedHashes: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[getProfileKey(RECEIVED_HASHES, selectedProfile(prefs))].orEmpty().split(",").filter(String::isNotBlank)
    }

    /** D6: the pool record of [profile] (the active one by default), when its owner run uses one (B5e3b). */
    internal suspend fun activePoolPanel(profile: Int? = null): PoolPanel? = withContext(Dispatchers.IO) {
        val prefs = dataStore.data.first()
        poolPanelOf(prefs, profile ?: selectedProfile(prefs)) { vps ->
            readSecret(prefs, DEPLOY_WEB_PASSWORD_ENCRYPTED, DEPLOY_WEB_PASSWORD, vps)
        }
    }

    internal suspend fun vkPool(vps: Int): List<String> = vkPoolOf(dataStore.data.first(), vps)

    internal suspend fun saveVkPool(vps: Int, pool: List<String>) {
        dataStore.edit { prefs -> writeVkPool(prefs, vps, pool) }
    }

    internal suspend fun handOverState(vps: Int) = handOverStateOf(dataStore.data.first(), vps)

    internal suspend fun recordHandOver(vps: Int, sent: List<String>, cert: SubscriptionCertRecord?) {
        dataStore.edit { prefs -> writeHandOver(prefs, vps, sent, cert) }
    }

    internal suspend fun clearHandOver(vps: Int) {
        dataStore.edit { prefs -> clearHandOver(prefs, vps) }
    }

    /** B5h2: the hand-over's [result] for [vps]; true for a new failure (rule 10 announces only that). */
    internal suspend fun recordHandOverResult(vps: Int, result: HandOverResult): Boolean {
        var newFailure = false
        dataStore.edit { prefs -> newFailure = writeHandOverResult(prefs, vps, result) }
        return newFailure
    }

    /** B5h2: the open profile's own server, in «Авто API», with a hand-over on record: its last result. */
    internal val handOverNotice: Flow<HandOverResult?> =
        dataStore.data.map { prefs -> handOverNoticeOf(prefs, selectedProfile(prefs)) }.distinctUntilChanged()

    /** B5h2 (U3): the last refresh's outcome of [url]; true for a new failure. */
    internal suspend fun recordSubscriptionOutcome(url: String, error: String, skipped: String): Boolean {
        var newFailure = false
        dataStore.edit { prefs -> newFailure = writeSubscriptionOutcome(prefs, url, error, skipped) }
        return newFailure
    }

    /** B5h2 (U3): the open profile's subscription, when its last refresh failed or skipped nodes. */
    internal val subscriptionNotice: Flow<SubscriptionMeta?> =
        dataStore.data.map { prefs -> subscriptionNoticeOf(prefs, selectedProfile(prefs)) }.distinctUntilChanged()

    /** S8: true when [profile]'s stored set changed (written only for a link profile). */
    internal suspend fun saveReceivedHashes(profile: Int, hashes: List<String>, sourceLink: String): Boolean {
        var changed = false
        dataStore.edit { prefs -> changed = receivedHashesKeys(prefs, profile, hashes, sourceLink) }
        return changed
    }

    val linkHashesRemoved: Flow<Set<String>> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        (prefs[getProfileKey(LINK_HASHES_REMOVED, profile)] ?: "").split(",").filter(String::isNotBlank).toSet()
    }

    suspend fun saveLinkHashesRemoved(removed: Set<String>) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            if (removed.isEmpty()) {
                prefs.remove(getProfileKey(LINK_HASHES_REMOVED, profile))
            } else {
                prefs[getProfileKey(LINK_HASHES_REMOVED, profile)] = removed.joinToString(",")
            }
        }
    }

    val extraWorkers: Flow<Boolean> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(EXTRA_WORKERS, profile)] ?: false
    }
    val autoJsRiskAcknowledged: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[AUTO_JS_RISK_ACKNOWLEDGED] ?: false
    }
    val tcpTransportRiskAcknowledged: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[TCP_TRANSPORT_RISK_ACKNOWLEDGED] ?: false
    }
    val autoPauseOnWifi: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[AUTO_PAUSE_ON_WIFI] ?: false
    }
    /** B5f2: the switch, the rule and the networks. */
    internal val wifiRules: Flow<WifiRules> = dataStore.data.map { readWifiRules(it) }.distinctUntilChanged()
    val batterySaverMode: Flow<String> = dataStore.data.map { it[BATTERY_SAVER_MODE] ?: BatterySaver.DEFAULT_MODE }
    val batterySaverPercent: Flow<Int> = dataStore.data.map { it[BATTERY_SAVER_PERCENT] ?: BatterySaver.DEFAULT_PERCENT }
    suspend fun isBatteryOptimizationPromptHandled(): Boolean =
        dataStore.data.first()[BATTERY_OPTIMIZATION_PROMPT_HANDLED] ?: false

    val themeMode: Flow<String> = dataStore.data.map { it[THEME_MODE] ?: "system" }
    val isDynamicColor: Flow<Boolean> = dataStore.data.map { it[IS_DYNAMIC_COLOR] ?: false }
    val themePalette: Flow<String> = dataStore.data.map { it[THEME_PALETTE] ?: "indigo" }

    val selectedFingerprint: Flow<String> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(SELECTED_FINGERPRINT, profile)] ?: "firefox"
    }
    val activeClientIds: Flow<String> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(ACTIVE_CLIENT_IDS, profile)] ?: CsqttConstants.Tunnel.DEFAULT_CLIENT_IDS
    }
    val clientIdCheckResults: Flow<String> = dataStore.data.map { prefs ->
        prefs[CLIENT_ID_CHECK_RESULTS] ?: "{}"
    }
    val vkHashCheckResults: Flow<String> = dataStore.data.map { prefs ->
        val profile = selectedProfile(prefs)
        prefs[getProfileKey(VK_HASH_CHECK_RESULTS, profile)] ?: "{}"
    }
    val connectionGeneration: Flow<Long> = dataStore.data.map { prefs ->
        prefs[CONNECTION_GENERATION] ?: 0L
    }

    val tunnelWasRunning: Flow<Boolean> = dataStore.data.map { it[TUNNEL_WAS_RUNNING] ?: false }
    val updateLastCheckAt: Flow<Long> = dataStore.data.map { it[UPDATE_LAST_CHECK_AT] ?: 0L }
    val updateLatestVersion: Flow<String> = dataStore.data.map { it[UPDATE_LATEST_VERSION] ?: "" }
    val updateLastError: Flow<String> = dataStore.data.map { it[UPDATE_LAST_ERROR] ?: "" }
    val updateCheckIntervalHours: Flow<Int> = dataStore.data.map {
        it[UPDATE_CHECK_INTERVAL_HOURS] ?: CsqttConstants.Update.DEFAULT_CHECK_INTERVAL_HOURS
    }
    val updatePostponeUntil: Flow<Long> = dataStore.data.map { it[UPDATE_POSTPONE_UNTIL] ?: 0L }
    val updatePostponeVersion: Flow<String> = dataStore.data.map { it[UPDATE_POSTPONE_VERSION] ?: "" }

    // Last in the class, after every property: Kotlin assigns them top to bottom, and the collectors below start on
    // other threads at once. Above the flows they read, a collector could run before its flow was assigned and call
    // collect on null, which crashed the app at launch on slower phones (Redmi).
    /** «Показывать логи», app-wide (the roles batch): on unless turned off. */
    val showLogsTab: Flow<Boolean> = dataStore.data.map { prefs ->
        (prefs[SHOW_LOGS_TAB] ?: true).also { cachedShowLogsTab = it }
    }

    /** «Показывать деплой», app-wide: off unless turned on; store step 5 turned it on for owners and anyone with a server. */
    val showDeployTab: Flow<Boolean> = dataStore.data.map { prefs ->
        (prefs[SHOW_DEPLOY_TAB] ?: false).also { cachedShowDeployTab = it }
    }

    init {
        // the cached values are the screens' (the active profile): only an Active store starts them
        if (scope == ProfileScope.Active && uiCacheStarted.compareAndSet(false, true)) {
            // the numbered steps ran already (StoreMigrations); these need the keystore or the package list
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                migrateConnectionPasswordsToLocalStorage()
                migrateSecretsToKeystore()
                migrateLegacyWhitelistMode()
                migrateSubscriptionMeta()
                migrateDeployServerNames()
            }
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                launch { vkHashMode.collect { cachedVkHashMode = it } }
                launch { vkAccessToken.collect { cachedVkAccessToken = it } }
                launch { obfsMode.collect { cachedObfsMode = it } }
                launch { turnTransport.collect { cachedTurnTransport = it } }
                launch { srtpMode.collect { cachedSrtpMode = it } }
                launch { csqttLinkMode.collect { cachedCsqttLinkMode = it } }
                launch { workersPerHash.collect { cachedWorkersPerHash = it } }
                launch { manualPortsEnabled.collect { cachedManualPortsEnabled = it } }
                launch { serverPeerPort.collect { cachedServerPeerPort = it } }
                launch { customTurnIps.collect { cachedCustomTurnIps = it } }
                launch { excludedApps.collect { cachedExcludedApps = it } }
                launch { whitelistApps.collect { cachedWhitelistApps = it } }
                launch { isWhitelist.collect { cachedIsWhitelist = it } }
                launch { domainRoutingEnabled.collect { cachedDomainRoutingEnabled = it } }
                launch { domainIsWhitelist.collect { cachedDomainIsWhitelist = it } }
                launch { excludeRuZonesEnabled.collect { cachedExcludeRuZonesEnabled = it } }
                launch { templateDomainsEnabled.collect { cachedTemplateDomainsEnabled = it } }
                launch { directDomains.collect { cachedDirectDomains = it } }
                launch { directDomainsBlack.collect { cachedDirectDomainsBlack = it } }
                launch { directDomainsWhite.collect { cachedDirectDomainsWhite = it } }
                launch { ipRoutingEnabled.collect { cachedIpRoutingEnabled = it } }
                launch { ipIsWhitelist.collect { cachedIpIsWhitelist = it } }
                launch { directIpsBlack.collect { cachedDirectIpsBlack = it } }
                launch { directIpsWhite.collect { cachedDirectIpsWhite = it } }
            }
        }
    }

    suspend fun saveTunnelWasRunning(running: Boolean) {
        val boot = if (running) bootCount() else null
        val update = if (running) appUpdateTime() else null
        dataStore.edit { prefs ->
            prefs[TUNNEL_WAS_RUNNING] = running
            if (boot != null) prefs[TUNNEL_WAS_RUNNING_BOOT] = boot else prefs.remove(TUNNEL_WAS_RUNNING_BOOT)
            if (update != null) prefs[TUNNEL_WAS_RUNNING_UPDATE] = update else prefs.remove(TUNNEL_WAS_RUNNING_UPDATE)
        }
    }

    /** B5f: the run's flag, and the boot count and install time it was set with (null: none recorded). */
    internal data class WasRunning(val running: Boolean, val boot: Int?, val update: Long?)

    internal suspend fun tunnelWasRunningState(): WasRunning = dataStore.data.first().let {
        WasRunning(it[TUNNEL_WAS_RUNNING] ?: false, it[TUNNEL_WAS_RUNNING_BOOT], it[TUNNEL_WAS_RUNNING_UPDATE])
    }

    /** The app's install time (it changes with every update), or null. */
    @Suppress("DEPRECATION")
    internal fun appUpdateTime(): Long? = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime
    }.getOrNull()

    /** The phone's boot count, or null where Android does not give it. */
    internal fun bootCount(): Int? = runCatching {
        android.provider.Settings.Global.getInt(appContext.contentResolver, android.provider.Settings.Global.BOOT_COUNT)
    }.getOrNull()

    suspend fun saveThemeMode(mode: String) {
        dataStore.edit { prefs ->
            prefs[THEME_MODE] = mode
        }
    }

    suspend fun saveDynamicColor(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[IS_DYNAMIC_COLOR] = enabled
        }
    }

    suspend fun saveThemePalette(palette: String) {
        dataStore.edit { prefs ->
            prefs[THEME_PALETTE] = palette
        }
    }

    suspend fun saveFingerprint(fingerprint: String) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(SELECTED_FINGERPRINT, profile)] = fingerprint
        }
    }

    suspend fun saveActiveClientIds(clientIds: String) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(ACTIVE_CLIENT_IDS, profile)] = clientIds
        }
    }

    suspend fun saveClientIdCheckResults(resultsJson: String) {
        dataStore.edit { prefs ->
            prefs[CLIENT_ID_CHECK_RESULTS] = resultsJson
        }
    }

    suspend fun saveVkHashCheckResults(resultsJson: String) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(VK_HASH_CHECK_RESULTS, profile)] = resultsJson
        }
    }

    /** B5h2 (U2): a finished check's results, merged into what this store's profile holds when it ends. */
    internal suspend fun mergeVkHashCheckResults(checked: Map<String, VkHashValidationStatus>) {
        if (checked.isEmpty()) return
        dataStore.edit { prefs ->
            val key = getProfileKey(VK_HASH_CHECK_RESULTS, selectedProfile(prefs))
            prefs[key] = VkHashValidationCodec.encode(VkHashValidationCodec.decode(prefs[key] ?: "{}") + checked)
        }
    }

    suspend fun invalidateVkHash(hash: String) {
        val normalized = hash.trim()
        if (normalized.isEmpty()) return
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            val key = getProfileKey(VK_HASH_CHECK_RESULTS, profile)
            val results = VkHashValidationCodec.decode(prefs[key] ?: "{}") +
                (normalized to VkHashValidationStatus.Invalid)
            prefs[key] = VkHashValidationCodec.encode(results)
        }
    }

    suspend fun reserveConnectionGeneration(
        proposed: Long = 0L,
        nowSeconds: Long = System.currentTimeMillis() / 1000L,
    ): Long {
        var reserved = 0L
        dataStore.edit { prefs ->
            reserved = reserveTunnelGenerationId(
                nowSeconds.coerceAtLeast(0L),
                proposed.coerceAtLeast(0L),
                prefs[CONNECTION_GENERATION] ?: 0L,
            )
            prefs[CONNECTION_GENERATION] = reserved
        }
        return reserved
    }

    suspend fun recordConnectionGeneration(generation: Long): Long {
        var persisted = 0L
        dataStore.edit { prefs ->
            persisted = maxOf(prefs[CONNECTION_GENERATION] ?: 0L, generation.coerceAtLeast(0L))
            prefs[CONNECTION_GENERATION] = persisted
        }
        return persisted
    }

    suspend fun saveTunnelDiagnostic(code: String, message: String) {
        val normalizedCode = code.trim().take(80)
        val normalizedMessage = message.trim().replace(Regex("\\s+"), " ").take(400)
        dataStore.edit { prefs ->
            prefs[TUNNEL_LAST_DIAGNOSTIC] = "$normalizedCode|$normalizedMessage"
            prefs[TUNNEL_LAST_DIAGNOSTIC_AT] = System.currentTimeMillis()
        }
    }

    suspend fun clearTunnelDiagnostic() {
        dataStore.edit { prefs ->
            prefs.remove(TUNNEL_LAST_DIAGNOSTIC)
            prefs.remove(TUNNEL_LAST_DIAGNOSTIC_AT)
        }
    }

    /** B5h1: [cause] stays until the next start, so «Подключение» shows it after process death too. */
    suspend fun saveLastStopCause(cause: String) {
        dataStore.edit { it[LAST_STOP_CAUSE] = cause }
    }

    suspend fun clearLastStopCause() {
        dataStore.edit { it.remove(LAST_STOP_CAUSE) }
    }

    /** The run is on [profile] from now: a start, the switch onto it, a pick while paused on Wi-Fi. */
    internal suspend fun saveRunProfile(profile: Int) {
        dataStore.edit { it[RUN_PROFILE] = profile }
    }

    internal suspend fun clearRunProfile() {
        dataStore.edit { it.remove(RUN_PROFILE) }
    }

    suspend fun saveUpdateState(lastCheckAt: Long, latestVersion: String, error: String) {
        dataStore.edit { prefs ->
            prefs[UPDATE_LAST_CHECK_AT] = lastCheckAt
            prefs[UPDATE_LATEST_VERSION] = latestVersion
            prefs[UPDATE_LAST_ERROR] = error
        }
    }

    suspend fun saveUpdateCheckIntervalHours(hours: Int) {
        dataStore.edit { prefs ->
            prefs[UPDATE_CHECK_INTERVAL_HOURS] = hours
        }
    }

    suspend fun saveUpdatePostpone(version: String, until: Long) {
        dataStore.edit { prefs ->
            prefs[UPDATE_POSTPONE_VERSION] = version
            prefs[UPDATE_POSTPONE_UNTIL] = until
        }
    }

    suspend fun saveUpdateDialogShown(version: String, shownAt: Long) {
        dataStore.edit { prefs ->
            prefs[UPDATE_DIALOG_LAST_SHOWN_VERSION] = version
            prefs[UPDATE_DIALOG_LAST_SHOWN_AT] = shownAt
        }
    }

    suspend fun saveUpdateDialogAction(version: String, action: String, actedAt: Long) {
        dataStore.edit { prefs ->
            prefs[UPDATE_DIALOG_LAST_ACTION_VERSION] = version
            prefs[UPDATE_DIALOG_LAST_ACTION] = action
            prefs[UPDATE_DIALOG_LAST_ACTION_AT] = actedAt
        }
    }

    suspend fun saveActiveProfile(profile: Int) {
        dataStore.edit { prefs ->
            prefs[ACTIVE_PROFILE] = profile.coerceIn(
                CsqttConstants.Profiles.MIN_INDEX,
                CsqttConstants.Profiles.MAX_INDEX,
            )
        }
    }

    suspend fun saveShowSystemApps(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[SHOW_SYSTEM_APPS] = enabled
        }
    }

    suspend fun saveInfoActionsExpanded(expanded: Boolean) {
        dataStore.edit { prefs ->
            prefs[INFO_ACTIONS_EXPANDED] = expanded
        }
    }

    suspend fun saveInfoProjectExpanded(expanded: Boolean) {
        dataStore.edit { prefs ->
            prefs[INFO_PROJECT_EXPANDED] = expanded
        }
    }

    suspend fun saveCsqttLink(link: String) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            writeLinkKeys(prefs, profile, link)
        }
    }

    suspend fun saveCsqttLinkMode(enabled: Boolean) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(CSQTT_LINK_MODE, profile)] = enabled
        }
    }

    suspend fun saveCsqttParticipantLink(link: String) {
        dataStore.edit { prefs -> importParticipantLinkKeys(prefs, link, intoNew = false, activate = true) }
    }

    internal suspend fun linkImportState(tunnelRunning: Boolean): LinkImportState =
        linkImportState(dataStore.data.first(), tunnelRunning)

    /** M-70: the profile [link] was written to and its name, or null when no profile was free. */
    internal suspend fun importParticipantLink(link: String, intoNew: Boolean, activate: Boolean): Pair<Int, String>? {
        var written: Pair<Int, String>? = null
        dataStore.edit { prefs ->
            importParticipantLinkKeys(prefs, link, intoNew, activate)?.let { written = it to profileDisplayName(prefs, it) }
        }
        return written
    }

    suspend fun saveShowLogsTab(show: Boolean) {
        dataStore.edit { it[SHOW_LOGS_TAB] = show }
    }

    suspend fun saveShowDeployTab(show: Boolean) {
        dataStore.edit { it[SHOW_DEPLOY_TAB] = show }
    }

    /**
     * The Туннель tab's text actions: the active profile waits for a server ([linkMode] false) or a link, only while it
     * is still empty (checked in the same edit). False when nothing changed.
     */
    suspend fun switchEmptyProfileMode(linkMode: Boolean): Boolean {
        var switched = false
        dataStore.edit { prefs -> switched = switchEmptyProfileModeKeys(prefs, prefs[ACTIVE_PROFILE] ?: 0, linkMode) }
        return switched
    }

    suspend fun saveWorkersPerHash(workersPerHash: Int) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(WORKERS_PER_HASH, profile)] = WorkerCountPolicy.normalize(workersPerHash, maximum = CsqttConstants.Tunnel.MAX_WORKERS)
        }
    }

    suspend fun saveParticipantTunnelSettings(vkHashes: String, workersPerHash: Int) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(VK_HASHES, profile)] = vkHashes
            prefs[getProfileKey(WORKERS_PER_HASH, profile)] =
                WorkerCountPolicy.normalize(workersPerHash, maximum = CsqttConstants.Tunnel.MAX_WORKERS)
        }
    }

    suspend fun save(
        peer: String,
        vkHashes: String,
        secondaryVkHash: String,
        workersPerHash: Int,
        protocol: String,
        listenPort: Int,
        sni: String = "",
        noDns: Boolean = false
    ) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(PEER, profile)] = peer
            prefs[getProfileKey(VK_HASHES, profile)] = vkHashes
            prefs[getProfileKey(SECONDARY_VK_HASH, profile)] = secondaryVkHash
            prefs[getProfileKey(WORKERS_PER_HASH, profile)] = WorkerCountPolicy.normalize(workersPerHash, maximum = CsqttConstants.Tunnel.MAX_WORKERS)
            prefs[getProfileKey(PROTOCOL, profile)] = protocol
            prefs[getProfileKey(LISTEN_PORT, profile)] = listenPort
            prefs[getProfileKey(SNI, profile)] = sni
            prefs[getProfileKey(NO_DNS, profile)] = noDns
        }
    }

    /** A deploy-form write: into the active profile's VPS record, created on the first write (B5b2). */
    private suspend fun editVps(write: MutablePreferences.(vps: Int) -> Unit) {
        dataStore.edit { prefs -> vpsForWrite(prefs, prefs[ACTIVE_PROFILE] ?: 0)?.let { prefs.write(it) } }
    }

    /**
     * The deploy form's «Ручные порты». Off, a deploy uses 46000/46002; the profile's stored ports are what the tunnel
     * dials, so they change only when a deploy succeeds (saveDeployedServer), never on the switch (M-85).
     */
    suspend fun saveManualPortsEnabled(enabled: Boolean) = editVps { vps ->
        this[getProfileKey(MANUAL_PORTS_ENABLED, vps)] = enabled
    }

    suspend fun saveVkHashesOnly(vkHashes: String) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(VK_HASHES, profile)] = vkHashes
        }
    }

    suspend fun saveSshKeysMode(enabled: Boolean) = editVps { vps ->
        this[getProfileKey(SSH_KEYS_MODE, vps)] = enabled
    }

    /**
     * After a successful deploy the profile that started it points at the server it installed or updated (its ports
     * whatever they are, the synced [password]), only while it still links [vps] (applyDeployedServer). False when it no
     * longer does: nothing was written.
     */
    suspend fun saveDeployedServer(profile: Int, vps: Int, host: String, peerPort: Int, webPort: Int, password: String?): Boolean {
        var written = false
        dataStore.edit { prefs -> written = applyDeployedServer(prefs, profile, vps, host, peerPort, webPort, password) }
        return written
    }

    /** The active profile's VPS record, created now if it has none: a deploy captures it before it runs. */
    internal suspend fun activeVpsForDeploy(): Int? {
        var vps: Int? = null
        editVps { vps = it }
        return vps
    }

    suspend fun saveDockerInstall(enabled: Boolean) = editVps { vps ->
        this[getProfileKey(DOCKER_INSTALL, vps)] = enabled
    }

    suspend fun regenerateDeploySecretPathUuid() = editVps { vps ->
        this[getProfileKey(DEPLOY_SECRET_PATH_UUID, vps)] = UUID.randomUUID().toString()
    }

    /** A record without a panel secret path gets one; opening the tab of a profile without a record writes nothing. */
    suspend fun ensureDeploySecretPathUuid() {
        dataStore.edit { prefs ->
            val vps = linkedVps(prefs, prefs[ACTIVE_PROFILE] ?: 0) ?: return@edit
            val key = getProfileKey(DEPLOY_SECRET_PATH_UUID, vps)
            if (prefs[key].isNullOrBlank()) prefs[key] = UUID.randomUUID().toString()
        }
    }

    suspend fun saveSshKeys(privateKey: String, keyPassphrase: String, certificate: String) = editVps { vps ->
        putSecret(SSH_PRIVATE_KEY_ENCRYPTED, SSH_PRIVATE_KEY, privateKey, vps)
        putSecret(SSH_KEY_PASSPHRASE_ENCRYPTED, SSH_KEY_PASSPHRASE, keyPassphrase, vps)
        putSecret(SSH_CERTIFICATE_ENCRYPTED, SSH_CERTIFICATE, certificate, vps)
    }

    /** The form's ports with «Ручные порты» on: drafts on the VPS record; the profile's ports change only on success. */
    suspend fun savePorts(peer: Int, web: Int, sshPortStr: String) {
        dataStore.edit { prefs -> putDeployDraftPorts(prefs, prefs[ACTIVE_PROFILE] ?: 0, peer, web, sshPortStr) }
    }

    /** The deploy form's SSH port alone, while «Ручные порты» is off (the profile's own ports stay). */
    suspend fun saveDeploySshPort(sshPortStr: String) = editVps { vps ->
        this[getProfileKey(DEPLOY_SSH_PORT, vps)] = sshPortStr
    }

    suspend fun saveServerPeerPort(peer: Int) {
        if (peer !in 1..65535) return
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(SERVER_PEER_PORT, profile)] = peer
        }
    }

    suspend fun saveDeploy(ip: String) = editVps { vps ->
        val previous = this[getProfileKey(DEPLOY_IP, vps)]
        this[getProfileKey(DEPLOY_IP, vps)] = ip
        if (previous != ip) {
            this[getProfileKey(DEPLOY_SERVER_CONFIGURED, vps)] = false
            for (key in listOf(DEPLOY_LAST_INSPECTION_AT, DEPLOY_LAST_INSPECTION_OK, DEPLOY_LAST_INSPECTION_ARCH,
                DEPLOY_LAST_INSPECTION_SERVICE, DEPLOY_LAST_INSPECTION_VERSION, DEPLOY_LAST_INSPECTION_MESSAGE,
                DEPLOY_LAST_INSPECTION_PANEL)) {
                remove(getProfileKey(key, vps))
            }
        }
    }

    suspend fun saveExcludedApps(packages: String) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(EXCLUDED_APPS, profile)] = packages
            prefs[SPLIT_TUNNEL_WHITELIST_MIGRATED] = true
        }
    }

    suspend fun saveWhitelistApps(packages: String) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(WHITELIST_APPS, profile)] = packages
            prefs[SPLIT_TUNNEL_WHITELIST_MIGRATED] = true
        }
    }

    suspend fun saveAppRoutingLists(blacklist: String, whitelist: String, isWhitelist: Boolean? = null) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(EXCLUDED_APPS, profile)] = blacklist
            prefs[getProfileKey(WHITELIST_APPS, profile)] = whitelist
            if (isWhitelist != null) prefs[getProfileKey(IS_WHITELIST, profile)] = isWhitelist
            prefs[SPLIT_TUNNEL_WHITELIST_MIGRATED] = true
        }
    }

    // [forProfile]: a deploy writes into the profile that started it, as saveDeployedServer does — the active one may
    // have changed during the minutes it ran
    suspend fun saveConnectionPassword(password: String, forProfile: Int? = null) {
        // one rule for every field (M-99): a password GETCONF cannot carry is never stored
        if (password.isNotBlank() && !isValidTunnelPassword(password)) return
        dataStore.edit { prefs ->
            val profile = forProfile ?: prefs[ACTIVE_PROFILE] ?: 0
            val localKey = getProfileKey(CONNECTION_PASSWORD_LOCAL, profile)
            val legacyKey = getProfileKey(CONNECTION_PASSWORD, profile)
            val encryptedKey = getProfileKey(CONNECTION_PASSWORD_ENCRYPTED, profile)
            if (password.isBlank()) {
                prefs.remove(localKey)
            } else {
                prefs[localKey] = password
            }
            prefs.remove(legacyKey)
            prefs.remove(encryptedKey)
        }
    }

    suspend fun resetUnreadableConnectionPassword(): Boolean {
        var reset = false
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            val localKey = getProfileKey(CONNECTION_PASSWORD_LOCAL, profile)
            val legacyKey = getProfileKey(CONNECTION_PASSWORD, profile)
            val encryptedKey = getProfileKey(CONNECTION_PASSWORD_ENCRYPTED, profile)
            if (
                shouldResetUnreadableTunnelPassword(
                    prefs[localKey],
                    prefs[legacyKey],
                    prefs[encryptedKey],
                    secureStore::decrypt,
                )
            ) {
                prefs.remove(localKey)
                prefs.remove(legacyKey)
                prefs.remove(encryptedKey)
                reset = true
            }
        }
        return reset
    }

    suspend fun saveDeploySecrets(mainPass: String, sshLogin: String, sshPass: String, webLogin: String, webPass: String) =
        editVps { vps ->
            putSecret(DEPLOY_MAIN_PASSWORD_ENCRYPTED, DEPLOY_MAIN_PASSWORD, mainPass, vps)
            this[getProfileKey(DEPLOY_LOGIN, vps)] = sshLogin
            putSecret(DEPLOY_PASSWORD_ENCRYPTED, DEPLOY_PASSWORD, sshPass, vps)
            this[getProfileKey(DEPLOY_WEB_LOGIN, vps)] = webLogin
            putSecret(DEPLOY_WEB_PASSWORD_ENCRYPTED, DEPLOY_WEB_PASSWORD, webPass, vps)
        }

    suspend fun saveVkAuthMode(mode: String) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            val normalized = when (mode) {
                CsqttConstants.VkAuth.MODE_CAPTCHA -> CsqttConstants.VkAuth.MODE_CAPTCHA
                CsqttConstants.VkAuth.MODE_AUTO_JS -> CsqttConstants.VkAuth.MODE_AUTO_JS
                else -> CsqttConstants.VkAuth.MODE_CALLS
            }
            prefs[getProfileKey(VK_AUTH_MODE, profile)] = normalized
            prefs[getProfileKey(VK_HASH_MODE, profile)] = vkHashModeForAuthMode(
                requestedAuthMode = normalized,
                currentHashMode = prefs[getProfileKey(VK_HASH_MODE, profile)],
                hasVkToken = readSecret(prefs, VK_ACCESS_TOKEN_ENCRYPTED, VK_ACCESS_TOKEN, profile).isNotBlank(),
            )
        }
    }

    suspend fun saveObfsMode(mode: String) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(OBFS_MODE, profile)] = mode
        }
    }

    /** A value outside [VpnMtu.RANGE] is not stored. */
    suspend fun saveVpnMtu(mtu: Int) {
        if (mtu !in VpnMtu.RANGE) return
        dataStore.edit { prefs ->
            prefs[getProfileKey(VPN_MTU, selectedProfile(prefs))] = mtu
        }
    }

    suspend fun saveDpiSplit(mode: DpiSplitMode) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(DPI_SPLIT, profile)] = mode.storedValue
        }
    }

    suspend fun saveTurnTransport(transport: String) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(TURN_TRANSPORT, profile)] = when (transport) {
                CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS -> CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS
                else -> CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT
            }
        }
    }

    suspend fun saveSrtpMode(mode: String) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(SRTP_MODE, profile)] = when (mode) {
                CsqttConstants.SrtpMode.WRAP -> CsqttConstants.SrtpMode.WRAP
                CsqttConstants.SrtpMode.DTLS -> CsqttConstants.SrtpMode.DTLS
                else -> CsqttConstants.SrtpMode.DEFAULT
            }
        }
    }

    suspend fun saveSelectedTurnSubnet(subnetId: String, ipLimit: String? = null) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            val limit = ipLimit ?: prefs[getProfileKey(TURN_SUBNET_IP_LIMIT, profile)] ?: "ALL"
            val allIps = if (subnetId.isNotBlank()) TurnSubnetRegistry.getIpsForSubnet(subnetId) else emptyList()
            val limitCount = limit.toIntOrNull()
            val ips = if (allIps.isEmpty()) {
                ""
            } else if (limitCount != null && limitCount > 0) {
                allIps.take(limitCount).joinToString(",")
            } else {
                allIps.joinToString(",")
            }
            prefs[getProfileKey(SELECTED_TURN_SUBNET, profile)] = subnetId
            prefs[getProfileKey(CUSTOM_TURN_IPS, profile)] = ips
            if (ipLimit != null) {
                prefs[getProfileKey(TURN_SUBNET_IP_LIMIT, profile)] = ipLimit
            }
        }
    }

    suspend fun saveTurnSubnetIpLimit(limit: String) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            val subnetId = prefs[getProfileKey(SELECTED_TURN_SUBNET, profile)] ?: ""
            val allIps = if (subnetId.isNotBlank()) TurnSubnetRegistry.getIpsForSubnet(subnetId) else emptyList()
            val limitCount = limit.toIntOrNull()
            val ips = if (allIps.isEmpty()) {
                ""
            } else if (limitCount != null && limitCount > 0) {
                allIps.take(limitCount).joinToString(",")
            } else {
                allIps.joinToString(",")
            }
            prefs[getProfileKey(TURN_SUBNET_IP_LIMIT, profile)] = limit
            prefs[getProfileKey(CUSTOM_TURN_IPS, profile)] = ips
        }
    }

    suspend fun saveExtraWorkers(enabled: Boolean) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(EXTRA_WORKERS, profile)] = enabled
            if (!enabled) {
                val current = prefs[getProfileKey(WORKERS_PER_HASH, profile)] ?: CsqttConstants.Tunnel.DEFAULT_WORKERS
                val maximum = WorkerCountPolicy.defaultMaximum(extraWorkers = false)
                if (current > maximum) {
                    prefs[getProfileKey(WORKERS_PER_HASH, profile)] =
                        WorkerCountPolicy.normalize(current, maximum = maximum)
                }
            }
        }
    }

    suspend fun saveAutoJsRiskAcknowledged(acknowledged: Boolean) {
        dataStore.edit { prefs ->
            prefs[AUTO_JS_RISK_ACKNOWLEDGED] = acknowledged
        }
    }

    suspend fun saveTcpTransportRiskAcknowledged(acknowledged: Boolean) {
        dataStore.edit { prefs ->
            prefs[TCP_TRANSPORT_RISK_ACKNOWLEDGED] = acknowledged
        }
    }

    suspend fun saveAutoPauseOnWifi(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[AUTO_PAUSE_ON_WIFI] = enabled
        }
    }

    internal suspend fun saveWifiRule(rule: WifiRule) {
        dataStore.edit { prefs -> prefs[WIFI_RULE] = rule.stored }
    }

    /** False when the rule or the switch changed since the offer was made (a stale notice). */
    internal suspend fun rememberWifiNetwork(name: String, rule: WifiRule): Boolean {
        var remembered = false
        dataStore.edit { prefs -> remembered = rememberWifi(prefs, name, rule) }
        return remembered
    }

    internal suspend fun checkWifiNetwork(name: String, checked: Boolean) {
        dataStore.edit { prefs -> checkWifi(prefs, name, checked) }
    }

    internal suspend fun forgetWifiNetwork(name: String) {
        dataStore.edit { prefs -> forgetWifi(prefs, name) }
    }

    internal suspend fun dismissWifiNetwork(name: String) {
        dataStore.edit { prefs -> dismissWifi(prefs, name) }
    }

    suspend fun saveBatterySaverMode(mode: String) {
        dataStore.edit { prefs -> prefs[BATTERY_SAVER_MODE] = mode }
    }

    suspend fun saveBatterySaverPercent(percent: Int) {
        dataStore.edit { prefs -> prefs[BATTERY_SAVER_PERCENT] = percent.coerceIn(5, 95) }
    }

    suspend fun isPermissionIntroShown(): Boolean = dataStore.data.first()[PERMISSION_INTRO_SHOWN] ?: false

    suspend fun markPermissionIntroShown() {
        dataStore.edit { prefs -> prefs[PERMISSION_INTRO_SHOWN] = true }
    }

    suspend fun markBatteryOptimizationPromptHandled() {
        dataStore.edit { prefs ->
            prefs[BATTERY_OPTIMIZATION_PROMPT_HANDLED] = true
        }
    }

    suspend fun saveCaptchaMode(mode: String) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(CAPTCHA_MODE, profile)] = mode
        }
    }

    suspend fun saveCaptchaSolveMethod(method: String) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(CAPTCHA_SOLVE_METHOD, profile)] = method
        }
    }

    suspend fun saveWbvCaptchaSolveMethod(method: String) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(CAPTCHA_WBV_SOLVE_METHOD, profile)] = method
            if (prefs[getProfileKey(CAPTCHA_MODE, profile)] == "wv") {
                prefs[getProfileKey(CAPTCHA_SOLVE_METHOD, profile)] = method
            }
        }
    }

    suspend fun saveVkHashMode(mode: String) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            val currentAuthMode = prefs[getProfileKey(VK_AUTH_MODE, profile)]
            val nextAuthMode = vkAuthModeForHashMode(mode, currentAuthMode)
            val selection = normalizeVkModeSelection(
                requestedAuthMode = nextAuthMode,
                requestedHashMode = mode,
                hasVkToken = readSecret(prefs, VK_ACCESS_TOKEN_ENCRYPTED, VK_ACCESS_TOKEN, profile).isNotBlank(),
            )
            prefs[getProfileKey(VK_AUTH_MODE, profile)] = selection.authMode
            prefs[getProfileKey(VK_HASH_MODE, profile)] = selection.hashMode
        }
    }

    /** A sign-in's token for the active profile; archived by its user id (M-79) with its expiry (0 = permanent). */
    suspend fun saveVkAccessToken(token: String, userId: String, expiresInSeconds: Long = 0L) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs.putSecret(VK_ACCESS_TOKEN_ENCRYPTED, VK_ACCESS_TOKEN, token, profile)
            prefs[getProfileKey(VK_ACCESS_TOKEN_USER_ID, profile)] = userId
            val expiresAt = if (expiresInSeconds > 0) System.currentTimeMillis() + expiresInSeconds * 1000 else 0L
            prefs[VK_ACCOUNTS] = VkAccounts.put(prefs[VK_ACCOUNTS].orEmpty(), userId, secureStore.encrypt(token), expiresAt)
        }
    }

    /** «Удалить токен», or a token VK refused: the active profile's token goes, and its account leaves the archive. */
    suspend fun clearVkAccessToken() {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(VK_ACCESS_TOKEN_USER_ID, profile)]?.let { userId ->
                prefs[VK_ACCOUNTS] = VkAccounts.remove(prefs[VK_ACCOUNTS].orEmpty(), userId)
            }
            prefs.remove(getProfileKey(VK_ACCESS_TOKEN_ENCRYPTED, profile))
            prefs.remove(getProfileKey(VK_ACCESS_TOKEN, profile))
            prefs.remove(getProfileKey(VK_ACCESS_TOKEN_USER_ID, profile))
        }
    }

    suspend fun saveIsWhitelist(enabled: Boolean) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(IS_WHITELIST, profile)] = enabled
            prefs[SPLIT_TUNNEL_WHITELIST_MIGRATED] = true
        }
    }

    suspend fun saveExceptionsMode(packages: String, isWhitelist: Boolean) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            if (isWhitelist) {
                prefs[getProfileKey(WHITELIST_APPS, profile)] = packages
            } else {
                prefs[getProfileKey(EXCLUDED_APPS, profile)] = packages
            }
            prefs[getProfileKey(IS_WHITELIST, profile)] = isWhitelist
            prefs[SPLIT_TUNNEL_WHITELIST_MIGRATED] = true
        }
    }

    suspend fun saveDomainRoutingEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(DOMAIN_ROUTING_ENABLED, profile)] = enabled
        }
    }

    /** The domains dialog's save, in one write: the live push never sees the mode without its list (M-57). */
    suspend fun saveDomainRules(isWhitelist: Boolean, black: String, white: String) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(DOMAIN_IS_WHITELIST, profile)] = isWhitelist
            prefs[getProfileKey(DIRECT_DOMAINS_BLACK, profile)] = black
            prefs[getProfileKey(DIRECT_DOMAINS_WHITE, profile)] = white
        }
    }

    /** The IP dialog's save, in one write. */
    suspend fun saveIpRules(isWhitelist: Boolean, black: String, white: String) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(IP_IS_WHITELIST, profile)] = isWhitelist
            prefs[getProfileKey(IP_ROUTING_ENABLED, profile)] = true
            prefs[getProfileKey(DIRECT_IPS_BLACK, profile)] = black
            prefs[getProfileKey(DIRECT_IPS_WHITE, profile)] = white
        }
    }

    /** M-63: a validated rules import, every selected section in one write. */
    internal suspend fun applyRoutingImport(plan: RoutingImportPlan) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            plan.apps?.let { apps ->
                apps.black?.let { prefs[getProfileKey(EXCLUDED_APPS, profile)] = it.sorted().joinToString(",") }
                apps.white?.let { prefs[getProfileKey(WHITELIST_APPS, profile)] = it.sorted().joinToString(",") }
                apps.isWhitelist?.let { prefs[getProfileKey(IS_WHITELIST, profile)] = it }
                prefs[SPLIT_TUNNEL_WHITELIST_MIGRATED] = true
            }
            plan.domains?.let { domains ->
                domains.isWhitelist?.let { prefs[getProfileKey(DOMAIN_IS_WHITELIST, profile)] = it }
                domains.templateDomains?.let { prefs[getProfileKey(TEMPLATE_DOMAINS_ENABLED, profile)] = it }
                domains.black?.let { prefs[getProfileKey(DIRECT_DOMAINS_BLACK, profile)] = it.joinToString(",") }
                domains.white?.let { prefs[getProfileKey(DIRECT_DOMAINS_WHITE, profile)] = it.joinToString(",") }
            }
            plan.ips?.let { ips ->
                ips.isWhitelist?.let { prefs[getProfileKey(IP_IS_WHITELIST, profile)] = it }
                prefs[getProfileKey(IP_ROUTING_ENABLED, profile)] = true
                ips.black?.let { prefs[getProfileKey(DIRECT_IPS_BLACK, profile)] = it.joinToString(",") }
                ips.white?.let { prefs[getProfileKey(DIRECT_IPS_WHITE, profile)] = it.joinToString(",") }
            }
        }
    }

    suspend fun saveExcludeRuZonesEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(EXCLUDE_RU_ZONES_ENABLED, profile)] = enabled
        }
    }

    suspend fun saveTemplateDomainsEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(TEMPLATE_DOMAINS_ENABLED, profile)] = enabled
        }
    }

    suspend fun saveDomainIsWhitelist(isWhitelist: Boolean) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(DOMAIN_IS_WHITELIST, profile)] = isWhitelist
        }
    }

    suspend fun saveDirectDomains(domains: String) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(DIRECT_DOMAINS, profile)] = domains
            prefs[getProfileKey(DIRECT_DOMAINS_BLACK, profile)] = domains
        }
    }

    suspend fun saveDirectDomainsBlack(domains: String) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(DIRECT_DOMAINS_BLACK, profile)] = domains
            prefs[getProfileKey(DIRECT_DOMAINS, profile)] = domains
        }
    }

    suspend fun saveDirectDomainsWhite(domains: String) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(DIRECT_DOMAINS_WHITE, profile)] = domains
        }
    }

    suspend fun saveIpRoutingEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(IP_ROUTING_ENABLED, profile)] = enabled
        }
    }

    suspend fun saveIpIsWhitelist(isWhitelist: Boolean) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(IP_IS_WHITELIST, profile)] = isWhitelist
        }
    }

    suspend fun saveDirectIpsBlack(ips: String) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(DIRECT_IPS_BLACK, profile)] = ips
        }
    }

    suspend fun saveDirectIpsWhite(ips: String) {
        dataStore.edit { prefs ->
            val profile = selectedProfile(prefs)
            prefs[getProfileKey(DIRECT_IPS_WHITE, profile)] = ips
        }
    }

    private suspend fun migrateSubscriptionMeta() {
        dataStore.edit { prefs ->
            val meta = decodeSubscriptionMeta(prefs[SUBSCRIPTION_META]).toMutableMap()
            var changed = false
            for (profile in CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX) {
                val url = prefs[getProfileKey(SUBSCRIPTION_URL, profile)]?.trim().orEmpty()
                if (url.isEmpty() || meta.containsKey(url)) continue
                meta[url] = SubscriptionMeta(
                    url = url,
                    title = prefs[getProfileKey(SUBSCRIPTION_TITLE, profile)]?.trim().orEmpty(),
                )
                changed = true
            }
            if (changed) {
                prefs[SUBSCRIPTION_META] = encodeSubscriptionMeta(meta)
            }
        }
    }

    /**
     * Server names used to be stored in the shared connection profile name, which made
     * deploy-only profiles show up as named owner profiles. Move that name to its own
     * deploy key and clear the connection profile name for server-only slots.
     */
    private suspend fun migrateDeployServerNames() {
        val current = dataStore.data.first()
        if (current[DEPLOY_PROFILE_NAMES_CLEANED] == true) return
        dataStore.edit { prefs ->
            for (profile in CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX) {
                val ip = prefs[getProfileKey(DEPLOY_IP, profile)]?.trim().orEmpty()
                if (ip.isEmpty()) continue
                val profileName = prefs[getProfileKey(PROFILE_NAME, profile)]?.trim().orEmpty()
                if (profileName.isNotEmpty() &&
                    prefs[getProfileKey(DEPLOY_SERVER_NAME, profile)].isNullOrBlank()
                ) {
                    prefs[getProfileKey(DEPLOY_SERVER_NAME, profile)] = profileName
                }
                val hasConnection = !prefs[getProfileKey(PEER, profile)].isNullOrBlank() ||
                    !prefs[getProfileKey(CSQTT_LINK, profile)].isNullOrBlank()
                if (!hasConnection) {
                    prefs.remove(getProfileKey(PROFILE_NAME, profile))
                }
            }
            prefs[DEPLOY_PROFILE_NAMES_CLEANED] = true
        }
    }

    private suspend fun migrateSecretsToKeystore() {
        dataStore.edit { prefs ->
            for (profile in CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX) {
                prefs.migrateSecret(getProfileKey(DEPLOY_PASSWORD_ENCRYPTED, profile), getProfileKey(DEPLOY_PASSWORD, profile))
                prefs.migrateSecret(getProfileKey(DEPLOY_MAIN_PASSWORD_ENCRYPTED, profile), getProfileKey(DEPLOY_MAIN_PASSWORD, profile))
                prefs.migrateSecret(getProfileKey(DEPLOY_WEB_PASSWORD_ENCRYPTED, profile), getProfileKey(DEPLOY_WEB_PASSWORD, profile))
            }
        }
    }

    private suspend fun migrateConnectionPasswordsToLocalStorage() {
        dataStore.edit { prefs ->
            for (profile in CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX) {
                val localKey = getProfileKey(CONNECTION_PASSWORD_LOCAL, profile)
                val legacyKey = getProfileKey(CONNECTION_PASSWORD, profile)
                val encryptedKey = getProfileKey(CONNECTION_PASSWORD_ENCRYPTED, profile)
                val password = resolveTunnelPasswordStorage(
                    prefs[localKey],
                    prefs[legacyKey],
                    prefs[encryptedKey],
                    secureStore::decrypt,
                )
                if (password.state == StoredSecretState.Readable) {
                    if (password.value.isBlank()) {
                        prefs.remove(localKey)
                    } else {
                        prefs[localKey] = password.value
                    }
                    prefs.remove(legacyKey)
                    prefs.remove(encryptedKey)
                }
            }
        }
    }

    suspend fun migrateLegacyWhitelistMode() {
        val currentPrefs = dataStore.data.first()
        if (currentPrefs[SPLIT_TUNNEL_WHITELIST_MIGRATED] == true) return

        val hasLegacyWhitelist =
            (CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX).any { profile ->
            currentPrefs[getProfileKey(IS_WHITELIST, profile)] == true
        }
        val installedApps = if (hasLegacyWhitelist) installedSplitTunnelPackages() else emptySet()
        dataStore.edit { prefs ->
            if (prefs[SPLIT_TUNNEL_WHITELIST_MIGRATED] == true) return@edit

            for (profile in CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX) {
                val whitelistKey = getProfileKey(IS_WHITELIST, profile)
                val appsKey = getProfileKey(EXCLUDED_APPS, profile)
                val whitelistAppsKey = getProfileKey(WHITELIST_APPS, profile)
                if (prefs[whitelistAppsKey] == null && prefs[whitelistKey] == true) {
                    val existing = prefs[appsKey]
                    if (!existing.isNullOrEmpty()) {
                        prefs[whitelistAppsKey] = existing
                    }
                }
                if (prefs[whitelistKey] == true) {
                    val legacyExcluded = prefs[appsKey]
                        ?.split(",")
                        ?.filter { it.isNotEmpty() }
                        ?.toSet()
                        ?: emptySet()
                    prefs[appsKey] = (installedApps - legacyExcluded).sorted().joinToString(",")
                }
            }

            prefs[SPLIT_TUNNEL_WHITELIST_MIGRATED] = true
        }
    }

    private fun installedSplitTunnelPackages(): Set<String> {
        return runCatching {
            appContext.packageManager
                .getInstalledApplications(PackageManager.GET_META_DATA)
                .map { it.packageName }
                .filter {
                    it != appContext.packageName &&
                        !it.contains("vkontakte") &&
                        !it.contains("vk.calls")
                }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private fun readSecret(
        prefs: Preferences,
        encryptedKey: Preferences.Key<String>,
        legacyKey: Preferences.Key<String>,
        profile: Int
    ): String = readSecretState(prefs, encryptedKey, legacyKey, profile).value

    private fun readSecretState(
        prefs: Preferences,
        encryptedKey: Preferences.Key<String>,
        legacyKey: Preferences.Key<String>,
        profile: Int
    ): StoredSecret {
        val profEncryptedKey = getProfileKey(encryptedKey, profile)
        val profLegacyKey = getProfileKey(legacyKey, profile)
        return resolveStoredSecret(
            prefs[profEncryptedKey],
            prefs[profLegacyKey],
            secureStore::decrypt,
        )
    }

    private fun readConnectionPasswordState(
        prefs: Preferences,
        profile: Int,
    ): StoredSecret = resolveTunnelPasswordStorage(
        prefs[getProfileKey(CONNECTION_PASSWORD_LOCAL, profile)],
        prefs[getProfileKey(CONNECTION_PASSWORD, profile)],
        prefs[getProfileKey(CONNECTION_PASSWORD_ENCRYPTED, profile)],
        secureStore::decrypt,
    )

    private fun MutablePreferences.putSecret(
        encryptedKey: Preferences.Key<String>,
        legacyKey: Preferences.Key<String>,
        value: String,
        profile: Int
    ) = putSecretValue(
        getProfileKey(encryptedKey, profile),
        getProfileKey(legacyKey, profile),
        value,
        secureStore::encrypt,
        secureStore::decrypt,
    )

    private fun MutablePreferences.migrateSecret(
        encryptedKey: Preferences.Key<String>,
        legacyKey: Preferences.Key<String>
    ) {
        val legacyValue = this[legacyKey]
        val encryptedValue = this[encryptedKey]
        if (!encryptedValue.isNullOrBlank()) {
            remove(legacyKey)
            return
        }
        if (!legacyValue.isNullOrBlank()) {
            runCatching {
                this[encryptedKey] = secureStore.encrypt(legacyValue)
                remove(legacyKey)
            }
        }
    }

    suspend fun exportConfigJson(
        includeTunnel: Boolean,
        includeDeploy: Boolean,
        includeExceptions: Boolean,
        includeSettings: Boolean
    ): String {
        val prefs = dataStore.data.first()
        // the ACTIVE profile's values (base keys are profile 0's), and secrets the way the store keeps them
        val p = prefs[ACTIVE_PROFILE] ?: 0
        // its VPS record's (B5b2); a profile without one exports the defaults, as an empty slot did
        val v = linkedVps(prefs, p) ?: -1
        val root = JSONObject()
        root.put("version", 1)
        root.put("exported_at", System.currentTimeMillis())

        if (includeTunnel) {
            val tunnel = JSONObject()
            tunnel.put("peer", prefs[getProfileKey(PEER, p)] ?: "")
            tunnel.put("listen_port", prefs[getProfileKey(LISTEN_PORT, p)] ?: CsqttConstants.Network.DEFAULT_LOCAL_PORT)
            tunnel.put("manual_ports_enabled", (if (v >= 0) prefs[getProfileKey(MANUAL_PORTS_ENABLED, v)] else null) ?: false)
            tunnel.put("server_peer_port", prefs[getProfileKey(SERVER_PEER_PORT, p)] ?: CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT)
            tunnel.put("connection_password", readConnectionPasswordState(prefs, p).value)
            tunnel.put("obfs_mode", prefs[getProfileKey(OBFS_MODE, p)] ?: CsqttConstants.Tunnel.DEFAULT_OBFS_MODE)
            tunnel.put("turn_transport", prefs[getProfileKey(TURN_TRANSPORT, p)] ?: CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT)
            tunnel.put("dpi_split", DpiSplitMode.fromStored(prefs[getProfileKey(DPI_SPLIT, p)]).storedValue)
            // a value the profile chose, or null: a default is the importing app's own (review F2)
            tunnel.put("vpn_mtu", prefs[getProfileKey(VPN_MTU, p)]?.takeIf { it in VpnMtu.RANGE } ?: JSONObject.NULL)
            tunnel.put("srtp_mode", prefs[getProfileKey(SRTP_MODE, p)] ?: CsqttConstants.SrtpMode.DEFAULT)
            tunnel.put("custom_turn_ips", prefs[getProfileKey(CUSTOM_TURN_IPS, p)] ?: "")
            tunnel.put("sni", prefs[getProfileKey(SNI, p)] ?: "")
            tunnel.put("no_dns", prefs[getProfileKey(NO_DNS, p)] ?: false)
            root.put("tunnel", tunnel)
        }

        if (includeDeploy) {
            val deploy = JSONObject()
            fun <T> record(key: Preferences.Key<T>): T? = if (v >= 0) prefs[getProfileKey(key, v)] else null
            deploy.put("deploy_ip", record(DEPLOY_IP) ?: "")
            deploy.put("deploy_login", record(DEPLOY_LOGIN) ?: "")
            deploy.put("deploy_ssh_port", record(DEPLOY_SSH_PORT) ?: "")
            deploy.put("server_web_port", prefs[getProfileKey(SERVER_WEB_PORT, p)] ?: CsqttConstants.Network.DEFAULT_SERVER_WEB_PORT)
            deploy.put("docker_install", record(DOCKER_INSTALL) ?: true)
            deploy.put("ssh_keys_mode", record(SSH_KEYS_MODE) ?: false)
            deploy.put("vk_hashes", prefs[getProfileKey(VK_HASHES, p)] ?: "")
            deploy.put("secondary_vk_hash", prefs[getProfileKey(SECONDARY_VK_HASH, p)] ?: "")
            deploy.put("vk_auth_mode", prefs[getProfileKey(VK_AUTH_MODE, p)] ?: CsqttConstants.VkAuth.MODE_CALLS)
            deploy.put("vk_hash_mode", prefs[getProfileKey(VK_HASH_MODE, p)] ?: CsqttConstants.VkAutoHash.MODE_MANUAL)
            // the VK access token is a VK account credential: it stays on this device (an export goes to the clipboard
            // and Downloads). Exports before 2.5.2 carried it blank in practice; the import below leaves a stored one alone.
            deploy.put("workers_per_hash", prefs[getProfileKey(WORKERS_PER_HASH, p)] ?: CsqttConstants.Tunnel.DEFAULT_WORKERS)
            deploy.put("extra_workers", prefs[getProfileKey(EXTRA_WORKERS, p)] ?: false)
            deploy.put("csqtt_link", prefs[getProfileKey(CSQTT_LINK, p)] ?: "")
            deploy.put("csqtt_link_mode", prefs[getProfileKey(CSQTT_LINK_MODE, p)] ?: false)
            deploy.put("secret_path_enabled", record(DEPLOY_SECRET_PATH_ENABLED) ?: false)
            deploy.put("secret_path_uuid", record(DEPLOY_SECRET_PATH_UUID) ?: "")
            root.put("deploy", deploy)
        }

        if (includeExceptions) {
            val exc = JSONObject()
            exc.put("excluded_apps", prefs[getProfileKey(EXCLUDED_APPS, p)] ?: "")
            exc.put("whitelist_apps", prefs[getProfileKey(WHITELIST_APPS, p)] ?: "")
            exc.put("is_whitelist", prefs[getProfileKey(IS_WHITELIST, p)] ?: false)
            exc.put("domain_routing_enabled", prefs[getProfileKey(DOMAIN_ROUTING_ENABLED, p)] ?: false)
            exc.put("domain_is_whitelist", prefs[getProfileKey(DOMAIN_IS_WHITELIST, p)] ?: false)
            exc.put("exclude_ru_zones_enabled", prefs[getProfileKey(EXCLUDE_RU_ZONES_ENABLED, p)] ?: false)
            exc.put("template_domains_enabled", prefs[getProfileKey(TEMPLATE_DOMAINS_ENABLED, p)] ?: false)
            exc.put("direct_domains_black", prefs[getProfileKey(DIRECT_DOMAINS_BLACK, p)] ?: prefs[getProfileKey(DIRECT_DOMAINS, p)] ?: "")
            exc.put("direct_domains_white", prefs[getProfileKey(DIRECT_DOMAINS_WHITE, p)] ?: "")
            exc.put("ip_routing_enabled", prefs[getProfileKey(IP_ROUTING_ENABLED, p)] ?: false)
            exc.put("ip_is_whitelist", prefs[getProfileKey(IP_IS_WHITELIST, p)] ?: false)
            exc.put("direct_ips_black", prefs[getProfileKey(DIRECT_IPS_BLACK, p)] ?: "")
            exc.put("direct_ips_white", prefs[getProfileKey(DIRECT_IPS_WHITE, p)] ?: "")
            exc.put("app_routing_all", prefs[getProfileKey(APP_ROUTING_ALL, p)] ?: false)
            exc.put("lan_bypass", prefs[getProfileKey(LAN_BYPASS, p)] ?: false)
            exc.put("dns_override", prefs[getProfileKey(DNS_OVERRIDE, p)] ?: "")
            root.put("exceptions", exc)
        }

        if (includeSettings) {
            val settings = JSONObject()
            settings.put("theme_mode", prefs[THEME_MODE] ?: "system")
            settings.put("is_dynamic_color", prefs[IS_DYNAMIC_COLOR] ?: false)
            settings.put("theme_palette", prefs[THEME_PALETTE] ?: "pure_black")
            settings.put("auto_pause_on_wifi", prefs[AUTO_PAUSE_ON_WIFI] ?: false)
            settings.put("battery_saver_mode", prefs[BATTERY_SAVER_MODE] ?: BatterySaver.DEFAULT_MODE)
            settings.put("battery_saver_percent", prefs[BATTERY_SAVER_PERCENT] ?: BatterySaver.DEFAULT_PERCENT)
            settings.put("selected_fingerprint", prefs[getProfileKey(SELECTED_FINGERPRINT, p)] ?: "chrome")
            settings.put("selected_turn_subnet", prefs[getProfileKey(SELECTED_TURN_SUBNET, p)] ?: "")
            settings.put("turn_subnet_ip_limit", prefs[getProfileKey(TURN_SUBNET_IP_LIMIT, p)] ?: "ALL")
            root.put("settings", settings)
        }

        return root.toString(2)
    }

    /** The full backup (M-42): every profile, VPS record and app setting; server secrets only by [serverSecrets]. */
    suspend fun exportBackupJson(serverSecrets: Boolean): String =
        StoreBackup.encode(dataStore.data.first(), serverSecrets, secureStore::decrypt, System.currentTimeMillis())

    /** Replaces every profile, VPS record and backed-up setting with the backup's; false if it is not a valid backup. */
    suspend fun restoreBackupJson(json: String): Boolean = runCatching {
        dataStore.edit { prefs -> StoreBackup.restore(prefs, json, secureStore::encrypt, secureStore::decrypt) }
    }.isSuccess

    suspend fun importConfigJson(
        jsonString: String,
        includeTunnel: Boolean,
        includeDeploy: Boolean,
        includeExceptions: Boolean,
        includeSettings: Boolean
    ): Boolean {
        return runCatching {
            val root = JSONObject(jsonString)
            dataStore.edit { mutable ->
                // into the ACTIVE profile (base keys are profile 0's), secrets the way the store keeps them
                val p = mutable[ACTIVE_PROFILE] ?: 0
                // deploy values go to the profile's VPS record (B5b2): a file naming a server creates one, others need one
                val deployIp = root.optJSONObject("deploy")?.optString("deploy_ip").orEmpty()
                val v = if (includeDeploy && deployIp.isNotBlank()) vpsForWrite(mutable, p) else linkedVps(mutable, p)
                if (includeTunnel && root.has("tunnel")) {
                    val tunnel = root.getJSONObject("tunnel")
                    if (tunnel.has("peer")) mutable[getProfileKey(PEER, p)] = tunnel.getString("peer")
                    if (tunnel.has("listen_port")) mutable[getProfileKey(LISTEN_PORT, p)] = tunnel.getInt("listen_port")
                    if (tunnel.has("manual_ports_enabled") && v != null) {
                        mutable[getProfileKey(MANUAL_PORTS_ENABLED, v)] = tunnel.getBoolean("manual_ports_enabled")
                    }
                    if (tunnel.has("server_peer_port")) mutable[getProfileKey(SERVER_PEER_PORT, p)] = tunnel.getInt("server_peer_port")
                    // a blank password in an export is "none in that export" (older exports carried a stored one blank):
                    // it never wipes the password this profile has; nor does one that breaks the password rule
                    val pw = if (tunnel.has("connection_password")) tunnel.getString("connection_password") else ""
                    if (pw.isNotBlank() && isValidTunnelPassword(pw)) {
                        // as saveConnectionPassword stores it
                        mutable[getProfileKey(CONNECTION_PASSWORD_LOCAL, p)] = pw
                        mutable.remove(getProfileKey(CONNECTION_PASSWORD, p))
                        mutable.remove(getProfileKey(CONNECTION_PASSWORD_ENCRYPTED, p))
                    }
                    if (tunnel.has("obfs_mode")) mutable[getProfileKey(OBFS_MODE, p)] = tunnel.getString("obfs_mode")
                    if (tunnel.has("turn_transport")) mutable[getProfileKey(TURN_TRANSPORT, p)] = tunnel.getString("turn_transport")
                    if (tunnel.has("dpi_split")) {
                        mutable[getProfileKey(DPI_SPLIT, p)] = DpiSplitMode.fromStored(tunnel.optString("dpi_split")).storedValue
                    }
                    // null: the exported profile ran at the default; a file without the key (before 2.6.0) says nothing
                    if (tunnel.has("vpn_mtu")) {
                        if (tunnel.isNull("vpn_mtu")) {
                            mutable.remove(getProfileKey(VPN_MTU, p))
                        } else {
                            tunnel.optInt("vpn_mtu", -1).takeIf { it in VpnMtu.RANGE }?.let { mutable[getProfileKey(VPN_MTU, p)] = it }
                        }
                    }
                    if (tunnel.has("srtp_mode")) mutable[getProfileKey(SRTP_MODE, p)] = tunnel.getString("srtp_mode")
                    if (tunnel.has("custom_turn_ips")) mutable[getProfileKey(CUSTOM_TURN_IPS, p)] = tunnel.getString("custom_turn_ips")
                    if (tunnel.has("sni")) mutable[getProfileKey(SNI, p)] = tunnel.getString("sni")
                    if (tunnel.has("no_dns")) mutable[getProfileKey(NO_DNS, p)] = tunnel.getBoolean("no_dns")
                }
                if (includeDeploy && root.has("deploy")) {
                    val deploy = root.getJSONObject("deploy")
                    if (v != null) {
                        if (deploy.has("deploy_ip")) mutable[getProfileKey(DEPLOY_IP, v)] = deploy.getString("deploy_ip")
                        if (deploy.has("deploy_login")) mutable[getProfileKey(DEPLOY_LOGIN, v)] = deploy.getString("deploy_login")
                        if (deploy.has("deploy_ssh_port")) mutable[getProfileKey(DEPLOY_SSH_PORT, v)] = deploy.getString("deploy_ssh_port")
                        if (deploy.has("docker_install")) mutable[getProfileKey(DOCKER_INSTALL, v)] = deploy.getBoolean("docker_install")
                        if (deploy.has("ssh_keys_mode")) mutable[getProfileKey(SSH_KEYS_MODE, v)] = deploy.getBoolean("ssh_keys_mode")
                        if (deploy.has("secret_path_enabled")) mutable[getProfileKey(DEPLOY_SECRET_PATH_ENABLED, v)] = deploy.getBoolean("secret_path_enabled")
                        if (deploy.has("secret_path_uuid")) mutable[getProfileKey(DEPLOY_SECRET_PATH_UUID, v)] = deploy.getString("secret_path_uuid")
                    }
                    if (deploy.has("server_web_port")) mutable[getProfileKey(SERVER_WEB_PORT, p)] = deploy.getInt("server_web_port")
                    if (deploy.has("vk_hashes")) mutable[getProfileKey(VK_HASHES, p)] = deploy.getString("vk_hashes")
                    if (deploy.has("secondary_vk_hash")) mutable[getProfileKey(SECONDARY_VK_HASH, p)] = deploy.getString("secondary_vk_hash")
                    if (deploy.has("vk_auth_mode")) mutable[getProfileKey(VK_AUTH_MODE, p)] = deploy.getString("vk_auth_mode")
                    if (deploy.has("vk_hash_mode")) mutable[getProfileKey(VK_HASH_MODE, p)] = deploy.getString("vk_hash_mode")
                    // blank (every export before 2.5.2) leaves the stored token alone; exports no longer carry it
                    deploy.optString("vk_access_token").takeIf { it.isNotBlank() }?.let {
                        mutable.putSecret(VK_ACCESS_TOKEN_ENCRYPTED, VK_ACCESS_TOKEN, it, p)
                    }
                    if (deploy.has("workers_per_hash")) mutable[getProfileKey(WORKERS_PER_HASH, p)] = deploy.getInt("workers_per_hash")
                    if (deploy.has("extra_workers")) mutable[getProfileKey(EXTRA_WORKERS, p)] = deploy.getBoolean("extra_workers")
                    if (deploy.has("csqtt_link")) writeLinkKeys(mutable, p, deploy.getString("csqtt_link"))
                    if (deploy.has("csqtt_link_mode")) mutable[getProfileKey(CSQTT_LINK_MODE, p)] = deploy.getBoolean("csqtt_link_mode")
                }
                if (includeExceptions && root.has("exceptions")) {
                    val exc = root.getJSONObject("exceptions")
                    if (exc.has("excluded_apps")) mutable[getProfileKey(EXCLUDED_APPS, p)] = exc.getString("excluded_apps")
                    if (exc.has("whitelist_apps")) mutable[getProfileKey(WHITELIST_APPS, p)] = exc.getString("whitelist_apps")
                    if (exc.has("is_whitelist")) mutable[getProfileKey(IS_WHITELIST, p)] = exc.getBoolean("is_whitelist")
                    if (exc.has("domain_routing_enabled")) mutable[getProfileKey(DOMAIN_ROUTING_ENABLED, p)] = exc.getBoolean("domain_routing_enabled")
                    if (exc.has("domain_is_whitelist")) mutable[getProfileKey(DOMAIN_IS_WHITELIST, p)] = exc.getBoolean("domain_is_whitelist")
                    if (exc.has("exclude_ru_zones_enabled")) mutable[getProfileKey(EXCLUDE_RU_ZONES_ENABLED, p)] = exc.getBoolean("exclude_ru_zones_enabled")
                    if (exc.has("template_domains_enabled")) mutable[getProfileKey(TEMPLATE_DOMAINS_ENABLED, p)] = exc.getBoolean("template_domains_enabled")
                    if (exc.has("direct_domains_black")) mutable[getProfileKey(DIRECT_DOMAINS_BLACK, p)] = exc.getString("direct_domains_black")
                    if (exc.has("direct_domains_white")) mutable[getProfileKey(DIRECT_DOMAINS_WHITE, p)] = exc.getString("direct_domains_white")
                    if (exc.has("ip_routing_enabled")) mutable[getProfileKey(IP_ROUTING_ENABLED, p)] = exc.getBoolean("ip_routing_enabled")
                    if (exc.has("ip_is_whitelist")) mutable[getProfileKey(IP_IS_WHITELIST, p)] = exc.getBoolean("ip_is_whitelist")
                    if (exc.has("direct_ips_black")) mutable[getProfileKey(DIRECT_IPS_BLACK, p)] = exc.getString("direct_ips_black")
                    if (exc.has("direct_ips_white")) mutable[getProfileKey(DIRECT_IPS_WHITE, p)] = exc.getString("direct_ips_white")
                    if (exc.has("app_routing_all")) mutable[getProfileKey(APP_ROUTING_ALL, p)] = exc.getBoolean("app_routing_all")
                    if (exc.has("lan_bypass")) mutable[getProfileKey(LAN_BYPASS, p)] = exc.getBoolean("lan_bypass")
                    if (exc.has("dns_override")) mutable[getProfileKey(DNS_OVERRIDE, p)] = exc.getString("dns_override")
                }
                if (includeSettings && root.has("settings")) {
                    val s = root.getJSONObject("settings")
                    if (s.has("theme_mode")) mutable[THEME_MODE] = s.getString("theme_mode")
                    if (s.has("is_dynamic_color")) mutable[IS_DYNAMIC_COLOR] = s.getBoolean("is_dynamic_color")
                    if (s.has("theme_palette")) mutable[THEME_PALETTE] = s.getString("theme_palette")
                    if (s.has("auto_pause_on_wifi")) mutable[AUTO_PAUSE_ON_WIFI] = s.getBoolean("auto_pause_on_wifi")
                    // an unknown mode (a newer or edited export) leaves the stored one: the UI could show it only as «Авто»
                    s.optString("battery_saver_mode").takeIf { mode -> BatterySaver.MODES.any { it.first == mode } }
                        ?.let { mutable[BATTERY_SAVER_MODE] = it }
                    if (s.has("battery_saver_percent")) mutable[BATTERY_SAVER_PERCENT] = s.getInt("battery_saver_percent").coerceIn(5, 95)
                    if (s.has("selected_fingerprint")) mutable[getProfileKey(SELECTED_FINGERPRINT, p)] = s.getString("selected_fingerprint")
                    if (s.has("selected_turn_subnet")) mutable[getProfileKey(SELECTED_TURN_SUBNET, p)] = s.getString("selected_turn_subnet")
                    if (s.has("turn_subnet_ip_limit")) mutable[getProfileKey(TURN_SUBNET_IP_LIMIT, p)] = s.getString("turn_subnet_ip_limit")
                }
            }
            true
        }.getOrDefault(false)
    }
}
