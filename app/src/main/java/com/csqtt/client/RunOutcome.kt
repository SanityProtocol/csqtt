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
    /** B6: the server refused this phone: the password's other devices fill its limit, none silent long enough to give way. */
    const val DEVICE_LIMIT = "Достигнут лимит устройств для этого пароля. Отключите VPN на другом устройстве, подождите " +
        "несколько минут, если оно уже выключено, или попросите владельца поднять лимит"

    /**
     * B6: [coreLine] as the device limit's cause when it is the core's refusal for it («FATAL_AUTH: достигнут лимит
     * устройств для этого пароля», its fatal event, or a worker's AUTH_RETRY copy of it in the log); null for any other.
     */
    fun deviceLimit(coreLine: String): String? = DEVICE_LIMIT.takeIf { coreLine.contains("лимит устройств") }

    fun startError(message: String?): String = "Ошибка запуска: ${message?.trim()?.takeIf { it.isNotEmpty() } ?: "неизвестная"}"

    /**
     * H5 (B5h2): a cause can carry the core's own line or an exception's message, and it is stored and announced, so it
     * is masked as a log line is: secrets, links, call links and the server owner's hashes.
     */
    fun masked(cause: String, ownerHashes: List<String>): String =
        LogRedaction.redact(maskOwnerHashes(withoutLogStickers(cause), ownerHashes))

    /**
     * B5f (rule 10): Android ended the run and never restarted it — a phone maker's battery cleaner, as a rule. The cause
     * and no advice (P3 §1.9: the app asks for no battery exemption); the screen puts «Настройки приложения» under it.
     */
    const val SYSTEM_KILLED = "Туннель остановлен системой"
    /** B5f: the phone restarted with the tunnel on; nothing brings it back by itself. */
    const val REBOOTED = "Телефон перезагрузился: туннель выключен"
    /** B5f (review F1): installing an update stops the app, and Android restarts none of its services. */
    const val UPDATED = "CSQTT обновился: туннель выключен"
    /**
     * B5f (review F2, verify N2): a force-stop — Android's «Остановить», a swipe from the recent apps, or a phone maker's
     * battery cleaner, which Android records the same way — so the way to the app's settings stays under it, for the
     * case it was not the user.
     */
    const val STOPPED_OUTSIDE = "Туннель остановлен вне приложения"

    /**
     * B5f: the cause of a run that ended with no stop, decided a few seconds after the app's screen starts — long enough
     * for a sticky restart Android has pending to show as a start. [wasRunning]: the run's own flag (set at its start,
     * cleared by every stop); [savedBoot] and [bootNow]: the phone's boot count then and now; [savedUpdate] and
     * [updateNow]: the app's install time then and now (none saved: an earlier build set the flag, so an update came);
     * [userStopped]: Android says the user stopped the app. Null when there is nothing to say: no run was cut, a run or a
     * start is on, or a cause is already shown.
     */
    fun systemStop(
        wasRunning: Boolean,
        savedBoot: Int?,
        bootNow: Int?,
        savedUpdate: Long?,
        updateNow: Long?,
        userStopped: Boolean,
        runOn: Boolean,
        failure: String?,
    ): String? = when {
        !wasRunning || runOn || failure != null -> null
        savedUpdate == null || (updateNow != null && savedUpdate != updateNow) -> UPDATED
        savedBoot != null && bootNow != null && savedBoot != bootNow -> REBOOTED
        userStopped -> STOPPED_OUTSIDE
        else -> SYSTEM_KILLED
    }

    /** B5e4: VK refused new calls for a reason other than the token, through every retry of the backoff. */
    fun vkRefusesCalls(reason: String?): String =
        "VK не создаёт звонки" + reason?.trim()?.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()

    /** B5e4: the core exits before it is up, start after start; its last line says why, when it said anything. */
    fun crashLoop(lastLine: String?, exitCode: Int?): String =
        lastLine?.trim()?.takeIf { it.isNotEmpty() } ?: "Клиент не запускается (код ${exitCode ?: "?"})"

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

/**
 * What a call recovery works from ([RunKeeps.recoveryBasis]): [source] is where the run goes on and with which
 * password, [hashMode] and [workers] what it runs as, [account] the token its calls are made with (null: the one its
 * profile holds), [forceApi] whether «Авто API» is told to the resolver instead of read from the profile once more.
 * [own]: all of it is the run's own, and the run never leaves its identity.
 */
