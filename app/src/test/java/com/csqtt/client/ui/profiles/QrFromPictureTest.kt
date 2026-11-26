package com.csqtt.client.ui.profiles

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * «Из галереи» (P3-PLAN §1.7): a QR code in a saved picture is read by the reader the camera uses — dark on light as it
 * is printed, light on dark as a dark-theme screenshot has it, with other things around it; a picture without a code
 * gives nothing.
 */
class QrFromPictureTest {
    private val link = "csqtt://connect?v=2&host=198.51.100.77&peer=46000&password=pw&hashes=${"E".repeat(20)}"

    /** The code for [text] in the middle of a picture [side] px wide, [dark] modules on a [light] ground. */
    private fun picture(text: String, side: Int = 600, dark: Int = 0xFF000000.toInt(), light: Int = 0xFFFFFFFF.toInt()): IntArray {
        val code = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 360, 360)
        val pixels = IntArray(side * side) { light }
        val offset = (side - 360) / 2
        for (y in 0 until 360) for (x in 0 until 360) if (code[x, y]) pixels[(y + offset) * side + x + offset] = dark
        return pixels
    }

    @Test fun aCodeInAPictureIsRead() {
        assertEquals(link, decodeQrPixels(600, 600, picture(link)))
    }

    @Test fun aLightCodeOnADarkGroundIsReadToo() {
        assertEquals(link, decodeQrPixels(600, 600, picture(link, dark = 0xFFE8EAED.toInt(), light = 0xFF1A1D23.toInt())))
    }

    @Test fun aPictureWithoutACodeGivesNothing() {
        assertNull(decodeQrPixels(200, 200, IntArray(200 * 200) { if ((it / 200 + it % 200) % 7 == 0) 0xFF202020.toInt() else 0xFFF0F0F0.toInt() }))
    }

    @Test fun aCertificatesFingerprintIsShownByItsEnds() {
        assertEquals("3F:9A:0C:…:E1:C2", fingerprintShort("3f9a0c" + "00".repeat(27) + "e1c2"))
        assertEquals("AB:CD", fingerprintShort(" abcd "))
    }
}
