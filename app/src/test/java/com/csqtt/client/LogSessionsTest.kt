package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** B5g (M-88): the log as a chronological book — connections, repeats, its bounds and the unread errors. */
class LogSessionsTest {
    private var now = 1_000L
    private val book = LogBook(capacity = 20, clock = { now })

    private fun say(key: String, text: String = key, error: Boolean = false) {
        now += 1_000
        book.append(key, text, 50, error, if (error) LogLevel.ERR else LogLevel.LOG)
    }

    private fun texts() = book.snapshot().map { if (it.separator) "== ${it.message}" else "${it.message} x${it.count}" }

    @Test
    fun `records keep their order, and each connection opens under its own numbered heading`() {
        say("before")
        book.beginConnection()
        say("a")
        say("b")
        book.beginConnection()
        say("c")
        assertEquals(listOf("before x1", "== Подключение #1", "a x1", "b x1", "== Подключение #2", "c x1"), texts())
        // nothing is cleared at a start: the last connection stays above the new one
        val times = book.snapshot().map { it.timeMs }
        assertEquals(times.sorted(), times)
    }

    @Test
    fun `a repeat folds into one record at the end, with the new time, but never across a heading`() {
        book.beginConnection()
        say("refresh", "[TURN] Refresh аллокации подтверждён ✓")
        say("channel", "[TURN] ChannelBind активен ✓")
        say("refresh", "[TURN] Refresh аллокации подтверждён ✓")
        say("channel", "[TURN] ChannelBind активен ✓")
        assertEquals(
            listOf("== Подключение #1", "[TURN] Refresh аллокации подтверждён ✓ x2", "[TURN] ChannelBind активен ✓ x2"),
            texts(),
        )
        assertEquals(now, book.snapshot().last().timeMs)
        // the same key with another text is another record (worker 3 then worker 5 is not «worker 5 ×2»)
        say("err", "[ВОРКЕР #3] Ошибка", error = true)
        say("err", "[ВОРКЕР #5] Ошибка", error = true)
        assertEquals(5, book.snapshot().size)
        // a new connection starts its own count
        book.beginConnection()
        say("refresh", "[TURN] Refresh аллокации подтверждён ✓")
        assertEquals("[TURN] Refresh аллокации подтверждён ✓ x1", texts().last())
    }

    @Test
    fun `a repeat further back than the fold window is a new record`() {
        say("x")
        repeat(LogBook.FOLD_WINDOW) { say("other$it") }
        say("x")
        assertEquals(LogBook.FOLD_WINDOW + 2, book.snapshot().size)
        assertEquals("x x1", texts().last())
    }

    @Test
    fun `the book keeps its last records and caps a long text`() {
        repeat(30) { say("k$it") }
        val kept = book.snapshot()
        assertEquals(20, kept.size)
        assertEquals("k10", kept.first().message)
        assertEquals("k29", kept.last().message)
        assertEquals(kept.map { it.id }.distinct(), kept.map { it.id })
        book.append("long", "y".repeat(5_000), 50, false, null)
        assertEquals(LogBook.MAX_TEXT, book.snapshot().last().message.length)
        book.append("empty", "", 50, false, null)
        assertEquals("long", book.snapshot().last().key)
    }

    @Test
    fun `a new error counts as unread, a repeat of it does not, and reading or clearing resets the count`() {
        say("ok")
        say("e1", error = true)
        say("e1", error = true)
        say("e2", error = true)
        assertEquals(2, book.unreadErrors.value)
        book.markErrorsRead()
        assertEquals(0, book.unreadErrors.value)
        say("e3", error = true)
        assertEquals(1, book.unreadErrors.value)
        book.beginConnection()
        book.clear()
        assertTrue(book.snapshot().isEmpty())
        assertEquals(0, book.unreadErrors.value)
        // the numbering goes on after a clear
        book.beginConnection()
        assertEquals("== Подключение #2", texts().single())
    }

    @Test
    fun `every change moves the version, so a tab that is shown copies again`() {
        val start = book.version.value
        say("a")
        val afterAppend = book.version.value
        say("a")
        val afterFold = book.version.value
        book.beginDeploy()
        val afterSection = book.version.value
        book.clear()
        assertNotEquals(start, afterAppend)
        assertNotEquals(afterAppend, afterFold)
        assertNotEquals(afterFold, afterSection)
        assertNotEquals(afterSection, book.version.value)
        assertFalse(book.snapshot().any { it.separator })
    }

    @Test
    fun `the copied text says when, what and how many times`() {
        val utc = ZoneOffset.UTC
        val line = LogEntry(1, 3_723_000, "k", "[TURN] Refresh ✓", count = 4)
        assertEquals("01:02:03  [TURN] Refresh ✓ ×4", LogText.line(line, utc))
        assertEquals("01:02:03  [СЕТЬ] Сеть есть", LogText.line(line.copy(message = "[СЕТЬ] Сеть есть", count = 1), utc))
        val heading = LogEntry(2, 3_600_000, LogBook.SECTION_KEY, "Подключение #2", separator = true)
        assertEquals("── Подключение #2 · 01:00:00 ──", LogText.line(heading, utc))
        assertEquals(
            "── Подключение #2 · 01:00:00 ──\n01:02:03  [TURN] Refresh ✓ ×4",
            LogText.text(listOf(heading, line), utc),
        )
    }
}
