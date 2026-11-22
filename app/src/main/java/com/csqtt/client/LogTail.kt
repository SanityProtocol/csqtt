package com.csqtt.client

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * B5g (the operator, 2026-11-22: built, with the error trigger): the last [RECORDS] records of «Логи» kept across the
 * process's death, so the lines before a crash, a kill or a reboot are there when the app is opened again. Written whole
 * (a temp file renamed over the last), never per line: after a run's stop, when the app leaves the screen, at a crash,
 * and after an error record at most once a minute — that one covers Android ending the process while the tunnel runs in
 * the background. Nothing is written when nothing changed; an empty log removes the file. The records are redacted
 * before they are recorded, and the app's files are excluded from every backup (data_extraction_rules).
 */
internal object LogTail {
    const val RECORDS = 300
    const val ERROR_GAP_MS = 60_000L
    /** A stop's own last lines (the VPN's, the app's; the core's are dropped once its run ends) come just after it. */
    const val STOP_SETTLE_MS = 2_000L
    private const val FILE = "log-tail.json"

    @OptIn(ExperimentalCoroutinesApi::class)
    private val writer = Dispatchers.IO.limitedParallelism(1)

    @Volatile
    private var dir: File? = null

    @Volatile
    private var savedVersion = -1L
    private val delayedSavePending = AtomicBoolean(false)
    private val errorGate = ErrorWriteGate(ERROR_GAP_MS)

    /** At the process's start: what the last process left goes in front of this one's records. */
    fun restore(context: Context) {
        val directory = context.filesDir.also { dir = it }
        TunnelManager.scope.launch(writer) {
            val earlier = runCatching { decode(File(directory, FILE).readText()) }.getOrDefault(emptyList())
            LogBook.app.restore(earlier)
        }
    }

    /**
     * The app left the screen (now), or a run stopped (once its last lines are in): written if anything changed. Only
     * the delayed requests share one write: a write for now never waits behind a stop's, since the user who stops and
     * then swipes the app away ends the process within those seconds (review F1).
     */
    fun requestSave(delayMs: Long = 0L) {
        if (delayMs <= 0L) {
            TunnelManager.scope.launch(writer) { saveNow() }
            return
        }
        if (!delayedSavePending.compareAndSet(false, true)) return
        TunnelManager.scope.launch(writer) {
            delay(delayMs)
            delayedSavePending.set(false)
            saveNow()
        }
    }

    /** An error was recorded: written now, or at the end of the minute since the last such write. */
    fun errorRecorded() {
        val wait = errorGate.claim(SystemClock.elapsedRealtime()) ?: return
        TunnelManager.scope.launch(writer) {
            delay(wait)
            errorGate.done(SystemClock.elapsedRealtime())
            saveNow()
        }
    }

    /** Writes the tail if the log changed since the last write; on the caller's thread (the crash handler's too). */
    fun saveNow() {
        val directory = dir ?: return
        val book = LogBook.app
        val version = book.version.value
        if (version == savedVersion) return
        val records = book.snapshot().takeLast(RECORDS)
        runCatching {
            val file = File(directory, FILE)
            if (records.isEmpty()) {
                file.delete()
            } else {
                val temp = File.createTempFile(FILE, ".tmp", directory)
                temp.writeText(encode(records))
                if (!temp.renameTo(file)) {
                    temp.delete()
                    return
                }
            }
            savedVersion = version
        }
    }

    fun encode(records: List<LogEntry>): String = JSONArray().apply {
        records.forEach { entry ->
            put(
                JSONObject()
                    .put("t", entry.timeMs)
                    .put("k", entry.key)
                    .put("m", entry.message)
                    .put("c", entry.count)
                    .put("p", entry.priority)
                    .put("e", entry.isError)
                    .put("l", entry.level?.name ?: "")
                    .put("s", entry.separator),
            )
        }
    }.toString()

    /** What [encode] wrote; a record it cannot read is left out, a file it cannot read gives nothing. */
    fun decode(text: String): List<LogEntry> {
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            val message = json.optString("m").take(LogBook.MAX_TEXT)
            if (message.isEmpty()) return@mapNotNull null
            LogEntry(
                id = 0,
                timeMs = json.optLong("t"),
                key = json.optString("k"),
                message = message,
                count = json.optInt("c", 1).coerceAtLeast(1),
                priority = json.optInt("p", 99),
                isError = json.optBoolean("e"),
                level = LogLevel.entries.firstOrNull { it.name == json.optString("l") },
                separator = json.optBoolean("s"),
            )
        }.takeLast(RECORDS)
    }
}

/**
 * At most one error-triggered write per [gapMs]: the first error writes at once, later ones within the gap share one write
 * at its end. Pure, for the tests.
 */
internal class ErrorWriteGate(private val gapMs: Long) {
    private var lastWriteAt: Long? = null
    private var pending = false

    /** The delay before a write, or null when one is already waiting. */
    @Synchronized
    fun claim(now: Long): Long? {
        if (pending) return null
        pending = true
        val last = lastWriteAt ?: return 0L
        return (last + gapMs - now).coerceAtLeast(0L)
    }

    @Synchronized
    fun done(now: Long) {
        pending = false
        lastWriteAt = now
    }
}
