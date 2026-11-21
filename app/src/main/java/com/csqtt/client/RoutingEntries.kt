package com.csqtt.client

/**
 * One check per kind of routing entry, shared by the server rule (M-56), the rules import (M-63) and the classic screen.
 * Every entry the app sends reaches the core inside one stdin line, `UPDATE_DOMAINS|k=v;k=v;…domains=a,b;…`, so an entry
 * must never hold a field or line separator.
 */
internal object RoutingEntries {
    private val domainLabel = Regex("^[a-z0-9Ѐ-ӿ]([a-z0-9Ѐ-ӿ\\-]{0,61}[a-z0-9Ѐ-ӿ])?\$")
    private val packageName = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")

    /** The forms a user types or pastes, cut down to the host: no scheme, `www.`, path, port, query or quotes. */
    fun cleanDomain(raw: String): String = raw.trim()
        .lowercase()
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .substringBefore('/')
        .substringBefore(':')
        .substringBefore('?')
        .substringBefore('#')
        .trim(',', ';', ' ', '\t', '\n', '\r', '.', '(', ')', '[', ']', '"', '\'')

    /** A host name the user may add on the classic screen or import: labels of letters, digits and inner hyphens. */
    fun isValidDomain(domain: String): Boolean {
        if (domain.length < 3 || domain.length > 253) return false
        val parts = domain.split('.')
        if (parts.size < 2) return false
        val tld = parts.last()
        if (tld.length < 2 || !tld.any { it.isLetter() }) return false
        return parts.all { it.matches(domainLabel) }
    }

    /**
     * A server's domain entry: the core cleans URL forms and a leading `!` (force through the tunnel, M-31) itself, so
     * only what would break the command line, or could never be a host, is refused.
     */
    fun isSafeServerDomain(entry: String): Boolean =
        entry.length in 3..253 && entry.contains('.') &&
            entry.none { it.isISOControl() || it.isWhitespace() || it == ';' || it == '|' || it == '=' || it == ',' }

    fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            val num = part.toIntOrNull() ?: return@all false
            num in 0..255 && (part == "0" || !part.startsWith("0"))
        }
    }

    /** An address, an address/prefix or a range `a-b`: the three forms the core's `parse_ip_rules` takes. */
    fun isValidIpRule(raw: String): Boolean {
        val item = raw.trim()
        if (item.isEmpty()) return false
        // a break inside an entry would split the core's command line and lose every rule after it (review F1)
        if (item.any { it.isWhitespace() || it.isISOControl() }) return false
        if (item.contains('/')) {
            val parts = item.split('/')
            if (parts.size != 2) return false
            val prefix = parts[1].toIntOrNull() ?: return false
            return prefix in 0..32 && isValidIpv4(parts[0].trim())
        }
        if (item.contains('-')) {
            val parts = item.split('-')
            if (parts.size != 2) return false
            return isValidIpv4(parts[0].trim()) && isValidIpv4(parts[1].trim())
        }
        return isValidIpv4(item)
    }

    fun cleanIpRule(raw: String): String {
        val item = raw.trim()
        if (item.contains('-')) {
            val parts = item.split('-')
            if (parts.size == 2) return "${parts[0].trim()}-${parts[1].trim()}"
        }
        return item
    }

    /** A package on the classic screen or in an import (at least two parts). */
    fun isValidPackage(pkg: String): Boolean =
        pkg.length in 3..255 && pkg.split('.').size >= 2 && pkg.matches(packageName)

    fun parseDomainsFromText(text: String): List<String> = text
        .split(Regex("[,;\\s\\n\\r]+"))
        .map(::cleanDomain)
        .filter(::isValidDomain)
        .distinct()
        .take(MAX_LOCAL_ENTRIES)

    fun parseIpsFromText(text: String): List<String> = text
        .split(Regex("[,;\\s\\n\\r]+"))
        .map(::cleanIpRule)
        .filter(::isValidIpRule)
        .distinct()
        .take(MAX_LOCAL_ENTRIES)

    /** The classic screen keeps at most this many domains or IPs a list. */
    const val MAX_LOCAL_ENTRIES = 1024
}
