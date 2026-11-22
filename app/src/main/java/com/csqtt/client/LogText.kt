package com.csqtt.client

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** B5g: «Логи» as text — a record's time, the lines copied and shared. Pure, for the tests. */
internal object LogText {
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun time(timeMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        TIME.format(Instant.ofEpochMilli(timeMs).atZone(zone))

    fun line(entry: LogEntry, zone: ZoneId = ZoneId.systemDefault()): String = when {
        entry.separator -> "── ${entry.message} · ${time(entry.timeMs, zone)} ──"
        entry.count > 1 -> "${time(entry.timeMs, zone)}  ${entry.message} ×${entry.count}"
        else -> "${time(entry.timeMs, zone)}  ${entry.message}"
    }

    fun text(entries: List<LogEntry>, zone: ZoneId = ZoneId.systemDefault()): String =
        entries.joinToString("\n") { line(it, zone) }
}
