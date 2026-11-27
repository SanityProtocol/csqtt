package com.csqtt.client

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * D6 (a1): the server panel's API as the owner's app uses it (B5e3b): its default VK hashes, set to the owner's pool.
 * `POST /<secret>/api/settings` with `x-api-key` = the web password and `{"vk_hashes": "a,b"}`: a partial update, so
 * every other setting is kept. TLS is B5c1's rule: a CA certificate always; a self-signed one on first use, then only it.
 */
internal object PanelApi {
    /** The server takes 1–6 default hashes (web_panel.rs `normalize_client_vk_hashes`). */
    const val MAX_DEFAULT_HASHES = 6
    private const val TIMEOUT_MS = 8_000

    sealed class Outcome {
        data object Ok : Outcome()
        data class Refused(val code: Int, val message: String) : Outcome()
        data object CertificateChanged : Outcome()
        /** The panel's API key header carries visible ASCII only; another password is never sent (review F1). */
        data object PasswordNotAscii : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    /** [observed]: the leaf trusted by a successful call, to be recorded. */
    data class Result(val outcome: Outcome, val observed: SubscriptionCertRecord?)

    fun settingsUrl(panel: PoolPanel): String {
        val host = if (':' in panel.host && !panel.host.startsWith("[")) "[${panel.host}]" else panel.host
        return "https://$host:${panel.webPort}/${panel.secretPath}/api/settings"
    }

    fun setDefaultHashes(
        panel: PoolPanel,
        hashes: List<String>,
        cert: SubscriptionCertRecord?,
        nowMs: Long = System.currentTimeMillis(),
    ): Result {
        if (panel.webPassword.any { it !in ' '..'~' }) return Result(Outcome.PasswordNotAscii, null)
        val url = runCatching { URL(settingsUrl(panel)) }.getOrElse { return Result(Outcome.Failed("неверный адрес"), null) }
        val pin = SubscriptionCertPin(url.host.trim('[', ']'), panel.webPort, cert, nowMs)
        var connection: HttpURLConnection? = null
        return try {
            connection = (url.openConnection() as javax.net.ssl.HttpsURLConnection).apply {
                sslSocketFactory = pin.socketFactory
                hostnameVerifier = pin.hostnameVerifier
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
                useCaches = false
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("x-api-key", panel.webPassword)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "csqtt-client")
            }
            val body = JSONObject().put("vk_hashes", hashes.take(MAX_DEFAULT_HASHES).joinToString(",")).toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code == 200) {
                Result(Outcome.Ok, pin.observed)
            } else {
                val text = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
                Result(Outcome.Refused(code, text.trim().take(200)), null)
            }
        } catch (e: Exception) {
            Result(if (pin.refusedByPin) Outcome.CertificateChanged else Outcome.Failed(e.message ?: e.javaClass.simpleName), null)
        } finally {
            runCatching { connection?.disconnect() }
        }
    }
}

/** D6 (a1): hands the owner's pool to its server, once per try, no retry loop and no timer (B5e3b). */
internal object VkPoolHandOver {
    private val lock = Mutex()
    private val refreshing = MutableStateFlow(false)

    /** Whether [refresh] runs now: «Пул звонков VK» holds its buttons meanwhile, made again or not. */
    val working: StateFlow<Boolean> = refreshing

    /**
     * Sends [panel]'s pool when it differs from what the panel last accepted (always with [force]). Returns the log
     * line, or null when there was nothing to send. A failure is tried again at the next pool run. A failure of a try
     * that is not the [lastTry] (the deploy end tries three times) is neither kept nor announced; a success always is.
     */
    suspend fun handOver(
        store: SettingsStore,
        panel: PoolPanel,
        force: Boolean = false,
        lastTry: Boolean = true,
    ): Pair<Boolean, String>? = lock.withLock {
        val pool = store.vkPool(panel.vps).take(PanelApi.MAX_DEFAULT_HASHES)
        if (pool.isEmpty()) return@withLock null
        val (sent, cert) = store.handOverState(panel.vps)
        if (!force && pool == sent) return@withLock null
        val result = withContext(Dispatchers.IO) { PanelApi.setDefaultHashes(panel, pool, cert) }
        val (ok, line) = when (val outcome = result.outcome) {
            PanelApi.Outcome.Ok -> {
                store.recordHandOver(panel.vps, pool, result.observed)
                true to "[OK] Хеши звонков переданы серверу (${pool.size}) ✓"
            }
            is PanelApi.Outcome.Refused -> false to when (outcome.code) {
                401 -> "[ERR] Сервер не принял хеши: пароль панели не подошёл"
                else -> "[ERR] Сервер не принял хеши · код=${outcome.code} ${outcome.message}"
            }
            PanelApi.Outcome.PasswordNotAscii ->
                false to "[ERR] Хеши не переданы: пароль панели не только из латиницы, цифр и знаков — API его не примет, смените пароль панели"
            PanelApi.Outcome.CertificateChanged ->
                false to "[ERR] Хеши не переданы: сертификат панели сменился (обновите сервер на вкладке установки)"
            is PanelApi.Outcome.Failed ->
                false to "[ERR] Хеши не переданы: панель не ответила (адрес, порт или секретный путь) · ${outcome.reason}"
        }
        if (ok || lastTry) recordResult(store, panel.vps, ok, line)
        ok to line
    }

