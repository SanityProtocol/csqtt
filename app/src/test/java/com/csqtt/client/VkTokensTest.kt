package com.csqtt.client

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val TOKEN = "vk1.a." + "Ab3_-".repeat(10)

/** M-82 / D30: a permanent token is taken, an expiring one asked again once and then refused, on both sign-in paths. */
class ExpiringTokenRefusedTest {
    @Test fun permanentTakenExpiringRetriedOnceThenRefused() {
        assertEquals(VkTokenDecision.Accept, VkTokenPolicy.decide(expiresIn = 0, retried = false))
        assertEquals(VkTokenDecision.Accept, VkTokenPolicy.decide(expiresIn = 0, retried = true))
        assertEquals(VkTokenDecision.Retry, VkTokenPolicy.decide(expiresIn = 86_400, retried = false))
        assertEquals(VkTokenDecision.RefuseExpiring, VkTokenPolicy.decide(expiresIn = 86_400, retried = true))
    }
}

/** M-82: the parser behind P3's paste field. */
class PastedTokenParseTest {
    @Test fun aBareTokenOrTheSignInsAddressIsRead() {
        assertEquals(VkTokenInput(TOKEN, "", 0), VkTokenParse.parse("  $TOKEN \n"))
        val url = "https://oauth.vk.com/blank.html#access_token=$TOKEN&expires_in=0&user_id=12345"
        assertEquals(VkTokenInput(TOKEN, "12345", 0), VkTokenParse.parse(url))
        val expiring = "https://oauth.vk.ru/blank.html#access_token=$TOKEN&expires_in=86400&user_id=7"
        assertEquals(86_400L, VkTokenParse.parse(expiring)?.expiresIn)
    }

    @Test fun anythingElseIsRefused() {
        for (text in listOf("", "hello", "vk1.a.short", "$TOKEN extra", "https://oauth.vk.com/blank.html#error=access_denied",
            "https://oauth.vk.com/blank.html#access_token=not-a-token&user_id=1")) {
            assertNull(text, VkTokenParse.parse(text))
        }
    }
}

/** M-79: accounts archived by user id; a removal drops only that account; a non-numeric id is never archived. */
class VkAccountArchiveTest {
    @Test fun accountsAreKeptByUserIdAndRemovedOneByOne() {
        var json = ""
        json = VkAccounts.put(json, "12345", "enc-a", 0L)
        json = VkAccounts.put(json, "777", "enc-b", 1_800_000_000_000L)
        json = VkAccounts.put(json, "12345", "enc-a2", 0L)   // a new sign-in replaces the account's token
        assertEquals(listOf("12345", "777"), VkAccounts.ids(json))
        assertEquals("enc-a2", org.json.JSONObject(json).getJSONObject("12345").getString("t"))
        json = VkAccounts.remove(json, "777")
        assertEquals(listOf("12345"), VkAccounts.ids(json))
        assertEquals(json, VkAccounts.put(json, "", "x", 0L))
        assertEquals(json, VkAccounts.put(json, "id12", "x", 0L))
        assertEquals(emptyList<String>(), VkAccounts.ids("not json"))
    }

    // P3 B3, «Аккаунты VK»: a profile takes an archived account without a new sign-in
    private val archive = stringPreferencesKey("vk_accounts_v1")
    private fun enc(value: String) = "enc($value)"
    private fun dec(value: String?) = value?.removePrefix("enc(")?.removeSuffix(")")

    private fun archived(): androidx.datastore.preferences.core.MutablePreferences = mutablePreferencesOf().also { prefs ->
        prefs[archive] = VkAccounts.put(VkAccounts.put("", "12345", enc("token-a"), 0L), "777", enc("token-b"), 0L)
    }

    @Test fun aProfileTakesAnArchivedAccountsTokenAndItsId() {
        val prefs = archived()
        assertTrue(SettingsStore.selectVkAccountKeys(prefs, 4, "777", ::enc, ::dec))
        assertEquals(enc("token-b"), prefs[stringPreferencesKey("vk_access_token_encrypted_4")])
        assertEquals("777", prefs[stringPreferencesKey("vk_access_token_user_id_4")])
        assertNull("another profile is not touched", prefs[stringPreferencesKey("vk_access_token_encrypted")])
        assertEquals("the archive is as it was", listOf("12345", "777"), VkAccounts.ids(prefs[archive]!!))
    }

