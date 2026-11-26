package com.csqtt.client

/** What the store says about where a link could go (read in one pass). */
internal data class LinkImportState(
    /** The profile that already connects to the link's server, the open one before another; null when none does. */
    val same: Int?,
    val sameName: String,
    /** The open profile while it holds nothing yet: the link fills it and takes no second slot. */
    val emptyOpen: Int?,
    val freeProfile: Boolean,
    /** The profile the tunnel is on while it runs, starts, stops or is paused for Wi-Fi; null when it is off. */
    val runProfile: Int?,
    val runName: String,
)

/**
 * The choices one import offers (M-70; the canvas, I15): an import never writes a profile the user did not pick, never
 * the one the tunnel is on, and a new profile is never made the open one by itself.
 */
internal data class LinkImportPlan(
    /** The profile already on the link's server, which «Обновить «…»» would write; null when there is none. */
    val same: Int?,
    /** «Обновить» can be taken: not while the tunnel is on that profile (the write would land mid-session). */
    val update: Boolean,
    /** A profile can be added: a slot is free, or the open profile is empty and takes the link. */
    val add: Boolean,
    /** The profile «Добавить» writes: the open one while it is empty, else null for a new one. */
    val addInto: Int?,
)

internal object LinkImport {
    /** The tunnel is running, starting, stopping or paused for Wi-Fi: its profile is not written (M-70). */
    fun tunnelBusy(): Boolean = TunnelManager.running.value || TunnelManager.starting.value ||
        TunnelManager.stopping.value || TunnelManager.autoPausedForWifi.value

    fun endpoint(host: String, port: Int): String = "${host.trim().trim('[', ']').lowercase()}:$port"

    fun plan(state: LinkImportState): LinkImportPlan = LinkImportPlan(
        same = state.same,
        update = state.same != null && state.same != state.runProfile,
        add = state.emptyOpen != null || state.freeProfile,
        addInto = state.emptyOpen,
    )
}
