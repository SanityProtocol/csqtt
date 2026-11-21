package com.csqtt.client

/** An IPv4 prefix, as the VPN builder takes it. */
internal data class Ipv4Prefix(val address: String, val length: Int) {
    val start: Long get() = ipv4ToLong(address)
    val size: Long get() = 1L shl (32 - length)
    override fun toString() = "$address/$length"

    companion object {
        fun parse(text: String): Ipv4Prefix? {
            val parts = text.trim().split('/', limit = 2)
            val ip = parts[0]
            val length = if (parts.size == 2) parts[1].toIntOrNull() ?: return null else 32
            if (!RoutingEntries.isValidIpv4(ip) || length !in 0..32) return null
            // the network address, so 10.1.2.3/8 and 10.0.0.0/8 are one prefix
            val mask = if (length == 0) 0L else (0xFFFFFFFFL shl (32 - length)) and 0xFFFFFFFFL
            return Ipv4Prefix(longToIpv4(ipv4ToLong(ip) and mask), length)
        }
    }
}

internal fun ipv4ToLong(ip: String): Long = ip.split('.').fold(0L) { acc, part -> (acc shl 8) or part.toLong() }
internal fun longToIpv4(value: Long): String = (3 downTo 0).joinToString(".") { ((value shr (it * 8)) and 0xFF).toString() }

/** What one profile asks of the VPN interface, beside the server's TUNCONF and rule. */
internal data class VpnSettings(
    val appsWhitelist: Boolean = false,
    val whitelistApps: Set<String> = emptySet(),
    val excludedApps: Set<String> = emptySet(),
    /** M-59: «Все с VPN» — every app but the transport ones, both lists kept. */
    val allApps: Boolean = false,
    /** M-64: local networks bypass the tunnel. */
    val lanBypass: Boolean = false,
    /** M-67: one or two IPv4 resolvers in place of the server's. */
    val dnsOverride: String = "",
    val ipsWhitelist: Boolean = false,
    val ips: String = "",
)

/**
 * Everything the VPN interface is built from (M-59, M-62, M-64, M-66, M-67), decided in one pure function so a test can
 * read it; `TunVpnService` only applies it to a `Builder`. An interface is reused only for an equal plan.
 *
 * IPv6 (M-66 / D25): the plan never holds an IPv6 address or route and the service never calls `allowFamily`, so Android
 * blocks IPv6 for every tunnelled app — an immediate "network unreachable" that apps fall back from, never a leak.
 */
