package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
