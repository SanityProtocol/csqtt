package com.csqtt.client.ui.shell

import androidx.lifecycle.SavedStateHandle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * §4.2 rule 2 and M-174: a password being typed is kept in memory only. It is in nothing the navigation saves, a new
 * model (a recreate) still finds it, and the form that is done takes its own drafts away and no other form's.
 */
class SecretDraftNeverSavedTest {
    private val secret = "correct horse battery staple"

    @After
    fun clear() = SecretDrafts.clear("")

    @Test
    fun `a secret draft is in no value the navigation saves`() {
        val handle = SavedStateHandle()
        val nav = NavModel(handle)
        nav.select(Tab.Server)
        nav.open(Dest("vps-access", Layer.Sheet, "7"))
        SecretDrafts["vps-access:7:ssh-password"] = secret
        val saved = handle.keys().map { it to handle.get<Any?>(it) }
        assertFalse(saved.toString(), saved.toString().contains(secret))
        assertFalse(saved.toString().contains("ssh-password"))
    }

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
