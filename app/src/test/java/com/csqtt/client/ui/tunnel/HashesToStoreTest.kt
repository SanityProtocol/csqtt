package com.csqtt.client.ui.tunnel

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.csqtt.client.SettingsStore
import com.csqtt.client.VkHashValidationCodec
import com.csqtt.client.VkHashValidationStatus
import com.csqtt.client.measureSubnetRtt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** P3 B3: what «Хеши звонков VK» stores of its fields, and what «TURN-подсети» measures of a subnet. */
class HashesToStoreTest {
    @Test
    fun `a hash entered again loses what the last check said of it, and no other does`() {
        val a = "A".repeat(20); val b = "B".repeat(20); val c = "C".repeat(20)
        val results = mapOf(a to VkHashValidationStatus.Invalid, b to VkHashValidationStatus.Valid)
        val key = stringPreferencesKey("vk_hash_check_results_7")
        fun store() = mutablePreferencesOf(key to VkHashValidationCodec.encode(results))
        fun left(forget: Set<String>, profile: Int = 7) = store().also { SettingsStore.forgetVkHashCheckResultsKeys(it, profile, forget) }
            .let { VkHashValidationCodec.decode(it[key] ?: "{}") }
        assertEquals(mapOf(b to VkHashValidationStatus.Valid), left(setOf(a)))
        assertEquals(emptyMap<String, VkHashValidationStatus>(), left(setOf(a, b, c)))
        assertEquals("one the check never spoke of: nothing is rewritten", results, left(setOf(c)))
        assertEquals(results, left(emptySet()))
        assertEquals("another profile's are its own", results, left(setOf(a), profile = 8))
    }

    private val a = "A".repeat(20)
    private val b = "B".repeat(20)

    @Test
    fun `each hash is stored once, a link to a call as its hash, and what is too short not at all`() {
        assertEquals("$a,$b", hashesToStore(listOf(a, "", b, "", "", "")))
        assertEquals("one entered twice is one", a, hashesToStore(listOf(a, a, "", "", "", "")))
        assertEquals("a call's link is its hash", "$a,$b", hashesToStore(listOf("https://vk.com/call/join/$a", b)))
        assertEquals("the link and the hash of one call are one", a, hashesToStore(listOf("https://vk.com/call/join/$a", a)))
        assertEquals("too short to be a hash", b, hashesToStore(listOf("short", b)))
        assertEquals("every field emptied: nothing is stored", "", hashesToStore(List(6) { "" }))
    }

    @Test
    fun `a subnet's ping is the mean of its first and its last three servers, and none when none answers`() {
        val ips = (1..8).map { "192.0.2.$it" }
        assertNull(measureSubnetRtt(emptyList()) { 10.0 })
        assertNull("no server answered", measureSubnetRtt(ips) { null })
        assertEquals(10.0, measureSubnetRtt(ips) { 10.0 }!!, 0.001)
        // the first three answer in 10 ms, the last three in 30: both ends count alike
        val asked = mutableListOf<String>()
        val rtt = measureSubnetRtt(ips) { ip ->
            asked += ip
            if (ip.substringAfterLast('.').toInt() <= 3) 10.0 else 30.0
        }
        assertEquals(20.0, rtt!!, 0.001)
        assertEquals("the first three and the last three, none of the middle", listOf(1, 2, 3, 6, 7, 8), asked.map { it.substringAfterLast('.').toInt() })
        // one end silent: the other end's mean
        assertEquals(30.0, measureSubnetRtt(ips) { ip -> if (ip.substringAfterLast('.').toInt() <= 3) null else 30.0 }!!, 0.001)
    }
}
