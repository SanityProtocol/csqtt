package com.csqtt.client

/**
 * The local domain and IP rules of one profile, read at once (M-57, Fikisq-slim's `RoutingScreenSnapshot`). The start,
 * the restore, every live push and the return from a server rule all derive what the core gets from this one value.
 * "The list is the switch", for domains as for IPs: the stored `domain_routing_enabled` is no longer read (nothing in the
 * app ever set it, so every push turned domain routing off); RU zones and the template count only in blacklist mode.
 */
internal data class RoutingSnapshot(
    val domainIsWhitelist: Boolean = false,
    val directDomainsBlack: String = "",
    val directDomainsWhite: String = "",
    val excludeRuZonesEnabled: Boolean = false,
    val templateDomainsEnabled: Boolean = false,
    val ipIsWhitelist: Boolean = false,
    val directIpsBlack: String = "",
    val directIpsWhite: String = "",
) {
    val activeDomains: String get() = if (domainIsWhitelist) directDomainsWhite else directDomainsBlack
    val activeIps: String get() = if (ipIsWhitelist) directIpsWhite else directIpsBlack
    val useRuZones: Boolean get() = !domainIsWhitelist && excludeRuZonesEnabled
    val useTemplate: Boolean get() = !domainIsWhitelist && templateDomainsEnabled
    val hasDomainRules: Boolean get() = activeDomains.isNotBlank() || useRuZones || useTemplate
    val hasIpRules: Boolean get() = activeIps.isNotBlank()

    fun toUpdate(): DomainRoutingUpdate = DomainRoutingUpdate(
        enabled = hasDomainRules,
        excludeRuZones = useRuZones,
        templateDomains = useTemplate,
        isWhitelist = domainIsWhitelist,
        directDomains = activeDomains,
        ipRoutingEnabled = hasIpRules,
        ipIsWhitelist = ipIsWhitelist,
        directIps = activeIps,
    )
}

/** Every field of the core's `UPDATE_DOMAINS` line: it replaces the core's whole domain and IP config, so all are sent. */
internal data class DomainRoutingUpdate(
    val enabled: Boolean,
    val excludeRuZones: Boolean,
    val templateDomains: Boolean,
    val isWhitelist: Boolean,
    val directDomains: String,
    val ipRoutingEnabled: Boolean,
    val ipIsWhitelist: Boolean,
    val directIps: String,
) {
    fun toCommand(): String {
        fun flag(value: Boolean) = if (value) 1 else 0
        return "UPDATE_DOMAINS|enabled=${flag(enabled)};ru=${flag(excludeRuZones)};template=${flag(templateDomains)};" +
            "whitelist=${flag(isWhitelist)};domains=$directDomains;ip_enabled=${flag(ipRoutingEnabled)};" +
            "ip_whitelist=${flag(ipIsWhitelist)};ips=$directIps"
    }
}

/** A server rule's domains and IPs, in the same shape (its RU zones are never used; its template is the server's choice). */
internal fun ServerRoutingRule.toUpdate(): DomainRoutingUpdate = DomainRoutingUpdate(
    enabled = templateDomains || domains.isNotEmpty(),
    excludeRuZones = false,
    templateDomains = templateDomains,
    isWhitelist = domainsIsWhite,
    directDomains = domains.joinToString(","),
    ipRoutingEnabled = ips.isNotEmpty(),
    ipIsWhitelist = ipIsWhite,
    directIps = ips.joinToString(","),
)

/**
 * M-58: whether two rules build a different VPN interface. Only the apps and, in IP blacklist mode, the addresses become
 * VPN inputs (excluded routes); a whitelist's addresses make no route. A null rule is the local app filter.
 */
internal fun vpnInputsDiffer(built: ServerRoutingRule?, active: ServerRoutingRule?): Boolean {
    if (built == null || active == null) return (built == null) != (active == null)
    return built.isWhite != active.isWhite ||
        built.packages.toSet() != active.packages.toSet() ||
        built.packagesListed != active.packagesListed ||
        built.ipIsWhite != active.ipIsWhite ||
        (!active.ipIsWhite && built.ips.toSet() != active.ips.toSet())
}
