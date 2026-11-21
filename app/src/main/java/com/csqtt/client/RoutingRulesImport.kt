package com.csqtt.client

import org.json.JSONArray
import org.json.JSONObject

/** One section of a rules import; a null field leaves the stored value as it is. */
internal data class AppsImport(val black: List<String>?, val white: List<String>?, val isWhitelist: Boolean?)
internal data class ListsImport(
    val black: List<String>?,
    val white: List<String>?,
    val isWhitelist: Boolean?,
    val templateDomains: Boolean? = null,
)

/** Everything an import writes, validated as a whole before the one write (M-63). */
internal data class RoutingImportPlan(val apps: AppsImport? = null, val domains: ListsImport? = null, val ips: ListsImport? = null) {
    val isEmpty: Boolean get() = apps == null && domains == null && ips == null
}

/** The stored rules an import merges into or falls back on (the active profile). */
internal data class RoutingImportCurrent(
    val appsWhitelist: Boolean = false,
    val blackApps: Set<String> = emptySet(),
    val whiteApps: Set<String> = emptySet(),
    val domainsWhitelist: Boolean = false,
    val blackDomains: String = "",
    val whiteDomains: String = "",
    val ipsWhitelist: Boolean = false,
    val blackIps: String = "",
    val whiteIps: String = "",
)

internal sealed interface RoutingImportResult {
    data class Ready(val plan: RoutingImportPlan) : RoutingImportResult
    data class Refused(val reason: String) : RoutingImportResult
}

/**
 * The classic screen's rules import (M-63): every selected section is parsed and checked before anything is written; a
 * section of the wrong shape refuses the whole import with its name, so nothing half-applies. Two inputs, as before:
 * the JSON export (each section replaces the stored one) and plain text (entries added to the active lists).
 */
internal object RoutingRulesImport {
    private const val MAX_TEXT = 1 shl 20

    fun parse(
        text: String,
        apps: Boolean,
        domains: Boolean,
        ips: Boolean,
        current: RoutingImportCurrent,
    ): RoutingImportResult {
        if (!apps && !domains && !ips) return RoutingImportResult.Refused("Выберите хотя бы один пункт")
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return RoutingImportResult.Refused("Буфер обмена пуст")
        if (trimmed.length > MAX_TEXT) return RoutingImportResult.Refused("Слишком большой текст: больше 1 МБ")
        val root = if (trimmed.startsWith("{")) {
            runCatching { JSONObject(trimmed) }.getOrNull()
                ?: return RoutingImportResult.Refused("Повреждённый JSON: правила не импортированы")
        } else {
            null
        }
        val plan = try {
            if (root != null) fromJson(root, apps, domains, ips, current) else fromText(trimmed, apps, domains, ips, current)
        } catch (e: SectionError) {
            return RoutingImportResult.Refused("Раздел «${e.section}» повреждён: правила не импортированы")
        }
        return if (plan.isEmpty) RoutingImportResult.Refused("В буфере обмена нет подходящих данных") else RoutingImportResult.Ready(plan)
    }

    private class SectionError(val section: String) : Exception(section)

    private fun JSONObject.section(name: String): JSONObject? {
        if (!has(name)) return null
        return get(name) as? JSONObject ?: throw SectionError(name)
    }

    /** A list key: absent → null; present, it must be an array of strings. */
    private fun JSONObject.strings(section: String, key: String): List<String>? {
        if (!has(key)) return null
        val array = get(key) as? JSONArray ?: throw SectionError(section)
        return (0 until array.length()).map { index -> array.get(index) as? String ?: throw SectionError(section) }
    }

    private fun JSONObject.mode(section: String): Boolean? = when {
        has("active_mode") -> when (opt("active_mode")) {
            "whitelist" -> true
            "blacklist" -> false
            else -> throw SectionError(section)
        }
        has("is_whitelist") -> when (val value = opt("is_whitelist")) {
            is Boolean -> value
            "true" -> true
            "false" -> false
            else -> throw SectionError(section)
        }
        else -> null
    }

    private fun fromJson(
        root: JSONObject,
        apps: Boolean,
        domains: Boolean,
        ips: Boolean,
        current: RoutingImportCurrent,
    ): RoutingImportPlan {
        val appsPart = if (apps) root.section("apps")?.let { obj ->
            AppsImport(
                // as before: a JSON apps section replaces both lists (a missing list empties it)
                black = (obj.strings("apps", "blacklist") ?: emptyList()).filter(RoutingEntries::isValidPackage).distinct(),
                white = (obj.strings("apps", "whitelist") ?: emptyList()).filter(RoutingEntries::isValidPackage).distinct(),
                isWhitelist = obj.mode("apps"),
            )
        } else {
            null
        }
        val domainsPart = if (domains) root.section("domains")?.let { obj ->
            val white = obj.mode("domains") ?: current.domainsWhitelist
            val template = obj.opt("template_domains")
            if (template != null && template !is Boolean) throw SectionError("domains")
            lists(
                obj, "domains", "direct_domains", white,
                clean = RoutingEntries::cleanDomain, valid = RoutingEntries::isValidDomain,
            ).copy(isWhitelist = white, templateDomains = template as? Boolean ?: false)
        } else {
            null
        }
        val ipsPart = if (ips) root.section("ips")?.let { obj ->
            val white = obj.mode("ips") ?: current.ipsWhitelist
            lists(
                obj, "ips", "direct_ips", white,
                clean = RoutingEntries::cleanIpRule, valid = RoutingEntries::isValidIpRule,
            ).copy(isWhitelist = white)
        } else {
            null
        }
        return RoutingImportPlan(appsPart, domainsPart, ipsPart)
    }

