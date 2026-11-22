package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M-86 (B5g): the core's PROBE answer as the app reads it, and the line «Подключение» shows for it. */
class ProbeEventTest {
    private fun parse(json: String) = TunnelEventParser.parse(CsqttConstants.General.CSQTT_EVENT_PREFIX + "PROBE|" + json)

    @Test
    fun theCoresAnswersAreRead() {
        assertEquals(
            TunnelEventParser.Event.Probe("77.88.8.8", 53, 84, "synack"),
            parse("""{"target":"77.88.8.8","port":53,"ms":84,"result":"synack"}"""),
        )
        assertEquals(
            TunnelEventParser.Event.Probe("10.31.0.1", 53, 41, "rst"),
            parse("""{"target":"10.31.0.1","port":53,"ms":41,"result":"rst"}"""),
        )
        // no time without an answer: the core writes null
        val timeout = parse("""{"target":"77.88.8.8","port":53,"ms":null,"result":"timeout"}""") as TunnelEventParser.Event.Probe
        assertNull(timeout.ms)
        assertEquals("timeout", timeout.result)
        assertEquals("invalid", (parse("""{"target":null,"port":null,"ms":null}""") as TunnelEventParser.Event.Probe).result)
    }

    @Test
    fun theLineSaysTheTimeOrWhatWentWrong() {
        val ok = TunnelProbe.Done("synack", 84, "77.88.8.8")
        assertEquals("Пинг через туннель: 84 мс", probeLine(ok))
        assertFalse(ok.failed)
        val closed = TunnelProbe.Done("rst", 41, "10.31.0.1")
        assertEquals("Пинг через туннель: 41 мс (порт закрыт, путь работает)", probeLine(closed))
        assertFalse(closed.failed)
        assertEquals("Пинг: нет ответа за 5 с", probeLine(TunnelProbe.Done("timeout", null, "77.88.8.8")))
        assertEquals("Пинг: нет активных потоков", probeLine(TunnelProbe.Done("no_workers", null, "77.88.8.8")))
        assertTrue(TunnelProbe.Done("timeout", null, "x").failed)
        assertTrue(TunnelProbe.Done("invalid", null, "x").failed)
    }
}
