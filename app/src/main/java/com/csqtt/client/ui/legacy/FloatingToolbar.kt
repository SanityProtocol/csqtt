package com.csqtt.client.ui.legacy

import kotlinx.coroutines.launch
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.verticalScroll
import com.csqtt.client.CsqttConstants
import com.csqtt.client.TunnelManager
import com.csqtt.client.ui.legacy.components.CsqttSettingRow
import com.csqtt.client.ui.legacy.design.CsqttShapes
import com.csqtt.client.ui.legacy.design.CsqttSizes
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import com.csqtt.client.showRaisedToast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import com.csqtt.client.ui.legacy.dialogs.ImportExportDialog
import com.csqtt.client.ui.legacy.dialogs.QrScannerDialog
import com.csqtt.client.ui.legacy.dialogs.LinkImportDialog
import com.csqtt.client.ui.legacy.dialogs.TurnSubnetsDialog

@Composable
fun FloatingToolbar(
    settingsStore: SettingsStore,
    csqttLinkMode: Boolean,
    activeProfile: Int,
    onActiveProfileChange: (Int) -> Unit,
    currentTheme: String,
    onThemeChange: (String) -> Unit,
    activeFingerprint: String,
    onFingerprintChange: (String) -> Unit,
    autoPauseOnWifi: Boolean,
    onAutoPauseOnWifiChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** The way to «Сервер» for a user who has no server yet (P3 B0: the tab is in the bar only for one who has); null hides it. */
    onOpenServer: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vkAuthMode by settingsStore.vkAuthMode.collectAsStateWithLifecycle(
        initialValue = CsqttConstants.VkAuth.MODE_CALLS,
    )
    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val selectedTurnSubnet by settingsStore.selectedTurnSubnet.collectAsStateWithLifecycle(
        initialValue = "",
    )
    val turnSubnetIpLimit by settingsStore.turnSubnetIpLimit.collectAsStateWithLifecycle(
        initialValue = "ALL",
    )
    var isExpanded by rememberSaveable { mutableStateOf(false) }
    var showQrScanner by rememberSaveable { mutableStateOf(false) }
    var scannedLink by rememberSaveable { mutableStateOf<String?>(null) }
    var showImportExportDialog by rememberSaveable { mutableStateOf(false) }
    var showAutoPauseInfo by rememberSaveable { mutableStateOf(false) }
    var showTurnSubnetsDialog by rememberSaveable { mutableStateOf(false) }
    val profileList by settingsStore.profileList.collectAsStateWithLifecycle(initialValue = emptyList())
    var editingProfileIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var editingProfileName by rememberSaveable { mutableStateOf("") }
    // the delete confirmation inside the name dialog; true when the profile's VPS record (SSH data) goes with it
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    var deleteTakesVps by rememberSaveable { mutableStateOf(false) }
    val isDeploying by com.csqtt.client.DeployManager.isDeploying.collectAsStateWithLifecycle()
    val isDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val settingsIconTint = if (isDarkTheme) Color.White else Color.Black

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showQrScanner = true
        } else {
            context.showRaisedToast("Требуется разрешение на камеру для сканирования QR-кода", Toast.LENGTH_SHORT)
        }
    }

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
            onClick = {
                val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                if (hasPermission) {
                    showQrScanner = true
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            modifier = Modifier.size(38.dp),
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_qr_scan),
                contentDescription = "Сканировать QR-код",
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

    if (showQrScanner) {
        QrScannerDialog(
            onDismissRequest = { showQrScanner = false },
            onQrCodeScanned = { rawLink ->
                if (com.csqtt.client.ui.legacy.utils.parseCsqttLink(rawLink) != null) {
                    // the same confirmation as a csqtt:// link (M-70): never written without asking
                    showQrScanner = false
                    scannedLink = rawLink.trim()
                    true
                } else {
                    context.showRaisedToast("QR-код не содержит ссылку CSQTT", Toast.LENGTH_SHORT)
                    false
                }
            }
        )
    }

    scannedLink?.let { raw ->
        LinkImportDialog(settingsStore = settingsStore, raw = raw, onDone = { scannedLink = null })
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
                                // the operator (2026-11-21): the new profile opens for editing and the tunnel stays on the
                                // profile it runs on (adding is not a pick: nothing reconnects)
                                val runName = profileList.firstOrNull { it.first == TunnelManager.runTargetProfile() }?.second
                                    ?.takeIf { TunnelManager.running.value || TunnelManager.starting.value || TunnelManager.autoPausedForWifi.value }
                                scope.launch {
                                    when {
                                        settingsStore.addProfile() == null ->
                                            context.showRaisedToast("Все 32 профиля заняты", Toast.LENGTH_SHORT)
                                        runName != null ->
                                            context.showRaisedToast("Туннель остаётся на «$runName»", Toast.LENGTH_SHORT)
                                    }
                                }
                            } else {
                                key.toIntOrNull()?.let(onActiveProfileChange)
                            }
                        },
                        onItemEdit = { key ->
                            val p = key.toIntOrNull() ?: 0
                            editingProfileIndex = p
                            editingProfileName = profileList.firstOrNull { it.first == p }?.second.orEmpty()
                            confirmingDelete = false
                        },
                        itemEditable = { it != ADD_PROFILE_KEY },
                    )

                    CompactDropdownSetting(
                        title = "Отпечаток",
                        selectedKey = activeFingerprint,
                        options = listOf(
                            "chrome" to "Chrome",
                            "safari" to "Safari",
                            "firefox" to "Firefox",
                            "edge" to "Edge",
                            "opera" to "Opera",
                        ),
                        enabled = true,
                        onSelected = onFingerprintChange,
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

                    if (!csqttLinkMode) {
                        SheetAction("Выбор TURN подсетей", onClick = { showTurnSubnetsDialog = true })
                    }

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

    if (showTurnSubnetsDialog) {
        TurnSubnetsDialog(
            selectedSubnetId = selectedTurnSubnet,
            turnSubnetIpLimit = turnSubnetIpLimit,
            onSelectSubnet = { id ->
                scope.launch { settingsStore.saveSelectedTurnSubnet(id) }
            },
            onSelectIpLimit = { limit ->
                scope.launch { settingsStore.saveTurnSubnetIpLimit(limit) }
            },
            onDismiss = { showTurnSubnetsDialog = false },
        )
    }

    editingProfileIndex?.let { p ->
        Dialog(
            onDismissRequest = { editingProfileIndex = null; confirmingDelete = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                shape = CsqttShapes.Dialog,
                color = appSectionCardColor(),
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, appSectionCardBorderColor()),
                tonalElevation = 0.dp,
                modifier = Modifier.fillMaxWidth(0.92f),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Название профиля",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(
                            onClick = { editingProfileIndex = null },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (confirmingDelete) {
                        Text(
                            text = if (deleteTakesVps) {
                                "Профиль «$editingProfileName» и данные для входа на его сервер (SSH, пароли установки) " +
                                    "будут удалены из приложения. Сам сервер и установка на нём не меняются."
                            } else {
                                "Профиль «$editingProfileName» будет удалён из приложения."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { confirmingDelete = false },
                                modifier = Modifier.weight(1f).height(CsqttSizes.ControlHeight),
                                shape = CsqttShapes.Control,
                            ) { Text("Отмена", maxLines = 1) }
                            Button(
                                onClick = {
                                    val targetProfile = p
                                    editingProfileIndex = null
                                    confirmingDelete = false
                                    scope.launch { settingsStore.deleteProfileFromClassic(targetProfile) }
                                },
                                modifier = Modifier.weight(1f).height(CsqttSizes.ControlHeight),
                                shape = CsqttShapes.Control,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                            ) { Text("Удалить", maxLines = 1) }
                        }
                    } else {
                        OutlinedTextField(
                            value = editingProfileName,
                            onValueChange = { editingProfileName = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = CsqttShapes.Control,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            ),
                            trailingIcon = {
                                IconButton(
                                    onClick = { editingProfileName = "" },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Очистить",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            },
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    when {
                                        tunnelRunning || TunnelManager.starting.value ->
                                            context.showRaisedToast("Отключите туннель, чтобы удалить профиль", Toast.LENGTH_SHORT)
                                        isDeploying ->
                                            context.showRaisedToast("Дождитесь конца установки", Toast.LENGTH_SHORT)
                                        else -> scope.launch {
                                            deleteTakesVps = settingsStore.profileHoldsOnlyLinkToVps(p)
                                            confirmingDelete = true
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f).height(CsqttSizes.ControlHeight),
                                shape = CsqttShapes.Control,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            ) { Text("Удалить", maxLines = 1) }
                            Button(
                                onClick = {
                                    val targetProfile = p
                                    val targetName = editingProfileName
                                    editingProfileIndex = null
                                    scope.launch {
                                        settingsStore.saveProfileName(targetProfile, targetName)
                                    }
                                },
                                modifier = Modifier.weight(1f).height(CsqttSizes.ControlHeight),
                                shape = CsqttShapes.Control,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            ) {
                                Text(
                                    text = "Сохранить",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
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
