package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** §4.2 rule 9 and M-90: a question stays until it is settled; one message at a time, the newer in the older's place. */
class OwnersTest {
    @Test
    fun `a question stays until it is settled, and asking again under its id replaces it`() {
        val questions = Questions()
        questions.ask(Question("deploy:3", QuestionPlace.Server, "Нужен ваш выбор"))
        questions.ask(Question("captcha", QuestionPlace.Home, "Нужна капча"))
        questions.ask(Question("deploy:3", QuestionPlace.Server, "Ключ сервера изменился"))
        assertEquals(listOf("captcha", "deploy:3"), questions.pending.value.map { it.id })
        assertEquals("Ключ сервера изменился", questions.pending.value.last().title)

        questions.settle("captcha")
        questions.settle("not asked")
        assertEquals(listOf("deploy:3"), questions.pending.value.map { it.id })
    }

    @Test
    fun `a new message takes the place of the one shown, and a late dismiss of the old one leaves it`() {
        val messages = Messages()
        messages.show("Скопировано")
        val first = messages.current.value!!
        messages.show("Обновление отложено на 24 часа")
        val second = messages.current.value!!
        assertEquals("Обновление отложено на 24 часа", second.text)

        messages.dismiss(first.id)
        assertEquals(second, messages.current.value)
        messages.dismiss(second.id)
        assertNull(messages.current.value)
    }

    @Test
    fun `a message's action runs once and takes the message away`() {
        val messages = Messages()
        var undone = 0
        messages.show("Порядок изменён", action = "Отменить") { undone++ }
        val shown = messages.current.value!!
        messages.act(shown.id)
        messages.act(shown.id)
        assertEquals(1, undone)
        assertNull(messages.current.value)
    }
}
