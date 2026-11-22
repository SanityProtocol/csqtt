package com.csqtt.client

import androidx.datastore.preferences.core.stringPreferencesKey
import com.csqtt.client.Store253Snapshots.load
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** B5h2 (U3, rule 10): a subscription refresh's last outcome is stored and said on «Подключение», not only logged. */
class SubscriptionOutcomeTest {
    private val url = "https://sub.example.org/s/abc"

    /** Profile 3 came from the subscription; profile 4 did not. */
    private fun prefs() = load("""{"strings": {"subscription_url_3": "$url", "peer_4": "203.0.113.4"}}""").apply {
        this[stringPreferencesKey("subscription_meta_v1")] = JSONObject()
            .put(url, JSONObject().put("url", url).put("title", "Мой VPN").put("autoUpdateMinutes", 60).put("lastAttemptAt", 1_000L))
            .toString()
    }

    @Test
    fun aNewFailureIsStoredAndAnnouncedOnce() {
        val prefs = prefs()
        assertTrue(SettingsStore.writeSubscriptionOutcome(prefs, url, "Ошибка сети: timeout", ""))
        assertEquals("Ошибка сети: timeout", SettingsStore.subscriptionNoticeOf(prefs, 3)?.lastError)
        assertFalse("the same failure again is not announced", SettingsStore.writeSubscriptionOutcome(prefs, url, "Ошибка сети: timeout", ""))
        assertTrue("another failure is", SettingsStore.writeSubscriptionOutcome(prefs, url, "Сервер вернул 503", ""))
    }

    @Test
    fun theSameFailureWithOtherPortsOrPointersIsNotNew() {
        val prefs = prefs()
        val timeout = { port: Int -> "Ошибка сети: failed to connect to /203.0.113.2 (port 443) from /10.0.0.5 (port $port) after 10000ms" }
        assertTrue(SettingsStore.writeSubscriptionOutcome(prefs, url, timeout(51234), ""))
        assertFalse(SettingsStore.writeSubscriptionOutcome(prefs, url, timeout(51999), ""))
        assertFalse(SettingsStore.writeSubscriptionOutcome(prefs, url, timeout(40001) + " ", ""))
        assertTrue(SettingsStore.writeSubscriptionOutcome(prefs, url, "Ошибка TLS: SSL handshake aborted: ssl=0x7c8f6a5d48", ""))
        assertFalse(SettingsStore.writeSubscriptionOutcome(prefs, url, "Ошибка TLS: SSL handshake aborted: ssl=0x7d10aa0c00", ""))
    }

    @Test
    fun whatARefreshKeepsIsMasked() {
        val (error, skipped) = subscriptionOutcomeTexts(false, "Ошибка сети: token=SECRET123 csqtt://203.0.113.9:443/x", 0)
        assertFalse(error, error.contains("SECRET123") || error.contains("203.0.113.9"))
        assertEquals("", skipped)
        val ok = subscriptionOutcomeTexts(true, "Подписка — добавлено: 1, пропущено: 2 (нет свободного профиля: 2)", 2)
        assertEquals("" to "добавлено: 1, пропущено: 2 (нет свободного профиля: 2)", ok)
        assertEquals("" to "", subscriptionOutcomeTexts(true, "Подписка — обновлено: 3", 0))
    }

    @Test
    fun anotherSubscriptionsFailureShowsWhenTheOpenProfileHasNone() {
        val prefs = prefs()
        SettingsStore.writeSubscriptionOutcome(prefs, url, "Сервер вернул 503", "")
        // profile 4 came from no subscription: the failing one its notice was about is shown
        assertEquals(url, SettingsStore.subscriptionNoticeOf(prefs, 4)?.url)
        // skipped nodes alone stay with their own profile
        SettingsStore.writeSubscriptionOutcome(prefs, url, "", "пропущено: 1")
        assertNull(SettingsStore.subscriptionNoticeOf(prefs, 4))
    }

    @Test
    fun aSuccessClearsItAndKeepsTheRest() {
        val prefs = prefs()
        SettingsStore.writeSubscriptionOutcome(prefs, url, "Сервер вернул 503", "")
        assertFalse(SettingsStore.writeSubscriptionOutcome(prefs, url, "", ""))
        assertNull(SettingsStore.subscriptionNoticeOf(prefs, 3))
        // the outcome fields sit beside the record's own, which stay as they were
        val meta = JSONObject(prefs[stringPreferencesKey("subscription_meta_v1")]!!).getJSONObject(url)
        assertEquals(60, meta.getInt("autoUpdateMinutes"))
        assertEquals("Мой VPN", meta.getString("title"))
        assertFalse(meta.has("lastError"))
    }

    @Test
    fun skippedNodesAreSaidButNotAnnounced() {
        val prefs = prefs()
        assertFalse(SettingsStore.writeSubscriptionOutcome(prefs, url, "", "пропущено: 2 (нет свободного профиля: 2)"))
        val meta = SettingsStore.subscriptionNoticeOf(prefs, 3)!!
        assertEquals("Подписка «Мой VPN»: пропущено: 2 (нет свободного профиля: 2) · 14:05", subscriptionNoticeLine(meta) { "14:05" })
    }

    @Test
    fun theLineForTheOpenProfilesSubscription() {
        val prefs = prefs()
        assertNull("nothing to say before a refresh", SettingsStore.subscriptionNoticeOf(prefs, 3))
        SettingsStore.writeSubscriptionOutcome(prefs, url, "Ошибка сети: timeout", "")
        assertEquals(
            "Подписка «Мой VPN» не обновилась: Ошибка сети: timeout · 14:05",
            subscriptionNoticeLine(SettingsStore.subscriptionNoticeOf(prefs, 3)!!) { "14:05" },
        )
        assertNull(subscriptionNoticeLine(SubscriptionMeta(url = url, title = "Мой VPN")))
    }

    @Test
    fun theTimeIsTodaysOrDated() {
        val utc = TimeZone.getTimeZone("UTC")
        val now = 1_763_800_000_000L // 2025-11-22 08:26:40 UTC
        assertEquals("08:20", noticeTime(now - 400_000L, now, utc))
        assertEquals("21.11 08:26", noticeTime(now - 86_400_000L, now, utc))
    }
}
