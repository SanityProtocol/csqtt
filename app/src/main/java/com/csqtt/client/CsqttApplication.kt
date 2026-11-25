package com.csqtt.client

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
class CsqttApplication : Application() {
    /** What outlives the screens (§4.2 rules 8 and 9): made once, with the process. */
    internal val owners = AppOwners()

    override fun onCreate() {
        super.onCreate()
        // B5g: the log's tail an earlier process left goes in front of this one's; a crash writes this one's
        LogTail.restore(this)
        val crashHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                TunnelManager.updateLog(
                    "app_crash",
                    "✗ Сбой приложения: ${error.javaClass.name}: ${error.message.orEmpty().take(300)}",
                    99,
                    true,
                )
                LogTail.saveNow()
            }
            crashHandler?.uncaughtException(thread, error)
        }
        DeployManager.init(this)

        // The QS tile and the widgets follow the tunnel from process start, not from MainActivity:
        // a tile or widget tap starts the process too, and the tile is refreshed only on request.
        TunnelManager.initObservers(this)

        // B5h2 (verify N1): a captcha need lives in one process; a notice an earlier process left (an app update, a crash)
        // is no question any more, and its «Отменить и выключить» would stop a run the system restored
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .cancel(CsqttConstants.Notifications.CAPTCHA_NOTIFICATION_ID)
        }

        val settingsStore = SettingsStore(this)
        // B5h1: the cause of a stop an earlier process recorded is the state again, unless a run began meanwhile
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            TunnelManager.restoreStoppedCause(runCatching { settingsStore.lastStopCause.first() }.getOrNull())
        }
    }
}
