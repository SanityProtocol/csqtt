package com.csqtt.client

/**
 * The per-profile DPI split for TURN over TCP/TLS (the core's `--dpi-split`): the setup bytes of each
 * TURN stream go out in pieces. Ported from Gooby's presets (gentle, current, extended); see the core's
 * dpi_split.rs.
 */
enum class DpiSplitMode(val storedValue: String) {
    OFF("off"),
    LIGHT("light"),
    MEDIUM("medium"),
    HEAVY("heavy");

    /** Core arguments: only when on and the transport is TCP, since UDP has no stream to split. */
    fun arguments(turnTransport: String): List<String> =
        if (this != OFF && isAvailable(turnTransport)) listOf("-dpi-split", storedValue) else emptyList()

    companion object {
        const val EXTRA = "dpi_split"

        fun isAvailable(turnTransport: String): Boolean =
            turnTransport == CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS

        fun fromStored(value: String?): DpiSplitMode =
            entries.firstOrNull { it.storedValue == value } ?: OFF
    }
}
