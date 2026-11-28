package com.csqtt.client

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What outlives the screens (APP-PLAN §4.2 rules 8 and 9), made once with the process: work the user started runs in an
 * owner here, its state a StateFlow the screens only draw, and a question for the user stays in [questions] until it is
 * answered. The deploy, hash-check, VK sign-in, ping and notice owners join with their screens' cards.
 */
internal class AppOwners {
    val questions = Questions()
    val messages = Messages()
    val refusals = Refusals()
}

/** A profile's settings refused something asked of them: «Туннель» marks what [profile]'s form lacks. */
internal data class Refusal(val profile: Int, val id: Long)

/**
 * Where a refusal that was not asked from a tab is kept until «Туннель» has taken it: «Переподключить» sits in the
 * shell, and with settings a start would refuse it opens the form on what is missing (the operator, 2026-11-28: «Keep
 * the tunnel, show what is missing»). [Refusal.id] tells one refusal from the next, of the same profile too, and from
 * one a screen made again has already taken.
 */
internal class Refusals(private val id: () -> Long = { System.nanoTime() }) {
    private val last = MutableStateFlow<Refusal?>(null)
    val current: StateFlow<Refusal?> = last.asStateFlow()

    fun refuse(profile: Int) {
        last.value = Refusal(profile, id())
    }
}

/** Where a question is answered: the tab its badge sits on. */
internal enum class QuestionPlace { Home, Tunnel, Server }

/** A question the app cannot go on without (a deploy choice, a changed host key): [id] names it in its owner. */
internal data class Question(val id: String, val place: QuestionPlace, val title: String)

/**
 * Rule 9: a question lives here from the moment its owner asks until it is answered or no longer stands, whatever screen
 * is open. The tab's badge, the cards on «Главная» and «Логи» and the notification all read [pending].
 */
internal class Questions {
    private val asked = MutableStateFlow<List<Question>>(emptyList())
    val pending: StateFlow<List<Question>> = asked.asStateFlow()

    /** Asks, or replaces the question of the same [Question.id]: an owner asks one thing at a time under one id. */
    fun ask(question: Question) = asked.update { list -> list.filterNot { it.id == question.id } + question }

    fun settle(id: String) = asked.update { list -> list.filterNot { it.id == id } }
}

/** A short message over the tab bar; [until] is when its time is up, on its owner's clock. */
internal class Message(val id: Long, val text: String, val action: String?, internal val onAction: (() -> Unit)?, val until: Long)

/**
 * The app's short messages (M-90's rule, kept): one at a time, a new one takes the place of the one shown. A message
 * confirms what the user just did; a result they rely on is never only here (rule 8). Its time runs from when it was
 * raised, whatever draws it: one nobody saw in time is not shown hours later, at the next opening of the app.
 */
internal class Messages(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val shown = MutableStateFlow<Message?>(null)
    val current: StateFlow<Message?> = shown.asStateFlow()
    private val ids = java.util.concurrent.atomic.AtomicLong()

    fun show(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        val life = if (action != null) WITH_ACTION_MS else PLAIN_MS
        shown.value = Message(ids.incrementAndGet(), text, action, onAction, until = now() + life)
    }

    /** How long [message] may still be shown, in milliseconds; 0 when its time is up. */
    fun remaining(message: Message): Long = (message.until - now()).coerceAtLeast(0)

    /** Only the message [id] names goes: one shown after it stays. */
    fun dismiss(id: Long) = shown.update { if (it?.id == id) null else it }

    fun act(id: Long) {
        val message = shown.value?.takeIf { it.id == id } ?: return
        dismiss(id)
        message.onAction?.invoke()
    }

    private companion object {
        const val PLAIN_MS = 4_000L
        const val WITH_ACTION_MS = 8_000L
    }
}
