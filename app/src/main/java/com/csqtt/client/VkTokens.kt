package com.csqtt.client

import org.json.JSONObject
import java.net.URLDecoder

/** What a sign-in does with the token VK gave it (M-82 / D30). */
internal enum class VkTokenDecision { Accept, Retry, RefuseExpiring }

internal object VkTokenPolicy {
    /**
     * A permanent token (expires_in 0) is taken. VK now and then hands out an expiring one, so the sign-in asks once
     * more; a second expiring token is refused (D30), on both sign-in paths: it would stop working within a day, mid-use.
     */
    fun decide(expiresIn: Long, retried: Boolean): VkTokenDecision = when {
        expiresIn <= 0L -> VkTokenDecision.Accept
        !retried -> VkTokenDecision.Retry
        else -> VkTokenDecision.RefuseExpiring
    }

    const val EXPIRING_REFUSED =
        "VK выдал временный токен — он перестал бы работать через сутки. Войдите ещё раз позже или используйте свои хеши звонков"
}

/** A token pasted by hand: a `vk1.a.…` token, or the whole `…blank.html#access_token=…` address the sign-in ends on. */
internal data class VkTokenInput(val token: String, val userId: String, val expiresIn: Long)

internal object VkTokenParse {
    private val tokenShape = Regex("^vk1\\.a\\.[A-Za-z0-9_.-]{20,}$")

    /** The parser behind P3's paste field (M-82); null for anything that is not one of the two forms. */
    fun parse(text: String): VkTokenInput? {
        val trimmed = text.trim()
        if (tokenShape.matches(trimmed)) return VkTokenInput(trimmed, userId = "", expiresIn = 0L)
        val fragment = trimmed.substringAfter('#', missingDelimiterValue = "")
        if (fragment.isEmpty()) return null
        val params = fragment.split('&').associate { part ->
            val key = part.substringBefore('=')
            key to runCatching { URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
        }
        val token = params["access_token"]?.takeIf(tokenShape::matches) ?: return null
        val expiresIn = params["expires_in"]?.toLongOrNull() ?: 0L
        return VkTokenInput(token, params["user_id"].orEmpty().filter(Char::isDigit), expiresIn)
    }
}

/**
 * M-79: VK accounts archived by numeric user id, each token encrypted on its own, so P3's accounts dialog can list and
 * select them without a new sign-in. A link's token is never archived. Stored as one JSON object:
 * `{"<user id>": {"t": "<encrypted token>", "e": <expiry ms, 0 = permanent>}}`; never in a backup.
 */
internal object VkAccounts {
    fun put(json: String, userId: String, encryptedToken: String, expiresAtMs: Long): String {
        if (userId.isBlank() || !userId.all(Char::isDigit)) return json
        val root = parse(json)
        root.put(userId, JSONObject().put("t", encryptedToken).put("e", expiresAtMs))
        return root.toString()
    }

    fun remove(json: String, userId: String): String {
        if (userId.isBlank()) return json
        val root = parse(json)
        root.remove(userId)
        return root.toString()
    }

    fun ids(json: String): List<String> = parse(json).keys().asSequence().toList().sorted()

    /** The archived account's token as stored, encrypted; null when the account is not there. */
    fun encryptedToken(json: String, userId: String): String? =
        parse(json).optJSONObject(userId)?.optString("t")?.takeIf { it.isNotEmpty() }

    private fun parse(json: String): JSONObject = runCatching { JSONObject(json) }.getOrElse { JSONObject() }
}
