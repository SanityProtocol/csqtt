package com.csqtt.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

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

/** One probe at a time and its answer (the core's PROBE event), apart from Android for the tests. */
internal class ProbeSlot {
    private val pending = AtomicReference<CompletableDeferred<TunnelEventParser.Event.Probe>?>(null)

    /** The slot for one probe, or null while another is waiting. */
    fun open(): CompletableDeferred<TunnelEventParser.Event.Probe>? =
        CompletableDeferred<TunnelEventParser.Event.Probe>().takeIf { pending.compareAndSet(null, it) }

    /** The core's answer reaches the probe waiting for it; with none waiting it is dropped. */
    fun answer(event: TunnelEventParser.Event.Probe) {
        pending.get()?.complete(event)
    }

    /** The answer, or null after [timeoutMs]; the slot is free again either way. */
    suspend fun await(slot: CompletableDeferred<TunnelEventParser.Event.Probe>, timeoutMs: Long): TunnelEventParser.Event.Probe? =
        try {
            withTimeoutOrNull(timeoutMs) { slot.await() }
        } finally {
            pending.compareAndSet(slot, null)
        }

    fun free(slot: CompletableDeferred<TunnelEventParser.Event.Probe>) {
        pending.compareAndSet(slot, null)
    }
}
