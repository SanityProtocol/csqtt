package com.csqtt.client

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.csqtt.client.Store253Snapshots.load
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B5h2 (rule 10): the D6 hand-over's last result, a success too, is kept per server and said on «Подключение» for the
 * owner profile that runs on the pool; only a new failure is announced.
 */
class HandOverResultTest {
    private val ok = HandOverResult(true, "Хеши звонков переданы серверу (6)", 1_000L)
    private val refused = HandOverResult(false, "Сервер не принял хеши: пароль панели не подошёл", 2_000L)

    /** Profile 2, own server on VPS record 2, in «Авто API». */
    private fun owner() = load(
        """{"strings": {"peer_2": "203.0.113.2", "deploy_ip_2": "203.0.113.2",
            "vk_hash_mode_2": "${CsqttConstants.VkAutoHash.MODE_AUTO_API}"},
            "ints": {"vps_ref_2": 2}}""",
    )

    @Test
    fun keptOnlyWhileTheRecordHasItsServer() {
        val prefs = load("""{"strings": {"peer_2": "203.0.113.2"}, "ints": {"vps_ref_2": 2}}""")
        assertFalse(SettingsStore.writeHandOverResult(prefs, 2, refused))
        assertNull(prefs[stringPreferencesKey("deploy_vk_pool_handover_2")])
    }

    @Test
    fun onlyANewFailureIsAnnounced() {
        val prefs = owner()
        assertTrue(SettingsStore.writeHandOverResult(prefs, 2, refused))
        assertFalse("the same failure again", SettingsStore.writeHandOverResult(prefs, 2, refused.copy(at = 3_000L)))
        assertFalse("a success", SettingsStore.writeHandOverResult(prefs, 2, ok))
        assertEquals(ok, SettingsStore.handOverNoticeOf(prefs, 2))
        assertTrue("a failure after a success", SettingsStore.writeHandOverResult(prefs, 2, refused))
    }

    @Test
    fun theSameFailureWithAnotherPortIsNotNew() {
        val prefs = owner()
        val timeout = { port: Int ->
            HandOverResult(false, "Хеши не переданы: панель не ответила (адрес, порт или секретный путь) · failed to connect " +
                "to /203.0.113.2 (port 8443) from /10.0.0.5 (port $port) after 8000ms", 1L)
        }
        assertTrue(SettingsStore.writeHandOverResult(prefs, 2, timeout(51234)))
        assertFalse(SettingsStore.writeHandOverResult(prefs, 2, timeout(51999)))
        assertTrue("another failure is new", SettingsStore.writeHandOverResult(prefs, 2, refused))
    }

    @Test
    fun theNoticeTextHasNoLogTagsAndIsMasked() {
        assertEquals("Хеши звонков переданы серверу (6)", handOverNoticeText("[OK] Хеши звонков переданы серверу (6) ✓"))
        val failed = handOverNoticeText("[ERR] Сервер не принял хеши · код=403 password=hunter2")
        assertEquals("Сервер не принял хеши · код=403 password=${LogRedaction.HIDDEN}", failed)
    }

    @Test
    fun anotherOwnerProfilesFailureShowsWhenTheOpenProfileHasNone() {
        val prefs = owner()
        SettingsStore.writeHandOverResult(prefs, 2, refused)
        // profile 5 is no owner profile on a pool: the failure its notice was about is shown
        assertEquals(refused, SettingsStore.handOverNoticeOf(prefs, 5))
        // a success elsewhere is not
        SettingsStore.writeHandOverResult(prefs, 2, ok)
        assertNull(SettingsStore.handOverNoticeOf(prefs, 5))
    }

    @Test
    fun shownForTheOwnerProfileOnThePoolOnly() {
        val prefs = owner()
        SettingsStore.writeHandOverResult(prefs, 2, ok)
        assertEquals(ok, SettingsStore.handOverNoticeOf(prefs, 2))
        assertEquals("Хеши звонков переданы серверу (6) · 09:30", handOverNoticeLine(ok) { "09:30" })

        val manual = owner().apply { this[stringPreferencesKey("vk_hash_mode_2")] = CsqttConstants.VkAutoHash.MODE_MANUAL }
        SettingsStore.writeHandOverResult(manual, 2, ok)
        assertNull(SettingsStore.handOverNoticeOf(manual, 2))

        val link = owner().apply { this[booleanPreferencesKey("csqtt_link_mode_2")] = true }
        SettingsStore.writeHandOverResult(link, 2, ok)
        assertNull(SettingsStore.handOverNoticeOf(link, 2))
    }

    @Test
    fun aDeployEndClearsIt() {
        val prefs = owner()
        SettingsStore.writeHandOverResult(prefs, 2, refused)
        SettingsStore.clearHandOver(prefs, 2)
        assertNull(SettingsStore.handOverNoticeOf(prefs, 2))
        assertTrue("the fresh server's first failure is new again", SettingsStore.writeHandOverResult(prefs, 2, refused))
    }

    @Test
    fun encodedAndDecoded() {
        assertEquals(refused, HandOverResult.decode(refused.encode()))
        assertNull(HandOverResult.decode("not json"))
        assertNull(HandOverResult.decode(null))
    }
}
