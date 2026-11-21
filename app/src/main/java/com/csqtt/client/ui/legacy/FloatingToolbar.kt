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
import com.csqtt.client.R

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.verticalScroll
import com.csqtt.client.CsqttConstants
import com.csqtt.client.TunnelManager
import com.csqtt.client.ui.legacy.components.CsqttSegmentedControl
import com.csqtt.client.ui.legacy.components.CsqttSettingRow
import com.csqtt.client.ui.legacy.design.CsqttShapes
import com.csqtt.client.ui.legacy.design.CsqttSizes
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import com.csqtt.client.BatterySaver
import com.csqtt.client.ui.legacy.dialogs.ImportExportDialog
import com.csqtt.client.ui.legacy.dialogs.QrScannerDialog
import com.csqtt.client.ui.legacy.dialogs.LinkImportDialog
import com.csqtt.client.ui.legacy.dialogs.SupportDeveloperDialog
import com.csqtt.client.ui.legacy.dialogs.TurnSubnetsDialog

private enum class UsageRole { Owner, Participant }

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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // M-75: the role is the user's (what the app shows); how this profile connects is its own switch below
    val uiOwnerMode by settingsStore.uiOwnerMode.collectAsStateWithLifecycle(initialValue = !csqttLinkMode)
    val selectedRole = if (uiOwnerMode) UsageRole.Owner else UsageRole.Participant
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
    var showSupportDeveloperDialog by rememberSaveable { mutableStateOf(false) }
    var showImportExportDialog by rememberSaveable { mutableStateOf(false) }
    var showAutoPauseInfo by rememberSaveable { mutableStateOf(false) }
    var showBatterySaverInfo by rememberSaveable { mutableStateOf(false) }
    val batterySaverMode by settingsStore.batterySaverMode.collectAsStateWithLifecycle(
        initialValue = BatterySaver.DEFAULT_MODE,
    )
    val batterySaverPercent by settingsStore.batterySaverPercent.collectAsStateWithLifecycle(
        initialValue = BatterySaver.DEFAULT_PERCENT,
    )
    var showTurnSubnetsDialog by rememberSaveable { mutableStateOf(false) }
    val profileList by settingsStore.profileList.collectAsStateWithLifecycle(initialValue = emptyList())
    val participantShowLogs by settingsStore.participantShowLogs.collectAsStateWithLifecycle(
        initialValue = false,
    )
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
            Toast.makeText(context, "Требуется разрешение на камеру для сканирования QR-кода", Toast.LENGTH_SHORT).show()
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
    ) {
        IconButton(
            onClick = { showSupportDeveloperDialog = true },
            modifier = Modifier.size(38.dp),
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_donate_heart),
                contentDescription = "Поддержать разработчика",
                modifier = Modifier.size(24.dp),
                tint = settingsIconTint,
            )
        }

        if (uiOwnerMode) {
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

    if (showSupportDeveloperDialog) {
        SupportDeveloperDialog(
            onDismissRequest = { showSupportDeveloperDialog = false }
        )
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
                    Toast.makeText(context, "QR-код не содержит ссылку CSQTT", Toast.LENGTH_SHORT).show()
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

                    Text(
                        "Ваша роль использования",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )

                    CsqttSegmentedControl(
                        options = listOf(
                            UsageRole.Owner to "Владелец",
                            UsageRole.Participant to "Участник",
                        ),
                        selected = selectedRole,
                        onSelected = { role ->
                            val owner = role == UsageRole.Owner
                            if (owner != uiOwnerMode) scope.launch { settingsStore.saveUiOwnerMode(owner) }
                        },
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .height(CsqttSizes.CompactControlHeight),
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

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
                                scope.launch {
                                    if (settingsStore.addProfile() == null) {
                                        Toast.makeText(context, "Все 32 профиля заняты", Toast.LENGTH_SHORT).show()
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

                    // M-75: how this profile connects; a change mid-session would land at the next recovery restart
                    CsqttSettingRow(
                        title = "Подключение по ссылке",
                        description = if (tunnelRunning) "Отключите туннель, чтобы переключить" else "Ссылка csqtt:// от владельца сервера",
                        checked = csqttLinkMode,
                        enabled = !tunnelRunning,
                        onCheckedChange = { enabled -> scope.launch { settingsStore.saveCsqttLinkMode(enabled) } },
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

                    CompactDropdownSetting(
                        title = BatterySaver.TITLE,
                        selectedKey = batterySaverMode,
                        options = BatterySaver.MODES,
                        enabled = true,
                        onSelected = { mode -> scope.launch { settingsStore.saveBatterySaverMode(mode) } },
                        onTitleInfo = { showBatterySaverInfo = true },
                    )

                    // the level counts only in «Свой уровень»; «Авто» follows the phone's battery saver, so it is shown greyed
                    if (batterySaverMode != BatterySaver.MODE_OFF) {
                        CompactDropdownSetting(
                            title = BatterySaver.LEVEL_TITLE,
                            selectedKey = batterySaverPercent.toString(),
                            options = (BatterySaver.PERCENT_CHOICES + batterySaverPercent).distinct().sorted()
                                .map { percent -> percent.toString() to "≤ $percent %" },
                            enabled = batterySaverMode == BatterySaver.MODE_LEVEL,
                            onSelected = { key ->
                                key.toIntOrNull()?.let { percent -> scope.launch { settingsStore.saveBatterySaverPercent(percent) } }
                            },
                        )
                    }

                    if (!uiOwnerMode) {
                        CsqttSettingRow(
                            title = "Показывать логи",
                            checked = participantShowLogs,
                            onCheckedChange = { checked ->
                                scope.launch { settingsStore.saveParticipantShowLogs(checked) }
                            },
                        )
                    }

                    if (!csqttLinkMode) {
                        OutlinedButton(
                            onClick = { showTurnSubnetsDialog = true },
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
                                text = "Выбор TURN подсетей",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
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

    if (showBatterySaverInfo) {
        BatterySaverInfoDialog(onDismiss = { showBatterySaverInfo = false })
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
                                        tunnelRunning || TunnelManager.starting.value -> Toast.makeText(
                                            context, "Отключите туннель, чтобы удалить профиль", Toast.LENGTH_SHORT,
                                        ).show()
                                        isDeploying -> Toast.makeText(
                                            context, "Дождитесь конца установки", Toast.LENGTH_SHORT,
                                        ).show()
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
