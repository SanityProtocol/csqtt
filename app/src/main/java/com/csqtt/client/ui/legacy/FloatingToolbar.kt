package com.csqtt.client.ui.legacy

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.csqtt.client.SettingsStore
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.AppUpdates
import com.csqtt.client.BuildConfig
import com.csqtt.client.R

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.verticalScroll
import com.csqtt.client.CsqttConstants
import com.csqtt.client.ui.legacy.components.CsqttSettingRow
import com.csqtt.client.ui.legacy.design.CsqttShapes
import com.csqtt.client.ui.legacy.design.CsqttSizes
import androidx.compose.ui.platform.LocalContext
import com.csqtt.client.ui.legacy.dialogs.ImportExportDialog

@Composable
fun FloatingToolbar(
    settingsStore: SettingsStore,
    activeProfile: Int,
    onActiveProfileChange: (Int) -> Unit,
    currentTheme: String,
    onThemeChange: (String) -> Unit,
    autoPauseOnWifi: Boolean,
    onAutoPauseOnWifiChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** The way to «Сервер» for a user who has no server yet (P3 B0: the tab is in the bar only for one who has); null hides it. */
    onOpenServer: (() -> Unit)? = null,
    /** «+ Добавить профиль» of the picker: the shell's «Добавить профиль» (P3 B2). */
    onAddProfile: () -> Unit = {},
) {
    val context = LocalContext.current
    var isExpanded by rememberSaveable { mutableStateOf(false) }
    var showImportExportDialog by rememberSaveable { mutableStateOf(false) }
    var showAutoPauseInfo by rememberSaveable { mutableStateOf(false) }
    val profileList by settingsStore.profileList.collectAsStateWithLifecycle(initialValue = emptyList())
    val isDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val settingsIconTint = if (isDarkTheme) Color.White else Color.Black

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
    ) {
        IconButton(
            onClick = { showImportExportDialog = true },
            modifier = Modifier.size(38.dp),
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_import_export),
                contentDescription = "Импорт и экспорт конфигурации",
                modifier = Modifier.size(24.dp),
                tint = settingsIconTint,
            )
        }

        IconButton(
            onClick = { isExpanded = true },
            modifier = Modifier.size(38.dp),
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_settings_gear),
                contentDescription = stringResource(R.string.quick_settings),
                modifier = Modifier.size(24.dp),
                tint = settingsIconTint,
            )
        }
    }

    if (showImportExportDialog) {
        ImportExportDialog(
            settingsStore = settingsStore,
            onDismissRequest = { showImportExportDialog = false }
        )
    }

    if (isExpanded) {
            Dialog(
                onDismissRequest = { isExpanded = false },
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
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Настройки",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(
                            onClick = { isExpanded = false },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.action_close),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // participants switch profiles too (one per link), as in V2. The real profiles only, and a last item
                    // that adds one (M-97)
                    CompactDropdownSetting(
                        title = "Профиль конфигурации",
                        selectedKey = activeProfile.toString(),
                        options = profileList.map { (profile, name) -> profile.toString() to name } +
                            (ADD_PROFILE_KEY to "+ Добавить профиль"),
                        enabled = true,
                        onSelected = { key ->
                            if (key == ADD_PROFILE_KEY) {
                                // the ways to add a profile are the shell's sheet now; this dialog would lie over it
                                isExpanded = false
                                onAddProfile()
                            } else {
                                key.toIntOrNull()?.let(onActiveProfileChange)
                            }
                        },
                    )

                    CompactDropdownSetting(
                        title = "Оформление",
                        selectedKey = currentTheme,
                        options = listOf(
                            CsqttConstants.Theme.MODE_SYSTEM to "Система",
                            CsqttConstants.Theme.MODE_LIGHT to "Светлая",
                            CsqttConstants.Theme.MODE_DARK to "Тёмная",
                        ),
                        enabled = true,
                        onSelected = onThemeChange,
                    )

                    CsqttSettingRow(
                        title = "Автопауза при Wi-Fi",
                        checked = autoPauseOnWifi,
                        onCheckedChange = onAutoPauseOnWifiChange,
                        onInfoClick = { showAutoPauseInfo = true },
                        infoContentDescription = "Как работает автопауза при Wi-Fi",
                    )

                    // B5f2: where the pause acts, and the networks of the two list rules
                    if (autoPauseOnWifi) WifiRuleControls(settingsStore)

                    // P3 B0: «Показывать деплой» is gone, so someone without a server opens «Сервер» from here; the tab
                    // stays in the bar while it is open, and for good once a server is there
                    if (onOpenServer != null) {
                        SheetAction("Установить свой сервер", onClick = {
                            isExpanded = false
                            onOpenServer()
                        })
                    }

                    // M-91 (B5g): the check on demand; «Инфо», which held it, has had no way in since 2.5.0 (review F1).
                    // It runs in the app's scope: its answer is a toast, or the update dialog for a newer version
                    val checkingUpdates by AppUpdates.checking.collectAsStateWithLifecycle()
                    SheetAction(
                        text = if (checkingUpdates) "Проверяем обновления…" else "Проверить обновления",
                        enabled = !checkingUpdates,
                        onClick = {
                            AppUpdates.checkNow(context, settingsStore, "v${BuildConfig.VERSION_NAME.removePrefix("v")}")
                        },
                    )
                }
            }
        }
    }

    if (showAutoPauseInfo) {
        WifiAutoPauseInfoDialog(onDismiss = { showAutoPauseInfo = false })
    }

}

private const val ADD_PROFILE_KEY = "add"

/** An action at the foot of «Настройки»: the sheet's outlined pill, the width of the sheet. */
@Composable
private fun SheetAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(CsqttSizes.ControlHeight),
        shape = CsqttShapes.Pill,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
