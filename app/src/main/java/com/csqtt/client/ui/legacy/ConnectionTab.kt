package com.csqtt.client.ui.legacy

import android.content.Intent
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import com.csqtt.client.ConnectionState
import com.csqtt.client.buildTunnelStartIntent
import com.csqtt.client.formatTraffic
import com.csqtt.client.showsTraffic
import kotlinx.coroutines.delay
import com.csqtt.client.BatterySaver
import com.csqtt.client.ConnectionStartBlocker
import com.csqtt.client.ConnectionStartPolicy
import com.csqtt.client.CsqttConstants
import com.csqtt.client.HandOverResult
import com.csqtt.client.StopCause
import com.csqtt.client.ManlCaptchaWebViewManager
import com.csqtt.client.SubscriptionMeta
import com.csqtt.client.handOverNoticeLine
import com.csqtt.client.subscriptionNoticeLine
import com.csqtt.client.R
import com.csqtt.client.SettingsStore
import com.csqtt.client.StoredSecretState
import com.csqtt.client.TunnelAuthSnapshot
import com.csqtt.client.TunnelManager
import com.csqtt.client.TunnelService
import com.csqtt.client.VkHashValidationCodec
import com.csqtt.client.newTunnelSessionId
import com.csqtt.client.showRaisedToast
import com.csqtt.client.ui.legacy.components.CsqttScreen
import com.csqtt.client.ui.legacy.design.CsqttShapes
import com.csqtt.client.ui.legacy.design.CsqttSizes
import com.csqtt.client.ui.legacy.utils.parseCsqttLink
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
internal fun ConnectionTab(
    settingsStore: SettingsStore,
    tunnelAuthSettings: TunnelAuthSnapshot,
    onInvalidConfiguration: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val connectionPassword = tunnelAuthSettings.connectionPassword
    val connectionState by TunnelManager.connectionState.collectAsStateWithLifecycle()
    val cooldownActive by TunnelManager.cooldownActive.collectAsStateWithLifecycle()

    val csqttLinkMode = tunnelAuthSettings.csqttLinkMode
    val csqttLink = tunnelAuthSettings.csqttLink
    // null until the store has emitted: a tap before that starts nothing (§4.2 rule 3)
    val storedPeer by settingsStore.peer.collectAsStateWithLifecycle(initialValue = null)
    val peer = storedPeer.orEmpty()
    val vkHashes by settingsStore.vkHashes.collectAsStateWithLifecycle(initialValue = "")
    val vkHashCheckResultsJson by settingsStore.vkHashCheckResults.collectAsStateWithLifecycle(initialValue = "{}")
    val manualPortsEnabled by settingsStore.manualPortsEnabled.collectAsStateWithLifecycle(initialValue = false)
    val serverPeerPort by settingsStore.serverPeerPort.collectAsStateWithLifecycle(
        initialValue = CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT,
    )
    val vkAuthMode by settingsStore.vkAuthMode.collectAsStateWithLifecycle(initialValue = CsqttConstants.VkAuth.MODE_CALLS)
    val savedHashMode by settingsStore.vkHashMode.collectAsStateWithLifecycle(initialValue = SettingsStore.cachedVkHashMode)
    val savedVkAccessToken by settingsStore.vkAccessToken.collectAsStateWithLifecycle(initialValue = SettingsStore.cachedVkAccessToken)

    var pendingStartAfterVpnPermission by remember { mutableStateOf(false) }
    // B5h2 (rule 10): the background operations the user relies on, one line each, not only in «Логи»
    val subscriptionNotice by settingsStore.subscriptionNotice.collectAsStateWithLifecycle(initialValue = null)
    val handOverNotice by settingsStore.handOverNotice.collectAsStateWithLifecycle(initialValue = null)

    val parsedLink = remember(csqttLink) { parseCsqttLink(csqttLink) }
    val linkHashesRemoved by settingsStore.linkHashesRemoved.collectAsStateWithLifecycle(initialValue = emptySet())
    // the owner's hashes still in use: the ones the participant took out do not count
    val linkHashes = parsedLink?.hashes.orEmpty().filterNot { it in linkHashesRemoved }
    val linkTokenActive = csqttLinkMode && parsedLink?.vkAccessToken?.isNotEmpty() == true
    val rawManualHashes = remember(vkHashes) {
        vkHashes.split(Regex("[,\\s\\n]+"))
            .map(String::trim)
            .filter { it.length >= 16 }
            .distinct()
    }
    val activeManualHashes = remember(vkHashes, vkHashCheckResultsJson) {
        VkHashValidationCodec.active(
            rawManualHashes,
            VkHashValidationCodec.decode(vkHashCheckResultsJson),
        )
    }
    val manualHashes = if (activeManualHashes.isNotEmpty()) activeManualHashes else rawManualHashes
    val accountAutoJsMode = vkAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS
    val hashSettingsLoaded = savedHashMode != null && savedVkAccessToken != null
    val autoHashMode = accountAutoJsMode || (
        savedHashMode != null && savedHashMode != CsqttConstants.VkAutoHash.MODE_MANUAL
    )
    val vkTokenActive = savedVkAccessToken?.isNotBlank() == true
    val hashesReady = hashSettingsLoaded && ConnectionStartPolicy.hasReadyVkCredentials(
        linkMode = csqttLinkMode,
        linkHashCount = linkHashes.size,
        linkTokenPresent = linkTokenActive,
        autoHashMode = autoHashMode,
        storedTokenPresent = vkTokenActive,
        manualHashCount = manualHashes.size,
    )
    val peerPortValid = !manualPortsEnabled || serverPeerPort in 1..65535
    val trimmedPeer = peer.trim()
    val isLocalOrUnspecifiedPeer = trimmedPeer == "0.0.0.0" || trimmedPeer == "127.0.0.1" || trimmedPeer.equals("localhost", ignoreCase = true) || trimmedPeer == "::1"
    val startBlocker = ConnectionStartPolicy.blocker(
        linkMode = csqttLinkMode,
        linkValid = parsedLink != null,
        peerValid = trimmedPeer.isNotBlank() && !trimmedPeer.contains(":") && !isLocalOrUnspecifiedPeer,
        peerPortValid = peerPortValid,
        connectionPasswordSet = connectionPassword.isNotBlank(),
        connectionPasswordReadable = tunnelAuthSettings.connectionPasswordState != StoredSecretState.Unreadable,
        hashesReady = hashesReady,
    )
    val isValid = startBlocker == null

    fun startTunnelService() {
        scope.launch {
            TunnelManager.isLoggingEnabled = settingsStore.loggingEnabled.first()
            val nextGen = System.currentTimeMillis() / 1000L
            val salt = newTunnelSessionId()
            val intent = buildTunnelStartIntent(context, settingsStore, nextGen, salt) ?: run {
                context.showRaisedToast("Заполните настройки подключения", Toast.LENGTH_SHORT)
                return@launch
            }
            runCatching { context.startForegroundService(intent) }
                .onFailure { error ->
                    TunnelManager.updateLog(
                        "foreground_request_error",
                        "Android заблокировал запуск VPN: ${error.message ?: error.javaClass.simpleName}",
                        99,
                        true,
                    )
                    Toast.makeText(
                        context,
                        "Android заблокировал запуск VPN. Проверьте ограничения батареи приложения.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (pendingStartAfterVpnPermission) {
            pendingStartAfterVpnPermission = false
            if (VpnService.prepare(context) == null) {
                startTunnelService()
            } else {
                context.showRaisedToast("VPN-разрешение не выдано", Toast.LENGTH_SHORT)
            }
        }
    }

    fun toggleTunnel() {
        val state = TunnelManager.currentConnectionState()
        if (state is ConnectionState.Stopping || TunnelManager.stopping.value) return
        if (state.powerIsOn) {
            pendingStartAfterVpnPermission = false
            context.startService(Intent(context, TunnelService::class.java).apply { action = "STOP" })
            return
        }
        if (storedPeer == null || !hashSettingsLoaded || !tunnelAuthSettings.isLoaded) return
        if (!isValid) {
            val blocker = requireNotNull(startBlocker)
            TunnelManager.updateLog(
                "connection_configuration_${blocker.name}",
                "[ПОДКЛЮЧЕНИЕ] Запуск не начат: ${blocker.message}",
                99,
                true,
            )
            if (blocker == ConnectionStartBlocker.StoredSecret) {
                scope.launch {
                    val reset = settingsStore.resetUnreadableConnectionPassword()
                    if (reset) {
                        context.showRaisedToast(
                            "Сохранённый пароль сброшен. Введите его заново в разделе «Авторизация».",
                            Toast.LENGTH_LONG,
                        )
                    }
                    onInvalidConfiguration()
                }
                return
            }
            onInvalidConfiguration()
            return
        }
        val vpnIntent = VpnService.prepare(context)
        if (vpnIntent != null) {
            pendingStartAfterVpnPermission = true
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            startTunnelService()
        }
    }

    CsqttScreen {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(bottom = 16.dp)
                .offset(y = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            TunnelConnectionPanel(
                state = connectionState,
                cooldownActive = cooldownActive,
                configurationValid = isValid,
                onToggleTunnel = { toggleTunnel() },
                subscription = subscriptionNotice,
                handOver = handOverNotice,
            )
        }
    }
}

@Composable
private fun TunnelConnectionPanel(
    state: ConnectionState,
    cooldownActive: Boolean,
    configurationValid: Boolean,
    onToggleTunnel: () -> Unit,
    subscription: SubscriptionMeta? = null,
    handOver: HandOverResult? = null,
) {
    val tunnelStopping = state is ConnectionState.Stopping
    val powerOn = state.powerIsOn
    val canToggle = !tunnelStopping && (powerOn || !cooldownActive || !configurationValid)
    val activeVisual = powerOn || tunnelStopping
    val logoScale = if (activeVisual) 1.06f else 0.96f
    val statusColor = when (state) {
        is ConnectionState.Connected -> MaterialTheme.colorScheme.primary
        ConnectionState.Preparing, ConnectionState.Connecting -> MaterialTheme.colorScheme.primary
        is ConnectionState.Failed -> MaterialTheme.colorScheme.error
        ConnectionState.ServerNotResponding, ConnectionState.CaptchaNeeded -> MaterialTheme.colorScheme.error
        ConnectionState.Idle -> if (cooldownActive) {
            MaterialTheme.colorScheme.tertiary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        else -> MaterialTheme.colorScheme.tertiary
    }
    // «Подключено» only on a working tunnel (M-43, #45)
    val statusText = when {
        state is ConnectionState.Idle && cooldownActive -> "Ожидание"
        state is ConnectionState.Failed -> "Отключено"
        else -> state.label
    }

    AppSectionCard(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_c_logo),
                contentDescription = when {
                    tunnelStopping -> "Ожидание отключения VPN"
                    powerOn -> "Остановить VPN"
                    else -> "Включить VPN"
                },
                modifier = Modifier
                    .size(172.dp)
                    .clip(CsqttShapes.Pill)
                    .clickable(
                        enabled = canToggle,
                        onClick = onToggleTunnel,
                    )
                    .scale(logoScale),
                colorFilter = if (activeVisual) {
                    null
                } else {
                    ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
                },
                alpha = when {
                    activeVisual -> 1f
                    canToggle -> 0.66f
                    else -> 0.34f
                },
            )

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = statusColor,
                textAlign = TextAlign.Center,
            )

            if (state is ConnectionState.Failed) {
                Text(
                    text = state.reason,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                // B5f: the cause asks for a setting, so it carries the way there (review: «the battery prompt beside it»)
                if (state.reason == StopCause.SYSTEM_KILLED || state.reason == StopCause.STOPPED_OUTSIDE) BackgroundWorkButton()
            }

            // «Нужна капча» (B5h2): the captcha screen, which follows the core's ask; the logo still stops
            if (state is ConnectionState.CaptchaNeeded) {
                val context = LocalContext.current
                Button(
                    onClick = { runCatching { context.startActivity(ManlCaptchaWebViewManager.promptIntent(context)) } },
                    modifier = Modifier.height(CsqttSizes.CompactControlHeight),
                    shape = CsqttShapes.Pill,
                ) {
                    Text("Решить капчу", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }

            TunnelUptimeText(tunnelRunning = activeVisual)

            if (showsTraffic(state)) TunnelLiveFigures()

            NotificationsOffWarning()

            subscription?.let { meta ->
                subscriptionNoticeLine(meta)?.let { line ->
                    // a failure in the error colour; skipped nodes of a refresh that worked as a warning (review)
                    val failed = meta.lastError.isNotEmpty()
                    OutcomeLine(line, if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)
                }
            }
            handOver?.let {
                OutcomeLine(
                    handOverNoticeLine(it),
                    if (it.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }

            // «Экономия батареи» in force: said under the status, where the user looks
            val batterySaving by BatterySaver.active.collectAsStateWithLifecycle()
            if (batterySaving && state.powerIsOn) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.BatterySaver,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = BatterySaver.BADGE,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** The uptime ticks once a second only while this text is on a started screen (M-48): no ticker runs off-screen. */
@Composable
private fun TunnelUptimeText(tunnelRunning: Boolean) {
    val mark by TunnelManager.uptime.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(mark, lifecycle) {
        if (mark.startedAtMs <= 0L) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = SystemClock.elapsedRealtime()
                delay(1_000L - (now - mark.startedAtMs).mod(1_000L))
            }
        }
    }
    val seconds = mark.frozenSeconds
        ?: mark.startedAtMs.takeIf { it > 0L }?.let { (now - it).coerceAtLeast(0L) / 1_000L }
    Text(
        text = formatTunnelUptime(seconds),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = if (tunnelRunning) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f)
        },
    )
}

/** Live traffic (M-89) and workers: active / target · the server's limit (L-27), read only while shown. */
@Composable
private fun TunnelLiveFigures() {
    val traffic by TunnelManager.traffic.collectAsStateWithLifecycle()
    val active by TunnelManager.activeWorkers.collectAsStateWithLifecycle()
    val target by TunnelManager.targetWorkers.collectAsStateWithLifecycle()
    val serverLimit by TunnelManager.serverAssignedWorkers.collectAsStateWithLifecycle()
    val trafficText = formatTraffic(traffic)
    if (trafficText.isNotEmpty()) {
        Text(
            text = trafficText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
    Text(
        text = workersLine(active, target, serverLimit),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

internal fun workersLine(active: Int, target: Int, serverLimit: Int?): String = buildString {
    append("Потоки: ").append(active)
    if (target > 0) append(" / ").append(target)
    if (serverLimit != null) append(" · сервер: ").append(serverLimit)
}

/**
 * B5f: «Разрешить работу в фоне» — Android's own request to run without battery limits; already exempt, the app's settings,
 * where the phone's maker keeps its own background limits.
 */
@Composable
private fun BackgroundWorkButton() {
    val context = LocalContext.current
    TextButton(
        onClick = {
            val exempt = context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName) == true
            val packageUri = Uri.parse("package:${context.packageName}")
            val intent = if (exempt) {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
            } else {
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
            }
            runCatching { context.startActivity(intent) }
                .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)) } }
        },
    ) {
        Text("Разрешить работу в фоне", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** B5h2: one background operation's last outcome (a subscription refresh, the hand-over of the pool to the server). */
@Composable
private fun OutcomeLine(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        textAlign = TextAlign.Center,
    )
}

/** Notifications off: the status and the captcha prompt cannot show (L-29). Checked on each return to the screen. */
@Composable
private fun NotificationsOffWarning() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(true) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }
    if (enabled) return
    Text(
        text = "Уведомления выключены — капча не появится",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier.clickable {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
    )
}

private fun formatTunnelUptime(seconds: Long?): String {
    val totalSeconds = seconds?.coerceAtLeast(0L) ?: 0L
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val sec = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, sec)
}

private fun formatMillis(value: Double): String = when {
    value < 0.05 -> "wall 0 мс"
    value < 10.0 -> String.format(Locale.US, "wall %.1f мс", value)
    else -> String.format(Locale.US, "wall %.0f мс", value)
}

private fun formatRate(value: Double): String = when {
    value < 0.05 -> "0/с"
    value < 10.0 -> String.format(Locale.US, "%.1f/с", value)
    else -> String.format(Locale.US, "%.0f/с", value)
}
