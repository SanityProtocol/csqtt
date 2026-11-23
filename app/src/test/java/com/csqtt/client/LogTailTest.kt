package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** B5g: the log's tail across the process's death — the file's format, the restore, the error trigger, the header. */
class LogTailTest {
    private fun entry(id: Long, message: String, error: Boolean = false, count: Int = 1, separator: Boolean = false) =
        LogEntry(
            id, 1_700_000_000_000 + id, if (separator) LogBook.SECTION_KEY else "k$id", message, count, 50, error,
            if (error) LogLevel.ERR else null, separator,
        )

    @Test
    fun `the file keeps every field of a record, whatever its text holds`() {
        val records = listOf(
            entry(1, "Подключение #1", separator = true),
            entry(2, "[TURN] Refresh аллокации подтверждён ✓", count = 7),
            entry(3, "✗ Ошибка: \"quoted\"\tтаб\nновая строка \\ слэш", error = true),
        )
        val back = LogTail.decode(LogTail.encode(records))
        assertEquals(records.map { it.copy(id = 0) }, back)
    }

    @Test
    fun `a damaged file gives nothing, and a damaged record is left out`() {
        assertTrue(LogTail.decode("").isEmpty())
        assertTrue(LogTail.decode("{not json").isEmpty())
        val mixed = """[{"t":1,"k":"a","m":"ok","c":2,"p":5,"e":false,"l":"NET","s":false}, 7, {"t":2,"m":""},
            {"t":3,"k":"b","m":"x","c":-4,"l":"NOPE"}]"""
        val back = LogTail.decode(mixed)
        assertEquals(listOf("ok", "x"), back.map { it.message })
        assertEquals(LogLevel.NET, back[0].level)
        assertEquals(1, back[1].count)
        assertNull(back[1].level)
    }

    @Test
    fun `the tail is the last records, at most RECORDS of them`() {
        val many = (1L..(LogTail.RECORDS + 40)).map { entry(it, "line $it") }
        val back = LogTail.decode(LogTail.encode(many))
        assertEquals(LogTail.RECORDS, back.size)
        assertEquals("line ${LogTail.RECORDS + 40}", back.last().message)
    }

    @Test
    fun `an earlier process's records go in front of this one's, under a restart heading, read already`() {
        var now = 5_000L
        val book = LogBook(capacity = 6, clock = { now })
        book.beginConnection()
        book.append("new", "this process", 50, true, LogLevel.ERR)
        val unread = book.unreadErrors.value
        book.restore(listOf(entry(1, "old one", error = true), entry(2, "old two")))
        val shown = book.snapshot()
        assertEquals(
            listOf("old one", "old two", LogBook.RESTART_LABEL, "Подключение #1", "this process"),
            shown.map { it.message },
        )
        assertTrue(shown[2].separator)
        assertEquals(shown.map { it.id }.distinct(), shown.map { it.id })
        assertEquals(unread, book.unreadErrors.value)
        // too many to keep: the newest stay, this process's own first among them
        book.restore((10L..20L).map { entry(it, "older $it") })
        val kept = book.snapshot()
        assertEquals(6, kept.size)
        assertEquals("this process", kept.last().message)
        // nothing to restore changes nothing
        val version = book.version.value
        book.restore(emptyList())
        assertEquals(version, book.version.value)
        now += 1
    }

    @Test
    fun `opening the app again and again, recording nothing, leaves one heading`() {
        // the device check (2026-11-23): each process life that recorded nothing added its own heading
        var file = LogTail.encode(listOf(entry(1, "old one"), entry(2, "old two", error = true)))
        var now = 10_000L
        repeat(3) {
            val book = LogBook(capacity = 10, clock = { now })
            book.restore(LogTail.decode(file))
            file = LogTail.encode(book.snapshot()) // the app leaves the screen: the tail is written
            now += 60_000
        }
        val back = LogTail.decode(file)
        assertEquals(listOf("old one", "old two", LogBook.RESTART_LABEL), back.map { it.message })
        assertEquals(130_000L, back.last().timeMs) // the newest start's time
        // a file of headings alone gives nothing to restore
        val book = LogBook(capacity = 10, clock = { now })
        val version = book.version.value
        book.restore(
            listOf(entry(3, LogBook.RESTART_LABEL, separator = true), entry(4, LogBook.RESTART_LABEL, separator = true)),
        )
        assertTrue(book.snapshot().isEmpty())
        assertEquals(version, book.version.value)
        // a heading with records after it stays where it is
        book.restore(listOf(entry(5, "older"), entry(6, LogBook.RESTART_LABEL, separator = true), entry(7, "later")))
        assertEquals(
            listOf("older", LogBook.RESTART_LABEL, "later", LogBook.RESTART_LABEL),
            book.snapshot().map { it.message },
        )
    }

    @Test
    fun `an error writes at once, then at most once a minute, later errors sharing one write`() {
        val gate = ErrorWriteGate(60_000)
        assertEquals(0L, gate.claim(1_000))
        assertNull(gate.claim(1_500))
        gate.done(1_000)
        assertEquals(50_000L, gate.claim(11_000))
        assertNull(gate.claim(30_000))
        gate.done(61_000)
        assertEquals(0L, gate.claim(200_000))
    }

    @Test
    fun `a shared log says what wrote it and when, then the records`() {
        val zone = ZoneOffset.ofHours(3)
        val header = LogText.header("2.6.0", 239, "14", 34, "OnePlus CPH2747", 1_763_850_000_000, zone)
        assertEquals(
            "CSQTT 2.6.0 (239), ядро этой сборки · Android 14 (SDK 34) · OnePlus CPH2747\n" +
                "Выгружено 2025-11-23 01:20:00 (UTC+03:00)",
            header,
        )
        val utcHeader = LogText.header("2.6.0", 239, "14", 34, "x", 0, ZoneOffset.UTC)
        assertTrue(utcHeader.endsWith("(UTC+00:00)"))
        val records = listOf(LogEntry(1, 0, "k", "[СЕТЬ] Активных: 10", 1))
        assertEquals("$header\n\n03:00:00  [СЕТЬ] Активных: 10", LogText.export(records, header, zone))
    }
}
