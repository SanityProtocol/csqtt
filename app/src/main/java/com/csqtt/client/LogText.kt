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

    /**
     * The first lines of a copied or shared log (B5g): what wrote it and when, with the zone its times are in. Nothing
     * else: no profile, server or account, and the records below are redacted when they are recorded.
     */
    fun header(
        appVersion: String,
        versionCode: Long,
        androidRelease: String,
        sdk: Int,
        device: String,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        return "CSQTT $appVersion ($versionCode), ядро этой сборки · Android $androidRelease (SDK $sdk) · $device\n" +
            "Выгружено ${EXPORTED.format(now)} (UTC${now.offset.id.replace("Z", "+00:00")})"
    }

    fun export(entries: List<LogEntry>, header: String, zone: ZoneId = ZoneId.systemDefault()): String =
        header + "\n\n" + text(entries, zone)

    private val EXPORTED: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
}
