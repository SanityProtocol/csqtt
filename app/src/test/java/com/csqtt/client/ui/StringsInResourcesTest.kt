package com.csqtt.client.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * D12 (P3-PLAN §1.1): the one design's text is in res/values/strings.xml, never in a composable. The app is Russian
 * now, so a string literal with a Cyrillic letter under ui/ is text for the user; classic's screens (ui/legacy) go with
 * their cards and are not read. Comments may say anything.
 */
class StringsInResourcesTest {
    private val root = File("src/main/java/com/csqtt/client/ui")

    @Test
    fun `no screen of the one design carries its text in code`() {
        assertTrue("the test runs from the app module: ${root.absolutePath}", root.isDirectory)
        val files = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.relativeTo(root).invariantSeparatorsPath.startsWith("legacy/") }
            .toList()
        assertTrue("the one design's sources are read: ${files.size}", files.size >= 10)
        val found = files.flatMap { file ->
            cyrillicLiterals(file.readText()).map { (line, text) -> "${file.relativeTo(root)}:$line \"$text\"" }
        }
        assertEquals("text for the user goes to strings.xml:\n" + found.joinToString("\n"), 0, found.size)
    }

    @Test
    fun `the scan finds literals with their lines, inside templates too, and skips comments`() {
        val dollar = '$'
        val source = listOf(
            "// «Сервер» in a comment is fine",
            "/* and /* nested */ «Логи» here */",
            "/** KDoc: «Туннель». */",
            "val a = \"Главная\"",
            "val b = \"plain ascii, a \\\" quote and a char\" + 'я'",
            "val c = \"$dollar{if (on) \"включено\" else off} x\"",
            "val d = \"\"\"",
            "  сырой текст",
            "\"\"\"",
            "val e = \"v$dollar{version}\" // «версия»",
        ).joinToString("\n")
        assertEquals(listOf(4 to "Главная", 6 to "включено", 7 to "\n  сырой текст\n"), cyrillicLiterals(source))
    }
}

/** The string literals of Kotlin [source] that hold a Cyrillic letter, each with the line it starts on. */
internal fun cyrillicLiterals(source: String): List<Pair<Int, String>> = LiteralScan(source).apply { code(inTemplate = false) }.found

private class LiteralScan(private val src: String) {
    private var at = 0
    private var line = 1
    val found = mutableListOf<Pair<Int, String>>()

    private fun starts(text: String) = src.startsWith(text, at)

    private fun step() {
        if (src[at] == '\n') line++
        at++
    }

    /** Code up to the end, or inside a string's template up to the brace that closes it. */
    fun code(inTemplate: Boolean) {
        var depth = 0
        while (at < src.length) {
            when {
                starts("//") -> while (at < src.length && src[at] != '\n') at++
                starts("/*") -> comment()
                starts("\"\"\"") -> string(raw = true)
                src[at] == '"' -> string(raw = false)
                src[at] == '\'' -> {
                    at++
                    while (at < src.length && src[at] != '\'') at += if (src[at] == '\\') 2 else 1
                    at++
                }
                src[at] == '{' -> { depth++; at++ }
                src[at] == '}' -> {
                    at++
                    if (inTemplate && depth == 0) return
                    depth--
                }
                else -> step()
            }
        }
    }

    /** Kotlin's block comments nest. */
    private fun comment() {
        var depth = 0
        do {
            when {
                starts("/*") -> { depth++; at += 2 }
                starts("*/") -> { depth--; at += 2 }
                else -> step()
            }
        } while (depth > 0 && at < src.length)
    }

    private fun string(raw: Boolean) {
        val startLine = line
        val text = StringBuilder()
        at += if (raw) 3 else 1
        while (at < src.length) {
            when {
                raw && starts("\"\"\"") -> { at += 3; break }
                !raw && src[at] == '"' -> { at++; break }
                !raw && src[at] == '\\' -> at += 2
                starts("\${") -> { at += 2; code(inTemplate = true) }
                else -> { text.append(src[at]); step() }
            }
        }
        if (text.any { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.CYRILLIC }) found += startLine to text.toString()
    }
}