    @Test fun anAccountThatIsNotThereOrCannotBeReadChangesNothing() {
        val prefs = archived()
        val before = prefs.asMap()
        assertFalse(SettingsStore.selectVkAccountKeys(prefs, 4, "999", ::enc, ::dec))
        assertFalse("the keystore lost its key", SettingsStore.selectVkAccountKeys(prefs, 4, "777", ::enc) { null })
        assertFalse(SettingsStore.selectVkAccountKeys(prefs, 4, "777", ::enc) { error("keystore") })
        assertEquals(before, prefs.asMap())
    }

    @Test fun aRemovedAccountLeavesTheArchiveAndTheProfileThatIsOnIt() {
        val prefs = archived()
        SettingsStore.selectVkAccountKeys(prefs, 4, "777", ::enc, ::dec)
        SettingsStore.selectVkAccountKeys(prefs, 5, "777", ::enc, ::dec)
        SettingsStore.selectVkAccountKeys(prefs, 6, "12345", ::enc, ::dec)

        SettingsStore.removeVkAccountKeys(prefs, 4, "777")
        assertEquals(listOf("12345"), VkAccounts.ids(prefs[archive]!!))
        assertNull(prefs[stringPreferencesKey("vk_access_token_encrypted_4")])
        assertNull(prefs[stringPreferencesKey("vk_access_token_user_id_4")])
        assertEquals("a profile that holds the token keeps it", enc("token-b"), prefs[stringPreferencesKey("vk_access_token_encrypted_5")])

        // an account removed from a profile that is on another one: only the archive changes
        SettingsStore.removeVkAccountKeys(prefs, 5, "12345")
        assertEquals(emptyList<String>(), VkAccounts.ids(prefs[archive]!!))
        assertEquals(enc("token-b"), prefs[stringPreferencesKey("vk_access_token_encrypted_5")])
        assertEquals(enc("token-a"), prefs[stringPreferencesKey("vk_access_token_encrypted_6")])
    }
}

/**
 * Card p3-vk-run-account: a token VK refused is cleared where it is still held, and nowhere else. A running tunnel makes
 * its calls with the account it started with, so its profile may hold another account by the time VK refuses the run's.
 */
class RefusedTokenTest {
    private val archive = stringPreferencesKey("vk_accounts_v1")
    private fun enc(value: String) = "enc($value)"
    private fun dec(value: String?) = value?.removePrefix("enc(")?.removeSuffix(")")
    private fun held(profile: Int) = stringPreferencesKey("vk_access_token_encrypted_$profile")
    private fun id(profile: Int) = stringPreferencesKey("vk_access_token_user_id_$profile")

    // two accounts signed in on the phone: 12345 holds token-a, 777 holds token-b
    private fun archived(): androidx.datastore.preferences.core.MutablePreferences = mutablePreferencesOf().also { prefs ->
        prefs[archive] = VkAccounts.put(VkAccounts.put("", "12345", enc("token-a"), 0L), "777", enc("token-b"), 0L)
    }

    @Test fun theProfileThatStillHoldsTheRefusedTokenLosesItAndTheAccountLeavesTheArchive() {
        val prefs = archived()
        SettingsStore.selectVkAccountKeys(prefs, 4, "777", ::enc, ::dec)
        SettingsStore.dropRefusedVkTokenKeys(prefs, 4, "token-b", ::dec)
        assertNull(prefs[held(4)])
        assertNull(prefs[id(4)])
        assertEquals("the other account is as it was", listOf("12345"), VkAccounts.ids(prefs[archive]!!))
    }

    @Test fun anAccountPickedUnderTheTunnelStaysWhenVkRefusesTheOneTheTunnelRanOn() {
        // the run started on 777; with the tunnel on the user picked 12345 for the next connection
        val prefs = archived()
        SettingsStore.selectVkAccountKeys(prefs, 4, "12345", ::enc, ::dec)
        SettingsStore.dropRefusedVkTokenKeys(prefs, 4, "token-b", ::dec)
        assertEquals("the profile keeps the account it holds by now", enc("token-a"), prefs[held(4)])
        assertEquals("12345", prefs[id(4)])
        assertEquals("the refused account is not offered again, the stored one is", listOf("12345"), VkAccounts.ids(prefs[archive]!!))
    }

