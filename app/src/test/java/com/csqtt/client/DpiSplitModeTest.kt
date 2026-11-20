package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Test

class DpiSplitModeTest {
    @Test
    fun argumentsOnlyOnTcp() {
        val tcp = CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS
        val udp = CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT
        assertEquals(listOf("-dpi-split", "medium"), DpiSplitMode.MEDIUM.arguments(tcp))
        assertEquals(listOf("-dpi-split", "light"), DpiSplitMode.LIGHT.arguments(tcp))
        assertEquals(emptyList<String>(), DpiSplitMode.HEAVY.arguments(udp))
        assertEquals(emptyList<String>(), DpiSplitMode.OFF.arguments(tcp))
    }

    @Test
    fun unknownStoredValueIsOff() {
        assertEquals(DpiSplitMode.OFF, DpiSplitMode.fromStored(null))
        assertEquals(DpiSplitMode.OFF, DpiSplitMode.fromStored("current"))
        assertEquals(DpiSplitMode.HEAVY, DpiSplitMode.fromStored("heavy"))
        assertEquals(listOf("Выкл", "Лёгкая", "Средняя", "Тяжёлая"), DpiSplitMode.entries.map { it.label })
    }
}
