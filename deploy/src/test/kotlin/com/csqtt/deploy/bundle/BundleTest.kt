// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.csqtt.deploy.bundle

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class BundleTest {
    private val dir = File(checkNotNull(System.getProperty("csqtt.bundle.dir")) { "run through Gradle: csqtt.bundle.dir is unset" })

    @Test
    fun theShippedListIsTheWholeDirectory() {
        val onDisk = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.sorted().toList()
        assertEquals(onDisk, Bundle.FILES.sorted())
    }

    @Test
    fun theShippedBytesAreTheSourceBytes() {
        for (name in Bundle.FILES) assertArrayEquals(name, File(dir, name).readBytes(), Bundle.bytes(name))
    }

    // M-146: a CRLF or a BOM breaks sh on the box; every file is plain UTF-8 with LF line ends and a final newline
    @Test
    fun everyFileIsPlainUtf8WithLfLineEnds() {
        for (name in Bundle.FILES) {
            val bytes = Bundle.bytes(name)
            val bom = bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
            assertFalse("$name starts with a BOM", bom)
            assertFalse("$name has a CR", bytes.contains('\r'.code.toByte()))
            assertTrue("$name does not end with a newline", bytes.isNotEmpty() && bytes.last() == '\n'.code.toByte())
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
        }
    }
}
