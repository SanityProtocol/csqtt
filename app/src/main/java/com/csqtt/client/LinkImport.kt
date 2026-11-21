package com.csqtt.client

/** What the store says about the profiles a link could go into (read in one pass). */
internal data class LinkImportState(
    val activeName: String,
    /** The active profile has no server and no link yet. */
    val activeEmpty: Boolean,
    /** host:port the active profile connects to, lower-cased; null when it has none. */
    val activeEndpoint: String?,
    val freeProfile: Boolean,
    val tunnelRunning: Boolean,
)

/** The choices one import offers (M-70): an import never writes anywhere the user did not pick. */
internal data class LinkImportPlan(
    /** «Заменить профиль «X»» / «В этот профиль» — writes the active profile. */
    val intoActive: Boolean,
    /** The active profile is empty or already this server: writing it replaces nothing else. */
    val activeIsSameOrEmpty: Boolean,
    /** «В новый профиль». */
    val intoNew: Boolean,
    /** Why a choice is missing, shown in the dialog; null when both are offered. */
    val note: String?,
)

internal object LinkImport {
    /** The tunnel is running, starting, stopping or paused for Wi-Fi: the active profile is not written (M-70). */
    fun tunnelBusy(): Boolean = TunnelManager.running.value || TunnelManager.starting.value ||
        TunnelManager.stopping.value || TunnelManager.autoPausedForWifi.value

    fun endpoint(host: String, port: Int): String = "${host.trim().trim('[', ']').lowercase()}:$port"

    /**
     * M-70: the active profile is offered unless the tunnel runs (a write there would land at the next recovery restart,
     * mid-session); a new profile is offered when one is free. The dialog always asks, and names the profile it replaces.
     */
    fun plan(linkEndpoint: String, state: LinkImportState): LinkImportPlan {
        val sameOrEmpty = state.activeEmpty || state.activeEndpoint == linkEndpoint
        val intoActive = !state.tunnelRunning
        val intoNew = state.freeProfile
        val note = when {
            !intoActive && !intoNew -> "Отключите туннель или удалите ненужный профиль: все 32 профиля заняты"
            !intoActive -> "Туннель работает: ссылка добавится в новый профиль"
            !intoNew -> "Все 32 профиля заняты"
            else -> null
        }
        return LinkImportPlan(intoActive = intoActive, activeIsSameOrEmpty = sameOrEmpty, intoNew = intoNew, note = note)
    }
}
