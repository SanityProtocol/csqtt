package com.csqtt.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

/**
 * B5h2 (B4): the run needs the user to solve a captcha, one episode per need. An ask from the core opens it. It stays open
 * when the ask times out or fails, because the core asks again after its 60 s captcha lock and its auto chain (about
 * 1.5–2 min), and a captcha solved after the core stopped waiting is dropped. A solved captcha, the run connecting or
 * getting credentials with no ask live, a stop, or no new ask for [IDLE_MS] end it. Pure, for the tests.
 */
internal class CaptchaEpisode {
    var open = false
        private set
    var live = false
        private set
    private var notified = false

    /** An ask. True when it posts the episode's one notification: the first ask that finds the app not in front. */
    @Synchronized
    fun ask(appInFront: Boolean): Boolean {
        open = true
        live = true
        if (appInFront || notified) return false
        notified = true
        return true
    }

    /** The live ask ended; a solved one ends the episode. True when the episode ended. */
    @Synchronized
    fun askEnded(solved: Boolean): Boolean {
        live = false
        return solved && end()
    }

    /** The run connected or got credentials, or no ask came for a while: the episode ends unless an ask is live. */
    @Synchronized
    fun settled(): Boolean = !live && end()

    @Synchronized
    fun stop(): Boolean {
        live = false
        return end()
    }

    private fun end(): Boolean {
        if (!open) return false
        open = false
        notified = false
        return true
    }

    companion object {
        /** No new ask this long after the last one ended: the need is stale (the core asks again within ~2 min). */
        const val IDLE_MS = 4 * 60_000L
    }
}

object ManlCaptchaWebViewManager {
    private const val CAPTCHA_TIMEOUT_MS = CsqttConstants.Timeouts.CAPTCHA_MANUAL_TIMEOUT_MS

    val captchaMutex = Mutex()
    val pendingResult = AtomicReference<CompletableDeferred<Result<String>>?>(null)
    private val activeActivityRef = AtomicReference<WeakReference<ManlCaptchaActivity>?>(null)
    var activeActivity: ManlCaptchaActivity?
        get() = activeActivityRef.get()?.get()
        set(value) {
            activeActivityRef.set(value?.let(::WeakReference))
        }
    /** The captcha screen is started: the app is in front for the prompt, as with MainActivity. */
    @Volatile
    var screenStarted = false
    @Volatile
    var pendingIntentToStart: Intent? = null

    private val episode = CaptchaEpisode()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var idleJob: Job? = null
    @Volatile
    private var appContext: Context? = null

    private val neededState = MutableStateFlow(false)
    /** The captcha episode is open: the connection state is «Нужна капча» (L-28; B5h2 B4). */
    val needed: StateFlow<Boolean> = neededState
    private val liveAskState = MutableStateFlow<String?>(null)
    /** The redirect URI of the ask the core is waiting on, for the captcha screen; null between asks. */
    val liveAsk: StateFlow<String?> = liveAskState

    /** The captcha screen. It follows the live ask and waits between asks (B5h2). */
    fun promptIntent(context: Context): Intent = Intent(context, ManlCaptchaActivity::class.java).addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
    )

    fun checkAndShowPendingCaptcha(context: Context) {
        val intent = pendingIntentToStart
        if (intent != null && !screenStarted) {
            context.startActivity(intent)
        }
    }

    /** The run stops or is replaced: the live ask is answered and the episode ends, atomically with an ask's start. */
    fun cancelCaptcha() {
        val ended = synchronized(episode) {
            pendingResult.getAndSet(null)?.completeExceptionally(CancellationException("Cancelled by system"))
            episode.stop().also { if (it) publishEndLocked() }
        }
        if (ended) clearCaptchaNotification()
    }

    /** The run connected or the core got credentials: a need with no ask live is over. Cheap with no episode open. */
    fun onRunProgress() {
        if (!neededState.value) return
        if (synchronized(episode) { episode.settled().also { if (it) publishEndLocked() } }) clearCaptchaNotification()
    }

    private fun publishEndLocked() {
        idleJob?.cancel()
        idleJob = null
        neededState.value = episode.open
    }

    private const val NOTIFICATION_ID = CsqttConstants.Notifications.CAPTCHA_NOTIFICATION_ID
    private const val CHANNEL_ID = CsqttConstants.Notifications.CAPTCHA_CHANNEL_ID

    private fun showCaptchaNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Уведомления защиты (Капча)",
            NotificationManager.IMPORTANCE_HIGH
        )
        notificationManager.createNotificationChannel(channel)

        // the screen reads the live ask itself, so the one notification of an episode serves every ask in it
        val openPendingIntent = PendingIntent.getActivity(
            context, 0, promptIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val cancelIntent = Intent(context, CaptchaCancelReceiver::class.java)
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context, 1, cancelIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Требуется подтверждение капчи")
            .setContentText("ВК запросил проверку безопасности. Нажмите для решения.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openPendingIntent)
            .setAutoCancel(true)
            .addAction(0, "Отменить и выключить", cancelPendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun clearCaptchaNotification() {
        val context = appContext ?: return
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
        }
    }

    suspend fun solveCaptchaAsync(context: Context, redirectUri: String, sessionToken: String): String {
        if (sessionToken.isBlank()) throw IllegalArgumentException("Пустой session token captcha")
        return captchaMutex.withLock {
            val app = context.applicationContext
            appContext = app
            val deferred = CompletableDeferred<Result<String>>()

            val notify = synchronized(episode) {
                pendingResult.getAndSet(deferred)?.cancel()
                idleJob?.cancel()
                idleJob = null
                episode.ask(appInFront = MainActivity.isForeground || screenStarted).also {
                    liveAskState.value = redirectUri
                    neededState.value = true
                }
            }
            if (notify) showCaptchaNotification(app)

            val intent = promptIntent(app)
            pendingIntentToStart = intent

            // an open screen loads this ask itself
            if (MainActivity.isForeground && !screenStarted) {
                context.startActivity(intent)
            }

            var solved = false
            try {
                withTimeout(CAPTCHA_TIMEOUT_MS) {
                    deferred.await().getOrThrow()
                }.also { solved = true }
            } finally {
                pendingResult.set(null)
                pendingIntentToStart = null
                // a timeout or an error keeps the episode, and the screen waits for the core's next ask
                val ended = synchronized(episode) {
                    liveAskState.value = null
                    val ended = episode.askEnded(solved)
                    if (ended) {
                        publishEndLocked()
                    } else if (episode.open) {
                        idleJob = scope.launch {
                            delay(CaptchaEpisode.IDLE_MS)
                            onRunProgress()
                        }
                    }
                    ended
                }
                if (ended) clearCaptchaNotification()
            }
        }
    }

    fun notifyResult(result: Result<String>) {
        val deferred = pendingResult.getAndSet(null) ?: return
        if (!deferred.isCompleted) {
            deferred.complete(result)
        }
    }
}
