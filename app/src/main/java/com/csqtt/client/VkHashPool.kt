package com.csqtt.client

import kotlinx.coroutines.sync.Mutex

/**
 * D6 (a1): the owner's pool of VK call hashes (B5e3b). The calls are minted by this app with the owner's token, outlive the
 * owner's tunnel (never ended at stop) and are handed to the server as its default hashes, so token-mode participants
 * join them with no token. A dead hash is dropped and replaced; live ones are kept, in order, and never re-minted.
 */
internal object VkHashPool {
    /** One read-fill-save of a pool at a time: a deploy's end and a connect right after it must not both mint (review F2). */
    val fillLock = Mutex()

    data class Fill(
        /** The live pool after this fill: the kept hashes first, then the minted ones. */
        val hashes: List<String>,
        val minted: Int,
        /** VK refused the token (4, 5, 27, 28); minting stopped there. */
        val tokenInvalid: Boolean,
        /** One line per call that could not be made, for the log; never a hash. */
        val errors: List<String>,
    )

    /** [pool] without [dead], blanks and repeats. */
    fun alive(pool: List<String>, dead: String?): List<String> =
        pool.map(String::trim).filter { it.isNotEmpty() && it != dead?.trim() }.distinct()

    /**
     * Mints what [pool] lacks of [target] calls after dropping [dead]. A pool larger than the target is kept whole, and
     * its dead hash is replaced too (it never shrinks: every live call serves participants). [mint] makes one call, its
     * retries and pacing included.
     */
    suspend fun fill(
        pool: List<String>,
        dead: String?,
        target: Int,
        mint: suspend () -> VkApiResult<VkApi.StartedCall>,
    ): Fill {
        val hashes = alive(pool, dead).toMutableList()
        // a dead hash is replaced even in a pool above the target, which never shrinks (review F1)
        val wanted = maxOf(target, alive(pool, null).size)
        val errors = ArrayList<String>()
        var minted = 0
        repeat((wanted - hashes.size).coerceAtLeast(0)) {
            when (val result = mint()) {
                is VkApiResult.Ok -> if (result.value.hash !in hashes) {
                    hashes += result.value.hash
                    minted++
                }
                is VkApiResult.ApiError -> {
                    errors += "код=${result.code} ${result.message}"
                    if (VkApi.isTokenInvalid(result)) return Fill(hashes, minted, tokenInvalid = true, errors = errors)
                }
                is VkApiResult.Failed -> errors += result.reason
            }
        }
        return Fill(hashes, minted, tokenInvalid = false, errors = errors)
    }
}
