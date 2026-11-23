package com.csqtt.client

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B6 (D21): one key per install, made at its first use and kept, from which the core derives the device id it sends for
 * each password; it stays on this phone (no export, no backup); and the server's refusal over the device limit stops the
 * run with its own cause, whichever way the core reports it.
 */
class DeviceKeyTest {
    private val stored = stringPreferencesKey("device_key")
    private var made = 0
    private val fresh = { "%032x".format(++made) }

    @Test
    fun `the first use makes the key and every later one reads it`() {
        val prefs = mutablePreferencesOf()
        val first = SettingsStore.installDeviceKey(prefs, fresh)
        assertEquals("00000000000000000000000000000001", first)
        assertEquals(first, prefs[stored])
        assertEquals(first, SettingsStore.installDeviceKey(prefs, fresh))
        assertEquals(1, made)
    }

    @Test
    fun `a stored value that is not a key is replaced`() {
        for (bad in listOf("", "xyz", "0".repeat(31), "0".repeat(33))) {
            val prefs = mutablePreferencesOf(stored to bad)
            val key = SettingsStore.installDeviceKey(prefs, fresh)
            assertTrue(Regex("[0-9a-f]{32}").matches(key))
            assertEquals(key, prefs[stored])
        }
    }

    @Test
    fun `a new key is 16 random bytes in hex`() {
        val key = SettingsStore.installDeviceKey(mutablePreferencesOf())
        assertTrue(key, Regex("[0-9a-f]{32}").matches(key))
        assertFalse(key == SettingsStore.installDeviceKey(mutablePreferencesOf()))
    }

    @Test
    fun `the key stays on this phone`() {
        assertTrue("device_key" in SettingsStore.notBackedUpGlobalKeys)
        assertFalse("device_key" in SettingsStore.backupGlobalKeys.keys)
        // a backup leaves it out, and a restore leaves this phone's in place, even over a file that carries one
        val key = "000102030405060708090a0b0c0d0e0f"
        val phone = mutablePreferencesOf(stored to key)
        val json = StoreBackup.encode(phone, serverSecrets = true, decrypt = { it }, exportedAt = 1L)
        assertFalse(json.contains(key))
        val planted = org.json.JSONObject(json).also { root ->
            (root.optJSONObject("settings") ?: org.json.JSONObject().also { root.put("settings", it) })
                .put("device_key", "f".repeat(32))
        }.toString()
        StoreBackup.restore(phone, planted, { it }, { it })
        assertEquals(key, phone[stored])
    }

    @Test
    fun `the device limit's refusal maps to its own cause`() {
        // the core's fatal event, and the workers' copies of the refusal in the log, which the app also reads
        assertEquals(StopCause.DEVICE_LIMIT, StopCause.deviceLimit("FATAL_AUTH: достигнут лимит устройств для этого пароля"))
        assertEquals(
            StopCause.DEVICE_LIMIT,
            StopCause.deviceLimit(
                "[ВОРКЕР #3] Ошибка подключения, изолированный повтор через 2s: AUTH_RETRY: достигнут лимит устройств для этого пароля",
            ),
        )
        assertEquals(
            StopCause.DEVICE_LIMIT,
            StopCause.deviceLimit(
                "[ВОРКЕР #4] Нефатальная ошибка разбора TUNCONF: FATAL_AUTH: достигнут лимит устройств для этого пароля, продолжаем ожидание",
            ),
        )
        assertNull(StopCause.deviceLimit("FATAL_AUTH: неверный пароль подключения"))
        assertNull(StopCause.deviceLimit("доступ отклонён сервером (server_busy)"))
        // the cause is stored and announced masked, as every cause is: nothing in it is taken for a secret
        assertEquals(StopCause.DEVICE_LIMIT, StopCause.masked(StopCause.DEVICE_LIMIT, listOf("H".repeat(20))))
    }
}
