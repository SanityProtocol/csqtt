package com.csqtt.client

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VpnWidgetToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                toggleTunnelFromQuickAction(context) { target -> openActivity(context, target) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle tunnel from widget", e)
                context.showRaisedToast("Ошибка запуска: ${e.localizedMessage}", Toast.LENGTH_SHORT)
            } finally {
                VpnWidgetProvider.updateAllWidgets(context)
                pendingResult.finish()
            }
        }
    }

    private fun openActivity(context: Context, intent: Intent) {
        runCatching {
            PendingIntent.getActivity(
                context,
                200,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            ).send()
        }.onFailure {
            context.startActivity(intent)
        }
    }

    companion object {
        const val ACTION = CsqttConstants.Widget.ACTION_TOGGLE
        private const val TAG = "VpnWidget"
    }
}
