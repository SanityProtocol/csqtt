package com.csqtt.client

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

object VkAutoCallsManager {
    data class StartResult(
        val hashes: String,
        val requestedCalls: Int,
        val createdCalls: Int,
    ) {
        val needsWorkerRedistribution: Boolean
            get() = createdCalls < requestedCalls
    }

    /**
     * B5h1 (B3): [result] is null when no call was made; [tokenInvalid] then says VK refused the token or there is none,
     * so no retry can make one until the user signs in again.
     */
    data class StartAttempt(val result: StartResult?, val tokenInvalid: Boolean = false)

    private data class ActiveCall(val callId: String, val hashKey: String)
    private data class PendingLog(val key: String, val message: String, val priority: Int, val isError: Boolean)

    private val activeCalls = CopyOnWriteArrayList<ActiveCall>()
    private val pendingLogs = ArrayList<PendingLog>()
    private val pendingLogsLock = Any()

    @Volatile
    private var activeToken: String? = null

    fun hasActiveCalls(): Boolean = activeCalls.isNotEmpty()

    fun callCountForWorkers(workers: Int): Int {
        return (1..CsqttConstants.Tunnel.MAX_VK_HASHES).firstOrNull {
            workers <= WorkerCountPolicy.maximumForHashes(it)
        } ?: CsqttConstants.Tunnel.MAX_VK_HASHES
    }

    internal fun requestDelayForCallCount(callCount: Int): Long =
        if (callCount <= 4) {
            CsqttConstants.VkAutoHash.AUTO_CALL_SMALL_DELAY_MS
        } else {
            CsqttConstants.VkAutoHash.AUTO_CALL_LARGE_DELAY_MS
        }

    suspend fun startAutoCalls(
        context: Context,
        token: String,
        workers: Int,
        clearStoredTokenOnInvalid: Boolean = true,
    ): StartAttempt {
        if (token.isBlank()) return StartAttempt(null, tokenInvalid = true)

        synchronized(pendingLogsLock) { pendingLogs.clear() }
        activeCalls.clear()

        val count = callCountForWorkers(workers)
        val hashes = ArrayList<String>(count)

        val requestDelayMs = requestDelayForCallCount(count)
        val requestPacer = VkRequestPacer(
            requestDelayMs,
            SystemClock::elapsedRealtime,
            { waitMs -> delay(waitMs) },
        )
        var completedSlots = 0
        while (completedSlots < count) {
            val result = runVkCallAttempts {
                requestPacer.awaitRequestSlot()
                withContext(Dispatchers.IO) { VkApi.startCall(token) }
            }
            completedSlots++
            when (result) {
                is VkApiResult.Ok<*> -> {
                    activeToken = token
                    val call = result.value as VkApi.StartedCall
                    activeCalls.add(ActiveCall(callId = call.callId, hashKey = sha256Hex(call.hash)))
                    hashes.add(call.hash)
                    log(
                        "vk_call_created_progress",
                        "[OK] Звонок VK создан ✓",
                        16,
                        false,
                    )
                }

                is VkApiResult.ApiError -> {
                    log(
                        "vk_auto_call_error",
                        "[ERR] Звонок VK не создан · код=${result.code} ${result.message}",
                        90,
                        true,
                    )
                    if (VkApi.isTokenInvalid(result)) {
                        if (clearStoredTokenOnInvalid) {
                            runCatching { SettingsStore(context, ProfileScope.Run).clearVkAccessToken() }
                        }
                        log(
                            "vk_token_invalidated",
                            if (clearStoredTokenOnInvalid) {
                                "[ERR] Токен VK недействителен · войдите снова"
                            } else {
                                "[ERR] Токен VK из ссылки недействителен"
                            },
                            95,
                            true,
                        )
                        finishCalls(ArrayList(activeCalls).also { activeCalls.clear() }, token, logResults = false)
                        activeToken = null
                        return StartAttempt(null, tokenInvalid = true)
                    }
                }

                is VkApiResult.Failed -> {
                    log(
                        "vk_auto_call_error",
                        "[ERR] Звонок VK не создан · ${result.reason}",
                        90,
                        true,
                    )
                }
            }
        }

        if (hashes.isNotEmpty() && hashes.size < count) {
            log(
                "vk_auto_call_partial",
                "[OK] Звонки VK ${hashes.size}/$count · потоки распределены",
                45,
                false,
            )
        }

        return StartAttempt(
            if (hashes.isEmpty()) {
                null
            } else {
                StartResult(
                    hashes = hashes.joinToString(","),
                    requestedCalls = count,
                    createdCalls = hashes.size,
                )
            },
        )
    }

