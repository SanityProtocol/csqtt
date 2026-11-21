package com.csqtt.client

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.HttpURLConnection
import java.net.URL

private const val TUNNEL_NOTIFICATION_CHANNEL_ID = CsqttConstants.Notifications.TUNNEL_CHANNEL_ID
private const val TUNNEL_NOTIFICATION_ID = CsqttConstants.Notifications.TUNNEL_NOTIFICATION_ID
private const val NOTIFICATION_MIN_REBUILD_INTERVAL_MS = 5_000L
private const val NETWORK_SWAP_SETTLE_MS = 4_000L
private const val NETWORK_LOSS_GRACE_MS = 2_000L
// Network callbacks drive the Wi-Fi auto-pause; this poll is only a safety net. The pause now lasts as long as the
// phone stays on Wi-Fi (hours), so a 1 s poll woke the app every second for all of it.
private const val AUTO_PAUSE_WIFI_RECONCILE_MS = 15_000L
private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1_000L
private const val AWAIT_NETWORK_SHOWN_AFTER_MS = 5_000L
private const val AWAIT_NETWORK_RESCAN_MS = 15_000L
// M-74: the tunnel service refreshes due subscriptions at most this often while connected
private const val SUBSCRIPTION_CHECK_WHILE_CONNECTED_MS = 60 * 60_000L
private const val WAKE_LOCK_RENEWAL_WINDOW_MS = 60 * 1_000L
private const val VK_PROBE_CONNECT_TIMEOUT_MS = 3_000
private const val VK_PROBE_READ_TIMEOUT_MS = 3_000
private val VK_PROBE_URLS = listOf(
    "https://login.vk.ru/",
    "https://api.vk.com/",
    "https://vk.ru/",
)

private data class ResolvedVkHashes(
    val value: String,
    val allowWorkerRedistribution: Boolean,
    val mode: String = CsqttConstants.VkAutoHash.MODE_MANUAL,
    val accessToken: String = "",
)