internal data class RecoveryBasis(
    val own: Boolean,
    val source: ConnectionSource,
    val hashMode: String,
    val workers: Int,
    val account: String?,
    val forceApi: Boolean,
)

/** What the run's profile holds at a recovery: read only for a run on a link's token, which follows its link. */
internal class ProfileReading(val source: ConnectionSource?, val hashMode: String, val workers: Int)

/** What the exit of a core that said «the account is refused» comes to ([RunKeeps.atExit]). */
internal sealed class AccountExit {
    /** The word was not said, or it is not this exit's to decide: the run is no longer wanted, or another cause ended it. */
    data object None : AccountExit()

    /** Another account is stored: the run goes on, on [account]. */
    data class GoOn(val account: String) : AccountExit()

    /** No other account: the core's word is the cause the run stops with, as it always was. */
    data class Stop(val cause: String) : AccountExit()
}

/** What an «Авто API» recovery made ([RunKeeps.mint]), and the [account] that is the run's from here: none when VK refused the last one tried. */
internal class Minted<T>(val made: T, val account: String)

/**
 * Card p3-vk-run-account (the operator, 2026-11-28): a run keeps what it started with. Nothing on «Туннель» is held
 * while the tunnel runs, and what is changed there says «при следующем подключении»: so a call recovery, which makes
 * the run's VK calls again in the middle of a run, takes the VK account, the hash mode, the address, the password and
 * the stream count from the run and not from its profile. The owner's pool of calls, and the panel it is handed to,
 * are still read where they are kept. A run on a link's own token is the exception it always was: its recovery reads
 * the link and the stored mode. VK refusing the run's own account, the run goes on with the account its profile holds
 * by then, when that is another ([next]): in «Авто API» within the recovery ([mint]), in «Авто ВК» at the exit of the
 * core that said so ([atExit]).
 */
internal object RunKeeps {
    /** VK's «the account cannot be used» from the core, as its ERROR event names it: `VK_ACCOUNT_5`, `_17`, `_18`. */
    private const val ACCOUNT_REFUSED = "VK_ACCOUNT_"

    /** The log's line where a run goes on with the stored account ([next]), in either auto mode. */
    const val NEXT_LOG = "[VK] Аккаунт туннеля отклонён VK · продолжаем с выбранным аккаунтом"

    /**
     * What a recovery of [run] works from. A run that did not start on a link's token: its own address, password,
     * hash mode, stream count and account — with no account of its own (a pool that served without one) the one its
     * profile holds; [profile] is not read. A run on a link's token: what [profile] holds, as before the card — null
     * when the profile resolves to nothing, and the recovery gives up.
     */
    fun recoveryBasis(run: TunnelParams, profile: ProfileReading?): RecoveryBasis? {
        if (!run.vkTokenFromLink) {
            return RecoveryBasis(
                own = true,
                source = ConnectionSource(
                    peer = run.peer, password = run.connectionPassword, hashes = "", hashesFromLink = false,
                    vkAccessToken = "", tokenFromLink = false,
                ),
                hashMode = run.vkHashMode,
                workers = run.workersPerHash,
                account = run.vkAccessToken.takeIf { it.isNotBlank() },
                forceApi = true,
            )
        }
        val source = profile?.source ?: return null
        return RecoveryBasis(
            own = false,
            source = source,
            hashMode = profile.hashMode,
            workers = profile.workers,
            account = source.vkAccessToken.takeIf { source.tokenFromLink },
            forceApi = source.tokenFromLink,
        )
    }

    /**
     * VK refused the run's own account ([refused]) and [stored] is the one its profile holds by now. The operator:
     * the run goes on with the stored one. Null when there is none to go on with — nothing stored, or the same account,
     * which then ends the run as it always did.
     */
    fun next(refused: String, stored: String): String? = stored.takeIf { it.isNotBlank() && it != refused }

    /**
     * The calls of an «Авто API» recovery. [make] makes them with [first]; when VK refuses that account ([refused]) and
     * it is the run's own ([own]: not a link's token, and not the stored account of a start), once more with the account
     * the profile holds by then — [stored] is asked only after the refusal, when the refused token has been cleared
     * from where it was held, so the same account is never tried twice. The second time [make] is given what the first
     * left, to go on from it.
     */
    suspend fun <T> mint(
        first: String,
        own: Boolean,
        refused: (T) -> Boolean,
        stored: suspend () -> String,
        make: suspend (account: String, left: T?) -> T,
    ): Minted<T> {
        val made = make(first, null)
        val other = if (own && refused(made)) next(first, stored()) else null
        if (other == null) return Minted(made, first.takeUnless { refused(made) }.orEmpty())
        val again = make(other, made)
        return Minted(again, other.takeUnless { refused(again) }.orEmpty())
    }

