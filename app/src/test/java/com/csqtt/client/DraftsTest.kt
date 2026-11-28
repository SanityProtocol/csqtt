package com.csqtt.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Card p3-apply-bar, the review's F1: «Переподключить» reads the store, so what a screen holds and has not stored yet —
 * a field still focused, a sheet's draft — is stored first, and the tap waits for it. A reconnect made a moment after
 * typing took the value before it, and the typed one came as one more change to apply.
 */
class DraftsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun end() = scope.cancel()

    @Test
    fun `every holder is asked, and its write is in the store before the asker goes on`() = runBlocking {
        val drafts = Drafts()
        val stored = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        drafts.hold("address") { drafts.write(scope) { gate.await(); synchronized(stored) { stored += "address" } } }
        drafts.hold("port") { drafts.write(scope) { gate.await(); synchronized(stored) { stored += "port" } } }
        val asked = async { drafts.storeAll() }
        // the writes are under way and not done: the asker waits
        repeat(20) { yield() }
        assertFalse("the asker went on before the writes were done", asked.isCompleted)
        gate.complete(Unit)
        withTimeout(5_000) { asked.await() }
        assertEquals(setOf("address", "port"), synchronized(stored) { stored.toSet() })
    }

    @Test
    fun `a holder that is gone is not asked, and one with nothing to store costs nothing`() = runBlocking {
        val drafts = Drafts()
        var asked = 0
        drafts.hold("gone") { asked += 100 }
        drafts.hold("idle") { asked += 1 }
        drafts.release("gone")
        withTimeout(5_000) { drafts.storeAll() }
        assertEquals(1, asked)
    }

    @Test
    fun `a write begun before the asking is waited for too`() = runBlocking {
        // a field left a moment before the tap: its write may still be on its way
        val drafts = Drafts()
        val gate = CompletableDeferred<Unit>()
        var done = false
        drafts.write(scope) { gate.await(); done = true }
        val asked = async { drafts.storeAll() }
        repeat(20) { yield() }
        assertFalse(asked.isCompleted)
        gate.complete(Unit)
        withTimeout(5_000) { asked.await() }
        assertTrue(done)
        // and one that is done is forgotten: the next asking does not wait on it
        withTimeout(5_000) { drafts.storeAll() }
    }

    @Test
    fun `a holder asked again under the same key takes the first one's place`() = runBlocking {
        val drafts = Drafts()
        val asked = mutableListOf<String>()
        drafts.hold("field") { asked += "old" }
        drafts.hold("field") { asked += "new" }
        withTimeout(5_000) { drafts.storeAll() }
        assertEquals(listOf("new"), asked)
    }
}
