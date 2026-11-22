package com.csqtt.client

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Toast

/**
 * M-90 (B5g): one toast at a time. Every toast of the app comes here: it is shown on the main looper (any thread may
 * ask), and the one shown before is cancelled instead of queued, so several in a row (a tap repeated, a check's outcome
 * after a copy) do not keep the screen covered for a minute. Raised above the bottom bar where Android still lets a text
 * toast move (below 11; on 11 and later the system ignores the offset — P3's in-app messages replace this).
 */
fun Context.showRaisedToast(message: String, length: Int = Toast.LENGTH_SHORT) {
    val app = applicationContext
    val yOffset = (180 * resources.displayMetrics.density).toInt()
    mainHandler.post {
        toastSlot.replace(
            Toast.makeText(app, message, length).apply {
                setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, yOffset)
            },
        )
    }
}

private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

private val toastSlot = ReplacingSlot<Toast>(cancel = Toast::cancel, show = Toast::show)

/** The replace rule, apart from Android for the tests: a new item cancels the one shown before it, then shows. */
internal class ReplacingSlot<T : Any>(private val cancel: (T) -> Unit, private val show: (T) -> Unit) {
    private var current: T? = null

    fun replace(next: T) {
        current?.let(cancel)
        current = next
        show(next)
    }
}
