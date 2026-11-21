package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** M-173: a log line never carries a password, a token, a csqtt link or a VK call link's hash. */
class LogRedactionTest {
    private fun redact(text: String) = LogRedaction.redact(text)

    @Test
    fun `key values are hidden, as text and as JSON keys`() {
        assertEquals("password=[скрыто] port=443", redact("password=hunter2 port=443"))
        assertEquals("Пароль: [скрыто]", redact("Пароль: Секрет123"))
        assertEquals("ответ {\"access_token\":\"[скрыто]\",\"expires_in\":0}",
            redact("ответ {\"access_token\":\"abc.DEF-123\",\"expires_in\":0}"))
        assertEquals("https://oauth.vk.ru/blank.html#access_token=[скрыто]&expires_in=0&user_id=1",
            redact("https://oauth.vk.ru/blank.html#access_token=vk1.a.QWERTY_123&expires_in=0&user_id=1"))
        assertEquals("Authorization: [скрыто]", redact("Authorization: Bearer abc.def"))
        assertEquals("CSQTT_WEB_SECRET=[скрыто] client_secret: [скрыто]",
            redact("CSQTT_WEB_SECRET=s3cr3t client_secret: xyz"))
        assertEquals("cookie: [скрыто]; remixsid=[скрыто]", redact("cookie: a=1; remixsid=SESSION"))
    }

    @Test
    fun `tokens, csqtt links and call links are hidden wherever they stand`() {
        assertEquals("ошибка vk1.a.[скрыто] отклонён", redact("ошибка vk1.a.AbC-12_x.y отклонён"))
        assertEquals("импорт csqtt://[скрыто] готов", redact("импорт csqtt://eyJwIjoiMSJ9?x=1 готов"))
        assertEquals("CSQTTR://[скрыто]", redact("CSQTTR://abc"))
        assertEquals("ссылка https://vk.com/call/join/[скрыто] ок",
            redact("ссылка https://vk.com/call/join/AbC_d-123 ок"))
        assertEquals("vk.ru/call/join/[скрыто]", redact("vk.ru/call/join/xyz"))
    }

    @Test
    fun `ordinary lines stay as they are`() {
        listOf(
            "[КЛИЕНТ] Нужен пароль в переменной CSQTT_PASSWORD",
            "[ВОРКЕР 3] Подключено, хеш: от владельца, порт 46000",
            "Токены обновлены: 3",
            "[VK] Хеш \"AbCdEfGh12…\" устарел",
            "tokens: 4",
            "",
        ).forEach { assertEquals(it, redact(it)) }
    }

    @Test
    fun `owner-hash masking still applies before redaction`() {
        val owner = "AbCdEfGh1234567890xyz"
        val line = redact(maskOwnerHashes("хеш $owner · password=p", listOf(owner)))
        assertEquals("хеш от владельца · password=[скрыто]", line)
        assertFalse(line.contains(owner.take(8)))
    }
}
