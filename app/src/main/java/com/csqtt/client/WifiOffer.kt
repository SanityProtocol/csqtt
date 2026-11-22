package com.csqtt.client

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

/**
 * B5f2 (the operator, 2026-11-22): «Запомнить «<сеть>»?» while the app is not in front — quiet, once per network a run
 * meets, private on the lock screen. The card on «Подключение» asks the same while the app is in front.
 */
internal object WifiOfferNotice {
    private const val CHANNEL_ID = CsqttConstants.Notifications.WIFI_CHANNEL_ID
    private const val NOTIFICATION_ID = CsqttConstants.Notifications.WIFI_OFFER_NOTIFICATION_ID
    const val ACTION_REMEMBER = "com.csqtt.client.WIFI_REMEMBER"
    const val ACTION_DISMISS = "com.csqtt.client.WIFI_DISMISS"
    const val EXTRA_NAME = "wifi_name"
    const val EXTRA_RULE = "wifi_rule"
    private const val TITLE = "Новая сеть Wi-Fi"

    fun post(context: Context, name: String, rule: WifiRule): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "CSQTT: новые сети Wi-Fi", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Предложение запомнить сеть Wi-Fi для автопаузы"
                setShowBadge(false)
            },
        )
        // the offer is replaced, never stacked: an earlier one's network is behind the phone by now
        fun action(action: String, requestCode: Int) = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, WifiOfferReceiver::class.java).setAction(action)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_RULE, rule.stored),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            context,
            7,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val dismiss = action(ACTION_DISMISS, 9)
        // the lock screen shows that there is a question, never the network's name (F6)
        val public = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_c)
            .setContentTitle(TITLE)
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_c)
            .setContentTitle(TITLE)
            .setContentText(WifiCopy.offer(name))
            .setStyle(NotificationCompat.BigTextStyle().bigText("${WifiCopy.offer(name)} ${rule.rememberEffect}"))
            .setContentIntent(open)
            .setDeleteIntent(dismiss)
            .addAction(0, "Не сейчас", dismiss)
            .addAction(0, "Запомнить", action(ACTION_REMEMBER, 8))
            .setAutoCancel(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .build()
        return runCatching { manager.notify(NOTIFICATION_ID, notification) }.isSuccess
    }

    fun cancel(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
    }
}

/** The notice's two actions. Not exported; «Запомнить» acts only under the rule it was offered in, with the switch on. */
class WifiOfferReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != WifiOfferNotice.ACTION_REMEMBER && action != WifiOfferNotice.ACTION_DISMISS) return
        val name = normalizeWifiSsid(intent.getStringExtra(WifiOfferNotice.EXTRA_NAME)) ?: return
        val rule = WifiRule.entries.firstOrNull { it.stored == intent.getStringExtra(WifiOfferNotice.EXTRA_RULE) }
        val app = context.applicationContext
        val pending = goAsync()
        TunnelManager.scope.launch {
            try {
                val store = SettingsStore(app)
                if (action == WifiOfferNotice.ACTION_REMEMBER) {
                    // a stale rule changes nothing: the card on «Подключение» asks again under the rule in force
                    if (rule != null) store.rememberWifiNetwork(name, rule)
                } else {
                    store.dismissWifiNetwork(name)
                }
                // a late action of an earlier offer leaves a newer offer's notice alone
                val offer = TunnelManager.wifiStatus.value?.offer
                if (offer == null || offer == name) WifiOfferNotice.cancel(app)
            } finally {
                pending.finish()
            }
        }
    }
}
