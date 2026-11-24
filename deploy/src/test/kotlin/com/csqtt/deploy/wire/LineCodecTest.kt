// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.csqtt.deploy.wire

import com.csqtt.deploy.bundle.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class LineCodecTest {
    // every character the codec escapes, UTF-8 it must leave as is (alone, and beside an escape, where the decode goes
    // through bytes), an already escaped-looking value, and an empty field last
    private val awkward = listOf("a|b", "100%", "line1\nline2\r", "путь/с пробелом", "%7C stays %7C", "п|р😀", "")

    // the bytes both halves must print for `awkward`: a CR left raw would still parse, so the line itself is pinned
    private val awkwardLine = "CSQTT_INV|a%7Cb|100%25|line1%0Aline2%0D|путь/с пробелом|%257C stays %257C|п%7Cр😀|"

    @Test
    fun escapedFieldsRoundTrip() {
        assertEquals(awkwardLine, LineCodec.line("INV", *awkward.toTypedArray()))
        val out = awkwardLine + "\n" + LineCodec.end(1) + "\n"
        assertEquals(Report.Complete(listOf(Line("INV", awkward))), LineCodec.parse(out))
    }

    @Test
    fun noiseAroundTheMarkersIsSkipped() {
        val out = "Welcome to Ubuntu\nLast login: Mon\n" + LineCodec.line("PRE", "tun", "ok") + "\r\nfortune: …\n" +
            LineCodec.end(1) + "\nlogout\n"
        assertEquals(Report.Complete(listOf(Line("PRE", listOf("tun", "ok")))), LineCodec.parse(out))
    }

    @Test
    fun aKindWithoutFieldsHasNone() {
        assertEquals(Report.Complete(listOf(Line("PRE", emptyList()))), LineCodec.parse("CSQTT_PRE\nCSQTT_END|1\n"))
    }

    @Test
    fun aReportCutOffBeforeItsEndLineIsBroken() {
        val report = LineCodec.parse(LineCodec.line("INV", "a") + "\n" + LineCodec.line("INV", "b") + "\n")
        assertTrue(report is Report.Broken)
    }

    @Test
    fun anEndLineThatCountsOtherwiseIsBroken() {
        val report = LineCodec.parse(LineCodec.line("INV", "a") + "\n" + LineCodec.end(2) + "\n")
        assertEquals(Report.Broken("the end line counts 2 lines, 1 arrived"), report)
    }

    @Test
    fun malformedMarkerLinesAreBroken() {
        assertTrue(LineCodec.parse("CSQTT_bad kind|x\nCSQTT_END|1\n") is Report.Broken)
        assertTrue(LineCodec.parse("CSQTT_INV|x\nCSQTT_END|one\n") is Report.Broken)
        assertTrue(LineCodec.parse("CSQTT_INV|x\nCSQTT_END|1|extra\n") is Report.Broken)
    }

    @Test
    fun aStrayPercentIsKeptAsItIs() {
        assertEquals("50%", LineCodec.unescape("50%"))
        assertEquals("%zz and %4", LineCodec.unescape("%zz and %4"))
        assertEquals("ab|", LineCodec.unescape("ab%7c"))
    }

    @Test
    fun theShellHalfSpeaksTheSameCodec() {
        // values are set inside the script file, not passed as arguments: the JVM encodes arguments in the platform charset,
        // which is ASCII in a container without a locale
        val script = """
            v1='a|b'; v2='100%'; v3=${'$'}(printf 'line1\nline2\r'); v4='путь/с пробелом'; v5='%7C stays %7C'
            v6='п|р😀'; v7=''
            csqtt_emit INV "${'$'}v1" "${'$'}v2" "${'$'}v3" "${'$'}v4" "${'$'}v5" "${'$'}v6" "${'$'}v7"
            csqtt_emit PRE
            csqtt_end
        """.trimIndent()
        val out = sh(script)
        // byte for byte what the engine's half prints, then the same report
        assertEquals(awkwardLine + "\n" + LineCodec.line("PRE") + "\n" + LineCodec.end(2) + "\n", out)
        assertEquals(Report.Complete(listOf(Line("INV", awkward), Line("PRE", emptyList()))), LineCodec.parse(out))
    }

    @Test
    fun anEmitFromAPipelineSubshellBreaksTheReport() {
        // marker.sh's warning: the line prints, the count stays behind, and the report must not be trusted
        val script = """
            printf 'x\n' | while read -r line; do csqtt_emit X "${'$'}line"; done
            csqtt_end
        """.trimIndent()
        assertEquals(Report.Broken("the end line counts 0 lines, 1 arrived"), LineCodec.parse(sh(script)))
    }

    private fun sh(body: String): String {
        val dir = kotlin.io.path.createTempDirectory("csqtt-marker").toFile()
        try {
            File(dir, "marker.sh").writeBytes(Bundle.bytes("lib/marker.sh"))
            File(dir, "test.sh").writeText(". ./marker.sh\n$body\n", Charsets.UTF_8)
            // output to a file, not a pipe read to its end: a shell that hangs must meet the timeout, not block the read
            val outFile = File(dir, "out")
            val process = ProcessBuilder("sh", "test.sh").directory(dir).redirectErrorStream(true).redirectOutput(outFile).start()
            process.outputStream.close()
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("sh did not finish in 30 s: ${outFile.readText(Charsets.UTF_8)}")
            }
            val out = outFile.readBytes().toString(Charsets.UTF_8)
            check(process.exitValue() == 0) { "sh failed (${process.exitValue()}): $out" }
            return out
        } finally {
            dir.deleteRecursively()
        }
    }
}
