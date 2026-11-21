package com.csqtt.client

/**
 * Every log line passes here before it is stored, so the screen, a copy and a shared file never carry a secret (M-173):
 * the value after a password/token/secret/authorization/cookie key (also as a JSON key), a VK token, a csqtt link and
 * the hash of a VK call link. The owner's hashes are masked before this, by maskOwnerHashes.
 */
internal object LogRedaction {
    const val HIDDEN = "[скрыто]"

    // a key starts a word (not "getCallToken" after "messages."); its value stays on its own line
    private val keyValue = Regex(
        "(?<![\\w.])((?:[A-Za-z_]*(?:password|passwd|pwd|token|secret|authorization|cookie|remixsid|session_key|" +
            "session_data|joinlink))|[Пп][Аа][Рр][Оо][Лл][Ьь])" +
            "([\"']?[^\\S\n]*[=:][^\\S\n]*[\"']?)(?:(?:bearer|basic)[^\\S\n]+)?[^\\s\"'&,;}\\]]+",
        RegexOption.IGNORE_CASE,
    )
    private val vkToken = Regex("vk1\\.a\\.[A-Za-z0-9_.-]+")
    private val csqttLink = Regex("\\b(csqttr?)://\\S+", RegexOption.IGNORE_CASE)
    // also URL-encoded, as the core's VK requests carry it (link=https%3A%2F%2Fvk.ru%2Fcall%2Fjoin%2F<hash>)
    private val callLink = Regex(
        "(vk\\.(?:com|ru)(?:/|%2F)call(?:/|%2F)join(?:/|%2F))[A-Za-z0-9_-]+",
        RegexOption.IGNORE_CASE,
    )

    fun redact(text: String): String = text
        .replace(keyValue) { it.groupValues[1] + it.groupValues[2] + HIDDEN }
        .replace(vkToken, "vk1.a.$HIDDEN")
        .replace(csqttLink) { it.groupValues[1] + "://" + HIDDEN }
        .replace(callLink) { it.groupValues[1] + HIDDEN }
}
