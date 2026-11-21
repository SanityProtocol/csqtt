package com.csqtt.client

import android.content.Context

/**
 * M-61: the app routing presets (#19), as a mechanism only. The lists are exact package IDs in res/raw — never inferred
 * from a label or a prefix — and nothing applies them by itself: P3's buttons offer them, where the operator also decides
 * their regions. VK's apps are not in them: they are always direct and locked (M-62).
 */
internal object AppRoutingPresets {
    /** One package per line; `#` starts a comment. */
    fun parse(text: String): List<String> = text.lineSequence()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() && RoutingEntries.isValidPackage(it) && it !in VpnPlan.VK_PACKAGES }
        .distinct()
        .toList()

    /** The preset for a list: «С VPN» for a whitelist, «Без VPN» for a blacklist. */
    fun load(context: Context, forWhitelist: Boolean): List<String> =
        context.resources.openRawResource(if (forWhitelist) R.raw.app_presets_vpn else R.raw.app_presets_direct)
            .bufferedReader().use { parse(it.readText()) }

    /** «Готовый список»: the installed preset apps are added; the user's own choices stay, in their order, first. */
    fun merge(existing: Set<String>, preset: List<String>, installed: Set<String>): Set<String> =
        LinkedHashSet(existing).apply { addAll(preset.filter { it in installed }) }

    /** The dialog's order: the selection in the user's order first, then every other app alphabetically. */
    fun orderedPackages(alphabetical: List<String>, selection: Set<String>): List<String> {
        val available = alphabetical.toSet()
        return selection.filter { it in available } + alphabetical.filterNot { it in selection }
    }
}