    /**
     * D6 (a1): fills the owner's pool (B5e3b) to one call per 10 of [workers]. Unlike [startAutoCalls], its calls are not
     * registered here, so a stop does not end them. An invalid token is cleared as there; the live pool still serves.
     */
    internal suspend fun fillPool(
        context: Context,
        token: String,
        pool: List<String>,
        dead: String?,
        workers: Int,
    ): VkHashPool.Fill {
        synchronized(pendingLogsLock) { pendingLogs.clear() }
        val target = callCountForWorkers(workers)
        val requestPacer = VkRequestPacer(
            requestDelayForCallCount(target),
            SystemClock::elapsedRealtime,
            { waitMs -> delay(waitMs) },
        )
        val fill = VkHashPool.fill(pool, dead, target) {
            if (token.isBlank()) {
                VkApiResult.Failed("нет токена VK")
            } else {
                runVkCallAttempts {
                    requestPacer.awaitRequestSlot()
                    withContext(Dispatchers.IO) { VkApi.startCall(token) }
                }
            }
        }
        fill.errors.forEach { log("vk_auto_call_error", "[ERR] Звонок VK не создан · $it", 90, true) }
        if (fill.tokenInvalid) {
            runCatching { SettingsStore(context, ProfileScope.Run).clearVkAccessToken() }
            log("vk_token_invalidated", "[ERR] Токен VK недействителен · войдите снова", 95, true)
        }
        if (fill.hashes.isNotEmpty()) {
            log(
                "vk_pool",
                "[OK] Пул звонков VK: ${fill.hashes.size}" + if (fill.minted > 0) " · новых ${fill.minted}" else "",
                16,
                false,
            )
        }
        return fill
    }

    fun replayPendingLogs() {
        val copy = synchronized(pendingLogsLock) {
            val snapshot = ArrayList(pendingLogs)
            pendingLogs.clear()
            snapshot
        }
        copy.forEach { entry ->
            TunnelManager.updateLog(entry.key, entry.message, entry.priority, entry.isError)
        }
    }

    fun finishActiveCalls() {
        if (activeCalls.isEmpty()) return
        val token = activeToken
        val calls = ArrayList(activeCalls)
        activeCalls.clear()
        activeToken = null
        if (token.isNullOrBlank()) return

        TunnelManager.scope.launch(Dispatchers.IO + NonCancellable) {
            withTimeoutOrNull(CsqttConstants.VkAutoHash.FINISH_CALLS_TIMEOUT_MS) {
                finishCalls(calls, token, logResults = true)
            }
        }
    }

    private suspend fun finishCalls(calls: List<ActiveCall>, token: String, logResults: Boolean) {
        val results = coroutineScope {
            val deferredResults = calls.mapIndexed { index, call ->
                async(Dispatchers.IO) {
                    index to VkApi.forceFinishCall(token, call.callId)
                }
            }
            deferredResults.awaitAll()
        }

        if (logResults) {
            for ((_, result) in results) {
                when (result) {
                    is VkApiResult.Ok<*> -> TunnelManager.updateLog(
                        "vk_auto_call_finished",
                        "[OK] Звонок VK завершён",
                        40,
                        false,
                    )

                    is VkApiResult.ApiError -> TunnelManager.updateLog(
                        "vk_auto_call_finish_error",
                        "[ERR] Звонок VK не завершён · код=${result.code}",
                        60,
                        false,
                    )

                    is VkApiResult.Failed -> TunnelManager.updateLog(
                        "vk_auto_call_finish_error",
                        "[ERR] Звонок VK не завершён · ${result.reason}",
                        60,
                        false,
                    )
                }
            }
        }
    }

    private fun log(key: String, message: String, priority: Int, isError: Boolean) {
        if (!TunnelManager.isLoggingEnabled) return
        TunnelManager.updateLog(key, message, priority, isError)
        synchronized(pendingLogsLock) {
            pendingLogs.add(PendingLog(key, message, priority, isError))
        }
    }

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
