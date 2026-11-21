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

/**
 * B5h1 (APP-PLAN §4.2 rule 10): the cause of every stop the user did not ask for — shown under «Отключено», kept until the
 * next start (also across process death), and announced by a notification of its own while the app is not in front.
 * Short: a notification line says what happened and what to do.
 */
internal object StopCause {
    const val VK_CALLS_NOT_CREATED = "Не удалось создать звонки VK"
    const val VK_SIGN_IN_AGAIN = "Войдите в VK заново"
    const val LINK_TOKEN_EXPIRED = "Ссылка устарела: попросите у владельца новую"
    const val ALL_HASHES_DEAD = "Все VK-хеши недействительны: добавьте новые"
    const val NO_HASHES_LEFT = "Не осталось рабочих VK-хешей"
    const val NO_CONNECTION_SOURCE = "Нет адреса сервера или ссылки: откройте «Туннель»"
    const val PASSWORD_UNREADABLE = "Пароль подключения не читается: введите его заново"
    const val NO_HASHES_OR_TOKEN = "Нет VK-хешей и VK-токена"
    const val FOREGROUND_REFUSED = "Android не дал запустить туннель в фоне"
    const val VPN_REVOKED = "VPN отключён Android или другим приложением: включите снова"

    fun startError(message: String?): String = "Ошибка запуска: ${message?.trim()?.takeIf { it.isNotEmpty() } ?: "неизвестная"}"

    /** VK calls could not be made: a token VK refused names what to do, a link's token is the owner's to renew. */
    fun vkCallsNotCreated(tokenInvalid: Boolean, tokenFromLink: Boolean): String = when {
        !tokenInvalid -> VK_CALLS_NOT_CREATED
        tokenFromLink -> LINK_TOKEN_EXPIRED
        else -> VK_SIGN_IN_AGAIN
    }

    /** A notification of its own only while the app is not in front; in front, «Подключение» shows the cause. */
    fun announces(appInFront: Boolean): Boolean = !appInFront

    /**
     * At the app's start, a cause an earlier process stored is shown only while no run is on or starting and none is
     * held: a system restart's restore that began meanwhile is the state, not the old stop.
     */
    fun restored(stored: String?, running: Boolean, starting: Boolean, current: String?): String? =
        stored?.trim()?.takeIf { it.isNotEmpty() && !running && !starting && current == null }
}

/** B5h1 (B3): what a VK-call recovery does once it asked for hashes. */
internal sealed class RecoveryOutcome {
    /** New hashes: the core restarts on them. */
    data object Restart : RecoveryOutcome()

    /** Nothing new now (a transient VK or network failure): the run goes on, the next dead call tries again. */
    data object KeepRunning : RecoveryOutcome()

    /** Retrying cannot help: the run stops with this cause. */
    data class Stop(val cause: String) : RecoveryOutcome()
}

/**
 * [hashes] are the recovery's new hashes, null when none could be got. A token VK refused can never mint again, so once
 * every hash of the run is dead ([runHasLiveHashes] false) it stops the run instead of leaving the core on a dead call
 * forever; while some still live, they carry it (review F1). An empty set stops it too (an «Авто ВК» run needs none).
 * The recovery runs only while the run's own profile is the active one (it resolves the active profile).
 */
internal fun recoveryOutcome(
    hashes: String?,
    autoJs: Boolean,
    tokenInvalid: Boolean,
    tokenFromLink: Boolean,
    runHasLiveHashes: Boolean,
): RecoveryOutcome = when {
    hashes == null -> if (tokenInvalid && !runHasLiveHashes) {
        RecoveryOutcome.Stop(StopCause.vkCallsNotCreated(tokenInvalid = true, tokenFromLink = tokenFromLink))
    } else {
        RecoveryOutcome.KeepRunning
    }
    hashes.isBlank() && !autoJs -> RecoveryOutcome.Stop(StopCause.NO_HASHES_LEFT)
    else -> RecoveryOutcome.Restart
}

/** The stop's own notification: «CSQTT отключён» and the cause; a tap opens the app, the next start withdraws it. */
internal object StopNotice {
    private const val CHANNEL_ID = CsqttConstants.Notifications.STOP_CHANNEL_ID
    private const val NOTIFICATION_ID = CsqttConstants.Notifications.STOP_NOTIFICATION_ID

    /** [silent]: no sound, for a stop the user may have made themselves (a VPN revoke, review F3). */
    fun post(context: Context, cause: String, silent: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "CSQTT: отключения", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Туннель остановился сам: причина и что сделать"
            },
        )
        val open = PendingIntent.getActivity(
            context,
            4,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_c)
            .setContentTitle("CSQTT отключён")
            .setContentText(cause)
            .setStyle(NotificationCompat.BigTextStyle().bigText(cause))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setSilent(silent)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    fun cancel(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
    }
}