    /**
     * B5h2 (rule 10): the result, a success too, is kept for «Подключение» (the run's log line is wiped at the tunnel's
     * start); a new failure is announced while the app is not in front, and a success withdraws it. Masked: the panel's
     * answer and a network error can carry anything.
     */
    private suspend fun recordResult(store: SettingsStore, vps: Int, ok: Boolean, line: String) {
        val text = handOverNoticeText(line)
        val id = CsqttConstants.Notifications.HANDOVER_NOTIFICATION_ID
        val tag = "handover:$vps"
        if (store.recordHandOverResult(vps, HandOverResult(ok, text, System.currentTimeMillis()))) {
            BackgroundNotice.post(store.appContext, id, "Хеши звонков не переданы серверу", text, tag)
        } else if (ok) {
            BackgroundNotice.cancel(store.appContext, id, tag)
        }
    }

    /**
     * «Обновить» and «Создать звонок» of «Пул звонков VK» (P3 B3); [store] is [profile]'s own. The pool is filled as a
     * connect fills it — the calls a check found [dead] replaced, and with [oneMore] one call beyond what it holds, up
     * to what the server takes — and handed to the server's panel. The hand-over's outcome and line; null when the
     * profile's run uses no pool, or the pool is empty.
     */
    suspend fun refresh(context: Context, store: SettingsStore, profile: Int, dead: Set<String>, oneMore: Boolean): Pair<Boolean, String>? {
        // one at a time: a second tap while calls are being made makes no more of them
        if (!refreshing.compareAndSet(expect = false, update = true)) return null
        try {
            val panel = store.activePoolPanel(profile) ?: return null
            if (store.vkHashMode.first() != CsqttConstants.VkAutoHash.MODE_AUTO_API) return null
            val workers = WorkerCountPolicy.normalize(store.workersPerHash.first())
            val token = store.vkAccessToken.first()
            VkHashPool.fillLock.withLock {
                val pool = store.vkPool(panel.vps)
                val live = pool.filterNot { it in dead }
                // the pool never shrinks: each dead call is made again; a new one only while the server would take it
                val more = (pool.size - live.size) + if (oneMore && pool.size < PanelApi.MAX_DEFAULT_HASHES) 1 else 0
                val fill = VkAutoCallsManager.fillPool(context, token, live, null, workers, tokenStore = store, extra = more)
                if (fill.hashes != pool) store.saveVkPool(panel.vps, fill.hashes)
            }
            return handOver(store, panel, force = true)
        } finally {
            refreshing.value = false
        }
    }

    /**
     * A deploy's end on [vps] from [profile]: the fresh server has no default hashes and may have a new self-signed leaf,
     * so the record of both is cleared (the SSH session just pinned the host). An owner in «Авто API» then gets a pool,
     * minted if missing, and it is sent; the panel may still be starting, so up to three tries, 5 s apart.
     */
    suspend fun atDeployEnd(context: Context, store: SettingsStore, profile: Int, vps: Int): Pair<Boolean, String>? {
        store.clearHandOver(vps)
        // the token, mode and workers are read for the active profile, the one the deploy ran from
        if (store.activeProfile.first() != profile) return null
        val panel = store.activePoolPanel(profile)?.takeIf { it.vps == vps } ?: return null
        if (store.vkHashMode.first() != CsqttConstants.VkAutoHash.MODE_AUTO_API) return null
        val workers = WorkerCountPolicy.normalize(store.workersPerHash.first())
        val token = store.vkAccessToken.first()
        VkHashPool.fillLock.withLock {
            val pool = store.vkPool(vps)
            val fill = VkAutoCallsManager.fillPool(context, token, pool, null, workers, tokenStore = store)
            if (fill.hashes != pool) store.saveVkPool(vps, fill.hashes)
        }
        var result: Pair<Boolean, String>? = null
        for (attempt in 1..3) {
            result = handOver(store, panel, force = true, lastTry = attempt == 3)
            if (result == null || result.first || attempt == 3) break
            delay(5_000)
        }
        return result
    }
}
