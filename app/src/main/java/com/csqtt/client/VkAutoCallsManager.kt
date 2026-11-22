package com.csqtt.client

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
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

object VkAutoCallsManager {
    /** [set]: the calls just made, kept apart until the run takes them (B5e4). */
    data class StartResult(
        val hashes: String,
        val requestedCalls: Int,
        val createdCalls: Int,
        internal val set: CallSet? = null,
    ) {
        val needsWorkerRedistribution: Boolean
            get() = createdCalls < requestedCalls
    }

    /**
     * B5h1 (B3): [result] is null when no call was made; [tokenInvalid] then says VK refused the token or there is none,
     * so no retry can make one until the user signs in again. [error]: VK's or the network's last answer (B5e4).
     */
    data class StartAttempt(val result: StartResult?, val tokenInvalid: Boolean = false, val error: String? = null)

    data class ActiveCall(val callId: String, val hashKey: String)

    /** B5e4: calls this app made for one run, with the token they were made with: what ends them. */
    data class CallSet(val calls: List<ActiveCall>, val token: String)
    /** The set the run is on: a stop ends it. A new set replaces it only when the run takes the new one (B5e4). */
    private var runSet: CallSet? = null

    /** How a set is ended; a test puts a recorder here. */
    internal var ender: (CallSet) -> Unit = ::endInBackground

    fun hasActiveCalls(): Boolean = synchronized(this) { runSet?.calls?.isNotEmpty() == true }

    /**
     * B5e4: the run takes [result]'s calls, and the set it was on is ended (for a start there is none: its stop ended
     * it). A recovery takes its set only where its restart is scheduled.
     */
    fun take(result: StartResult?) {
        val set = result?.set ?: return
        val previous = synchronized(this) { runSet.also { runSet = set } }
        previous?.let(ender)
    }

    /** B5e4: calls made for a run that will never take them (its recovery outlived it) are ended. */
    fun discard(result: StartResult?) {
        result?.set?.let(ender)
    }

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

    /** [tokenStore]: the store [token] was read from — a refused token is cleared there, not in another profile. */
    suspend fun startAutoCalls(
        context: Context,
        token: String,
        workers: Int,
        tokenStore: SettingsStore,
        clearStoredTokenOnInvalid: Boolean = true,
    ): StartAttempt {
        if (token.isBlank()) return StartAttempt(null, tokenInvalid = true)

        val count = callCountForWorkers(workers)
        val hashes = ArrayList<String>(count)
        // the run's own set stays as it is until the run takes this one (B5e4)
        val minted = ArrayList<ActiveCall>(count)
        var lastError: String? = null

        val requestDelayMs = requestDelayForCallCount(count)
        val requestPacer = VkRequestPacer(
            requestDelayMs,
            SystemClock::elapsedRealtime,
            { waitMs -> delay(waitMs) },
        )
        var completedSlots = 0
        try {
            while (completedSlots < count) {
                val result = runVkCallAttempts {
                    requestPacer.awaitRequestSlot()
                    withContext(Dispatchers.IO) { VkApi.startCall(token) }
                }
                completedSlots++
                when (result) {
                    is VkApiResult.Ok<*> -> {
                        val call = result.value as VkApi.StartedCall
                        minted.add(ActiveCall(callId = call.callId, hashKey = sha256Hex(call.hash)))
                        hashes.add(call.hash)
                        log(
                            "vk_call_created_progress",
                            "[OK] Звонок VK создан ✓",
                            16,
                            false,
                        )
                    }

                    is VkApiResult.ApiError -> {
                        lastError = "код ${result.code} ${result.message}".trim()
                        log(
                            "vk_auto_call_error",
                            "[ERR] Звонок VK не создан · код=${result.code} ${result.message}",
                            90,
                            true,
                        )
                        if (VkApi.isTokenInvalid(result)) {
                            if (clearStoredTokenOnInvalid) {
                                runCatching { tokenStore.clearVkAccessToken() }
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
                            // only what this attempt made: the run's own set is the run's to end
                            finishCalls(minted, token, logResults = false)
                            return StartAttempt(null, tokenInvalid = true)
                        }
                    }

                    is VkApiResult.Failed -> {
                        lastError = result.reason
                        log(
                            "vk_auto_call_error",
                            "[ERR] Звонок VK не создан · ${result.reason}",
                            90,
                            true,
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            // a stop or a switch during the mint: what it made is no run's, so it is ended (review)
            if (minted.isNotEmpty()) ender(CallSet(ArrayList(minted), token))
            throw e
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
                    set = CallSet(minted, token),
                )
            },
            error = lastError,
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
        tokenStore: SettingsStore,
    ): VkHashPool.Fill {
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
            runCatching { tokenStore.clearVkAccessToken() }
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

    /** The run ends: the set it is on is ended. */
    fun finishActiveCalls() {
        val set = synchronized(this) { runSet.also { runSet = null } } ?: return
        ender(set)
    }

    private fun endInBackground(set: CallSet) {
        if (set.token.isBlank() || set.calls.isEmpty()) return
        TunnelManager.scope.launch(Dispatchers.IO + NonCancellable) {
            withTimeoutOrNull(CsqttConstants.VkAutoHash.FINISH_CALLS_TIMEOUT_MS) {
                finishCalls(set.calls, set.token, logResults = true)
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

    // B5g: the log is no longer cleared at a start, so a line made while the run starts needs no replay after it
    private fun log(key: String, message: String, priority: Int, isError: Boolean) {
        TunnelManager.updateLog(key, message, priority, isError)
    }

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