    /**
     * «Авто ВК»: the core could not start its calls on the account and says so with a fatal [code]. On the user's own
     * account that word is held until the core has exited, where [atExit] decides between another account and the
     * stop; a link's token ([tokenFromLink]) has no other account, and any other fatal word ends the run at once.
     */
    fun holdsAccountRefusal(code: String, hashMode: String, tokenFromLink: Boolean): Boolean =
        code.startsWith(ACCOUNT_REFUSED) && hashMode == CsqttConstants.VkAutoHash.MODE_AUTO_JS && !tokenFromLink

    /**
     * The exit of a core. [refused]: the word it said of its account, null when it said none. [wanted]: the run is
     * still wanted; [fatal]: another cause has ended it meanwhile — the first cause is the one that counts. With the
     * word said and the exit its to decide: the run goes on with [stored] when that is another account than
     * [runAccount], and stops with the word when it is not. After a go-on the stored account is the run's own, so the
     * same exit a second time stops the run: there is no loop in it.
     */
    fun atExit(refused: String?, wanted: Boolean, fatal: Boolean, runAccount: String, stored: String): AccountExit = when {
        refused == null || !wanted || fatal -> AccountExit.None
        else -> next(runAccount, stored)?.let { AccountExit.GoOn(it) } ?: AccountExit.Stop(refused)
    }
}

/**
 * B5e4: an «Авто ВК» run whose calls are all dead while VK refuses new ones (flood control, for one) — the core reports a
 * dead call once per process, so the recovery retries on this backoff and then stops the run with VK's answer.
 */
internal object RefusalBackoff {
    private val DELAYS_MS = longArrayOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L)

    /** The wait before retry [retry] (from 0), or null once the retries are spent and the run stops. */
    fun delayFor(retry: Int): Long? = DELAYS_MS.getOrNull(retry)
}

/** B5e4: this many exits in a row before the process was up end the run instead of restarting forever (~4 min). */
internal const val CRASH_LOOP_STOP_STREAK = 8

/** The count of exits in a row that came before the process was up ([stableMs]): a stable one starts it again. */
internal fun nextCrashStreak(uptimeMs: Long, streak: Int, stableMs: Long): Int = if (uptimeMs >= stableMs) 0 else streak + 1

/** B5e4: what the exit handler does: the next streak, a crash loop's stop, a restart, and whether the run's calls end. */
internal data class ExitPlan(val nextStreak: Int, val crashLoop: Boolean, val restarts: Boolean, val endCalls: Boolean)

/**
 * [wantsRestart]: the run is still wanted; [fatal]: a fatal cause ended it; [canRestart]: there are params and a context to
 * restart with; [offline]: paused for no network — an exit then says nothing about the core, so it does not count
 * (review F4); [accountChange]: the core ended because VK refused its account and the run goes on with another
 * (RunKeeps.next) — nor does that one. A restart keeps the run's calls (it reuses their hashes); only the run's end
 * ends them.
 */
internal fun exitPlan(
    wantsRestart: Boolean,
    fatal: Boolean,
    canRestart: Boolean,
    uptimeMs: Long,
    streak: Int,
    offline: Boolean,
    stableMs: Long,
    accountChange: Boolean = false,
): ExitPlan {
    val shouldRestart = wantsRestart && !fatal
    val nextStreak = if (offline || accountChange) streak else nextCrashStreak(uptimeMs, streak, stableMs)
    val crashLoop = shouldRestart && nextStreak >= CRASH_LOOP_STOP_STREAK
    val restarts = shouldRestart && !crashLoop && canRestart
    return ExitPlan(nextStreak, crashLoop, restarts, endCalls = wantsRestart && !restarts)
}

/** B5e4: what a recovery that got no new calls does next. */
internal sealed class RefusalStep {
    /** The run goes on: it has live calls, or the recovery restarts or stops it anyway. */
    data object None : RefusalStep()
    data class Retry(val delayMs: Long) : RefusalStep()
    data object Stop : RefusalStep()
}

internal fun refusalStep(keepRunning: Boolean, runHasLiveHashes: Boolean, retry: Int): RefusalStep = when {
    !keepRunning || runHasLiveHashes -> RefusalStep.None
    else -> RefusalBackoff.delayFor(retry)?.let { RefusalStep.Retry(it) } ?: RefusalStep.Stop
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
