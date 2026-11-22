package com.csqtt.client

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One record of «Логи» (B5g): when, what, and how many times ([count]). A [separator] opens a connection or a deploy
 * («Подключение #N»). [priority] is the old status board's order, kept for P3's level filters (APP-PLAN K6).
 */
@Immutable
data class LogEntry(
    val id: Long,
    val timeMs: Long,
    val key: String,
    val message: String,
    val count: Int = 1,
    val priority: Int = 99,
    val isError: Boolean = false,
    val level: LogLevel? = null,
    val separator: Boolean = false,
)

/**
 * B5g: «Логи», always recorded (the operator, 2026-11-21: «Активное логирование» goes, APP-PLAN K7 reversed). The last
 * [capacity] records, under one lock, appended from any thread; nothing here touches UI state. A change stores [version],
 * which nothing collects while «Логи» is hidden or the screen is off: the tab copies the records only while it is shown.
 * A record said again within the last [FOLD_WINDOW] of its connection folds into one at the end (×N, the new time), so a
 * heartbeat — every worker's TURN refresh — stays one line instead of filling the log.
 */
internal class LogBook(
    private val capacity: Int = CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val records = ArrayDeque<LogEntry>(capacity)
    private var nextId = 1L
    private var connections = 0

    private val versionState = MutableStateFlow(0L)
    /** Bumped by every change; the tab copies [snapshot] when it moves. */
    val version: StateFlow<Long> = versionState
    private val unreadState = MutableStateFlow(0)
    /** Error records added since the tab was last opened (the nav badge); a fold adds none. */
    val unreadErrors: StateFlow<Int> = unreadState

    fun append(key: String, message: String, priority: Int, isError: Boolean, level: LogLevel?) {
        val text = message.take(MAX_TEXT)
        if (text.isEmpty()) return
        val now = clock()
        synchronized(lock) {
            if (!foldLocked(key, text, now)) {
                addLocked(LogEntry(nextId++, now, key, text, 1, priority, isError, level))
                if (isError) unreadState.value += 1
            }
            versionState.value += 1
        }
    }

    /** A new connection (a start, a switch, a restore): «Подключение #N», numbered within this process. */
    fun beginConnection() = section("Подключение #${synchronized(lock) { ++connections }}")

    fun beginDeploy() = section("Деплой")

    private fun section(label: String) {
        val now = clock()
        synchronized(lock) {
            addLocked(LogEntry(nextId++, now, SECTION_KEY, label, separator = true))
            versionState.value += 1
        }
    }

    fun snapshot(): List<LogEntry> = synchronized(lock) { records.toList() }

    fun clear() {
        synchronized(lock) {
            records.clear()
            unreadState.value = 0
            versionState.value += 1
        }
    }

    fun markErrorsRead() {
        unreadState.value = 0
    }

    private fun addLocked(entry: LogEntry) {
        records.addLast(entry)
        if (records.size > capacity) records.removeFirst()
    }

    /** The same key and text among the last [FOLD_WINDOW] records since the last separator: counted, moved to the end. */
    private fun foldLocked(key: String, text: String, now: Long): Boolean {
        val last = records.lastIndex
        for (index in last downTo maxOf(0, last - FOLD_WINDOW + 1)) {
            val entry = records[index]
            if (entry.separator) return false
            if (entry.key == key && entry.message == text) {
                records.removeAt(index)
                records.addLast(entry.copy(timeMs = now, count = entry.count + 1))
                return true
            }
        }
        return false
    }

    companion object {
        const val CAPACITY = 500
        const val MAX_TEXT = 1_024
        const val FOLD_WINDOW = 8
        const val SECTION_KEY = "section"

        /** The app's log. */
        val app = LogBook()
    }
}
