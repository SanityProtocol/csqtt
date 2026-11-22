package com.csqtt.client

/**
 * M-86 (B5g): the tunnel's own ping on «Подключение». The app's own traffic is kept out of its VPN, so the app cannot
 * time the tunnel from Kotlin: the core sends a TCP SYN from the tunnel address through its workers and times the answer
 * (B4b's PROBE). [Running] while asked; [Done] until the run ends.
 */
internal sealed interface TunnelProbe {
    data object Running : TunnelProbe

    /** [result] is the core's: `synack`, `rst` (the path works, the port is closed), `timeout`, `no_workers`, … */
    data class Done(val result: String, val ms: Long?, val target: String) : TunnelProbe {
        val failed: Boolean get() = result != "synack" && result != "rst"
    }
}

internal fun probeLine(probe: TunnelProbe.Done): String = when (probe.result) {
    "synack" -> "Пинг через туннель: ${probe.ms ?: "?"} мс"
    "rst" -> "Пинг через туннель: ${probe.ms ?: "?"} мс (порт закрыт, путь работает)"
    "timeout" -> "Пинг: нет ответа за 5 с"
    "no_workers" -> "Пинг: нет активных потоков"
    "busy" -> "Пинг: проверка уже идёт"
    else -> "Пинг: не удалось проверить"
}
