package com.csqtt.client

import org.json.JSONArray
import org.json.JSONObject

data class ServerRoutingRule(
    val isWhite: Boolean,
    val packages: List<String>,
    val domains: List<String>,
    val templateDomains: Boolean,
    val ipIsWhite: Boolean = false,
    val ips: List<String> = emptyList(),
    val domainsIsWhite: Boolean = isWhite,
    /**
     * The server named packages, even if none survived the check (M-56). An apps whitelist whose every entry was skipped
     * must still refuse to start («Whitelist пуст») instead of becoming a device-wide rule.
     */
    val packagesListed: Boolean = packages.isNotEmpty(),
)

/** A rule the server sent, and the entries left out of it (M-56 / D23). */
internal data class ParsedServerRoutingRule(val rule: ServerRoutingRule, val skipped: List<String>)

internal fun isRoutingPackageName(value: String): Boolean =
    value.length in 1..255 &&
        value.split('.').all { part ->
            part.isNotEmpty() && (part[0] in 'A'..'Z' || part[0] in 'a'..'z' || part[0] == '_') &&
                part.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' }
        }

private const val MAX_SERVER_LIST = 4096

/**
 * D23: an invalid entry (a package name, a domain that would break the core's command line, an address that is not
 * IPv4) is skipped and named; the rule is null — the tunnel stops, as before — only when it is broken as a whole: not a
 * JSON object, a list that is not an array, a non-string entry, or a list over 4096 entries.
 */
internal fun parseServerRoutingRuleChecked(value: String): ParsedServerRoutingRule? = runCatching {
    val json = JSONObject(value)
    val skipped = mutableListOf<String>()
    fun strings(key: String, valid: (String) -> Boolean): Pair<List<String>, Boolean> {
        if (!json.has(key)) return emptyList<String>() to false
        val array = json.get(key) as? JSONArray ?: error("routing list $key is not an array")
        require(array.length() <= MAX_SERVER_LIST) { "routing list too large" }
        val entries = (0 until array.length()).map { index ->
            require(array.get(index) is String) { "routing entry must be a string" }
            array.getString(index).trim()
        }.filter(String::isNotEmpty).distinct()
        val (good, bad) = entries.partition(valid)
        skipped += bad
        return good to entries.isNotEmpty()
    }
    val appsWhite = json.optBoolean("is_whitelist", json.optString("mode", "blacklist") == "whitelist")
    val domainsWhite = json.optBoolean(
        "domains_is_whitelist",
        json.optString("domains_mode", if (appsWhite) "whitelist" else "blacklist") == "whitelist",
    )
    val (packages, packagesListed) = strings("packages", ::isRoutingPackageName)
    val (domains, _) = strings("domains", RoutingEntries::isSafeServerDomain)
    val (ips, _) = strings("ips") { RoutingEntries.isValidIpRule(it) }
    ParsedServerRoutingRule(
        ServerRoutingRule(
            isWhite = appsWhite,
            packages = packages,
            domains = domains,
            templateDomains = json.optBoolean("template_domains", false),
            ipIsWhite = json.optBoolean("ip_is_whitelist", json.optString("ip_mode", "blacklist") == "whitelist"),
            ips = ips,
            domainsIsWhite = domainsWhite,
            packagesListed = packagesListed,
        ),
        skipped,
    )
}.getOrNull()

internal fun parseServerRoutingRule(value: String): ServerRoutingRule? = parseServerRoutingRuleChecked(value)?.rule

/** The skipped entries for the log: at most ten, each cut to 64 characters, control characters shown as «?». */
internal fun describeSkippedEntries(skipped: List<String>): String {
    val shown = skipped.take(10).joinToString(", ") { entry ->
        entry.take(64).map { if (it.isISOControl()) '?' else it }.joinToString("")
    }
    return if (skipped.size > 10) "$shown … (всего ${skipped.size})" else shown
}

/** M-55: the rule in force, in one line for the log. */
internal fun describeEffectiveRouting(
    source: String,
    appsWhite: Boolean,
    apps: Int,
    domainsWhite: Boolean,
    domains: Int,
    ruZones: Boolean,
    template: Boolean,
    ipsWhite: Boolean,
    ips: Int,
): String {
    fun mode(white: Boolean) = if (white) "белый список" else "чёрный список"
    val appsPart = if (apps == 0 && !appsWhite) "все с VPN" else "${mode(appsWhite)}, $apps"
    val extras = listOfNotNull(
        "RU-зоны".takeIf { ruZones && !domainsWhite },
        "шаблон".takeIf { template && !domainsWhite },
    )
    val domainsPart = if (domains == 0 && extras.isEmpty()) "нет" else (listOf("${mode(domainsWhite)}, $domains") + extras).joinToString(" + ")
    val ipsPart = if (ips == 0) "нет" else "${mode(ipsWhite)}, $ips"
    return "[СЕТЬ] Маршрутизация ($source): приложения — $appsPart; домены — $domainsPart; IP — $ipsPart"
}
