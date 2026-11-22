package com.csqtt.client

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * B5h2 (APP-PLAN §4.2 rule 10): the background operations the user relies on — a subscription refresh (U3) and the D6
 * hand-over of the owner's pool to the server — keep their last outcome in the store, show it as one line on
 * «Подключение», and announce a new failure while the app is not in front. A log line alone is not the signal.
 */

/**
 * «Is it new»: the same failure differs only in ports, timings or pointers (a timeout's local port, Conscrypt's ssl=0x…);
 * an HTTP code is kept — 503 (down for now) and 404 (gone) ask different things of the user (verify N2).
 */
internal fun failureKey(text: String): String = text.trim().replace(FAILURE_NOISE, "#")

// a number is collapsed whole, never from its middle, so a kept code stays whole too
private val FAILURE_NOISE = Regex("0x[0-9a-fA-F]+|(?<![0-9]|вернул |код=)[0-9]+")

/** U3: what a refresh keeps — its failure, or what it skipped — masked: a server's message can carry anything. */
internal fun subscriptionOutcomeTexts(success: Boolean, message: String, skipped: Int): Pair<String, String> {
    val error = if (success) "" else LogRedaction.redact(message).trim()
    val skippedText = if (success && skipped > 0) LogRedaction.redact(message.removePrefix("Подписка — ")).trim() else ""
    return error to skippedText
}

/** The hand-over's log line as «Подключение» and the notice say it: no log tags, masked. */
internal fun handOverNoticeText(logLine: String): String =
    LogRedaction.redact(logLine.removePrefix("[OK] ").removePrefix("[ERR] ").removeSuffix(" ✓")).trim()

/** The D6 hand-over's last result for a server: what its panel was told, and when. */
internal data class HandOverResult(val ok: Boolean, val text: String, val at: Long) {
    fun encode(): String = JSONObject().put("ok", ok).put("text", text).put("at", at).toString()

    companion object {
        fun decode(raw: String?): HandOverResult? = runCatching {
            val json = JSONObject(raw ?: return null)
            HandOverResult(json.getBoolean("ok"), json.getString("text"), json.optLong("at", 0L))
        }.getOrNull()
    }
}

/** «Подключение»'s line for the open profile's subscription: only when its last refresh failed or skipped nodes. */
internal fun subscriptionNoticeLine(meta: SubscriptionMeta, time: (Long) -> String = ::noticeTime): String? {
    val title = meta.title.ifBlank { "Подписка" }
    val at = meta.lastAttemptAt.takeIf { it > 0L }?.let { " · ${time(it)}" }.orEmpty()
    return when {
        meta.lastError.isNotEmpty() -> "Подписка «$title» не обновилась: ${meta.lastError}$at"
        meta.lastSkipped.isNotEmpty() -> "Подписка «$title»: ${meta.lastSkipped}$at"
        else -> null
    }
}

/** «Подключение»'s line for the open profile's server: the last hand-over, a success too. */
internal fun handOverNoticeLine(result: HandOverResult, time: (Long) -> String = ::noticeTime): String =
    result.text + result.at.takeIf { it > 0L }?.let { " · ${time(it)}" }.orEmpty()

/** «чч:мм» today, «дд.ММ чч:мм» before. */
internal fun noticeTime(atMs: Long, nowMs: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): String {
    fun format(pattern: String, ms: Long) = SimpleDateFormat(pattern, Locale.US).apply { timeZone = zone }.format(Date(ms))
    val today = format("yyyyMMdd", atMs) == format("yyyyMMdd", nowMs)
    return format(if (today) "HH:mm" else "dd.MM HH:mm", atMs)
}

/**
 * Rule 10's notice for a background operation that failed: posted only for a new failure, only while the app is not in
 * front, on a quiet channel (the tunnel runs on); a success withdraws it.
 */
internal object BackgroundNotice {
    private const val CHANNEL_ID = CsqttConstants.Notifications.BACKGROUND_CHANNEL_ID

    /** [tag] tells one subscription's notice from another's under the same [id]. */
    fun post(context: Context, id: Int, title: String, text: String, tag: String? = null) {
        if (MainActivity.isForeground) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "CSQTT: фоновые операции", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Подписка не обновилась, хеши звонков не переданы серверу"
            },
        )
        val open = PendingIntent.getActivity(
            context,
            6,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_c)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(tag, id, notification) }
    }

    fun cancel(context: Context, id: Int, tag: String? = null) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(tag, id) }
    }
}
