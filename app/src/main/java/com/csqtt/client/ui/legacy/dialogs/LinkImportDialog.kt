package com.csqtt.client.ui.legacy.dialogs

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.LinkImport
import com.csqtt.client.LinkImportState
import com.csqtt.client.SettingsStore
import com.csqtt.client.TunnelManager
import com.csqtt.client.showRaisedToast
import com.csqtt.client.ui.legacy.utils.parseCsqttLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * M-70: the one confirmation every link import goes through (the csqtt:// deep link, the QR scan). It names the
 * profile it would replace, offers a new profile, and never writes the active one while the tunnel runs.
 */
@Composable
internal fun LinkImportDialog(settingsStore: SettingsStore, raw: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val link = remember(raw) { parseCsqttLink(raw) }
    if (link == null) {
        LaunchedEffect(raw) {
            context.showRaisedToast("Неверная ссылка CSQTT", Toast.LENGTH_SHORT)
            onDone()
        }
        return
    }
    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val state by produceState<LinkImportState?>(initialValue = null, tunnelRunning) {
        value = settingsStore.linkImportState(tunnelRunning)
    }
    val plan = state?.let { LinkImport.plan(LinkImport.endpoint(link.host, link.port), it) }

    fun import(intoNew: Boolean) {
        val appContext = context.applicationContext
        // the process-wide scope: closing the dialog never cuts the write short
        TunnelManager.scope.launch {
            val written = settingsStore.importParticipantLink(raw.trim(), intoNew, activate = !TunnelManager.running.value)
            withContext(Dispatchers.Main) {
                appContext.showRaisedToast(
                    written?.let { "Ссылка CSQTT сохранена в профиль «${it.second}»" } ?: "Все 32 профиля заняты",
                    Toast.LENGTH_SHORT,
                )
            }
        }
        onDone()
    }

    val credential = when {
        link.hashes.isNotEmpty() -> "VK-хешей в ссылке: ${link.hashes.size}"
        link.vkAccessToken.isNotEmpty() -> "Устаревшая ссылка с токеном VK: попросите у владельца новую"
        else -> "В ссылке нет VK-хешей: добавьте свои или войдите в VK"
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Импорт ссылки CSQTT") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Сервер: ${link.host}:${link.port}")
                Text(credential, color = MaterialTheme.colorScheme.onSurfaceVariant)
                plan?.note?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                if (plan?.intoActive == true) {
                    val active = state?.activeName.orEmpty()
                    TextButton(onClick = { import(intoNew = false) }) {
                        Text(if (plan.activeIsSameOrEmpty) "В профиль «$active»" else "Заменить профиль «$active»")
                    }
                }
                if (plan?.intoNew == true) {
                    TextButton(onClick = { import(intoNew = true) }) { Text("В новый профиль") }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Отмена") } },
    )
}