class TunnelService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Default)

    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockExpiresAtElapsedMs = 0L
    private var wifiLock: WifiManager.WifiLock? = null
    private var updateJob: Job? = null
    private var startJob: Job? = null
    @Volatile
    private var startGeneration = 0L
    // the tunnel status notification: built and posted only while the screen is on, ≥ 5 s apart (M-48)
    private val notificationThrottle = NotificationThrottle(NOTIFICATION_MIN_REBUILD_INTERVAL_MS)
    @Volatile
    private var isStopping = false
    @Volatile
    private var resourcesReleased = false
    private var foregroundStarted = false
    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var airplaneModeReceiver: BroadcastReceiver? = null
    private var networkSwapJob: Job? = null
    private var networkLossJob: Job? = null
    private val physicalNetworks = PhysicalNetworkTracker<Network>()
    private val physicalCandidates = mutableSetOf<Network>()
    private val vkProbeJobs = mutableMapOf<Network, Job>()
    private val vkProbeLock = Any()
    private var vkRecoveryJob: Job? = null
    private var autoPauseSettingsJob: Job? = null
    private var autoPauseWifiWatchJob: Job? = null
    private var domainRoutingSettingsJob: Job? = null
    private val autoPauseMutex = Mutex()
    private val wifiNetworkLock = Any()
    private val physicalWifiNetworks = mutableSetOf<Network>()
    @Volatile
    private var autoPauseOnWifiEnabled = false
    @Volatile
    private var autoPauseRequested = false
    @Volatile
    private var autoPausedForWifi = false
    // «Экономия батареи»: the setting, and the charge as the sticky battery broadcast last said (main thread)
    @Volatile
    private var batterySaverMode = BatterySaver.DEFAULT_MODE
    // nothing is decided before the stored mode is read: the sticky battery broadcast arrives at once, and the default
    // («Авто») could let go of the locks for a moment under a user's «Выкл»
    @Volatile
    private var batterySaverLoaded = false
    @Volatile
    private var batterySaverPercent = BatterySaver.DEFAULT_PERCENT
    private var batteryLevel = -1
    private var batteryCharging = false
    private var systemPowerSave = false
    private var batteryReceiver: BroadcastReceiver? = null
    private var batterySaverJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        TunnelManager.activeScope = serviceScope
        createNotificationChannel()

        acquireWakeLock()
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        registerAirplaneModeReceiver()
        registerPhysicalNetworkCallback()
        observeAutoPauseOnWifi()
        observeDomainRoutingSettings()
        observeBatterySaver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            return if (restoreTunnel()) START_STICKY else START_NOT_STICKY
        }

        when (intent.action) {
            "START" -> {
                if (isStopping) return START_NOT_STICKY
                isStopping = false
                resourcesReleased = false
                startJob?.cancel()
                val requestGeneration = ++startGeneration
                autoPauseRequested = true
                TunnelManager.beginStartRequest()
                val notification = createNotification("Запуск...")
                if (!tryStartPersistentForeground(notification, "запуск VPN")) {
                    TunnelManager.starting.value = false
                    stopSelf()
                    return START_NOT_STICKY
                }

                val intentGenId = intent.getLongExtra("generation_id", 0L)
                val intentSalt = intent.getStringExtra("session_salt") ?: ""

                startJob = serviceScope.launch {
                    try {
                        val store = SettingsStore(applicationContext)
                        // the run is wanted from now on, however long it waits for a network: a kill and the system's
                        // restart (restoreTunnel) bring it back; every way a start ends saves false (stopTunnel)
                        store.saveTunnelWasRunning(true)
                        autoPauseOnWifiEnabled = store.autoPauseOnWifi.first()
                        TunnelManager.isLoggingEnabled = store.loggingEnabled.first()
                        if (reconcileWifiAutoPause()) {
                            TunnelManager.starting.value = false
                            return@launch
                        }
                        if (!isCurrentStart(requestGeneration)) return@launch
                        val genId = store.reserveConnectionGeneration(intentGenId)
                        val salt = if (intentSalt.isNotBlank() && genId == intentGenId) {
                            intentSalt
                        } else {
                            newTunnelSessionId()
                        }

                        val requestedWorkers = intent.getIntExtra("workers_per_hash", CsqttConstants.Tunnel.DEFAULT_WORKERS)
                        val requestedVkAuthMode = sanitizeVkAuthMode(intent.getStringExtra("vk_auth_mode"))
                        val intentHashes = intent.getStringExtra("vk_hashes") ?: ""
                        val hashesFromLink = intent.getBooleanExtra("vk_hashes_from_link", false)
                        val linkAccessToken = intent.getStringExtra("vk_access_token")
                            .orEmpty()
                            .takeIf { intent.getBooleanExtra("vk_token_from_link", false) && it.isNotBlank() }
                        val configuredHashMode = store.vkHashMode.first()
                        val vkAuthMode = when {
                            hashesFromLink && requestedVkAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS -> CsqttConstants.VkAuth.MODE_CALLS
                            linkAccessToken != null -> {
                                if (configuredHashMode == CsqttConstants.VkAutoHash.MODE_AUTO_JS) {
                                    CsqttConstants.VkAuth.MODE_AUTO_JS
                                } else {
                                    if (requestedVkAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS) CsqttConstants.VkAuth.MODE_CALLS else requestedVkAuthMode
                                }
                            }
                            else -> requestedVkAuthMode
                        }
                        val accountAutoJs = vkAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS
                        val extraWorkers = !accountAutoJs && store.extraWorkers.first()
                        val maxWorkers = WorkerCountPolicy.defaultMaximum(extraWorkers)
                        val workersPerHash = WorkerCountPolicy.normalize(requestedWorkers, maximum = maxWorkers)
                        TunnelManager.isLoggingEnabled = store.loggingEnabled.first()
                        if (accountAutoJs) {
                            store.saveVkAuthMode(CsqttConstants.VkAuth.MODE_AUTO_JS)
                        } else if (hashesFromLink && requestedVkAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS) {
                            store.saveVkAuthMode(CsqttConstants.VkAuth.MODE_CALLS)
                        }
                        awaitVkNetwork()
                        if (!isCurrentStart(requestGeneration)) return@launch
                        val resolvedHashes = if (hashesFromLink && linkAccessToken == null && !accountAutoJs) {
                            ResolvedVkHashes(intentHashes, false)
                        } else {
                            resolveAutoHashes(
                                store,
                                intentHashes,
                                workersPerHash,
                                when {
                                    accountAutoJs -> CsqttConstants.VkAutoHash.MODE_AUTO_JS
                                    linkAccessToken != null -> CsqttConstants.VkAutoHash.MODE_AUTO_API
                                    else -> null
                                },
                                linkAccessToken,
                                tokenFromLink = linkAccessToken != null,
                            )
                        }
                        if (resolvedHashes == null) {
                            TunnelManager.starting.value = false
                            TunnelManager.updateLog(
                                "vk_auto_calls_error",
                                "Авто-режим: не удалось создать звонки VK (нет access token или ошибка VK)",
                                99,
                                true,
                            )
                            launch(Dispatchers.Main) { if (isCurrentStart(requestGeneration)) stopTunnel(finishVkCalls = true) }
                            return@launch
                        }

                        val routing = store.routingSnapshot.first()   // M-57: one read, the same rule as every push

                        val params = TunnelParams(
                            peer = intent.getStringExtra("peer") ?: "",
                            vkHashes = resolvedHashes.value,
                            secondaryVkHash = intent.getStringExtra("secondary_vk_hash") ?: "",
                            workersPerHash = workersPerHash,
                            port = intent.getIntExtra("port", 0),
                            sni = intent.getStringExtra("sni") ?: "",
                            connectionPassword = intent.getStringExtra("connection_password") ?: "",
                            protocol = intent.getStringExtra("protocol") ?: "udp",
                            vkAuthMode = vkAuthMode,
                            captchaMode = sanitizeCaptchaMode(intent.getStringExtra("captcha_mode")),
                            captchaSolveMethod = intent.getStringExtra("captcha_solve_method") ?: "auto",
                            fingerprint = intent.getStringExtra("fingerprint") ?: "firefox",
                            clientIds = intent.getStringExtra("client_ids") ?: CsqttConstants.Tunnel.DEFAULT_CLIENT_IDS,
                            obfsMode = intent.getStringExtra("obfs_mode")
                                ?.takeIf { it == "unified" }
                                ?: CsqttConstants.Tunnel.DEFAULT_OBFS_MODE,
                            turnTransport = intent.getStringExtra("turn_transport")
                                ?.takeIf { it == CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS }
                                ?: CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT,
                            dpiSplit = DpiSplitMode.fromStored(intent.getStringExtra(DpiSplitMode.EXTRA)),
                            srtpMode = sanitizeSrtpMode(intent.getStringExtra("srtp_mode")),
                            customTurnIps = intent.getStringExtra("custom_turn_ips") ?: "",
                            generationId = genId,
                            sessionSalt = salt,
                            allowHashRedistribution = resolvedHashes.allowWorkerRedistribution,
                            vkHashMode = resolvedHashes.mode,
                            vkAccessToken = resolvedHashes.accessToken,
                            domainRoutingEnabled = routing.hasDomainRules,
                            domainIsWhitelist = routing.domainIsWhitelist,
                            excludeRuZones = routing.useRuZones,
                            templateDomains = routing.useTemplate,
                            directDomains = routing.activeDomains,
                            ipRoutingEnabled = routing.hasIpRules,
                            ipIsWhitelist = routing.ipIsWhitelist,
                            directIps = routing.activeIps,
                        )
                        launch(Dispatchers.Main) {
                            if (isCurrentStart(requestGeneration)) startTunnel(params)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("TunnelService", "Unable to prepare tunnel start", e)
                        TunnelManager.updateLog(
                            "service_start_error",
                            "Ошибка подготовки VPN-сервиса: ${e.message ?: e.javaClass.simpleName}",
                            99,
                            true,
                        )
                        TunnelManager.starting.value = false
                        launch(Dispatchers.Main) { if (isCurrentStart(requestGeneration)) stopTunnel(finishVkCalls = true) }
                    }
                }
            }
            "STOP", "DISCONNECT" -> {
                stopTunnel(finishVkCalls = true)
            }
            CsqttConstants.General.ACTION_RESTART_WHEN_VK_REACHABLE -> verifyVkAndRestart()
            CsqttConstants.General.ACTION_VERIFY_VK_REACHABILITY -> verifyVkAndSuspend()
            CsqttConstants.General.ACTION_RECOVER_VK_CALL -> {
                recoverUnavailableVkCall(
                    intent.getStringExtra(CsqttConstants.General.EXTRA_UNAVAILABLE_VK_HASH).orEmpty(),
                )
            }
            "DEPLOY_START" -> {
                try {
                    isStopping = false
                    resourcesReleased = false
                    val notification = createNotification("Установка на сервер...", "DEPLOY_CANCEL", "Отменить")
                    startPersistentForeground(notification)
                    prepareForDeploy()
                    acquireWakeLock()
                } catch (e: Exception) {
                    DeployManager.writeError(
                        "Deploy foreground service error (${e.javaClass.simpleName}): ${e.message}\n" +
                            e.stackTraceToString().take(1200)
                    )
                    TunnelManager.addDeployErrorLog(
                        "Не удалось запустить сервис установки: ${e.message?.take(120) ?: e.javaClass.simpleName}"
                    )
                    DeployManager.stopDeploy("Не удалось запустить сервис установки: ${e.message?.take(120)}")
                    runCatching { releaseTunnelResources() }
                    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                    foregroundStarted = false
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            "DEPLOY_CANCEL" -> {
                com.csqtt.client.DeployManager.writeError("✗ Установка отменена пользователем")
                com.csqtt.client.DeployManager.stopDeploy("error: Отменена пользователем")
                if (TunnelManager.running.value) {
                    notificationThrottle.invalidate()
                    postTunnelStatus()
                } else {
                    stopTunnel()
                }
            }
            "DEPLOY_STOP" -> {
                if (!TunnelManager.running.value) {
                    stopTunnel()
                } else {
                    updateNotification("Туннель активен")
                }
            }
            "RESTORE_NOTIFICATION" -> {
                if (foregroundStarted && !isStopping) {
                    notificationThrottle.invalidate()
                    postTunnelStatus()
                }
            }
        }
        return START_STICKY
    }

    private fun restoreTunnel(): Boolean {
        if (isStopping || !serviceJob.isActive) return false
        startJob?.cancel()
        val requestGeneration = ++startGeneration
        isStopping = false
        resourcesReleased = false
        TunnelManager.beginStartRequest()
        val notification = createNotification("Восстановление соединения...")
        if (!tryStartPersistentForeground(notification, "восстановление VPN")) {
            TunnelManager.starting.value = false
            stopSelf()
            return false
        }

        val appContext = applicationContext
        startJob = serviceScope.launch {
            try {
                val store = SettingsStore(appContext)

                val wasRunning = store.tunnelWasRunning.first()
                if (!wasRunning) {
                    Log.d("TunnelService", "restoreTunnel: tunnelWasRunning=false, автостарт отменён — чужой VPN не нужно убивать")
                    launch(Dispatchers.Main) { stopTunnel() }
                    return@launch
                }
                autoPauseRequested = true
                autoPauseOnWifiEnabled = store.autoPauseOnWifi.first()
                TunnelManager.isLoggingEnabled = store.loggingEnabled.first()
                if (reconcileWifiAutoPause()) return@launch

                val source = resolveConnectionSource(store)
                if (source == null) {
                    TunnelManager.updateLog(
                        "connection_source_error",
                        "[ПОДКЛЮЧЕНИЕ] Не удалось разобрать сохранённую ссылку или найти адрес сервера. Откройте настройки подключения.",
                        99,
                        true,
                    )
                    launch(Dispatchers.Main) {
                        if (isCurrentStart(requestGeneration)) stopTunnel(finishVkCalls = true)
                    }
                    return@launch
                }
                if (
                    !store.csqttLinkMode.first() &&
                    store.connectionPasswordState.first().state == StoredSecretState.Unreadable
                ) {
                    TunnelManager.updateLog(
                        "connection_secret_unreadable",
                        "[ПОДКЛЮЧЕНИЕ] Не удалось прочитать сохранённый пароль подключения. Откройте настройки и введите его заново.",
                        99,
                        true,
                    )
                    launch(Dispatchers.Main) {
                        if (isCurrentStart(requestGeneration)) stopTunnel(finishVkCalls = true)
                    }
                    return@launch
                }
                val genId = store.reserveConnectionGeneration()
                val salt = newTunnelSessionId()
                val requestedVkAuthMode = sanitizeVkAuthMode(store.vkAuthMode.first())
                TunnelManager.isLoggingEnabled = store.loggingEnabled.first()
                val configuredHashMode = store.vkHashMode.first()
                val vkAuthMode = when {
                    source.hashesFromLink && requestedVkAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS -> CsqttConstants.VkAuth.MODE_CALLS
                    source.tokenFromLink -> {
                        if (configuredHashMode == CsqttConstants.VkAutoHash.MODE_AUTO_JS) {
                            CsqttConstants.VkAuth.MODE_AUTO_JS
                        } else {
                            if (requestedVkAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS) CsqttConstants.VkAuth.MODE_CALLS else requestedVkAuthMode
                        }
                    }
                    else -> requestedVkAuthMode
                }
                val accountAutoJs = vkAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS
                val workersPerHash = WorkerCountPolicy.normalize(
                    store.workersPerHash.first(),
                    maximum = WorkerCountPolicy.defaultMaximum(
                        extraWorkers = !accountAutoJs && store.extraWorkers.first(),
                    ),
                )
                awaitVkNetwork()
                if (!isCurrentStart(requestGeneration)) return@launch
                val restoredHashes = when {
                    source.hashesFromLink && !source.tokenFromLink && !accountAutoJs -> ResolvedVkHashes(source.hashes, false)
                    else -> resolveAutoHashes(
                        store,
                        source.hashes,
                        workersPerHash,
                        when {
                            accountAutoJs -> CsqttConstants.VkAutoHash.MODE_AUTO_JS
                            source.tokenFromLink -> CsqttConstants.VkAutoHash.MODE_AUTO_API
                            else -> null
                        },
                        source.vkAccessToken.takeIf { source.tokenFromLink },
                        tokenFromLink = source.tokenFromLink,
                    )
                }
                val routing = store.routingSnapshot.first()   // M-57: one read, the same rule as every push

                val params = TunnelParams(
                    peer = source.peer,
                    vkHashes = restoredHashes?.value ?: "",
                    secondaryVkHash = store.secondaryVkHash.first(),
                    workersPerHash = workersPerHash,
                    port = 0,
                    sni = store.sni.first(),
                    connectionPassword = source.password,
                    obfsMode = store.obfsMode.first(),
                    turnTransport = store.turnTransport.first(),
                    dpiSplit = store.dpiSplit.first(),
                    srtpMode = sanitizeSrtpMode(store.srtpMode.first()),
                    customTurnIps = store.customTurnIps.first(),
                    vkAuthMode = vkAuthMode,
                    captchaMode = sanitizeCaptchaMode(store.captchaMode.first()),
                    captchaSolveMethod = store.captchaSolveMethod.first(),
                    fingerprint = store.selectedFingerprint.first(),
                    clientIds = store.activeClientIds.first(),
                    generationId = genId,
                    sessionSalt = salt,
                    allowHashRedistribution = restoredHashes?.allowWorkerRedistribution == true,
                    vkHashMode = restoredHashes?.mode ?: CsqttConstants.VkAutoHash.MODE_MANUAL,
                    vkAccessToken = restoredHashes?.accessToken.orEmpty(),
                    domainRoutingEnabled = routing.hasDomainRules,
                    domainIsWhitelist = routing.domainIsWhitelist,
                    excludeRuZones = routing.useRuZones,
                    templateDomains = routing.useTemplate,
                    directDomains = routing.activeDomains,
                    ipRoutingEnabled = routing.hasIpRules,
                    ipIsWhitelist = routing.ipIsWhitelist,
                    directIps = routing.activeIps,
                )
                if (
                    params.peer.isNotEmpty() &&
                    (
                        params.vkHashes.isNotEmpty() ||
                            params.vkHashMode == CsqttConstants.VkAutoHash.MODE_AUTO_JS
                    )
                ) {
                    launch(Dispatchers.Main) {
                        if (isCurrentStart(requestGeneration)) startTunnel(params)
                    }
                } else {
                    TunnelManager.updateLog(
                        "connection_credentials_error",
                        "[ПОДКЛЮЧЕНИЕ] В ссылке и настройках нет действующих VK-хэшей или VK-токена.",
                        99,
                        true,
                    )
                    launch(Dispatchers.Main) {
                        if (isCurrentStart(requestGeneration)) stopTunnel(finishVkCalls = true)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("TunnelService", "Unable to restore tunnel", e)
                TunnelManager.updateLog(
                    "service_restore_error",
                    "Ошибка восстановления VPN: ${e.message ?: e.javaClass.simpleName}",
                    99,
                    true,
                )
                launch(Dispatchers.Main) {
                    if (isCurrentStart(requestGeneration)) stopTunnel(finishVkCalls = true)
                }
            }
        }
        return true
    }

    private suspend fun resolveAutoHashes(
        store: SettingsStore,
        fallbackHashes: String,
        workersPerHash: Int,
        forcedMode: String? = null,
        accessTokenOverride: String? = null,
        tokenFromLink: Boolean = false,
        deadHash: String? = null,
    ): ResolvedVkHashes? {
        return when (val mode = forcedMode ?: store.vkHashMode.first()) {
            CsqttConstants.VkAutoHash.MODE_AUTO_API -> {
                val token = accessTokenOverride ?: store.vkAccessToken.first()
                // D6 (a1): an owner whose server this app can reach runs on its pool, which outlives the tunnel (B5e3b)
                val panel = if (tokenFromLink) null else store.activePoolPanel()
                if (panel != null) {
                    val pool = store.vkPool(panel.vps)
                    val fill = VkAutoCallsManager.fillPool(applicationContext, token, pool, deadHash, workersPerHash)
                    if (fill.hashes != pool) store.saveVkPool(panel.vps, fill.hashes)
                    if (fill.hashes.isEmpty()) return null
                    // the server's default follows the pool: once, in the background; a failure waits for the next run
                    serviceScope.launch {
                        VkPoolHandOver.handOver(store, panel)?.let { (ok, line) ->
                            TunnelManager.updateLog("vk_pool_handover", line, if (ok) 30 else 70, !ok)
                        }
                    }
                    return ResolvedVkHashes(
                        fill.hashes.joinToString(","),
                        fill.hashes.size < VkAutoCallsManager.callCountForWorkers(workersPerHash),
                        mode,
                    )
                }
                val result = VkAutoCallsManager.startAutoCalls(
                    applicationContext,
                    token,
                    workersPerHash,
                    clearStoredTokenOnInvalid = !tokenFromLink,
                )
                    ?: return null
                ResolvedVkHashes(
                    result.hashes,
                    result.needsWorkerRedistribution,
                    mode,
                )
            }

            CsqttConstants.VkAutoHash.MODE_AUTO_JS -> {
                val token = accessTokenOverride ?: store.vkAccessToken.first()
                if (token.isBlank()) return null
                ResolvedVkHashes(
                    "",
                    true,
                    mode,
                    token,
                )
            }

            else -> ResolvedVkHashes(fallbackHashes, false)
        }
    }

    private fun recoverUnavailableVkCall(hash: String) {
        if (hash.isBlank()) {
            TunnelManager.completeVkCallRecovery()
            return
        }
        serviceScope.launch {
            var restartScheduled = false
            try {
                val previous = TunnelManager.getCurrentParams() ?: return@launch
                val store = SettingsStore(applicationContext)
                val vkHashMode = store.vkHashMode.first()
                val source = resolveConnectionSource(store) ?: return@launch
                if (vkHashMode == CsqttConstants.VkAutoHash.MODE_MANUAL && !source.tokenFromLink) {
                    return@launch
                }
                val accountAutoJs = previous.vkAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS ||
                    (source.tokenFromLink && vkHashMode == CsqttConstants.VkAutoHash.MODE_AUTO_JS)
                val workers = WorkerCountPolicy.normalize(
                    store.workersPerHash.first(),
                    maximum = WorkerCountPolicy.defaultMaximum(
                        extraWorkers = !accountAutoJs && store.extraWorkers.first(),
                    ),
                )
                val resolved = when {
                    accountAutoJs -> resolveAutoHashes(
                        store,
                        fallbackHashes = "",
                        workersPerHash = workers,
                        forcedMode = CsqttConstants.VkAutoHash.MODE_AUTO_JS,
                        accessTokenOverride = source.vkAccessToken.takeIf { source.tokenFromLink },
                        tokenFromLink = source.tokenFromLink,
                    )
                    vkHashMode == CsqttConstants.VkAutoHash.MODE_AUTO_API || source.tokenFromLink -> resolveAutoHashes(
                        store,
                        fallbackHashes = source.hashes,
                        workersPerHash = workers,
                        forcedMode = if (source.tokenFromLink) CsqttConstants.VkAutoHash.MODE_AUTO_API else null,
                        accessTokenOverride = source.vkAccessToken.takeIf { source.tokenFromLink },
                        tokenFromLink = source.tokenFromLink,
                        deadHash = hash,
                    )
                    else -> ResolvedVkHashes(source.hashes, false)
                } ?: return@launch
                if (resolved.value.isBlank() && resolved.mode != CsqttConstants.VkAutoHash.MODE_AUTO_JS) {
                    launch(Dispatchers.Main) { TunnelManager.stop() }
                    return@launch
                }
                val nextGeneration = store.reserveConnectionGeneration(
                    proposed = if (previous.generationId == Long.MAX_VALUE) {
                        Long.MAX_VALUE
                    } else {
                        previous.generationId + 1
                    },
                )
                val next = previous.copy(
                    peer = source.peer,
                    vkHashes = resolved.value,
                    workersPerHash = workers,
                    connectionPassword = source.password,
                    vkAuthMode = if (accountAutoJs) CsqttConstants.VkAuth.MODE_AUTO_JS else previous.vkAuthMode,
                    generationId = nextGeneration,
                    sessionSalt = newTunnelSessionId(),
                    allowHashRedistribution = resolved.allowWorkerRedistribution,
                    vkHashMode = resolved.mode,
                    vkAccessToken = resolved.accessToken,
                )
                launch(Dispatchers.Main) {
                    TunnelManager.start(applicationContext, next, isSwitching = true)
                }
                restartScheduled = true
            } finally {
                if (!restartScheduled) TunnelManager.completeVkCallRecovery()
            }
        }
    }

    private fun tryStartPersistentForeground(notification: Notification, operation: String): Boolean {
        return try {
            startPersistentForeground(notification)
            true
        } catch (e: Exception) {
            Log.e("TunnelService", "Foreground service rejected during $operation", e)
            TunnelManager.updateLog(
                "foreground_start_error",
                "Android заблокировал foreground-сервис ($operation): ${e.message ?: e.javaClass.simpleName}",
                99,
                true,
            )
            false
        }
    }

    private fun isCurrentStart(generation: Long): Boolean =
        generation == startGeneration && !isStopping && serviceJob.isActive

    private fun startTunnel(params: TunnelParams) {
        if (isStopping || !serviceJob.isActive) return
        autoPauseRequested = true
        if (autoPauseOnWifiEnabled && wifiConnectedForAutoPause()) {
            serviceScope.launch { reconcileWifiAutoPause() }
            return
        }
        updateNotification("Подключение...")
        acquireWakeLock()
        acquireWifiLock()

        CaptchaWebViewManager.onTunnelStart(applicationContext)

        serviceScope.launch {
            runCatching { SettingsStore(applicationContext).saveTunnelWasRunning(true) }
        }

        TunnelManager.start(this, params)
        if (!physicalNetworks.hasUsableNetwork()) {
            TunnelManager.suspendForNoNetwork(physicalNetworkPauseReason(isAirplaneModeOn()))
        }
        TunnelManager.scope.launch(Dispatchers.Main) {
            VkAutoCallsManager.replayPendingLogs()
        }
        startStatsUpdater()
    }

    private fun registerPhysicalNetworkCallback() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networkLossJob?.cancel()
                networkLossJob = null
                requestWifiAutoPauseReconciliation()
                rememberPhysicalCandidate(network)
                if (autoPausedForWifi) return
                startVkNetworkProbe(network)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                updatePhysicalWifiNetwork(network, capabilities)
                requestWifiAutoPauseReconciliation()
                if (hasPhysicalInternetCapability(capabilities)) {
                    rememberPhysicalCandidate(network)
                    if (autoPausedForWifi) return
                    startVkNetworkProbe(network)
                } else {
                    forgetPhysicalNetwork(network)
                }
            }

            override fun onLost(network: Network) {
                forgetPhysicalWifiNetwork(network)
                requestWifiAutoPauseReconciliation()
                forgetPhysicalNetwork(network)
            }
        }
        networkCallback = callback
        runCatching {
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(),
                callback,
            )
        }.onFailure {
            Log.w("TunnelService", "Unable to register physical network callback", it)
            networkCallback = null
        }
    }

    private fun rememberPhysicalCandidate(network: Network) {
        synchronized(vkProbeLock) {
            physicalCandidates.add(network)
        }
    }

    private fun isPhysicalCandidate(network: Network): Boolean = synchronized(vkProbeLock) {
        network in physicalCandidates
    }

    private fun startVkNetworkProbe(network: Network) {
        if (autoPausedForWifi) return
        if (physicalNetworks.isUsable(network)) return
        synchronized(vkProbeLock) {
            if (network !in physicalCandidates || vkProbeJobs[network]?.isActive == true) return
            val job = serviceScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                var failures = 0
                try {
                    while (isActive && !physicalNetworks.isUsable(network)) {
                        if (probeVkThrough(network)) {
                            if (!isActive || !isPhysicalCandidate(network)) return@launch
                            updatePhysicalNetwork(network, true)
                            return@launch
                        }
                        failures++
                        delay(vkProbeRetryDelayMs(failures))
                    }
                } finally {
                    synchronized(vkProbeLock) {
                        if (vkProbeJobs[network] === coroutineContext[Job]) {
                            vkProbeJobs.remove(network)
                        }
                    }
                }
            }
            vkProbeJobs[network] = job
            job.start()
        }
    }

    private fun forgetPhysicalNetwork(network: Network) {
        synchronized(vkProbeLock) {
            physicalCandidates.remove(network)
            vkProbeJobs.remove(network)?.cancel()
        }
        updatePhysicalNetwork(network, false)
    }

    private fun probeVkThrough(network: Network): Boolean = VK_PROBE_URLS.any { address ->
        val url = URL(address)
        probeVkRequest { network.openConnection(url) as HttpURLConnection }
            ?: probeVkUnboundFallback(network, url)
    }

    private fun probeVkUnboundFallback(network: Network, url: URL): Boolean {
        val active = runCatching { connectivityManager.activeNetwork }.getOrNull()
        if (active != null && active != network) return false
        return probeVkRequest { url.openConnection() as HttpURLConnection } ?: false
    }

    private fun probeVkRequest(open: () -> HttpURLConnection): Boolean? {
        val connection = runCatching(open).getOrNull() ?: return null
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = VK_PROBE_CONNECT_TIMEOUT_MS
            connection.readTimeout = VK_PROBE_READ_TIMEOUT_MS
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Connection", "close")
            connection.setRequestProperty("Range", "bytes=0-0")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            isVkProbeHttpResponse(connection.responseCode)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * A wanted run waits for a network VK answers on as long as it takes, as a running tunnel does when its network goes
     * (suspendForNoNetwork): «Нет сети — ожидание», ended only by a stop, a Wi-Fi pause or a deploy, which cancel the
     * start job. There was a 60 s limit here: after it 2.5.3 left the service in the foreground saying «Запуск...», and
     * ending the run instead dropped the user's intent on a Wi-Fi auto-pause resume in a lift or the metro.
     * The wait follows the network callbacks (no poll) and holds the CPU as a running tunnel does, so a network that
     * comes back without a callback (data back on the same mobile network) is found by its probe within seconds;
     * «Экономия батареи» lets the lock go, as for a running tunnel.
     */
    private suspend fun awaitVkNetwork() {
        if (physicalNetworks.hasUsableNetwork()) return
        kickPhysicalCandidateProbes()
        try {
            coroutineScope {
                val waiting = launch {
                    // the first VK probe of a network that is there takes a moment: say «Нет сети» only after it
                    delay(AWAIT_NETWORK_SHOWN_AFTER_MS)
                    TunnelManager.awaitingNetwork.value = true
                    TunnelManager.updateLog(
                        "vk_network_wait",
                        "[СЕТЬ] Нет сети, через которую доступен VK · ожидание",
                        2,
                        false,
                        LogLevel.NET,
                    )
                    withContext(Dispatchers.Main) { if (!isStopping) updateNotification("Нет сети — ожидание") }
                    while (isActive) {
                        delay(AWAIT_NETWORK_RESCAN_MS)
                        // the lock renewed as the running tunnel's 2 s loop renews it; the networks looked at again in
                        // case a callback was missed
                        withContext(Dispatchers.Main) { if (!isStopping) acquireWakeLock() }
                        kickPhysicalCandidateProbes()
                    }
                }
                physicalNetworks.usable.first { it }
                waiting.cancel()
            }
        } finally {
            // after the scope has joined the waiting child: nothing sets the flag again after this
            TunnelManager.awaitingNetwork.value = false
        }
    }

    private fun kickPhysicalCandidateProbes() {
        currentPhysicalCandidates().forEach { network ->
            rememberPhysicalCandidate(network)
            startVkNetworkProbe(network)
        }
    }

    private fun currentPhysicalCandidates(): List<Network> {
        val candidates = synchronized(vkProbeLock) { physicalCandidates.toList() }
        if (candidates.isNotEmpty()) return candidates
        val active = runCatching { connectivityManager.activeNetwork }.getOrNull() ?: return emptyList()
        val usable = runCatching {
            connectivityManager.getNetworkCapabilities(active)
                ?.let(::hasPhysicalInternetCapability) == true
        }.getOrDefault(false)
        return if (usable) listOf(active) else emptyList()
    }

    private fun verifyVkAndRestart() {
        if (vkRecoveryJob?.isActive == true) return
        vkRecoveryJob = serviceScope.launch(Dispatchers.IO) {
            try {
                if (!TunnelManager.running.value || TunnelManager.activeWorkers.value != 0) {
                    return@launch
                }
                val candidates = currentPhysicalCandidates()
                val ready = candidates.firstOrNull(::probeVkThrough)
                if (ready != null) {
                    rememberPhysicalCandidate(ready)
                    updatePhysicalNetwork(ready, true)
                    if (TunnelManager.activeWorkers.value == 0) {
                        TunnelManager.restartAfterVkRecovery(applicationContext)
                    }
                    return@launch
                }
                candidates.forEach { network ->
                    rememberPhysicalCandidate(network)
                    startVkNetworkProbe(network)
                }
            }
            finally {
                if (vkRecoveryJob === coroutineContext[Job]) vkRecoveryJob = null
            }
        }
    }

    private fun verifyVkAndSuspend() {
        if (vkRecoveryJob?.isActive == true) return
        vkRecoveryJob = serviceScope.launch(Dispatchers.IO) {
            try {
                val candidates = currentPhysicalCandidates()
                val ready = candidates.firstOrNull(::probeVkThrough)
                if (ready != null) {
                    rememberPhysicalCandidate(ready)
                    updatePhysicalNetwork(ready, true)
                    return@launch
                }
                candidates.forEach { network ->
                    rememberPhysicalCandidate(network)
                    startVkNetworkProbe(network)
                }
            } finally {
                if (vkRecoveryJob === coroutineContext[Job]) vkRecoveryJob = null
            }
        }
    }

    private fun updatePhysicalNetwork(network: Network, usable: Boolean) {
        val transition = physicalNetworks.update(network, usable)
        if (autoPausedForWifi) return
        when (transition) {
            PhysicalNetworkTransition.AVAILABLE -> {
                networkLossJob?.cancel()
                networkLossJob = null
                TunnelManager.onPhysicalNetworkAvailable(applicationContext)
            }
            PhysicalNetworkTransition.UNAVAILABLE -> {
                networkSwapJob?.cancel()
                schedulePhysicalNetworkLossCheck()
            }
            PhysicalNetworkTransition.CHANGED -> Unit
            PhysicalNetworkTransition.NONE -> Unit
        }
    }

    private fun schedulePhysicalNetworkLossCheck() {
        if (networkLossJob?.isActive == true) return
        networkLossJob = serviceScope.launch {
            delay(NETWORK_LOSS_GRACE_MS)
            if (physicalNetworks.hasUsableNetwork() || autoPausedForWifi) return@launch

            currentPhysicalCandidates().forEach { network ->
                rememberPhysicalCandidate(network)
                startVkNetworkProbe(network)
            }
            if (!physicalNetworks.hasUsableNetwork()) {
                TunnelManager.suspendForNoNetwork(physicalNetworkPauseReason(isAirplaneModeOn()))
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (networkLossJob === job) networkLossJob = null
            }
        }
    }

    private fun registerAirplaneModeReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_AIRPLANE_MODE_CHANGED) return
                if (isAirplaneModeOn() && !physicalNetworks.hasUsableNetwork()) {
                    TunnelManager.suspendForNoNetwork(physicalNetworkPauseReason(true))
                }
            }
        }
        val registered = runCatching {
            ContextCompat.registerReceiver(
                this,
                receiver,
                IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }.isSuccess
        if (registered) airplaneModeReceiver = receiver
    }

    private fun hasPhysicalInternetCapability(capabilities: NetworkCapabilities): Boolean =
        isEligiblePhysicalNetwork(
            hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            notVpn = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
            wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        )

    private fun isAirplaneModeOn(): Boolean =
        runCatching {
            Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        }.getOrDefault(false)

    private fun stopTunnel(finishVkCalls: Boolean = false) {
        startJob?.cancel()
        startJob = null
        startGeneration++
        if (isStopping) return
        isStopping = true
        TunnelManager.stopping.value = true
        stopWifiAutoPauseWatch()
        autoPauseRequested = false
        TunnelManager.starting.value = false
        updateJob?.cancel()
        serviceScope.launch {
            // An in-flight pause can be suspended in DataStore/manager cleanup.
            // Finish manual shutdown after it; the final persisted intent is off.
            autoPauseMutex.withLock {
                autoPausedForWifi = false
                TunnelManager.leaveWifiAutoPause()
                runCatching { SettingsStore(applicationContext).saveTunnelWasRunning(false) }
                releaseTunnelResources(finishVkCalls)
                stopForeground(STOP_FOREGROUND_REMOVE)
                foregroundStarted = false
                stopSelf()
            }
        }
    }

    /**
     * A deploy stops the tunnel completely (M-45, Fikisq-slim): a pending start, the Wi-Fi auto-pause and its watch, the
     * network and VK recovery jobs and the VK calls all end, and the tunnel is not restored after it. Otherwise a racing
     * start or a Wi-Fi resume brought the tunnel back mid-deploy. The service stays: it carries the deploy's foreground.
     */
    private fun prepareForDeploy() {
        startJob?.cancel()
        startJob = null
        startGeneration++
        autoPauseRequested = false
        stopWifiAutoPauseWatch()
        updateJob?.cancel()
        updateJob = null
        networkSwapJob?.cancel()
        networkSwapJob = null
        networkLossJob?.cancel()
        networkLossJob = null
        vkRecoveryJob?.cancel()
        vkRecoveryJob = null
        synchronized(vkProbeLock) {
            vkProbeJobs.values.forEach(Job::cancel)
            vkProbeJobs.clear()
        }
        CaptchaWebViewManager.onTunnelStop()
        val wasActive = TunnelManager.running.value || TunnelManager.starting.value || autoPausedForWifi
        autoPausedForWifi = false
        TunnelManager.leaveWifiAutoPause()
        TunnelManager.starting.value = false
        if (wasActive) {
            VkAutoCallsManager.finishActiveCalls()
            TunnelManager.stop(finishVkCalls = true, notifyController = false)
        }
        serviceScope.launch { runCatching { SettingsStore(applicationContext).saveTunnelWasRunning(false) } }
        releaseWifiLock()
    }

    private fun releaseTunnelResources(finishVkCalls: Boolean = false) {
        if (resourcesReleased) return
        resourcesReleased = true
        stopWifiAutoPauseWatch()
        CaptchaWebViewManager.onTunnelStop()
        if (finishVkCalls) VkAutoCallsManager.finishActiveCalls()
        TunnelManager.stop(finishVkCalls, notifyController = false)
        releaseWakeLock()
        releaseWifiLock()
    }

    private fun observeAutoPauseOnWifi() {
        autoPauseSettingsJob?.cancel()
        autoPauseSettingsJob = serviceScope.launch {
            SettingsStore(applicationContext).autoPauseOnWifi.collect { enabled ->
                autoPauseOnWifiEnabled = enabled
                reconcileWifiAutoPause()
            }
        }
    }

    /**
     * «Экономия батареи»: in force («Авто» — the phone's battery saver is on; «Свой уровень» — at or below the chosen
     * charge and not charging), the service lets go of its wake lock and TunVpnService of its own; out of force again,
     * they are taken back while the tunnel runs. The charge and the phone's saver come from their broadcasts, which
     * arrive only while the CPU is awake anyway.
     */
    private fun observeBatterySaver() {
        val powerManager = getSystemService(PowerManager::class.java)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) {
                    systemPowerSave = powerManager?.isPowerSaveMode == true
                } else {
                    readBattery(intent)
                }
                reconcileBatterySaver()
            }
        }
        systemPowerSave = powerManager?.isPowerSaveMode == true
        runCatching {
            ContextCompat.registerReceiver(
                this,
                receiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
                    addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onSuccess { sticky ->
            batteryReceiver = receiver
            sticky?.let(::readBattery)
        }
        batterySaverJob?.cancel()
        batterySaverJob = serviceScope.launch {
            val store = SettingsStore(applicationContext)
            combine(store.batterySaverMode, store.batterySaverPercent) { mode, percent -> mode to percent }
                .collect { (mode, percent) ->
                    batterySaverMode = mode
                    batterySaverPercent = percent
                    batterySaverLoaded = true
                    withContext(Dispatchers.Main) { reconcileBatterySaver() }
                }
        }
    }

    private fun readBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        batteryLevel = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        batteryCharging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 ||
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun reconcileBatterySaver() {
        if (!batterySaverLoaded) return
        val save = BatterySaver.shouldSave(
            batterySaverMode, batterySaverPercent, batteryLevel, batteryCharging, systemPowerSave,
        )
        if (save == BatterySaver.active.value) return
        BatterySaver.setActive(save)
        if (save) {
            if (!DeployManager.isDeploying.value) releaseWakeLock()
            TunnelManager.updateLog(
                "battery_saver",
                if (batterySaverMode == BatterySaver.MODE_AUTO) {
                    "[BAT] Экономия батареи: включено энергосбережение телефона — при выключенном экране туннель может засыпать"
                } else {
                    "[BAT] Экономия батареи: заряд $batteryLevel % — при выключенном экране туннель может засыпать"
                },
                2,
            )
        } else {
            if (TunnelManager.running.value && !autoPausedForWifi) acquireWakeLock()
            TunnelManager.updateLog("battery_saver", "[BAT] Экономия батареи выключена — туннель снова не засыпает", 2)
        }
        TunVpnService.instance?.onBatterySaverChanged(save)
    }

    /**
     * M-57: the one pusher of local rules while the tunnel runs — any change to the active profile's domain or IP rules
     * (an edit, an import, a profile switch) sends the whole config, so a whitelist stays a whitelist and the IP rules
     * survive a domain edit. A server rule in force wins (pushLocalRouting is a no-op then).
     */
    private fun observeDomainRoutingSettings() {
        domainRoutingSettingsJob?.cancel()
        domainRoutingSettingsJob = serviceScope.launch {
            SettingsStore(applicationContext).routingSnapshot.drop(1).collect {
                if (TunnelManager.running.value) TunnelManager.pushLocalRouting(applicationContext)
            }
        }
    }

    private fun requestWifiAutoPauseReconciliation() {
        serviceScope.launch {
            reconcileWifiAutoPause()
        }
    }

    private suspend fun reconcileWifiAutoPause(): Boolean = runWifiAutoPauseTransition(serviceScope) {
        autoPauseMutex.withLock {
            if (isStopping) return@withLock autoPausedForWifi
            when (
                wifiAutoPauseAction(
                    enabled = autoPauseOnWifiEnabled,
                    wifiConnected = wifiConnectedForAutoPause(),
                    desiredRunning = autoPauseRequested,
                    paused = autoPausedForWifi,
                )
            ) {
                WifiAutoPauseAction.PAUSE -> enterWifiAutoPause()
                WifiAutoPauseAction.RESUME -> resumeFromWifiAutoPause()
                WifiAutoPauseAction.NONE -> Unit
            }
            autoPausedForWifi
        }
    }

    private suspend fun enterWifiAutoPause() {
        if (!autoPauseRequested || !autoPauseOnWifiEnabled || !wifiConnectedForAutoPause()) return
        autoPausedForWifi = true
        updateJob?.cancel()
        updateJob = null
        networkSwapJob?.cancel()
        networkSwapJob = null
        vkRecoveryJob?.cancel()
        vkRecoveryJob = null
        startJob?.cancel()
        startJob = null
        startGeneration++
        synchronized(vkProbeLock) {
            vkProbeJobs.values.forEach(Job::cancel)
            vkProbeJobs.clear()
        }
        CaptchaWebViewManager.onTunnelStop()
        val completed = completeWifiAutoPause(
            isRequested = { autoPauseRequested && !isStopping },
            saveRunningIntent = { SettingsStore(applicationContext).saveTunnelWasRunning(true) },
            pauseDataPlane = { TunnelManager.pauseForWifi() },
        )
        if (!completed) return
        releaseWakeLock()
        releaseWifiLock()
        updateNotification("Ожидание. Автопауза при Wi-Fi")
        startWifiAutoPauseWatch()
    }

    private fun resumeFromWifiAutoPause() {
        if (!autoPausedForWifi || !autoPauseRequested || isStopping) return
        autoPausedForWifi = false
        stopWifiAutoPauseWatch()
        TunnelManager.leaveWifiAutoPause()
        acquireWakeLock()
        TunnelManager.updateLog(
            "wifi_auto_resume",
            "[NET] Автопауза Wi-Fi завершена · восстановление подключения",
            2,
            false,
            LogLevel.NET,
        )
        updateNotification("Подключение...")
        if (!restoreTunnel()) stopTunnel()
    }

    private fun startWifiAutoPauseWatch() {
        if (autoPauseWifiWatchJob?.isActive == true) return
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                while (autoPausedForWifi && autoPauseRequested && isActive) {
                    delay(AUTO_PAUSE_WIFI_RECONCILE_MS)
                    reconcileWifiAutoPause()
                }
            } finally {
                if (autoPauseWifiWatchJob === coroutineContext[Job]) {
                    autoPauseWifiWatchJob = null
                }
            }
        }
        autoPauseWifiWatchJob = job
        job.start()
    }

    private fun stopWifiAutoPauseWatch() {
        autoPauseWifiWatchJob?.cancel()
        autoPauseWifiWatchJob = null
    }

    private fun updatePhysicalWifiNetwork(network: Network, capabilities: NetworkCapabilities) {
        val physicalWifi = hasPhysicalWifiCapability(capabilities)
        synchronized(wifiNetworkLock) {
            if (physicalWifi) {
                physicalWifiNetworks.add(network)
            } else {
                physicalWifiNetworks.remove(network)
            }
        }
    }

    private fun forgetPhysicalWifiNetwork(network: Network) {
        synchronized(wifiNetworkLock) {
            physicalWifiNetworks.remove(network)
        }
    }

    private fun wifiConnectedForAutoPause(): Boolean {
        return effectiveWifiAutoPauseConnection(
            callbackWifiConnected = hasPhysicalWifiNetwork(),
            defaultPhysicalWifiConnected = defaultPhysicalWifiNetwork(),
        )
    }

    private fun defaultPhysicalWifiNetwork(): Boolean? {
        // During a handover/closing TUN, Android can briefly have no default.
        // That is unknown, not proof that a still-present Wi-Fi disappeared.
        val active = runCatching { connectivityManager.activeNetwork }.getOrNull() ?: return null
        val capabilities = runCatching { connectivityManager.getNetworkCapabilities(active) }.getOrNull()
            ?: return null
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        return hasPhysicalWifiCapability(capabilities)
    }

    private fun hasPhysicalWifiNetwork(): Boolean {
        synchronized(wifiNetworkLock) {
            return physicalWifiNetworks.isNotEmpty()
        }
    }

    private fun hasPhysicalWifiCapability(capabilities: NetworkCapabilities): Boolean =
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)

    private fun sanitizeCaptchaMode(mode: String?): String {
        return when (mode?.lowercase()) {
            "auto" -> "auto"
            "rjs" -> "rjs"
            "wv" -> "wv"
            else -> "auto"
        }
    }

    private fun sanitizeVkAuthMode(mode: String?): String {
        return when (mode?.lowercase()) {
            CsqttConstants.VkAuth.MODE_CAPTCHA -> CsqttConstants.VkAuth.MODE_CAPTCHA
            CsqttConstants.VkAuth.MODE_AUTO_JS -> CsqttConstants.VkAuth.MODE_AUTO_JS
            else -> CsqttConstants.VkAuth.MODE_CALLS
        }
    }

    private fun sanitizeSrtpMode(mode: String?): String {
        return when (mode?.lowercase()) {
            CsqttConstants.SrtpMode.WRAP -> CsqttConstants.SrtpMode.WRAP
            CsqttConstants.SrtpMode.DTLS -> CsqttConstants.SrtpMode.DTLS
            else -> CsqttConstants.SrtpMode.AUTO
        }
    }

    private fun acquireWakeLock() {
        // «Экономия батареи» in force: the CPU may sleep, the tunnel with it — except for a deploy, which needs it awake
        if (BatterySaver.active.value && !DeployManager.isDeploying.value) {
            releaseWakeLock() // one a deploy or a server check took meanwhile
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (wakeLock?.isHeld == true && now < wakeLockExpiresAtElapsedMs - WAKE_LOCK_RENEWAL_WINDOW_MS) return
        if (wakeLock?.isHeld == true) wakeLock?.release()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "csqtt:tunnel_cpu"
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        wakeLockExpiresAtElapsedMs = now + WAKE_LOCK_TIMEOUT_MS
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager

        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "csqtt:wifi_perf").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
        wakeLockExpiresAtElapsedMs = 0L
    }

    private fun releaseWifiLock() {
        if (wifiLock?.isHeld == true) {
            wifiLock?.release()
        }
        wifiLock = null
    }

    private fun startStatsUpdater() {
        updateJob?.cancel()
        updateJob = serviceScope.launch(Dispatchers.Main) {
            var wasEverUp = TunnelManager.running.value || TunnelManager.processStartedAtMs > 0L
            val startedAt = SystemClock.elapsedRealtime()
            var subscriptionsCheckedAt = startedAt
            delay(1000)
            while (isActive) {
                val running = TunnelManager.running.value
                // M-74: subscriptions refresh while connected too, at most hourly, on this existing tick (no new wake-up)
                if (running && SystemClock.elapsedRealtime() - subscriptionsCheckedAt >= SUBSCRIPTION_CHECK_WHILE_CONNECTED_MS) {
                    subscriptionsCheckedAt = SystemClock.elapsedRealtime()
                    serviceScope.launch {
                        runCatching { SubscriptionSync.refreshDue(SettingsStore(applicationContext), whileConnected = true) }
                    }
                }
                wasEverUp = wasEverUp || running || TunnelManager.processStartedAtMs > 0L
                if (running) {
                    acquireWakeLock()
                    acquireWifiLock()
                }
                if (
                    TunnelServicePolicy.shouldStop(
                        wasEverRunning = wasEverUp,
                        running = running,
                        elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                        pausedForWifi = autoPausedForWifi,
                    )
                ) {
                    stopTunnel()
                    break
                }
                // The notification is built and posted only with the screen on: redrawing it with the screen off costs
                // a post every few seconds for nothing. The next tick after the screen comes on brings it up to date.
                postTunnelStatus()
                delay(2000)
            }
        }
    }

    /** The status notification from the connection state and STATS (M-43, M-89), throttled (M-48). */
    private fun postTunnelStatus() {
        // with the screen off nothing is built at all (review F5)
        if (!foregroundStarted || isStopping || !screenInteractive()) return
        val state = TunnelManager.connectionState.value
        val title = tunnelNotificationTitle(state)
        val text = if (showsTraffic(state)) formatTraffic(TunnelManager.traffic.value) else ""
        // the state's label goes out at once; the stream count and the figures wait for the 5 s spacing
        if (!notificationThrottle.shouldPost(
                state = state.label,
                text = "$title\n$text",
                nowMs = SystemClock.elapsedRealtime(),
                screenOn = true,
                visible = ::isNotificationVisible,
            )
        ) {
            return
        }
        getSystemService(NotificationManager::class.java)
            .notify(TUNNEL_NOTIFICATION_ID, createNotification(text, title = title))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            TUNNEL_NOTIFICATION_CHANNEL_ID,
            "CSQTT Туннель",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Уведомление о работе туннеля"
            setShowBadge(false)

            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(
        text: String,
        actionName: String = "STOP",
        actionTitle: String = "Отключить",
        title: String = "CSQTT",
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, if (actionName == "STOP") 1 else 2,
            Intent(this, TunnelService::class.java).apply { action = actionName },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val restoreIntent = PendingIntent.getService(
            this, 3,
            Intent(this, TunnelService::class.java).apply { action = "RESTORE_NOTIFICATION" },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, TUNNEL_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text.ifEmpty { null })
            .setSmallIcon(R.drawable.ic_stat_c)
            .setOngoing(true)
            .setLocalOnly(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_stop, actionTitle, stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true) 
            .setSilent(true) 
            .setShowWhen(false)
            .setUsesChronometer(false)
            .setWhen(0L)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setDeleteIntent(restoreIntent)
            .build()
            .also {
                it.flags = it.flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR or Notification.FLAG_FOREGROUND_SERVICE
            }
    }

    private fun startPersistentForeground(notification: Notification) {
        notification.flags = notification.flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR or Notification.FLAG_FOREGROUND_SERVICE
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(TUNNEL_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(TUNNEL_NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    /** A one-off notice at a transition (start, pause, deploy); the status posts that follow are not held back by it. */
    private fun updateNotification(text: String) {
        notificationThrottle.invalidate()
        val notification = createNotification(text)
        if (foregroundStarted) {
            getSystemService(NotificationManager::class.java).notify(TUNNEL_NOTIFICATION_ID, notification)
        } else {
            startPersistentForeground(notification)
        }
    }

    private fun screenInteractive(): Boolean =
        runCatching { (getSystemService(POWER_SERVICE) as PowerManager).isInteractive }.getOrDefault(true)

    private fun isNotificationVisible(): Boolean {
        return runCatching {
            getSystemService(NotificationManager::class.java)
                .activeNotifications
                .any { it.id == TUNNEL_NOTIFICATION_ID }
        }.getOrDefault(false)
    }

    override fun onDestroy() {
        isStopping = true
        startJob?.cancel()
        startJob = null
        startGeneration++
        autoPauseSettingsJob?.cancel()
        autoPauseSettingsJob = null
        domainRoutingSettingsJob?.cancel()
        domainRoutingSettingsJob = null
        batterySaverJob?.cancel()
        batterySaverJob = null
        batteryReceiver?.let { receiver -> runCatching { unregisterReceiver(receiver) } }
        batteryReceiver = null
        BatterySaver.setActive(false)
        stopWifiAutoPauseWatch()
        updateJob?.cancel()
        networkSwapJob?.cancel()
        networkSwapJob = null
        networkLossJob?.cancel()
        networkLossJob = null
        vkRecoveryJob?.cancel()
        vkRecoveryJob = null
        synchronized(vkProbeLock) {
            vkProbeJobs.values.forEach(Job::cancel)
            vkProbeJobs.clear()
            physicalCandidates.clear()
        }
        releaseTunnelResources(finishVkCalls = true)
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        serviceJob.cancel()
        networkCallback?.let { callback ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        airplaneModeReceiver?.let { receiver ->
            runCatching { unregisterReceiver(receiver) }
        }
        airplaneModeReceiver = null
        physicalNetworks.clear()
        synchronized(wifiNetworkLock) {
            physicalWifiNetworks.clear()
        }
        TunnelManager.activeScope = TunnelManager.scope
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

