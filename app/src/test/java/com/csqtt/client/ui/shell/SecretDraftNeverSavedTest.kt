package com.csqtt.client.ui.shell

import androidx.lifecycle.SavedStateHandle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §4.2 rule 2 and M-174: a password being typed is kept in memory only, in [SecretDrafts], which has no way to the saved
 * state. That nothing of it reaches what the system keeps of the activity is NavRestoreTest's check, on the bytes of the
 * saved state; here: a new model (a recreate) still finds the draft, and the form that is done takes its own drafts
 * away and no other form's.
 */
class SecretDraftNeverSavedTest {
    private val secret = "correct horse battery staple"

    @After
    fun clear() = SecretDrafts.clear("")

    @Test
    fun `the draft outlives the model, as a recreate does, and is gone when its form clears it`() {
        SecretDrafts["vps-access:7:ssh-password"] = secret
        SecretDrafts["vps-access:7:sudo"] = "s"
        SecretDrafts["tunnel:2:password"] = "other"
        NavModel(SavedStateHandle())
        assertEquals(secret, SecretDrafts["vps-access:7:ssh-password"])

        SecretDrafts.clear("vps-access:7:")
        assertEquals("", SecretDrafts["vps-access:7:ssh-password"])
        assertEquals("", SecretDrafts["vps-access:7:sudo"])
        assertEquals("another form's draft stays", "other", SecretDrafts["tunnel:2:password"])
    }

    @Test
    fun `an emptied field holds nothing`() {
        SecretDrafts["k"] = secret
        SecretDrafts["k"] = ""
        assertEquals("", SecretDrafts["k"])
    }
}
