package com.csqtt.client

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/**
 * B5f2 (M-52…M-54, #19's model with its fixes): which Wi-Fi networks pause the tunnel. «Автопауза при Wi-Fi» stays the
 * switch, the rule says where it acts. The two list rules need the network's name, which Android gives only with precise
 * location: a name it does not give never pauses (M-54), so a background start or location turned off leaves the VPN on,
 * never off on the very network the user excluded.
 */
internal enum class WifiRule(val stored: String, val label: String, val sentence: String) {
    ALL("all", "В любой", "VPN встаёт на паузу в любой сети Wi-Fi."),
    // #19's stored values: its «Только в выбранных сетях» was `blacklist`, its «Во всех, кроме выбранных» `whitelist`
    ONLY_CHECKED("blacklist", "В отмеченных", "VPN встаёт на паузу только в отмеченных сетях."),
    EXCEPT_CHECKED("whitelist", "Кроме отмеченных", "VPN встаёт на паузу в любой сети Wi-Fi, кроме отмеченных.");

    val usesNames: Boolean get() = this != ALL

    /** What «Запомнить» does under this rule: a remembered network is a checked one. */
    val rememberEffect: String
        get() = if (this == EXCEPT_CHECKED) {
            "В запомненной сети VPN будет работать без паузы."
        } else {
            "В запомненной сети VPN будет вставать на паузу."
        }

    companion object {
        fun fromStored(value: String?): WifiRule = entries.firstOrNull { it.stored == value } ?: ALL
    }
}

/** The rules as stored: a saved network keeps its check across rules (#19), [saved] includes every checked one. */
internal data class WifiRules(
    val enabled: Boolean = false,
    val rule: WifiRule = WifiRule.ALL,
    val checked: Set<String> = emptySet(),
    val saved: Set<String> = emptySet(),
    val dismissed: Set<String> = emptySet(),
) {
    /** Names are read, and location used, only while a list rule is on (F2): «В любой» needs neither. */
    val needsNames: Boolean get() = enabled && rule.usesNames
}

internal enum class WifiDecision { NONE, PAUSE, STAY, NAME_UNKNOWN }

/** [name]: the network's name as Android gave it, null when it gave none (no location, a background start). */
internal fun wifiDecision(rules: WifiRules, onWifi: Boolean, name: String?): WifiDecision = when {
    !rules.enabled || !onWifi -> WifiDecision.NONE
    rules.rule == WifiRule.ALL -> WifiDecision.PAUSE
    // M-54, F1: an unknown name never pauses in a list rule
    name == null -> WifiDecision.NAME_UNKNOWN
    (name in rules.checked) == (rules.rule == WifiRule.ONLY_CHECKED) -> WifiDecision.PAUSE
    else -> WifiDecision.STAY
}

/** «Запомнить «<сеть>»?»: in a list rule, on a Wi-Fi whose name is known and neither saved nor dismissed. */
internal fun wifiOffer(rules: WifiRules, onWifi: Boolean, name: String?): String? =
    name?.takeIf { rules.needsNames && onWifi && it !in rules.saved && it !in rules.dismissed }

/** What the service decided, for the screens and its notification (F6); null while no run is on. */
internal data class WifiStatus(
    val rule: WifiRule = WifiRule.ALL,
    /** The network the phone is on, in a list rule. */
    val name: String? = null,
    /** A list rule on a Wi-Fi whose name Android does not give: the rule cannot act. */
    val nameUnknown: Boolean = false,
    val offer: String? = null,
) {
    val listRule: Boolean get() = rule.usesNames
}

internal object WifiCopy {
    const val NAME_UNKNOWN = "Автопауза не действует: имя сети Wi-Fi неизвестно"
    fun offer(name: String) = "Запомнить «$name»?"
}

/** #19's sanitiser: Android's quotes off; its «<unknown ssid>», an empty name and anything multi-line refused. */
internal fun normalizeWifiSsid(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty() || value.any { it == '\n' || it == '\r' || it == '\u0000' }) return null
    if (value.equals("<unknown ssid>", ignoreCase = true)) return null
    val unquoted = if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
        value.substring(1, value.length - 1)
    } else {
        value
    }
    return unquoted.trim().takeIf { it.isNotEmpty() }
}

internal fun decodeWifiSsids(raw: String?): Set<String> =
    raw.orEmpty().lineSequence().mapNotNull(::normalizeWifiSsid).toCollection(linkedSetOf())

internal fun encodeWifiSsids(names: Collection<String>): String =
    names.mapNotNull(::normalizeWifiSsid).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER).joinToString("\n")

/**
 * F4: the service's foreground types at a start. API 34+: special use, with location while a list rule wants it; 29–33
 * an explicit type, since the two-argument call takes every type of the manifest (location included); 26–28: null, the
 * two-argument call (no types, nor a while-in-use rule).
 */
internal fun foregroundTypes(sdk: Int, wantLocation: Boolean): Int? = when {
    sdk >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
        (if (wantLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
    sdk >= 29 -> if (wantLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
    else -> null
}

/** The location type is wanted only while a list rule is on and precise location is granted. */
internal fun wantsLocationType(rules: WifiRules, fineGranted: Boolean): Boolean = rules.needsNames && fineGranted

internal fun hasFineLocation(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

internal fun locationEnabled(context: Context): Boolean = runCatching {
    LocationManagerCompat.isLocationEnabled(context.getSystemService(LocationManager::class.java))
}.getOrDefault(false)

/** The name of the Wi-Fi the phone is on, from Android's Wi-Fi service: precise location granted and turned on. */
@Suppress("DEPRECATION")
internal fun connectedWifiName(context: Context): String? {
    if (!hasFineLocation(context)) return null
    return runCatching {
        normalizeWifiSsid(context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid)
    }.getOrNull()
}
