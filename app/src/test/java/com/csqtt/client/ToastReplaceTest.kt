package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Test

/** M-90 (B5g): one toast at a time — a new one cancels the one shown before it instead of queueing behind it. */
class ToastReplaceTest {
    @Test
    fun `each toast cancels the one before it, then shows`() {
        val events = mutableListOf<String>()
        val slot = ReplacingSlot<String>(cancel = { events += "cancel $it" }, show = { events += "show $it" })
        slot.replace("copied")
        slot.replace("checking")
        slot.replace("up to date")
        assertEquals(
            listOf("show copied", "cancel copied", "show checking", "cancel checking", "show up to date"),
            events,
        )
    }
}