    /** The black and white lists of a domains or IPs section; the flat legacy key fills the active one when both are empty. */
    private fun lists(
        obj: JSONObject,
        section: String,
        flatKey: String,
        white: Boolean,
        clean: (String) -> String,
        valid: (String) -> Boolean,
    ): ListsImport {
        fun checked(entries: List<String>?) = entries.orEmpty().map(clean).filter(valid)
        var black = checked(obj.strings(section, "blacklist"))
        var whiteList = checked(obj.strings(section, "whitelist"))
        val flat = checked(obj.strings(section, flatKey))
        if (black.isEmpty() && whiteList.isEmpty() && flat.isNotEmpty()) {
            if (white) whiteList = flat else black = flat
        }
        black = black.distinct().take(RoutingEntries.MAX_LOCAL_ENTRIES)
        whiteList = whiteList.distinct().take(RoutingEntries.MAX_LOCAL_ENTRIES)
        // as before: a list is written when it has entries or is the section's active one (an empty active list clears it)
        return ListsImport(
            black = black.takeIf { it.isNotEmpty() || !white },
            white = whiteList.takeIf { it.isNotEmpty() || white },
            isWhitelist = white,
        )
    }

    private fun fromText(
        text: String,
        apps: Boolean,
        domains: Boolean,
        ips: Boolean,
        current: RoutingImportCurrent,
    ): RoutingImportPlan {
        val appsPart = if (apps) appExport(text)?.let { (black, white) ->
            if (black.isEmpty() && white.isEmpty()) {
                null
            } else {
                AppsImport(
                    black = if (black.isNotEmpty()) (current.blackApps + black).toList() else null,
                    white = if (white.isNotEmpty()) (current.whiteApps + white).toList() else null,
                    isWhitelist = null,
                )
            }
        } else {
            null
        }
        val domainsPart = if (domains) {
            val imported = RoutingEntries.parseDomainsFromText(text)
            if (imported.isEmpty()) null else merged(current.domainsWhitelist, current.blackDomains, current.whiteDomains, imported, RoutingEntries::parseDomainsFromText)
        } else {
            null
        }
        val ipsPart = if (ips) {
            val imported = RoutingEntries.parseIpsFromText(text)
            if (imported.isEmpty()) null else merged(current.ipsWhitelist, current.blackIps, current.whiteIps, imported, RoutingEntries::parseIpsFromText)
        } else {
            null
        }
        return RoutingImportPlan(appsPart, domainsPart, ipsPart)
    }

    /** Plain text adds to the list of the active mode; the mode itself is kept. */
    private fun merged(
        white: Boolean,
        blackStored: String,
        whiteStored: String,
        imported: List<String>,
        parse: (String) -> List<String>,
    ): ListsImport {
        val combined = (parse(if (white) whiteStored else blackStored) + imported).distinct().take(RoutingEntries.MAX_LOCAL_ENTRIES)
        return ListsImport(black = combined.takeUnless { white }, white = combined.takeIf { white }, isWhitelist = null)
    }

    /** The text app export: «Чёрный список:» / «Белый список:» (or English) headers, packages after them; null without a header. */
    fun appExport(text: String): Pair<Set<String>, Set<String>>? {
        val black = linkedSetOf<String>()
        val white = linkedSetOf<String>()
        var section: MutableSet<String>? = null
        var headerFound = false
        fun packages(s: String) = s.split(Regex("[,;\\s\\t]+"))
            .map { it.trim().trim(',', ';', '"', '\'') }
            .filter(RoutingEntries::isValidPackage)
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val lower = trimmed.lowercase()
            val header = when {
                listOf("чёрный-список:", "черный-список:", "чёрный список:", "черный список:", "blacklist:", "black:")
                    .any(lower::startsWith) -> black
                listOf("белый-список:", "белый список:", "whitelist:", "white:").any(lower::startsWith) -> white
                else -> null
            }
            if (header != null) {
                headerFound = true
                section = header
                header.addAll(packages(trimmed.substringAfter(':')))
            } else {
                section?.addAll(packages(trimmed))
            }
        }
        return if (headerFound) black to white else null
    }
}
