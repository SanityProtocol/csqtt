package com.csqtt.client

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** B5b1, M-37: saving an unchanged secret writes nothing, so it wakes no collector. */
class PutSecretNoOpTest {
    private val encrypted = stringPreferencesKey("deploy_password_encrypted_4")
    private val legacy = stringPreferencesKey("deploy_password_4")
    private var encryptions = 0
    private val encrypt = { value: String -> "enc${++encryptions}:$value" }
    private val decrypt = { stored: String? -> stored?.substringAfter(':') }

    @Test
    fun `an unchanged value keeps its ciphertext and calls no cipher`() {
        val prefs = mutablePreferencesOf()
        prefs.putSecretValue(encrypted, legacy, "secret", encrypt, decrypt)
        assertEquals("enc1:secret", prefs[encrypted])
        val before = prefs.toPreferences()
        prefs.putSecretValue(encrypted, legacy, "secret", encrypt, decrypt)
        assertEquals(1, encryptions)
        assertEquals(before, prefs.toPreferences())
    }

    @Test
    fun `a changed value is encrypted again, a blank one removes, and the legacy copy always goes`() {
        val prefs = mutablePreferencesOf()
        prefs[legacy] = "plain"
        prefs.putSecretValue(encrypted, legacy, "one", encrypt, decrypt)
        assertNull(prefs[legacy])
        prefs.putSecretValue(encrypted, legacy, "two", encrypt, decrypt)
        assertEquals("enc2:two", prefs[encrypted])
        prefs[legacy] = "plain"
        prefs.putSecretValue(encrypted, legacy, "two", encrypt, decrypt)
        assertEquals("enc2:two", prefs[encrypted])
        assertNull(prefs[legacy])
        prefs.putSecretValue(encrypted, legacy, " ", encrypt, decrypt)
        assertNull(prefs[encrypted])
    }

    @Test
    fun `a ciphertext that no longer decrypts is replaced`() {
        val prefs = mutablePreferencesOf()
        prefs[encrypted] = "garbage"
        prefs.putSecretValue(encrypted, legacy, "secret", encrypt) { error("keystore key lost") }
        assertEquals("enc1:secret", prefs[encrypted])
    }
}