internal data class VpnPlan(
    val clientIp: String,
    val routes: List<Ipv4Prefix>,
    /** API 33+ only: the routes left out of the tunnel. */
    val excludedRoutes: List<Ipv4Prefix>,
    val dnsServers: List<String>,
    /** The DNS servers reached through the tunnel (a /32 route each). */
    val tunnelledDns: List<String>,
    /** Whitelist mode: only these apps. Empty in every other mode. */
    val allowedApps: List<String>,
    /** Blacklist and all-apps modes: these apps bypass (CSQTT itself first). */
    val disallowedApps: List<String>,
    val dnsOverridden: Boolean = false,
    val dnsOverrideRefused: Boolean = false,
    val lanBypass: Boolean = false,
    val failure: String? = null,
) {
    companion object {
        /** M-62 / D24: VK's own apps never go through the tunnel that VK carries; shown locked on the classic screen. */
        val VK_PACKAGES = listOf(
            CsqttConstants.General.PACKAGE_VK,
            "com.vk.im",
            "com.vk.vkvideo",
            "com.vk.music",
            CsqttConstants.General.PACKAGE_VK_CALLS,
        )

        /** M-64: private, link-local, CGNAT, multicast and reserved IPv4 (PR #52's 224/3 included). */
        val LAN_PREFIXES = listOf("10.0.0.0/8", "100.64.0.0/10", "169.254.0.0/16", "172.16.0.0/12", "192.168.0.0/16", "224.0.0.0/3")
            .map { requireNotNull(Ipv4Prefix.parse(it)) }

        private const val FALLBACK_DNS = "77.88.8.8"
        private const val TIRAMISU = 33

        fun isLan(ip: String): Boolean {
            val value = ipv4ToLong(ip)
            return LAN_PREFIXES.any { value >= it.start && value < it.start + it.size }
        }

        /**
         * M-67 / D26: one or two IPv4 resolvers, comma or space separated. A public address goes through the tunnel; a
         * private one only with LAN bypass on (it is then reached directly). Null = no usable override.
         */
        fun dnsOverride(text: String, lanBypass: Boolean): List<String>? {
            val entries = text.split(',', ' ', ';', '\n').map(String::trim).filter(String::isNotEmpty).distinct()
            if (entries.isEmpty() || entries.size > 2) return null
            if (!entries.all(RoutingEntries::isValidIpv4)) return null
            val unusable = entries.any { ip ->
                val first = ip.substringBefore('.').toInt()
                first == 0 || first == 127 || (isLan(ip) && (first >= 224 || !lanBypass))
            }
            return if (unusable) null else entries
        }

        /** The IPv4 space minus [excluded]: below API 33 the routes themselves leave the excluded ranges out. */
        fun complement(excluded: List<Ipv4Prefix>): List<Ipv4Prefix> {
            var pieces = listOf(Ipv4Prefix("0.0.0.0", 0))
            for (cut in excluded) {
                pieces = pieces.flatMap { piece -> subtract(piece, cut) }
            }
            return pieces.sortedBy { it.start }
        }

        private fun subtract(piece: Ipv4Prefix, cut: Ipv4Prefix): List<Ipv4Prefix> {
            val pieceEnd = piece.start + piece.size
            val cutEnd = cut.start + cut.size
            if (cutEnd <= piece.start || cut.start >= pieceEnd) return listOf(piece)   // apart
            if (cut.start <= piece.start && cutEnd >= pieceEnd) return emptyList()     // covered
            // the cut lies inside: split the piece in halves and keep going
            val half = piece.length + 1
            val low = Ipv4Prefix(piece.address, half)
            val high = Ipv4Prefix(longToIpv4(piece.start + (piece.size / 2)), half)
            return subtract(low, cut) + subtract(high, cut)
        }

        fun of(
            sdk: Int,
            ownPackage: String,
            clientIp: String,
            serverDns: String,
            serverRule: ServerRoutingRule?,
            settings: VpnSettings,
            installed: (String) -> Boolean,
        ): VpnPlan {
            val transport = (listOf(ownPackage) + VK_PACKAGES).toSet()
            val override = settings.dnsOverride.takeIf { it.isNotBlank() }?.let { dnsOverride(it, settings.lanBypass) }
            val serverDnsList = serverDns.split(",").map(String::trim)
                .filter { it.isNotEmpty() && !it.contains(":") && RoutingEntries.isValidIpv4(it) }   // IPv4 only (M-66)
                .ifEmpty { listOf(FALLBACK_DNS) }
            val dns = override ?: serverDnsList
            // a private override (only allowed with LAN bypass) is reached directly; every other resolver — the server's
            // too, even a private one on the server's side — through the tunnel, by its own /32 route
            val tunnelledDns = override?.filterNot { isLan(it) } ?: serverDnsList

            val excluded = mutableListOf<Ipv4Prefix>()
            if (settings.lanBypass) excluded += LAN_PREFIXES
            // a local IP blacklist (no server rule) or the server's: excluded routes, API 33+ only, as before
            val ipBlacklist = when {
                serverRule != null -> if (!serverRule.ipIsWhite) serverRule.ips else emptyList()
                !settings.ipsWhitelist -> settings.ips.split(",", " ", "\n", "\t").map(String::trim).filter(String::isNotEmpty)
                else -> emptyList()
            }
            val routes: List<Ipv4Prefix>
            val excludedRoutes: List<Ipv4Prefix>
            if (sdk >= TIRAMISU) {
                routes = listOf(Ipv4Prefix("0.0.0.0", 0))
                excludedRoutes = (excluded + ipBlacklist.mapNotNull(Ipv4Prefix::parse)).distinct()
            } else {
                routes = if (settings.lanBypass) complement(LAN_PREFIXES) else listOf(Ipv4Prefix("0.0.0.0", 0))
                excludedRoutes = emptyList()
            }

            val whitelist: Boolean
            val selected: Set<String>
            when {
                serverRule != null -> { whitelist = serverRule.isWhite; selected = serverRule.packages.toSet() }
                settings.allApps -> { whitelist = false; selected = emptySet() }
                else -> {
                    whitelist = settings.appsWhitelist
                    selected = if (whitelist) settings.whitelistApps else settings.excludedApps
                }
            }
            fun plan(allowed: List<String> = emptyList(), disallowed: List<String> = emptyList(), failure: String? = null) = VpnPlan(
                clientIp = clientIp,
                routes = routes,
                excludedRoutes = excludedRoutes,
                dnsServers = dns,
                tunnelledDns = tunnelledDns,
                allowedApps = allowed,
                disallowedApps = disallowed,
                dnsOverridden = override != null,
                dnsOverrideRefused = settings.dnsOverride.isNotBlank() && override == null,
                lanBypass = settings.lanBypass,
                failure = failure,
            )
            val bypassing = { extra: Set<String> ->
                listOf(ownPackage) + (transport + extra).filter { it != ownPackage && installed(it) }.sorted()
            }
            if (!whitelist) return plan(disallowed = bypassing(selected))
            val included = selected.filter { it !in transport && installed(it) }.sorted()
            if (included.isNotEmpty()) return plan(allowed = included)
            // a server's empty package list is a device-wide domain/IP rule; a list that named apps never broadens
            return if (serverRule == null || serverRule.packagesListed) {
                plan(failure = "Whitelist пуст: выберите хотя бы одно установленное приложение")
            } else {
                plan(disallowed = bypassing(emptySet()))
            }
        }
    }
}