    @Test fun aPastedTokenWithNoAccountGoesFromItsProfileAlone() {
        val prefs = archived()
        prefs[held(4)] = enc("token-c")
        val before = prefs[archive]
        SettingsStore.dropRefusedVkTokenKeys(prefs, 4, "token-c", ::dec)
        assertNull(prefs[held(4)])
        assertEquals(before, prefs[archive])
    }

    @Test fun aTokenStoredBeforeEncryptionIsTheRefusedOneToo() {
        val prefs = archived()
        prefs[stringPreferencesKey("vk_access_token_4")] = "token-b"
        prefs[id(4)] = "777"
        SettingsStore.dropRefusedVkTokenKeys(prefs, 4, "token-b", ::dec)
        assertNull(prefs[stringPreferencesKey("vk_access_token_4")])
        assertNull(prefs[id(4)])
        assertEquals(listOf("12345"), VkAccounts.ids(prefs[archive]!!))
    }

    @Test fun aProfileOnAnOlderTokenLosesItAndTheAccountsNewerTokenStaysInTheArchive() {
        // account 777 was signed in to again from another profile: the archive holds its newer token, this profile the
        // older one. VK refuses the older: it goes from the profile, and the account is still offered with its newer one
        val prefs = archived()
        prefs[held(4)] = enc("token-old")
        prefs[id(4)] = "777"
        SettingsStore.dropRefusedVkTokenKeys(prefs, 4, "token-old", ::dec)
        assertNull(prefs[held(4)])
        assertNull(prefs[id(4)])
        assertEquals(listOf("12345", "777"), VkAccounts.ids(prefs[archive]!!))
        assertEquals(enc("token-b"), VkAccounts.encryptedToken(prefs[archive]!!, "777"))
    }

    @Test fun anotherProfileOnTheSameAccountKeepsTheTokenItHolds() {
        val prefs = archived()
        SettingsStore.selectVkAccountKeys(prefs, 4, "777", ::enc, ::dec)
        SettingsStore.selectVkAccountKeys(prefs, 5, "777", ::enc, ::dec)
        SettingsStore.dropRefusedVkTokenKeys(prefs, 4, "token-b", ::dec)
        assertEquals(enc("token-b"), prefs[held(5)])
        assertEquals("777", prefs[id(5)])
    }

    @Test fun whatIsNotTheRefusedTokenIsNotTouched() {
        val prefs = archived()
        SettingsStore.selectVkAccountKeys(prefs, 4, "777", ::enc, ::dec)
        val before = prefs.asMap()
        SettingsStore.dropRefusedVkTokenKeys(prefs, 4, "token-z", ::dec)
        SettingsStore.dropRefusedVkTokenKeys(prefs, 4, "", ::dec)
        SettingsStore.dropRefusedVkTokenKeys(prefs, 4, "  ", ::dec)
        // an entry the keystore cannot read is not the refused one, whether it answers nothing or fails
        SettingsStore.dropRefusedVkTokenKeys(prefs, 4, "token-b") { null }
        SettingsStore.dropRefusedVkTokenKeys(prefs, 4, "token-b") { error("keystore") }
        // no token is no refused token: a profile that holds none keeps what it has
        prefs[id(6)] = "777"
        val withSix = prefs.asMap()
        SettingsStore.dropRefusedVkTokenKeys(prefs, 6, "", ::dec)
        assertEquals(withSix, prefs.asMap())
        prefs.remove(id(6))
        assertEquals(before, prefs.asMap())
    }

    @Test fun theArchiveLosesOnlyTheAccountsThatHoldTheNamedToken() {
        var json = VkAccounts.put(VkAccounts.put("", "12345", "enc-a", 0L), "777", "enc-b", 0L)
        json = VkAccounts.put(json, "900", "enc-b", 0L)   // the same token under two ids
        assertEquals(listOf("12345"), VkAccounts.ids(VkAccounts.removeHolding(json) { it == "enc-b" }))
        assertEquals("nothing named: the stored text itself comes back", json, VkAccounts.removeHolding(json) { false })
        assertEquals("not json", VkAccounts.removeHolding("not json") { true })
    }
}
