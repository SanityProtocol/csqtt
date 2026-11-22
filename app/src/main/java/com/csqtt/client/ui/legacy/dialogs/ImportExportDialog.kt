package com.csqtt.client.ui.legacy.dialogs

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import com.csqtt.client.showRaisedToast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.csqtt.client.SettingsStore
import com.csqtt.client.copySensitive
import com.csqtt.client.ui.legacy.appSectionCardBorderColor
import com.csqtt.client.ui.legacy.appSectionCardColor
import com.csqtt.client.ui.legacy.components.CsqttSegmentedControl
import com.csqtt.client.ui.legacy.design.CsqttShapes
import com.csqtt.client.ui.legacy.design.CsqttSizes
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private enum class ImportExportTab {
    EXPORT,
    IMPORT
}

@Composable
fun ImportExportDialog(
    settingsStore: SettingsStore,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current

    var selectedTab by rememberSaveable { mutableStateOf(ImportExportTab.EXPORT) }

    var includeTunnel by rememberSaveable { mutableStateOf(true) }
    var includeDeploy by rememberSaveable { mutableStateOf(true) }
    var includeExceptions by rememberSaveable { mutableStateOf(true) }
    var includeSettings by rememberSaveable { mutableStateOf(true) }
    // M-42: the full backup (every profile, VPS record and setting) instead of the active profile's sections
    var fullBackup by rememberSaveable { mutableStateOf(false) }
    var includeServerSecrets by rememberSaveable { mutableStateOf(false) }
    // a backup read for import, waiting for the user to confirm that it replaces everything
    var pendingRestore by remember { mutableStateOf<String?>(null) }
    val tunnelRunning by com.csqtt.client.TunnelManager.running.collectAsState()
    val anySection = fullBackup || includeTunnel || includeDeploy || includeExceptions || includeSettings

    suspend fun exportJson(): String = if (fullBackup) {
        settingsStore.exportBackupJson(includeServerSecrets)
    } else {
        settingsStore.exportConfigJson(
            includeTunnel = includeTunnel,
            includeDeploy = includeDeploy,
            includeExceptions = includeExceptions,
            includeSettings = includeSettings,
        )
    }

    var isProcessing by remember { mutableStateOf(false) }

    val executeImportJson: (String) -> Unit = { jsonText ->
        if (jsonText.isBlank()) {
            context.showRaisedToast("Конфигурация пуста", Toast.LENGTH_SHORT)
        } else if (com.csqtt.client.StoreBackup.isBackup(jsonText)) {
            if (tunnelRunning) {
                context.showRaisedToast("Отключите туннель, чтобы восстановить копию", Toast.LENGTH_SHORT)
            } else {
                pendingRestore = jsonText
            }
        } else {
            scope.launch {
                isProcessing = true
                try {
                    val ok = settingsStore.importConfigJson(
                        jsonString = jsonText,
                        includeTunnel = includeTunnel,
                        includeDeploy = includeDeploy,
                        includeExceptions = includeExceptions,
                        includeSettings = includeSettings
                    )
                    if (ok) {
                        context.showRaisedToast("Конфигурация успешно импортирована ✓", Toast.LENGTH_LONG)
                        onDismissRequest()
                    } else {
                        context.showRaisedToast("Ошибка импорта: неверный формат JSON", Toast.LENGTH_SHORT)
                    }
                } catch (e: Exception) {
                    context.showRaisedToast("Ошибка импорта: ${e.message}", Toast.LENGTH_SHORT)
                } finally {
                    isProcessing = false
                }
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val text = stream.bufferedReader().use { it.readText() }
                    executeImportJson(text)
                }
            } catch (e: Exception) {
                context.showRaisedToast("Не удалось прочитать файл: ${e.message}", Toast.LENGTH_SHORT)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = CsqttShapes.Dialog,
            color = appSectionCardColor(),
            border = BorderStroke(1.dp, appSectionCardBorderColor()),
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 18.dp, vertical = 14.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Импорт и Экспорт",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Закрыть",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                CsqttSegmentedControl(
                    options = listOf(
                        ImportExportTab.EXPORT to "Экспорт",
                        ImportExportTab.IMPORT to "Импорт"
                    ),
                    selected = selectedTab,
                    onSelected = { selectedTab = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CsqttSizes.CompactControlHeight)
                )

                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (selectedTab == ImportExportTab.EXPORT) {
                        ConfigOptionRow(
                            title = "Все профили (полная копия)",
                            checked = fullBackup,
                            onCheckedChange = { fullBackup = it }
                        )
                    }
                    if (fullBackup && selectedTab == ImportExportTab.EXPORT) {
                        ConfigOptionRow(
                            title = "Пароли серверов (SSH, панель)",
                            checked = includeServerSecrets,
                            onCheckedChange = { includeServerSecrets = it }
                        )
                        Text(
                            text = "Файл подключает к вашим серверам: в нём пароли туннеля и хеши" +
                                if (includeServerSecrets) ", а также пароли и ключи SSH и пароли панели." else
                                    ". Пароли SSH и панели в него не попадут.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        )
                    } else {
                        ConfigOptionRow(
                            title = "Туннель",
                            checked = includeTunnel,
                            onCheckedChange = { includeTunnel = it }
                        )
                        ConfigOptionRow(
                            title = "Деплой",
                            checked = includeDeploy,
                            onCheckedChange = { includeDeploy = it }
                        )
                        ConfigOptionRow(
                            title = "Маршрут",
                            checked = includeExceptions,
                            onCheckedChange = { includeExceptions = it }
                        )
                        ConfigOptionRow(
                            title = "Настройки",
                            checked = includeSettings,
                            onCheckedChange = { includeSettings = it }
                        )
                        if (selectedTab == ImportExportTab.IMPORT) {
                            Text(
                                text = "Полная копия узнаётся сама и заменяет все профили — перед этим приложение спросит.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp)
                            )
                        }
                    }
                }

                if (selectedTab == ImportExportTab.EXPORT) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    isProcessing = true
                                    try {
                                        val json = exportJson()
                                        if (context.copySensitive("CSQTT config", json)) {
                                            context.showRaisedToast("Конфиг скопирован в буфер ✓", Toast.LENGTH_SHORT)
                                        }
                                    } catch (e: Exception) {
                                        context.showRaisedToast("Ошибка экспорта: ${e.message}", Toast.LENGTH_SHORT)
                                    } finally {
                                        isProcessing = false
                                    }
                                }
                            },
                            enabled = !isProcessing && anySection,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(CsqttSizes.ControlHeight),
                            shape = CsqttShapes.Pill,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Копировать в буфер",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isProcessing = true
                                    try {
                                        val json = exportJson()
                                        val savedPath = saveJsonToDownloads(context, json, if (fullBackup) "csqtt-backup" else "csqtt-config")
                                        if (savedPath != null) {
                                            context.showRaisedToast("Сохранено: $savedPath ✓", Toast.LENGTH_LONG)
                                        } else {
                                            context.showRaisedToast("Не удалось сохранить файл", Toast.LENGTH_SHORT)
                                        }
                                    } catch (e: Exception) {
                                        context.showRaisedToast("Ошибка: ${e.message}", Toast.LENGTH_SHORT)
                                    } finally {
                                        isProcessing = false
                                    }
                                }
                            },
                            enabled = !isProcessing && anySection,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(CsqttSizes.ControlHeight),
                            shape = CsqttShapes.Pill,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Скачать в Downloads",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val clip = clipboardManager.getText()?.text
                                if (!clip.isNullOrBlank()) {
                                    executeImportJson(clip.trim())
                                } else {
                                    context.showRaisedToast("Буфер обмена пуст", Toast.LENGTH_SHORT)
                                }
                            },
                            enabled = !isProcessing && anySection,
                            modifier = Modifier
                                .weight(1f)
                                .height(CsqttSizes.ControlHeight),
                            shape = CsqttShapes.Pill,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Из буфера",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }

                        OutlinedButton(
                            onClick = { filePickerLauncher.launch("application/json") },
                            enabled = !isProcessing && anySection,
                            modifier = Modifier
                                .weight(1f)
                                .height(CsqttSizes.ControlHeight),
                            shape = CsqttShapes.Pill,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.FileOpen,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Из файла",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }

    pendingRestore?.let { json ->
        RestoreConfirmDialog(
            onConfirm = {
                pendingRestore = null
                scope.launch {
                    isProcessing = true
                    try {
                        if (settingsStore.restoreBackupJson(json)) {
                            context.showRaisedToast("Копия восстановлена ✓", Toast.LENGTH_LONG)
                            onDismissRequest()
                        } else {
                            context.showRaisedToast("Ошибка: файл не является копией csqtt", Toast.LENGTH_SHORT)
                        }
                    } finally {
                        isProcessing = false
                    }
                }
            },
            onDismiss = { pendingRestore = null },
        )
    }
}

@Composable
private fun RestoreConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Восстановить полную копию?") },
        text = { Text("Все профили, серверы и настройки на этом устройстве будут заменены данными из копии.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Заменить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun ConfigOptionRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = MaterialTheme.colorScheme.primary,
                checkmarkColor = MaterialTheme.colorScheme.onPrimary
            )
        )
    }
}

private fun saveJsonToDownloads(context: Context, json: String, prefix: String): String? {
    val filename = "$prefix-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.json"
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                "Downloads/$filename"
            } else {
                null
            }
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir.mkdirs()
            val file = File(downloadsDir, filename)
            file.writeText(json, Charsets.UTF_8)
            file.absolutePath
        }
    } catch (e: Exception) {
        null
    }
}
