// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
//
// The engine's half of the line protocol between deploy v3 and a VPS (CSQTT-APP-PLAN §6.4, P2-PLAN §1.2); the bundle's half is
// deploy-bundle/lib/marker.sh. Everything the engine reads from a box travels as marker lines on stdout:
//   CSQTT_<KIND>|<field>|<field>…      each field with %, |, CR and LF percent-escaped (UTF-8 otherwise as is)
//   CSQTT_END|<count>                  last: how many marker lines came before it
// Any other line (an rc file's echo, a MOTD, a banner) is skipped, so a login shell's noise never reaches a parser. A report is
// trusted only when its end line arrives and its count matches: a channel cut mid-report, or a marker line that a subshell
// printed past the counter, makes it Broken, never a shorter truth. SSH already guards the bytes, so the end line carries no hash.
package com.csqtt.deploy.wire

import java.io.ByteArrayOutputStream

/** One marker line: its kind (`PRE`, `INV`, `RESULT`…) and its fields, unescaped. */
data class Line(val kind: String, val fields: List<String>)

sealed interface Report {
    /** The end line arrived and counted exactly these lines. */
    data class Complete(val lines: List<Line>) : Report

    /** Never trusted: no end line, a count that does not match, or a malformed marker line. */
    data class Broken(val reason: String) : Report
}

object LineCodec {
    const val PREFIX = "CSQTT_"
    const val END = "END"
    private val KIND = Regex("[A-Z0-9]+(_[A-Z0-9]+)*")

    fun escape(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '%' -> append("%25")
                '|' -> append("%7C")
                '\r' -> append("%0D")
                '\n' -> append("%0A")
                else -> append(ch)
            }
        }
    }

    /** Decodes every %XX (the bundle escapes only four, but a field may legally carry any); a stray % stays as it is. */
    fun unescape(field: String): String {
        if ('%' !in field) return field
        val out = ByteArrayOutputStream(field.length)
        var i = 0
        while (i < field.length) {
            val ch = field[i]
            val hi = if (ch == '%' && i + 2 < field.length) Character.digit(field[i + 1], 16) else -1
            val lo = if (hi >= 0) Character.digit(field[i + 2], 16) else -1
            if (hi >= 0 && lo >= 0) {
                out.write(hi * 16 + lo)
                i += 3
            } else {
                val end = if (Character.isHighSurrogate(ch) && i + 1 < field.length) i + 2 else i + 1
                out.write(field.substring(i, end).toByteArray(Charsets.UTF_8))
                i = end
            }
        }
        return out.toString(Charsets.UTF_8)
    }

    /** The line the bundle would print for these values (tests, and the engine's own fixtures). */
    fun line(kind: String, vararg fields: String): String {
        require(KIND.matches(kind) && kind != END) { "not a marker kind: $kind" }
        return buildString {
            append(PREFIX).append(kind)
            for (field in fields) append('|').append(escape(field))
        }
    }

    fun end(count: Int): String = "$PREFIX$END|$count"

    fun parse(output: String): Report {
        val lines = mutableListOf<Line>()
        for (raw in output.split('\n')) {
            val text = raw.removeSuffix("\r")
            if (!text.startsWith(PREFIX)) continue
            val parts = text.substring(PREFIX.length).split('|')
            val kind = parts[0]
            if (!KIND.matches(kind)) return Report.Broken("a malformed marker line: ${text.take(60)}")
            if (kind == END) {
                val count = parts.getOrNull(1)?.toIntOrNull()
                if (parts.size != 2 || count == null) return Report.Broken("a malformed end line: ${text.take(60)}")
                if (count != lines.size) return Report.Broken("the end line counts $count lines, ${lines.size} arrived")
                return Report.Complete(lines)
            }
            lines += Line(kind, parts.drop(1).map(::unescape))
        }
        return Report.Broken("no end line: the report was cut off after ${lines.size} lines")
    }
}
