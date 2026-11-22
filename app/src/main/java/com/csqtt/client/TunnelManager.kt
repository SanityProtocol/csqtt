package com.csqtt.client

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateListOf

@Immutable
enum class LogLevel {
    OK,
    DBG,
    NET,
    PING,
    TURN,
    LOG,
    ERR,
}

@Immutable
data class LogEntry(
    val key: String,
    val message: String,
    val count: Int = 1,
    val priority: Int = 99, 
    val isError: Boolean = false,
    val level: LogLevel? = null,
)

private val LOG_STICKERS = Regex("[\\p{So}\\p{Sk}\\uFE0F\\u200D]")
private val EMPTY_LOG_TAG = Regex("\\[\\s*]")
private val LOG_TIMESTAMP_PREFIX = Regex("^\\d{4}/\\d{2}/\\d{2}\\s\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?\\s")

/**
 * A participant's log never shows the server owner's VK hashes, not even the prefix displayVkHash or the client prints:
 * every piece of one (its first 8 characters and on, with a trailing … or ...) becomes «от владельца».
 */
internal fun maskOwnerHashes(text: String, ownerHashes: List<String>): String =
    ownerHashes.map(String::trim).filter { it.length >= 16 }.fold(text) { out, hash ->
        out.replace(Regex(Regex.escape(hash.take(8)) + "[A-Za-z0-9_-]*(?:…|\\.\\.\\.)?"), "от владельца")
    }

internal fun displayVkHash(hash: String): String = hash.trim().let { value ->
    if (value.length <= 10) value else value.take(10) + "…"
}

internal fun dnsProfileName(dns: String): String = when (dns.trim()) {
    "77.88.8.8,77.88.8.1" -> "Yandex DNS"
    "1.1.1.1,1.0.0.1" -> "Cloudflare DNS"
    "8.8.8.8,8.8.4.4" -> "Google DNS"
    "94.140.14.14,94.140.15.14" -> "AdGuard DNS"
    "111.88.96.54,111.88.96.55", "111.88.96.50,111.88.96.51" -> "Xbox DNS"
    "83.220.169.155,212.109.195.93" -> "Comms DNS"
    "193.233.112.67,193.233.112.88", "95.182.120.241,37.230.192.51" -> "Geohide DNS"
    "9.9.9.9,149.112.112.112" -> "Quad9 DNS"
    "208.67.222.222,208.67.220.220" -> "OpenDNS"
    "95.189.32.74,195.208.5.1" -> "Rostelecom DNS"
    "45.90.28.181,45.90.30.181" -> "NextDNS"
    "195.208.6.1,195.208.7.1" -> "BI.ZONE DNS"
    "195.208.4.1,195.208.5.1" -> "НСДИ DNS"
    else -> "Пользовательский DNS"
}

internal fun withoutLogStickers(message: String): String = message
    .replace(LOG_STICKERS) { symbol ->
        symbol.value.takeIf { it == "✓" || it == "✗" }.orEmpty()
    }
    .replace(EMPTY_LOG_TAG, "")
    .replace(Regex("[^\\S\r\n]+"), " ")
    .trim()

/**
 * The core's start command without its routing flags. It carries no secret: the hashes and the password travel in
 * [coreSecretEnv], since a command line is readable by any process that may list processes (M-172).
 */
internal fun coreBaseCommand(
    binaryPath: String,
    params: TunnelParams,
    totalWorkers: Int,
    effectiveAuthMode: String,
): List<String> = buildList {
    addAll(
        listOf(
            binaryPath,
            "-peer", params.peer,
            "-n", totalWorkers.toString(),
            "-listen", "${CsqttConstants.Network.LOCAL_LISTEN_HOST}:${params.port}",
            "-tun-uds", "csqtt_tun_uds",
        )
    )
    add("-vk-hash-mode")
    add(params.vkHashMode)
    if (params.fingerprint.isNotEmpty()) {
        add("-fingerprint")
        add(params.fingerprint)
    }
    if (params.clientIds.isNotEmpty()) {
        add("-client-ids")
        add(params.clientIds)
    }
    add("-obfs")
    add(params.obfsMode)
    add("-turn-transport")
    add(params.turnTransport)
    addAll(params.dpiSplit.arguments(params.turnTransport))
    add("-srtp-mode")
    add(params.srtpMode)
    if (params.customTurnIps.isNotBlank()) {
        add("-custom-turn-ips")
        add(params.customTurnIps)
    }
    add("-vk-auth-mode")
    add(effectiveAuthMode)
    if (params.generationId > 0) {
        add("-gen")
        add(params.generationId.toString())
    }
    if (params.sessionSalt.isNotEmpty()) {
        add("-salt")
        add(params.sessionSalt)
    }
    add("-captcha-mode")
    add(params.captchaMode)
}

/** The core's secrets, for its environment (the core reads CSQTT_PASSWORD and CSQTT_VK_HASHES). */
internal fun coreSecretEnv(password: String, hashes: List<String>): Map<String, String> = buildMap {
    if (password.isNotEmpty()) put("CSQTT_PASSWORD", password)
    if (hashes.isNotEmpty()) put(CsqttConstants.Tunnel.VK_HASHES_ENV, hashes.joinToString(","))
}

object TunnelManager {
    // the server owner's VK hashes of the participant link in use, masked in every log line (resolveConnectionSource)
    @Volatile
    internal var ownerHashes: List<String> = emptyList()

    // S8: the profile this run started from (resolveConnectionSource); hashes the server sends are stored for it
    @Volatile
    internal var sourceProfile: Int = -1

    // ... and the link it started with, null for an owner profile: only a link run takes the server's set (review F2, F3)
    @Volatile
    internal var sourceLink: String? = null

    /** The run's own profile, link and owner hashes (resolveConnectionSource writes them for the profile it resolves). */
    internal data class RunIdentity(val profile: Int, val link: String?, val ownerHashes: List<String>)

    internal fun runIdentity(): RunIdentity = RunIdentity(sourceProfile, sourceLink, ownerHashes)

    /**
     * The profile a pick is switching the run to, until switchProfile applies it or the run ends (verify N1): picks and
     * the add toast compare against where the run is going, not the old profile it still runs on meanwhile.
     */
    @Volatile
    internal var switchTarget: Int? = null

    internal fun runTargetProfile(): Int = switchTarget ?: sourceProfile

    internal fun applyRunIdentity(identity: RunIdentity) {
        sourceProfile = identity.profile
        sourceLink = identity.link
        ownerHashes = identity.ownerHashes
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var activeScope: CoroutineScope = scope

    @Volatile
    private var process: Process? = null
    @Volatile
    private var processGeneration = 0L
    @Volatile
    private var processIdentity: ProcessIdentity? = null
    @Volatile
    private var nativeProcessPid: Int? = null
    @Volatile
    private var readerJob: Job? = null
    @Volatile
    private var restartJob: Job? = null
    @Volatile
    private var panelRestartJob: Job? = null
    @Volatile
    private var workerRecoveryJob: Job? = null
    private val transportRestartPending = AtomicBoolean(false)
    private val panelRestartPending = AtomicBoolean(false)
    private val callRecoveryPending = AtomicBoolean(false)
    private val workerRecoveryPolicy = WorkerRecoveryPolicy()
    @Volatile
    private var crashRestartStreak = 0
    private const val WORKER_ZERO_CONFIRMATION_MS = 4_000L
    private const val PANEL_SERVER_RESTART_DELAY_MS = 4_000L
    private const val STARTUP_DIAGNOSTIC_MS = 30_000L
    private const val CRASH_RESTART_STABLE_MS = 30_000L
    private const val CRASH_RESTART_MAX_DELAY_MS = 60_000L
    private const val VPN_START_RETRY_MS = 10_000L
    private const val TUN_LOSS_REBUILD_WINDOW_MS = 60_000L
    // a stream is ready but GETCONF got no answer for this long: the server hangs (M-2, amurcanov #42)
    private const val SERVER_SILENT_MS = 20_000L

    private val startStopMutex = kotlinx.coroutines.sync.Mutex()
    private val lifecycleState = TunnelLifecycleState()
    private val lifecycleCommand = AtomicLong()

    private data class ProcessIdentity(
        val process: Process,
        val generation: Long,
        val ticket: TunnelLifecycleTicket,
        val shutdownGraceMs: Long,
        val startupProgress: TunnelStartupProgress = TunnelStartupProgress(),
    ) {
        val inputLock = Any()
        val readyWorkers = mutableSetOf<Int>()
        val dtlsWorkers = mutableSetOf<Int>()
        // the hashes of this process reported dead (CALL_UNAVAILABLE), in every mode (B5h1 review F1)
        val unavailableHashes = mutableSetOf<String>()
        // set synchronously, before any stop is launched: the reader's exit then never restarts this process (M-47)
        val fatal = FirstFatal()
        @Volatile
        var silenceArmed = false
    }

    private data class PendingLog(
        val identity: ProcessIdentity?,
        val key: String,
        var message: String,
        val priority: Int,
        val isError: Boolean,
        val level: LogLevel?,
        var count: Int,
    )

    private const val LOG_UI_FLUSH_MS = 120L
    private const val MAX_LOG_ENTRIES = 100
    private const val MAX_PENDING_LOGS = 128
    private const val MAX_LOG_MESSAGE_LENGTH = 4_096
    private val pendingLogLock = Any()
    private val pendingLogs = LinkedHashMap<String, PendingLog>()
    private val pendingLogFlushScheduled = AtomicBoolean(false)

    @Volatile
    private var wrapAuthTimeoutCount = 0
    private var lastVpnStartAttemptMs = 0L
    @Volatile
    private var lastTunLossMs = 0L
    @Volatile
    var processStartedAtMs = 0L
    @Volatile
    private var currentParams: TunnelParams? = null
    @Volatile
    private var strongAppContext: Context? = null
    @Volatile
    private var lastContext: WeakReference<Context>? = null
    private fun activeContext(): Context? = strongAppContext ?: lastContext?.get()
    @Volatile
    private var activeSettingsStore: SettingsStore? = null
    @Volatile
    private var currentCaptchaSolveMethod = "auto" 
    @Volatile
    private var lastAppliedDns: String? = null

    @Volatile
    var isLoggingEnabled = true
        set(value) {
            field = value
            if (!value) clearLogs()
        }

    val running = MutableStateFlow(false)
    val starting = MutableStateFlow(false)
    val stopping = MutableStateFlow(false)
    val logs = mutableStateListOf<LogEntry>()
    val unreadErrorCount = MutableStateFlow(0)
    val config = MutableStateFlow<String?>(null)
    val activeWorkers = MutableStateFlow(0)
    val autoPausedForWifi = MutableStateFlow(false)
    /** B5f2: what the service decided about the Wi-Fi the phone is on (F6); null while no run is on. */
    internal val wifiStatus = MutableStateFlow<WifiStatus?>(null)
    val vpnReady = MutableStateFlow(false)
    val serverRoutingActive = MutableStateFlow(false)
    val activeServerRoutingRule = MutableStateFlow<ServerRoutingRule?>(null)
    val serverAssignedWorkers = MutableStateFlow<Int?>(null)
    val lastTunnelDiagnostic = MutableStateFlow("")
    // the uptime is computed where it is shown, while it is shown: no ticker runs with the screen off (M-48)
    val uptime = MutableStateFlow(UptimeMark())

    private val pausedForNoNetwork = NetworkPauseGate()

    // the inputs of the connection state (M-43) that the flows above do not carry
    private val tunconfThisProcess = MutableStateFlow(false)
    private val everConnected = MutableStateFlow(false)
    private val networkPaused = MutableStateFlow(false)
    /** The service waits for a network VK answers on, before the process starts. */
    internal val awaitingNetwork = MutableStateFlow(false)
    private val serverSilent = MutableStateFlow(false)
    private val runFatal = AtomicReference(FirstFatal())
    val failure = MutableStateFlow<String?>(null)
    /** The run's traffic from STATS (M-89); null before the first reading. */
    val traffic = MutableStateFlow<TrafficSnapshot?>(null)
    private val trafficTracker = TrafficTracker()
    /** The workers the running process was started with (`-n`), 0 without a run (L-27). */
    val targetWorkers = MutableStateFlow(0)
    private val vpnIntentGate = VpnIntentGate()

    val connectionState: StateFlow<ConnectionState> = combine(
        listOf<kotlinx.coroutines.flow.Flow<Any?>>(
            running, starting, stopping, autoPausedForWifi, networkPaused, awaitingNetwork,
            ManlCaptchaWebViewManager.needed, tunconfThisProcess, vpnReady, activeWorkers, everConnected, serverSilent,
            failure,
        ),
    ) { values: Array<Any?> ->
        deriveConnectionState(
            ConnectionInputs(
                running = values[0] as Boolean,
                starting = values[1] as Boolean,
                stopping = values[2] as Boolean,
                pausedOnWifi = values[3] as Boolean,
                waitingForNetwork = values[4] as Boolean || values[5] as Boolean,
                captchaNeeded = values[6] as Boolean,
                tunconf = values[7] as Boolean,
                vpnReady = values[8] as Boolean,
                activeWorkers = values[9] as Int,
                everConnected = values[10] as Boolean,
                serverSilent = values[11] as Boolean,
                failure = values[12] as String?,
            ),
        )
    }.stateIn(scope, SharingStarted.Eagerly, ConnectionState.Idle)

    /** The state as of now, for a tap's decision: the flow above follows its inputs a moment later. */
    fun currentConnectionState(): ConnectionState = deriveConnectionState(
        ConnectionInputs(
            running = running.value,
            starting = starting.value,
            stopping = stopping.value,
            pausedOnWifi = autoPausedForWifi.value,
            waitingForNetwork = networkPaused.value || awaitingNetwork.value,
            captchaNeeded = ManlCaptchaWebViewManager.needed.value,
            tunconf = tunconfThisProcess.value,
            vpnReady = vpnReady.value,
            activeWorkers = activeWorkers.value,
            everConnected = everConnected.value,
            serverSilent = serverSilent.value,
            failure = failure.value,
        ),
    )

    /**
     * The service accepted a START (or restores one): the last run's failure is no longer the state, whatever ends this
     * attempt (review F3).
     */
    internal fun beginStartRequest(context: Context) {
        runFatal.set(FirstFatal())
        failure.value = null
        // a new run needs no captcha yet: a need left by the last one ends here (review H1)
        ManlCaptchaWebViewManager.cancelCaptcha()
        starting.value = true
        // B5h1: the last stop's cause and its notification belong to the run that ended
        val appContext = context.applicationContext
        StopNotice.cancel(appContext)
        scope.launch(stopCauseWriter) { runCatching { SettingsStore(appContext).clearLastStopCause() } }
    }

    // B5h1: the stop cause's clear and its save, in the order they were asked for (a start refused at once records its
    // cause right after the start cleared the last one)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val stopCauseWriter = Dispatchers.IO.limitedParallelism(1)

    /** B5h1: at the app's start, the cause an earlier process stored, unless a run began meanwhile (StopCause.restored). */
    internal fun restoreStoppedCause(stored: String?) {
        StopCause.restored(stored, running.value, starting.value, failure.value)?.let { failure.value = it }
    }

    /** The process is gone: its last speed is not the tunnel's (review F4); the totals stay. */
    private fun clearTrafficSpeed() {
        traffic.value = traffic.value?.copy(downBytesPerSecond = 0L, upBytesPerSecond = 0L, hasSpeed = false)
    }

    // a routing reload asked while no process runs (a crash restart's backoff): the next process rebuilds the TUN
    private val vpnRebuildPending = AtomicBoolean(false)

    private fun syncNetworkPaused() {
        networkPaused.value = pausedForNoNetwork.isPaused()
    }

    private fun markConnectedIfReady() {
        if (running.value && tunconfThisProcess.value && vpnReady.value && activeWorkers.value > 0) {
            everConnected.value = true
            // a process that has kept the run up for a while is healthy, however it ends later (B5e4 review F2); one
            // that dies within that time still counts, so a crash right after connecting keeps its backoff (verify)
            val start = processStartedAtMs
            if (start > 0L && SystemClock.elapsedRealtime() - start >= CRASH_RESTART_STABLE_MS) crashRestartStreak = 0
            // a captcha need with no ask live is over once the run works (B5h2)
            ManlCaptchaWebViewManager.onRunProgress()
        }
    }

    /**
     * The first fatal cause of the run is the one shown (M-47); a later one only goes to the log. B5h1: it is kept until
     * the next start, across process death too, and announced by its own notification while the app is not in front.
     */
    internal fun recordRunFailure(cause: String, context: Context? = activeContext(), silent: Boolean = false) {
        val fatal = runFatal.get()
        if (!fatal.record(StopCause.masked(cause, ownerHashes))) return
        val recorded = fatal.cause ?: return
        failure.value = recorded
        val appContext = context?.applicationContext ?: return
        scope.launch(stopCauseWriter) { runCatching { SettingsStore(appContext).saveLastStopCause(recorded) } }
        if (StopCause.announces(MainActivity.isForeground)) StopNotice.post(appContext, recorded, silent)
    }

    val cooldownActive = MutableStateFlow(false)
    private var cooldownJob: Job? = null

    private fun startUptimeTimer() {
        processStartedAtMs = SystemClock.elapsedRealtime()
        uptime.value = UptimeMark(startedAtMs = processStartedAtMs)
    }

    private fun stopUptimeTimer() {
        val start = processStartedAtMs
        if (start > 0L) {
            uptime.value = UptimeMark(frozenSeconds = (SystemClock.elapsedRealtime() - start) / 1000L)
            processStartedAtMs = 0L
        } else if (uptime.value.frozenSeconds == null) {
            uptime.value = UptimeMark()
        }
    }

    fun getCurrentParams(): TunnelParams? = currentParams

    /**
     * B5h1 review F1: whether the run still has a hash no CALL_UNAVAILABLE named. A recovery whose token VK refused can
     * mint no new call, so it stops the run only once none is left; until then the live calls carry it.
     */
    internal fun runHasLiveHashes(): Boolean {
        val identity = processIdentity ?: return false
        val hashes = currentParams?.vkHashes?.split(Regex("[,\\s\\n]+"))?.map(String::trim)?.filter(String::isNotEmpty)
            ?: return false
        val dead = synchronized(identity.unavailableHashes) { identity.unavailableHashes.toSet() }
        return hashes.any { it !in dead }
    }

    private fun processIdOf(activeProcess: Process): Int? {
        return runCatching {
            val field = activeProcess.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.getInt(activeProcess).takeIf { it > 0 }
        }.getOrNull() ?: Regex("pid=(\\d+)")
            .find(activeProcess.toString())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
    }

    init {
        scope.launch {
            running.collect { started ->
                if (started) starting.value = false
            }
        }
    }

    fun restartForNetworkSwap(context: Context) {
        requestTransportRestart(
            context.applicationContext,
            "network_swap",
            "[OK] Сеть доступна · переподключение",
        )
    }

    fun restartAfterVkRecovery(context: Context) {
        if (!running.value || activeWorkers.value != 0) return
        requestTransportRestart(
            context.applicationContext,
            "workers_zero_vk_ready",
            "[NET] Потоки: 0 · обновление TURN",
        )
    }

    fun onPhysicalNetworkAvailable(context: Context) {
        if (!running.value) return
        if (pausedForNoNetwork.resume()) {
            syncNetworkPaused()
            val identity = processIdentity ?: run {
                restartNowIfWaitingForNetwork()
                return
            }
            activeScope.launch {
                writeProcessCommand(identity, "RESUME", "network_resume_command_error")
            }
        }
    }

    /** B5f2: a crash restart scheduled while offline, and the job that waits the longest delay for it. */
    private class OfflineRestart(val job: Job, val context: Context, val params: TunnelParams, val epoch: Long)

    @Volatile
    @SuppressLint("StaticFieldLeak") // the application context only
    private var offlineRestart: OfflineRestart? = null

    /**
     * B5f2 (B5e4's backlog): an exit while offline waits the longest delay, so a network that comes back sooner starts the
     * restart now — if that restart is still the one pending and no process runs.
     */
    private fun restartNowIfWaitingForNetwork() {
        activeScope.launch {
            startStopMutex.withLock {
                val pending = offlineRestart ?: return@withLock
                offlineRestart = null
                if (restartJob !== pending.job || !pending.job.isActive || process != null) return@withLock
                scheduleRestartLocked(pending.context, pending.params, pending.epoch, 0L)
            }
        }
    }

    fun onVpnInterfaceReady(dns: String? = null, builtRule: ServerRoutingRule? = activeServerRoutingRule.value) {
        vpnReady.value = true
        markConnectedIfReady()
        lastTunnelDiagnostic.value = ""
        activeScope.launch { activeSettingsStore?.clearTunnelDiagnostic() }
        if (!dns.isNullOrBlank() && dns != lastAppliedDns) {
            lastAppliedDns = dns
            updateLog(
                key = "vpn_dns_active_${dnsProfileName(dns)}",
                message = "[SERVER] DNS применён: ${dnsProfileName(dns)} ✓",
                priority = 32,
                level = LogLevel.OK,
            )
        }
        if (activeServerRoutingRule.value == null) {
            activeContext()?.let { ctx -> activeScope.launch { logEffectiveLocalRouting(SettingsStore(ctx, ProfileScope.Run)) } }
        }
        rebuildIfRuleMoved(builtRule)
    }

    fun onRoutingUpdated(builtRule: ServerRoutingRule? = activeServerRoutingRule.value) {
        vpnReady.value = true
        lastTunnelDiagnostic.value = ""
        activeScope.launch { activeSettingsStore?.clearTunnelDiagnostic() }
        updateLog(
            key = "vpn_routing_updated",
            message = "[СЕТЬ] Маршрутизация приложений обновлена ✓",
            priority = 25,
            level = LogLevel.NET,
        )
        rebuildIfRuleMoved(builtRule)
    }

    /**
     * M-58: `startVpn` reads the server rule when it runs, so a ROUTING event that lands between a rebuild and vpnReady
     * left the VPN on the rule it read. On every ready the rule the interface was built with is compared with the active
     * one; a difference rebuilds once (the rebuild reads the active rule, so it settles).
     */
    private fun rebuildIfRuleMoved(builtRule: ServerRoutingRule?) {
        if (!vpnInputsDiffer(builtRule, activeServerRoutingRule.value)) return
        updateLog("vpn_rule_moved", "[СЕТЬ] Правило маршрутизации сменилось во время сборки VPN — пересборка", 25, level = LogLevel.NET)
        reloadVpn()
    }

    fun onVpnInterfaceStopped() {
        vpnReady.value = false
    }

    private fun recordTunnelDiagnostic(code: String, message: String) {
        val diagnostic = "$code: ${message.trim()}".take(480)
        lastTunnelDiagnostic.value = diagnostic
        val context = activeContext() ?: return
        scope.launch {
            val store = activeSettingsStore ?: SettingsStore(context, ProfileScope.Run).also {
                activeSettingsStore = it
            }
            store.saveTunnelDiagnostic(code, message)
        }
    }

    internal fun onVpnRecoverableFailure(message: String, kind: VpnFailureKind) {
        if (!VpnRecoveryPolicy.keepsTunnelRequested(kind)) {
            onVpnTerminalFailure(message, kind)
            return
        }
        vpnReady.value = false
        val diagnostic = VpnRecoveryPolicy.message(kind, message)
        recordTunnelDiagnostic(kind.code, diagnostic)
        updateLog("${kind.code}_recover", "[VPN] $diagnostic · восстановление", 98, true)
        val context = activeContext() ?: return
        if (!running.value || !lifecycleState.isDesiredRunning()) return
        requestTransportRestart(
            context.applicationContext,
            kind.code,
            "[VPN] Восстановление интерфейса и транспорта",
        )
    }

    internal fun onVpnTerminalFailure(
        message: String,
        kind: VpnFailureKind = VpnFailureKind.TunEstablishFailed,
    ) {
        vpnReady.value = false
        val diagnostic = VpnRecoveryPolicy.message(kind, message)
        recordTunnelDiagnostic(kind.code, diagnostic)
        updateLog("${kind.code}_terminal", "✗ VPN TUN: $diagnostic", 99, true)
        // B5h1: a revoke is another VPN app or the user in Android's settings, not a fault to read about
        // review F3: the user may have done it (Android's VPN panel, another VPN app), so its notice makes no sound
        val revoked = kind == VpnFailureKind.PermissionRevoked
        recordRunFailure(if (revoked) StopCause.VPN_REVOKED else "VPN: $diagnostic", silent = revoked)
        scope.launch {
            startStopMutex.withLock {
                stopLocked(processIdentity)
            }
        }
    }

    internal fun suspendForNoNetwork(reason: PhysicalNetworkPauseReason) {
        if (!running.value || !pausedForNoNetwork.pause()) return
        syncNetworkPaused()
        val message = when (reason) {
            PhysicalNetworkPauseReason.AIRPLANE_MODE -> "[NET] Режим полёта · ожидание"
            PhysicalNetworkPauseReason.OFFLINE -> "[NET] Сеть отключена · ожидание"
        }
        updateLog("network_pause", message, 2, false, LogLevel.NET)
        val identity = processIdentity ?: return
        activeScope.launch {
            if (pausedForNoNetwork.isPaused()) {
                writeProcessCommand(identity, "PAUSE", "network_pause_command_error")
            }
        }
    }

    fun clearUnreadErrors() {
        unreadErrorCount.value = 0
    }

    private fun handleTunnelEvent(
        event: TunnelEventParser.Event,
        identity: ProcessIdentity,
    ): Boolean {
        if (!isCurrent(identity)) return true
        when (event) {
            is TunnelEventParser.Event.Process -> {
                nativeProcessPid = event.pid
            }
            is TunnelEventParser.Event.Stats -> {
                activeWorkers.value = event.active
                if (workerRecoveryPolicy.observe(event.active)) {
                    workerRecoveryJob?.cancel()
                    workerRecoveryJob = null
                }
                if (event.active > 0) {
                    wrapAuthTimeoutCount = 0
                    val currentConfig = config.value
                    if (!vpnReady.value && currentConfig != null && currentConfig.startsWith("TUNCONF:") &&
                        SystemClock.elapsedRealtime() - lastVpnStartAttemptMs >= VPN_START_RETRY_MS
                    ) {
                        ensureVpnStarted(currentConfig, identity, reason = "stats_no_vpn")
                    }
                }
                markConnectedIfReady()
                traffic.value = trafficTracker.onStats(
                    identity.generation,
                    event.bytesUp,
                    event.bytesDown,
                    SystemClock.elapsedRealtime(),
                )
                val totalMB = (event.bytesUp + event.bytesDown) / (1024.0 * 1024.0)
                val msg = "Активных: ${event.active} | Трафик: %.2f МБ".format(totalMB)
                updateProcessLog(identity, "stats", "[СЕТЬ] $msg", 25, false, LogLevel.NET)
            }
            is TunnelEventParser.Event.ActiveZero -> {
                activeWorkers.value = 0
                scheduleWorkerZeroRecovery(identity)
            }
            is TunnelEventParser.Event.CallUnavailable -> handleUnavailableVkCall(event, identity)
            is TunnelEventParser.Event.NetworkSuspect -> verifyVkReachability(identity)
            is TunnelEventParser.Event.ServerRestart -> schedulePanelServerRestart(identity)
            is TunnelEventParser.Event.Ready -> {
                identity.startupProgress.streamReady()
                armServerSilence(identity)
                if (event.worker == 0 || identity.readyWorkers.add(event.worker)) {
                    updateProcessLog(identity, "ready", "[ВОРКЕР] Поток готов ✓", 20, false, LogLevel.OK)
                }
            }
            is TunnelEventParser.Event.Dtls -> {
                if (isCurrent(identity)) {
                    val suffix = event.message.trim()
                    val isError = suffix.contains("не состоялось") || suffix.contains("ошибка", ignoreCase = true)
                    val level = if (isError) LogLevel.ERR else LogLevel.OK
                    if (isError) {
                        val message = "[DTLS] Поток ${event.worker}: $suffix"
                        updateProcessLog(identity, "dtls_err_${event.worker}", message, 30, true, level)
                    } else {
                        val message = if (suffix.contains("завершено")) {
                            "[DTLS] Рукопожатие завершено, ключи SRTP установлены ✓"
                        } else if (suffix.isNotEmpty()) {
                            "[DTLS] $suffix"
                        } else {
                            "[DTLS] Рукопожатие выполняется"
                        }
                        updateProcessLog(identity, "dtls_status", message, 30, false, LogLevel.OK)
                    }
                }
            }
            is TunnelEventParser.Event.Config -> {
                val configStr = event.config.trim()
                if (configStr.isNotEmpty()) {
                    config.value = configStr
                    if (configStr.startsWith("TUNCONF:")) {
                        tunconfThisProcess.value = true
                        serverSilent.value = false
                        markConnectedIfReady()
                    }
                    activeScope.launch(Dispatchers.Main) {
                        if (!isCurrent(identity)) return@launch
                        if (configStr.startsWith("TUNCONF:")) {
                            ensureVpnStarted(configStr, identity, reason = "tunconf")
                        } else {
                            updateProcessLog(identity, "vpn_config_err", "Получен неизвестный формат конфига", 99, true)
                        }
                    }
                }
            }
            is TunnelEventParser.Event.Hashes -> {
                // an owner's run gets its own hashes back (the main password's set): neither masked nor stored (review F3)
                val link = sourceLink
                if (link != null) {
                    // masked in every later line, like the link's; stored for the next connect, a recovery restart included
                    ownerHashes = (ownerHashes + event.hashes).distinct()
                    val profile = sourceProfile
                    activeContext()?.let { ctx ->
                        activeScope.launch {
                            if (SettingsStore(ctx).saveReceivedHashes(profile, event.hashes, link)) {
                                updateProcessLog(
                                    identity,
                                    "server_hashes",
                                    "[СЕРВЕР] Владелец сменил хеши звонков: ${event.hashes.size} — применятся при следующем подключении",
                                    40,
                                    false,
                                    LogLevel.OK,
                                )
                            }
                        }
                    }
                }
            }
            is TunnelEventParser.Event.Routing -> {
                val routingJsonStr = event.routing.trim()
                if (routingJsonStr.isNotEmpty()) {
                    activeScope.launch(Dispatchers.Main) {
                        if (!isCurrent(identity)) return@launch
                        val rule = parseServerRoutingRuleJson(routingJsonStr)
                        if (rule == null) {
                            handleCriticalError("Сервер передал некорректное правило маршрутизации", identity)
                            return@launch
                        }
                        val activeConf = config.value
                        var clientIp = ""
                        var dns = ""
                        if (activeConf != null && activeConf.startsWith("TUNCONF:")) {
                            val parts = activeConf.substring("TUNCONF:".length).split(":")
                            clientIp = parts.getOrNull(0)?.trim().orEmpty()
                            dns = parts.getOrNull(1)?.trim().orEmpty()
                        }
                        applyServerRoutingRule(rule, clientIp, dns)
                    }
                }
            }
            is TunnelEventParser.Event.Workers -> {
                handleServerAssignedWorkers(event.workers, identity)
            }
            is TunnelEventParser.Event.Error -> {
                if (event.fatal) {
                    handleCriticalError(event.message, identity)
                } else {
                    updateProcessLog(identity, "event_error_${event.code}", event.message, 99, true)
                }
            }
            is TunnelEventParser.Event.CaptchaRequest -> {
                activeScope.launch {
                    if (isCurrent(identity)) {
                        handleCaptchaSolve(event.mode, event.redirectUri, event.sessionToken, identity)
                    }
                }
            }
            is TunnelEventParser.Event.Progress -> {
                if (event.kind == "credentials") {
                    identity.startupProgress.credentialReceived()
                    // VK gave credentials: a captcha need with no ask live is over (B5h2)
                    ManlCaptchaWebViewManager.onRunProgress()
                }
            }
            is TunnelEventParser.Event.TunLost -> {
                if (isCurrent(identity)) {
                    val now = SystemClock.elapsedRealtime()
                    val escalate = now - lastTunLossMs < TUN_LOSS_REBUILD_WINDOW_MS
                    lastTunLossMs = now
                    updateProcessLog(
                        identity,
                        if (escalate) "tun_lost_rebuild" else "tun_lost_reuse",
                        if (escalate) {
                            "[TUN] Запрос переподключения FD (пересборка интерфейса)"
                        } else {
                            "[TUN] Повторная выдача действующего TUN FD без пересборки"
                        },
                        20,
                        false,
                        LogLevel.OK,
                    )
                    config.value?.let { conf ->
                        ensureVpnStarted(
                            conf,
                            identity,
                            forceRebuild = escalate,
                            reason = if (escalate) "tun_lost_rebuild" else "tun_lost_reuse",
                        )
                    }
                }
            }
            else -> return false
        }
        return true
    }

    private fun handleServerAssignedWorkers(assignedWorkers: Int, identity: ProcessIdentity) {
        if (!isCurrent(identity)) return
        serverAssignedWorkers.value = if (assignedWorkers in TunnelEventParser.MIN_SERVER_WORKERS..CsqttConstants.Tunnel.MAX_WORKERS) assignedWorkers else null
    }

    private fun verifyVkReachability(identity: ProcessIdentity) {
        if (!isCurrent(identity) || !running.value) return
        val context = activeContext() ?: return
        runCatching {
            context.applicationContext.startService(
                Intent(context.applicationContext, TunnelService::class.java).apply {
                    action = CsqttConstants.General.ACTION_VERIFY_VK_REACHABILITY
                },
            )
        }
    }

    private fun recoverUnavailableVkCall(hash: String) {
        if (!running.value || !callRecoveryPending.compareAndSet(false, true)) return
        val context = activeContext() ?: run {
            callRecoveryPending.set(false)
            return
        }
        runCatching {
            context.applicationContext.startService(
                Intent(context.applicationContext, TunnelService::class.java).apply {
                    action = CsqttConstants.General.ACTION_RECOVER_VK_CALL
                    putExtra(CsqttConstants.General.EXTRA_UNAVAILABLE_VK_HASH, hash)
                },
            )
        }.onFailure {
            callRecoveryPending.set(false)
        }
    }

    private fun handleUnavailableVkCall(
        event: TunnelEventParser.Event.CallUnavailable,
        identity: ProcessIdentity,
    ) {
        val params = currentParams ?: return
        val displayHash = displayVkHash(event.hash)
        val reason = if (event.code == TunnelEventParser.CALL_NOT_FOUND_CODE) "звонок не найден" else "код ${event.code}"
        if (shouldReplaceUnavailableVkHash(params.vkHashMode)) {
            synchronized(identity.unavailableHashes) { identity.unavailableHashes.add(event.hash) }
            updateLog(
                key = "vk_hash_auto_replace_$displayHash",
                message = "[VK] Хеш \"$displayHash\" устарел · ошибка ${event.code} — $reason · создаём новый ✓",
                priority = 36,
                level = LogLevel.OK,
            )
            recoverUnavailableVkCall(event.hash)
            return
        }

        val isNewUnavailableHash = synchronized(identity.unavailableHashes) {
            identity.unavailableHashes.add(event.hash)
        }
        if (!isNewUnavailableHash) return
        updateProcessLog(
            identity = identity,
            key = "vk_hash_manual_invalid_$displayHash",
            message = "[VK] Хеш \"$displayHash\" устарел · ошибка ${event.code} — $reason · инвалидируем и используем активные хеши ✓",
            priority = 36,
            level = LogLevel.OK,
        )
        activeScope.launch {
            val remaining = params.vkHashes
                .split(Regex("[,\\s\\n]+"))
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .count { hash ->
                    synchronized(identity.unavailableHashes) {
                        hash !in identity.unavailableHashes
                    }
                }
            if (remaining == 0 && isCurrent(identity)) {
                recordRunFailure(StopCause.ALL_HASHES_DEAD)
                stop()
            }
        }
    }

    internal fun completeVkCallRecovery() {
        callRecoveryPending.set(false)
    }

    private fun ensureVpnStarted(
        configStr: String,
        identity: ProcessIdentity,
        forceRebuild: Boolean = false,
        reason: String = "ensure",
    ) {
        ensureVpnStartedMeasured(configStr, identity, forceRebuild, reason)
    }

    private fun ensureVpnStartedMeasured(
        configStr: String,
        identity: ProcessIdentity,
        forceRebuild: Boolean,
        reason: String,
    ) {
        if (!configStr.startsWith("TUNCONF:")) return
        val parts = configStr.removePrefix("TUNCONF:").split(":", limit = 5)
        val clientIp = parts.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val dns = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return

        val b64Routing = parts.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("pad=") && !it.startsWith("workers=") }
        var newServerRule: ServerRoutingRule? = null
        if (b64Routing != null) {
            val routingJsonStr = try {
                val decoded = android.util.Base64.decode(b64Routing, android.util.Base64.DEFAULT)
                if (decoded.size >= 2 && decoded[0] == 0x1f.toByte() && decoded[1] == 0x8b.toByte()) {
                    java.util.zip.GZIPInputStream(decoded.inputStream()).bufferedReader(Charsets.UTF_8).use { it.readText() }
                } else {
                    String(decoded, Charsets.UTF_8)
                }
            } catch (_: Exception) {
                null
            }
            newServerRule = routingJsonStr?.let(::parseServerRoutingRuleJson)
            if (newServerRule == null) {
                handleCriticalError("Сервер передал некорректное правило маршрутизации", identity)
                return
            }
        }
        if (newServerRule != null || activeServerRoutingRule.value != null) {
            applyServerRoutingRule(newServerRule, clientIp, dns)
        } else {
            // every new core process sends TUNCONF: a network swap, crash or VK-recovery restart starts the core with the
            // routing of the tunnel's start, so the local rules in force now are sent again (review B1, M-57)
            activeContext()?.let(::pushLocalRouting)
        }

        activeContext()?.let { ctx ->
            lastVpnStartAttemptMs = SystemClock.elapsedRealtime()
            val vpnIntent = Intent(ctx, TunVpnService::class.java).apply {
                action = "START"
                stampVpnIntent(this, identity.generation)
                putExtra("client_ip", clientIp)
                putExtra("dns", dns)
                putExtra("force_rebuild", forceRebuild || vpnRebuildPending.getAndSet(false))
                putExtra("reason", reason)
            }
            try {
                startTunVpnService(ctx, vpnIntent)
            } catch (e: Exception) {
                updateProcessLog(identity, "vpn_start_error", "Ошибка запуска VPN: ${e.readableMessage()}", 99, true)
            }
        }
    }

    private fun startTunVpnService(context: Context, intent: Intent) {
        context.startService(intent)
    }

    private const val EXTRA_VPN_GENERATION = "vpn_generation"
    private const val EXTRA_VPN_SEQUENCE = "vpn_sequence"

    /** (process generation, sequence) for TunVpnService: see [VpnIntentGate]. */
    private fun stampVpnIntent(intent: Intent, generation: Long) {
        intent.putExtra(EXTRA_VPN_GENERATION, generation)
        intent.putExtra(EXTRA_VPN_SEQUENCE, vpnIntentGate.stamp())
    }

    /** Whether TunVpnService applies [intent]: Android's own (no stamp) always, ours only while still current. */
    internal fun acceptsVpnIntent(intent: Intent): Boolean {
        if (!intent.hasExtra(EXTRA_VPN_SEQUENCE)) return true
        return vpnIntentGate.accept(
            isStop = intent.action == "STOP",
            generation = intent.getLongExtra(EXTRA_VPN_GENERATION, -1L),
            sequence = intent.getLongExtra(EXTRA_VPN_SEQUENCE, 0L),
            currentGeneration = processIdentity?.generation,
            runWanted = lifecycleState.isDesiredRunning(),
        )
    }

    /** A stream is ready; with no TUNCONF [SERVER_SILENT_MS] later, the server took the session and hangs (M-2). */
    private fun armServerSilence(identity: ProcessIdentity) {
        if (identity.silenceArmed) return
        identity.silenceArmed = true
        activeScope.launch {
            delay(SERVER_SILENT_MS)
            if (isCurrent(identity) && !tunconfThisProcess.value) {
                serverSilent.value = true
                updateProcessLog(
                    identity,
                    "server_silent",
                    "[СЕРВЕР] Поток готов, но сервер не отвечает на запрос конфигурации",
                    98,
                    true,
                )
            }
        }
    }

    private fun scheduleWorkerZeroRecovery(identity: ProcessIdentity) {
        val target = workerRecoveryPolicy.armAtZero() ?: return
        if (workerRecoveryJob?.isActive == true) return
        workerRecoveryJob = activeScope.launch {
            try {
                delay(WORKER_ZERO_CONFIRMATION_MS)
                if (!isCurrent(identity) || !running.value) return@launch
                if (!workerRecoveryPolicy.shouldRecover(activeWorkers.value, target)) return@launch
                val context = activeContext() ?: return@launch
                requestTransportRestart(
                    context.applicationContext,
                    "workers_zero_refresh",
                    "[NET] Потоки: 0 · обновление TURN",
                )
            } finally {
                if (workerRecoveryJob === coroutineContext[Job]) {
                    workerRecoveryJob = null
                }
            }
        }
    }

    private fun schedulePanelServerRestart(identity: ProcessIdentity) {
        if (!isCurrent(identity) || !running.value) return
        if (!panelRestartPending.compareAndSet(false, true)) return
        resetWorkerRecoveryState()
        panelRestartJob?.cancel()
        panelRestartJob = activeScope.launch {
            try {
                delay(PANEL_SERVER_RESTART_DELAY_MS)
                if (!isCurrent(identity) || !running.value) return@launch
                val context = activeContext() ?: return@launch
                requestTransportRestart(
                    context.applicationContext,
                    "panel_server_restart",
                    "[SERVER] Перезагрузка · переподключение",
                )
            } finally {
                panelRestartPending.set(false)
                if (panelRestartJob === coroutineContext[Job]) panelRestartJob = null
            }
        }
    }

    private fun requestTransportRestart(
        context: Context,
        logKey: String,
        message: String,
    ) {
        if (logKey == "workers_zero_refresh") {
            runCatching {
                context.startService(
                    Intent(context, TunnelService::class.java).apply {
                        action = CsqttConstants.General.ACTION_RESTART_WHEN_VK_REACHABLE
                    },
                )
            }.onFailure {
                updateLog(
                    "vk_recovery_service_error",
                    "Не удалось запустить проверку VK: ${it.readableMessage()}",
                    99,
                    true,
                )
            }
            return
        }
        if (!transportRestartPending.compareAndSet(false, true)) return
        val command = lifecycleCommand.incrementAndGet()
        val appContext = context.applicationContext
        activeScope.launch {
            try {
                val params = currentParams ?: return@launch
                if (!running.value) return@launch
                val newParams = runCatching { params.withNewSession(appContext) }
                    .getOrElse {
                        if (logKey == "network_swap") {
                            pausedForNoNetwork.restore()
                            syncNetworkPaused()
                        }
                        if (it is kotlin.coroutines.cancellation.CancellationException) throw it
                        updateLog(
                            "${logKey}_epoch_error",
                            "Ошибка обновления эпохи: ${it.readableMessage()}",
                            99,
                            true,
                        )
                        return@launch
                    }
                startStopMutex.withLock {
                    if (
                        lifecycleCommand.get() != command ||
                        !running.value ||
                        currentParams != params
                    ) {
                        return@withLock
                    }
                    val epoch = lifecycleState.requestRestart() ?: return@withLock
                    restartJob?.cancel()
                    restartJob = null
                    vpnReady.value = false
                    updateLog(logKey, message, 2, false)
                    terminateProcessLocked(processIdentity)
                    currentParams = newParams
                    startProcessLocked(appContext, newParams, epoch)
                }
            } finally {
                transportRestartPending.set(false)
            }
        }
    }

    private fun resetWorkerRecoveryState() {
        workerRecoveryJob?.cancel()
        workerRecoveryJob = null
        workerRecoveryPolicy.reset()
    }

    private var observersInitialized = false

    fun initObservers(context: Context) {
        if (observersInitialized) return
        observersInitialized = true
        val appContext = context.applicationContext
        scope.launch {
            connectionState.map { it.label }.distinctUntilChanged().collect {
                try {
                    VpnWidgetProvider.updateAllWidgets(appContext)
                    android.service.quicksettings.TileService.requestListeningState(
                        appContext,
                        android.content.ComponentName(appContext, QuickToggleTileService::class.java)
                    )
                } catch (e: Exception) {
                }
            }
        }
    }

    private val deploySessionCounter = AtomicLong()
    private val deployLineCounter = AtomicLong()
    @Volatile
    private var deploySessionId = 0L

    fun beginDeployLog(message: String) {
        deploySessionId = deploySessionCounter.incrementAndGet()
        deployLineCounter.set(0L)
        addDeployInfoLog(message)
    }

    fun addDeployInfoLog(message: String) = addDeployLog(message, LogLevel.LOG)

    fun addDeploySuccessLog(message: String) {
        addDeployLog(message, LogLevel.OK)
    }

    fun addDeployWarningLog(message: String) {
        addDeployLog("Предупреждение: $message", LogLevel.LOG)
    }

    fun addDeployErrorLog(message: String) {
        addDeployLog(message, LogLevel.ERR)
    }

    private fun addDeployLog(message: String, level: LogLevel) {
        val clean = message.trim().take(500)
        if (clean.isEmpty()) return
        if (deploySessionId == 0L) {
            deploySessionId = deploySessionCounter.incrementAndGet()
            deployLineCounter.set(0L)
        }
        val sequence = deployLineCounter.incrementAndGet().toString().padStart(5, '0')
        updateLog(
            key = "deploy_${deploySessionId}_$sequence",
            message = "[ДЕПЛОЙ] $clean",
            priority = 7,
            isError = level == LogLevel.ERR,
            level = level,
        )
    }

    internal fun updateLog(
        key: String,
        message: String,
        priority: Int,
        isError: Boolean = false,
        level: LogLevel? = null,
    ) {
        updateLogForProcess(null, key, message, priority, isError, level)
    }

    private fun updateProcessLog(
        identity: ProcessIdentity,
        key: String,
        message: String,
        priority: Int,
        isError: Boolean = false,
        level: LogLevel? = null,
    ) {
        updateLogForProcess(identity, key, message, priority, isError, level)
    }

    private fun updateLogForProcess(
        identity: ProcessIdentity?,
        key: String,
        message: String,
        priority: Int,
        isError: Boolean,
        level: LogLevel?,
    ) {
        if (identity != null && !isCurrent(identity)) return
        if (!isLoggingEnabled && !TunnelLogPolicy.isAllowedWhenInactiveLogging(key, isError, level)) return
        val cleanMessage = LogRedaction.redact(maskOwnerHashes(withoutLogStickers(message), ownerHashes))
            .take(MAX_LOG_MESSAGE_LENGTH)
        if (cleanMessage.isEmpty()) return
        val pendingKey = "${identity?.generation ?: 0L}:$key:$priority:$isError:${level?.name.orEmpty()}"
        synchronized(pendingLogLock) {
            val pending = pendingLogs[pendingKey]
            if (pending == null) {
                if (pendingLogs.size >= MAX_PENDING_LOGS) {
                    pendingLogs.remove(pendingLogs.entries.first().key)
                }
                pendingLogs[pendingKey] = PendingLog(identity, key, cleanMessage, priority, isError, level, 1)
            } else {
                pending.message = cleanMessage
                pending.count += 1
            }
            scheduleLogFlushLocked()
        }
    }

    private fun scheduleLogFlushLocked() {
        if (!pendingLogFlushScheduled.compareAndSet(false, true)) return
        scope.launch(Dispatchers.Main) {
            delay(LOG_UI_FLUSH_MS)
            flushPendingLogs()
        }
    }

    private fun flushPendingLogs() {
        val batch = synchronized(pendingLogLock) {
            val values = pendingLogs.values.toList()
            pendingLogs.clear()
            pendingLogFlushScheduled.set(false)
            values
        }
        batch.forEach(::applyPendingLog)
    }

    private fun applyPendingLog(pending: PendingLog) {
        val identity = pending.identity
        if (identity != null && !isCurrent(identity)) return
        val index = logs.indexOfFirst { it.key == pending.key }
        if (pending.isError && index == -1) {
            unreadErrorCount.value++
        }
        if (index != -1) {
            val entry = logs[index]
            if (entry.priority == pending.priority) {
                logs[index] = entry.copy(
                    count = entry.count + pending.count,
                    message = pending.message,
                    isError = pending.isError,
                    level = pending.level,
                )
            } else {
                logs.removeAt(index)
                insertSorted(
                    LogEntry(
                        pending.key,
                        pending.message,
                        entry.count + pending.count,
                        pending.priority,
                        pending.isError,
                        pending.level,
                    )
                )
            }
        } else {
            insertSorted(
                LogEntry(
                    pending.key,
                    pending.message,
                    pending.count,
                    pending.priority,
                    pending.isError,
                    pending.level,
                )
            )
        }

        while (logs.size > MAX_LOG_ENTRIES) {
            logs.removeAt(0)
        }
    }

    private fun isCurrent(identity: ProcessIdentity): Boolean =
        processIdentity === identity &&
            process === identity.process &&
            processGeneration == identity.generation &&
            lifecycleState.accepts(identity.ticket)

    private fun insertSorted(entry: LogEntry) {
        val comparator = compareBy<LogEntry>({ it.priority }, { if (it.isError) 1 else 0 }, { it.key })
        var inserted = false
        for (i in logs.indices) {
            if (comparator.compare(entry, logs[i]) < 0) {
                logs.add(i, entry)
                inserted = true
                break
            }
        }
        if (!inserted) {
            logs.add(entry)
        }
    }

    fun start(context: Context, params: TunnelParams, isSwitching: Boolean = false) {
        val command = lifecycleCommand.incrementAndGet()
        autoPausedForWifi.value = false
        stopping.value = false
        
        if (!isSwitching) {
            pausedForNoNetwork.reset()
            syncNetworkPaused()
        }
        
        activeScope.launch {
            startStopMutex.withLock {
                if (lifecycleCommand.get() != command) return@withLock
                if (!isSwitching && lifecycleState.isDesiredRunning()) {
                    starting.value = false
                    return@withLock
                }
                val appContext = context.applicationContext
                val epoch = if (isSwitching) {
                    lifecycleState.requestRestart() ?: return@withLock
                } else {
                    clearLogs()
                    config.value = null
                    wrapAuthTimeoutCount = 0
                    crashRestartStreak = 0
                    processStartedAtMs = 0L
                    currentParams = params
                    strongAppContext = appContext.applicationContext
                    lastContext = WeakReference(appContext)
                    activeSettingsStore = SettingsStore(appContext, ProfileScope.Run)
                    lastTunnelDiagnostic.value = ""
                    activeScope.launch { activeSettingsStore?.clearTunnelDiagnostic() }
                    currentCaptchaSolveMethod = params.captchaSolveMethod
                    runFatal.set(FirstFatal())
                    failure.value = null
                    everConnected.value = false
                    trafficTracker.reset()
                    traffic.value = null
                    lifecycleState.requestStart()
                }
                restartJob?.cancel()
                restartJob = null
                if (isSwitching) terminateProcessLocked(processIdentity)
                if (isSwitching) currentParams = params
                startProcessLocked(appContext, params, epoch)
            }
        }
    }

    /**
     * The operator (2026-11-21): a run moved onto another profile in place, the VPN staying up while the core is
     * replaced. Unlike a recovery's restart (the same run on new hashes) it is a new connection: the old run's captcha is
     * cancelled, its core ends the VK calls it made itself («Авто ВК»), [identity] becomes the run's only once its process
     * starts, and every per-server state starts fresh, so the old server's address, DNS and rule are never replayed into
     * the new one (profile-switch review F4, F5, F7, H2). Decided under the lock: with no run desired any more it is a
     * plain start (review H1).
     */
    internal fun switchProfile(context: Context, params: TunnelParams, identity: RunIdentity) {
        val command = lifecycleCommand.incrementAndGet()
        autoPausedForWifi.value = false
        stopping.value = false
        activeScope.launch {
            startStopMutex.withLock {
                if (lifecycleCommand.get() != command) return@withLock
                val appContext = context.applicationContext
                val replacing = lifecycleState.isDesiredRunning()
                ManlCaptchaWebViewManager.cancelCaptcha()
                CaptchaWebViewManager.onTunnelStop()
                CaptchaWebViewManager.onTunnelStart(appContext)
                restartJob?.cancel()
                restartJob = null
                if (replacing) terminateProcessLocked(processIdentity, finishVkCalls = true)
                applyRunIdentity(identity)
                // a newer pick made during this switch keeps its own target (switch verify 2)
                if (switchTarget == identity.profile) switchTarget = null
                config.value = null
                wrapAuthTimeoutCount = 0
                crashRestartStreak = 0
                processStartedAtMs = 0L
                currentParams = params
                strongAppContext = appContext
                lastContext = WeakReference(appContext)
                activeSettingsStore = SettingsStore(appContext, ProfileScope.Run)
                lastTunnelDiagnostic.value = ""
                currentCaptchaSolveMethod = params.captchaSolveMethod
                runFatal.set(FirstFatal())
                failure.value = null
                everConnected.value = false
                tunconfThisProcess.value = false
                serverSilent.value = false
                targetWorkers.value = 0
                serverRoutingActive.value = false
                activeServerRoutingRule.value = null
                serverAssignedWorkers.value = null
                lastAppliedDns = null
                vpnRebuildPending.set(false)
                trafficTracker.reset()
                traffic.value = null
                val epoch = if (replacing) lifecycleState.requestRestart() ?: return@withLock else lifecycleState.requestStart()
                startProcessLocked(appContext, params, epoch)
                // `running` does not change on a switch, so nothing else ends the `starting` its restore began
                starting.value = false
            }
        }
    }

    private fun failStartLocked(key: String, message: String) {
        updateLog(key, message, 99, true)
        recordRunFailure(message.removePrefix("Ошибка: "))
        lifecycleState.requestStop()
        stopUptimeTimer()
        running.value = false
        stopping.value = false
        starting.value = false
        activeWorkers.value = 0
    }

    private fun startProcessLocked(context: Context, params: TunnelParams, epoch: Long) {
        if (!lifecycleState.canStart(epoch)) {
            starting.value = false
            return
        }
        val existingProcess = process
        if (existingProcess != null) {
            if (existingProcess.isAlive) {
                terminateExactProcess(existingProcess, 1_000L, false)
            }
            process = null
            processIdentity = null
            nativeProcessPid = null
        }
        val hashList = params.vkHashes
            .split(Regex("[,\\s\\n]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(CsqttConstants.Tunnel.MAX_VK_HASHES)

        val jsHashMode = params.vkHashMode == CsqttConstants.VkAutoHash.MODE_AUTO_JS
        val jsAuthMode = params.vkAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS
        val effectiveAuthMode = if (jsAuthMode && !jsHashMode) {
            CsqttConstants.VkAuth.MODE_CALLS
        } else {
            params.vkAuthMode
        }
        if (hashList.isEmpty() && !jsHashMode) {
            failStartLocked("hash_error", "Ошибка: Хеш не указан")
            return
        }
        if (jsHashMode && params.vkAccessToken.isBlank()) {
            failStartLocked("vk_token_error", "Ошибка: VK access token не указан")
            return
        }

        val binaryPath = context.applicationInfo.nativeLibraryDir + "/${CsqttConstants.Tunnel.BINARY_NAME}"
        if (!File(binaryPath).exists()) {
            failStartLocked("binary_error", "Ошибка: Бинарный файл не найден")
            return
        }

        val totalWorkers = WorkerCountPolicy.normalizeForHashValues(
            params.workersPerHash,
            hashList,
            params.allowHashRedistribution || jsHashMode,
        )
        val cmd = coreBaseCommand(binaryPath, params, totalWorkers, effectiveAuthMode).toMutableList()
        val isDomainRoutingActive = params.domainRoutingEnabled || params.excludeRuZones || params.templateDomains || params.directDomains.isNotBlank()
        if (isDomainRoutingActive) {
            cmd.add("--domain-routing")
            if (params.domainIsWhitelist) {
                cmd.add("--domain-whitelist")
            }
            if (params.excludeRuZones && !params.domainIsWhitelist) {
                cmd.add("--exclude-ru-zones")
            }
            if (params.templateDomains && !params.domainIsWhitelist) {
                cmd.add("--template-domains")
                val templateFile = java.io.File(context.filesDir, "domains.txt")
                if (!templateFile.exists() || templateFile.length() == 0L) {
                    runCatching {
                        context.assets.open("domains.txt").use { input ->
                            templateFile.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
                if (templateFile.exists() && templateFile.length() > 0L) {
                    cmd.add("--domains-file")
                    cmd.add(templateFile.absolutePath)
                }
            }
            if (params.directDomains.isNotBlank()) {
                val userFile = java.io.File(context.filesDir, "user.txt")
                runCatching {
                    val content = params.directDomains.split(',').filter { it.isNotBlank() }.joinToString("\n")
                    userFile.writeText(content)
                }
                if (userFile.exists() && userFile.length() > 0L) {
                    cmd.add("--domains-file")
                    cmd.add(userFile.absolutePath)
                }
            }
        }
        if (params.ipRoutingEnabled || params.directIps.isNotBlank()) {
            cmd.add("--ip-routing")
            if (params.ipIsWhitelist) {
                cmd.add("--ip-whitelist")
            }
            if (params.directIps.isNotBlank()) {
                cmd.add("--direct-ips")
                cmd.add(params.directIps)
            }
        }

        val pb = ProcessBuilder(cmd)
        pb.directory(context.filesDir)
        pb.redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
        pb.environment()[CsqttConstants.Tunnel.PROCESS_ENV_EVENTS] = "1"
        pb.environment()["RAYON_NUM_THREADS"] = "2"
        pb.environment().putAll(coreSecretEnv(params.connectionPassword, hashList))

        val ticket = lifecycleState.reserveProcess(epoch, process == null)
        if (ticket == null) {
            starting.value = false
            return
        }
        val startedProcess = try {
            pb.start()
        } catch (e: Exception) {
            lifecycleState.releaseReservation(ticket)
            failStartLocked("critical_start_error", "Критическая ошибка запуска: ${e.readableMessage()}")
            return
        }
        if (jsHashMode) {
            val bootstrap = JSONObject()
                .put("token", params.vkAccessToken)
                .toString()
            val encoded = Base64.encodeToString(
                bootstrap.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP,
            )
            try {
                startedProcess.outputStream.write(
                    "VK_JS_BOOTSTRAP:$encoded\n".toByteArray(Charsets.UTF_8),
                )
                startedProcess.outputStream.flush()
            } catch (e: Exception) {
                startedProcess.destroy()
                lifecycleState.releaseReservation(ticket)
                failStartLocked(
                    "vk_js_bootstrap_error",
                    "Ошибка передачи данных Auto JS: ${e.readableMessage()}",
                )
                return
            }
        }
        processGeneration = if (processGeneration == Long.MAX_VALUE) 1L else processGeneration + 1L
        val identity = ProcessIdentity(
            startedProcess,
            processGeneration,
            ticket,
            3_500L,
        )
        process = startedProcess
        tunconfThisProcess.value = false
        serverSilent.value = false
        targetWorkers.value = totalWorkers
        callRecoveryPending.set(false)
        nativeProcessPid = processIdOf(startedProcess)
        processIdentity = identity
        running.value = true
        startUptimeTimer()
        wrapAuthTimeoutCount = 0
        activeWorkers.value = 0
        resetWorkerRecoveryState()
        startLogReader(identity)
        scheduleStartupDiagnostic(identity)
        config.value
            ?.trim()
            ?.takeIf { it.startsWith("TUNCONF:") }
            ?.let { retainedConfig ->
                activeScope.launch {
                    delay(250L)
                    if (isCurrent(identity)) ensureVpnStarted(retainedConfig, identity, reason = "startup")
                }
            }
    }

    private fun scheduleStartupDiagnostic(identity: ProcessIdentity) {
        activeScope.launch {
            delay(STARTUP_DIAGNOSTIC_MS)
            if (!isCurrent(identity) || activeWorkers.value > 0) return@launch
            when (identity.startupProgress.stage()) {
                TunnelStartupStage.WAITING_FOR_CREDENTIALS -> updateProcessLog(
                    identity,
                    "startup_waiting_credentials",
                    "[ДИАГНОСТИКА] Rust-клиент запущен, но VK не выдал TURN-креды: проверьте сеть, токен и капчу",
                    98,
                    true,
                )
                TunnelStartupStage.WAITING_FOR_TURN_OR_PEER -> updateProcessLog(
                    identity,
                    "startup_waiting_turn",
                    "[ДИАГНОСТИКА] TURN-креды получены, но ни один allocation/handshake с сервером не завершился",
                    98,
                    true,
                )
                TunnelStartupStage.READY -> Unit
            }
        }
    }

    private fun startLogReader(identity: ProcessIdentity) {
        val targetProcess = identity.process
        readerJob = activeScope.launch {
            val reader = targetProcess.inputStream.bufferedReader()
            var lastDiagnostic: String? = null

            try {
                reader.forEachLine { line ->
                    if (!isCurrent(identity)) return@forEachLine

                    val stripped =
                        if (line.length > 19 && line[0].isDigit() && line[4] == '/') {
                            line.replace(LOG_TIMESTAMP_PREFIX, "")
                        } else {
                            line
                        }
                    var lineTrim = stripped.trim()
                    val eventPrefix = CsqttConstants.General.CSQTT_EVENT_PREFIX
                    val eventIdx = lineTrim.indexOf(eventPrefix)
                    if (eventIdx >= 0) {
                        val eventPart = lineTrim.substring(eventIdx)
                        lineTrim = lineTrim.substring(0, eventIdx).trim()
                        val event = TunnelEventParser.parse(eventPart)
                        if (event != null) {
                            handleTunnelEvent(event, identity)
                        }
                        if (lineTrim.isEmpty()) {
                            return@forEachLine
                        }
                    } else {
                        val event = TunnelEventParser.parse(lineTrim)
                        if (event != null && handleTunnelEvent(event, identity)) {
                            return@forEachLine
                        }
                    }

                    val isCritical = lineTrim.startsWith("CAPTCHA_SOLVE|") ||
                        lineTrim.contains("FATAL_") ||
                        lineTrim.contains("[ФАТАЛ]", true) ||
                        lineTrim.contains("[PANIC]", true) ||
                        lineTrim.contains("AUTH_RETRY") ||
                        lineTrim.contains("WRAP_AUTH_TIMEOUT", true) ||
                        lineTrim.contains("[WRAP]") ||
                        lineTrim.startsWith("[ВОРКЕР") ||
                        lineTrim.startsWith("[КРЕД") ||
                        lineTrim.startsWith("[СЕТЬ]") ||
                        lineTrim.startsWith("[ПОТОКИ]") ||
                        lineTrim.startsWith("[VK JS]") ||
                        lineTrim.contains("DENIED:", true) ||
                        lineTrim.contains("Ошибка", true) ||
                        lineTrim.contains("error", true) ||
                        lineTrim.contains("FAIL", true)

                    if ((lineTrim.contains("[КЛИЕНТ] Все воркеры завершены", true) || isCritical) &&
                        identity.fatal.cause == null
                    ) {
                        lastDiagnostic = lineTrim.take(240)
                    }

                    if (!isLoggingEnabled && !isCritical) {
                        return@forEachLine
                    }

                    if (TunnelLogPolicy.isInternalRecovery(lineTrim)) {
                        return@forEachLine
                    }

                    if (lineTrim.contains("WRAP_AUTH_TIMEOUT", true)) {
                        if (activeWorkers.value > 0) {
                            wrapAuthTimeoutCount = 0
                            updateProcessLog(
                                identity,
                                "wrap_timeout_recovered",
                                "[WRAP] Один поток не прошёл handshake, активных=${activeWorkers.value}; повторяем",
                                50,
                                true
                            )
                        } else {
                            wrapAuthTimeoutCount++
                            updateProcessLog(
                                identity,
                                "wrap_timeout_wait",
                                "[WRAP] Handshake не подтвердился, проверяем пароль/сеть ($wrapAuthTimeoutCount)",
                                50,
                                true
                            )
                        }
                        return@forEachLine
                    }

                    if (lineTrim.contains("FATAL_AUTH") || lineTrim.contains("AUTH_RETRY")) {
                        val reason = when {
                            lineTrim.contains("неверный пароль") -> "Неверный пароль подключения"
                            lineTrim.contains("истёк") -> "Срок действия пароля истёк"
                            lineTrim.contains("деактивирован") -> "Аккаунт деактивирован"
                            else -> "Ошибка авторизации"
                        }
                        handleCriticalError("\uD83D\uDD12 $reason. Воркеры остановлены.", identity)
                        return@forEachLine
                    }

                    if (lineTrim.contains("FATAL_PROTOCOL")) {
                        handleCriticalError(
                            "🔁 Клиент и сервер используют разные версии протокола. Выполните деплой из этой версии приложения. Воркеры остановлены.",
                            identity,
                        )
                        return@forEachLine
                    }

                    if (lineTrim.startsWith("CAPTCHA_SOLVE|")) {
                        val payload = lineTrim.substringAfter("CAPTCHA_SOLVE|")
                        val parts = payload.split("|", limit = 3)
                        when (parts.size) {
                            3 -> {
                                val requestMode = parts[0]
                                val redirectUri = parts[1]
                                val sessionToken = parts[2]
                                activeScope.launch {
                                    if (isCurrent(identity)) {
                                        handleCaptchaSolve(requestMode, redirectUri, sessionToken, identity)
                                    }
                                }
                            }
                            2 -> {
                                val redirectUri = parts[0]
                                val sessionToken = parts[1]
                                activeScope.launch {
                                    if (isCurrent(identity)) {
                                        handleCaptchaSolve("selected", redirectUri, sessionToken, identity)
                                    }
                                }
                            }
                            else -> {
                                writeCaptchaResult("error:invalid CAPTCHA_SOLVE format", identity)
                            }
                        }
                        return@forEachLine
                    }

                    val isError = lineTrim.contains("Ошибка", true) || lineTrim.contains("error", true) || lineTrim.contains("FAIL", true) || lineTrim.contains("timeout", true) || lineTrim.contains("refused", true) || lineTrim.contains("FATAL_", true)

                    when {
                        lineTrim.contains("[КАПЧА] AUTO:") -> {
                            var text = lineTrim.substringAfter("[КАПЧА] AUTO:").trim()
                            text = text.replace(Regex("\\s*\\([^)]+\\)\\s*"), " ").trim()

                            val isErr = text.contains("ошибка", true) ||
                                text.contains("timeout", true) ||
                                text.contains("не решил", true)
                            val stableKey = when {
                                text.contains("старт") -> "captcha_auto_1"
                                (text.contains("Go v2") || text.contains("Rust v2")) && text.contains("2 попыт") -> "captcha_auto_2"
                                text.contains("WBV Auto попытка") -> "captcha_auto_3"
                                text.contains("финальная") -> "captcha_auto_4"
                                text.contains("ручной WebView") -> "captcha_auto_5"
                                text.contains("решил") || text.contains("решила") -> "captcha_auto_done"
                                else -> "captcha_auto_${text.take(18).hashCode()}"
                            }
                            updateProcessLog(identity, stableKey, "[КАПЧА AUTO] $text", 60, isErr)
                        }

                        lineTrim.contains("[КАПЧА] RJS:") -> {
                            var text = lineTrim.substringAfter("[КАПЧА] RJS:").trim()
                            text = text.replace(Regex("\\s*\\([^)]+\\)\\s*"), " ").trim()

                            val stableKey = when {
                                text.contains("Загрузка") || text.contains("fetch") -> "captcha_rjs_1"
                                text.contains("PoW") -> "captcha_rjs_2"
                                text.contains("осматривает") || text.contains("человек") -> "captcha_rjs_3"
                                text.contains("captchaNotRobot") || text.contains("Отправка") -> "captcha_rjs_4"
                                text.contains("endSession") -> "captcha_rjs_5"
                                text.contains("решена") -> "captcha_rjs_6"
                                else -> "captcha_rjs_${text.take(15).hashCode()}"
                            }
                            updateProcessLog(identity, stableKey, "[КАПЧА RJS] $text", 60, false)
                        }

                        lineTrim.contains("[КАПЧА] WBV:") -> {
                            var text = lineTrim.substringAfter("[КАПЧА] WBV:").trim()
                            text = text.replace(Regex("\\s*\\([^)]+\\)\\s*"), " ").trim()

                            val isErr = text.contains("Ошибка")
                            val stableKey = when {
                                text.contains("Запрос") -> "captcha_wv_step_2"
                                text.contains("Токен") -> "captcha_wv_step_5"
                                isErr -> "captcha_wv_err"
                                else -> "captcha_wv_go_other"
                            }
                            updateProcessLog(identity, stableKey, "[КАПЧА WBV] $text", 60, isErr)
                        }

                        lineTrim.contains("Решаю VK Smart Captcha") ->
                            updateProcessLog(identity, "captcha_start", "[КАПЧА] Решение капчи...", 60, false)
                        lineTrim.contains("Smart Captcha решена") ->
                            updateProcessLog(identity, "captcha_done", "[КАПЧА] Капча решена ✓", 60, false)
                        lineTrim.contains("капча не решена") || lineTrim.contains("ошибка решения капчи") ->
                            updateProcessLog(identity, "captcha_failed", "[КАПЧА] Ошибка решения капчи", 99, true, LogLevel.ERR)
                        lineTrim == "[VK JS] Звонок создан" || lineTrim.contains("Звонок VK создан") || lineTrim.contains("Звонок создан") -> {
                            updateProcessLog(identity, "vk_js_call_progress", "[VK JS] Звонок VK создан ✓", 16, false, LogLevel.OK)
                        }
                        lineTrim.startsWith("[VK JS] Создатель вышел из звонков") ->
                            updateProcessLog(identity, "vk_js_creator_left", "[VK JS] Владелец вышел из звонка ✓", 19, false, LogLevel.OK)
                        lineTrim.startsWith("[VK JS] Звонки завершены") ->
                            updateProcessLog(identity, "vk_js_call_finished", "[VK JS] Звонок завершён ✓", 19, false, LogLevel.OK)
                        lineTrim.startsWith("[VK JS] Создатель удерживает звонок") ->
                            updateProcessLog(identity, "vk_js_creator_held", "[VK JS] Владелец удерживает звонок до отключения", 18, false, LogLevel.OK)

                        lineTrim.startsWith("[VK JS] Креды аккаунта") && lineTrim.contains("недоступны") -> {
                            updateProcessLog(identity, "vk_js_account_credentials_progress", "[VK JS] Креды аккаунта недоступны", 17, true, LogLevel.ERR)
                        }
                        lineTrim.startsWith("[VK JS] Креды аккаунта") -> {
                            updateProcessLog(identity, "vk_js_account_credentials_progress", "[VK JS] Креды аккаунта готовы ✓", 17, false, LogLevel.OK)
                        }
                        lineTrim.startsWith("[VK JS] Переход на Авто") -> {
                            val text = lineTrim.substringAfter("[VK JS]").trim()
                            val failed = text.contains("не выполнен")
                            val suffix = if (failed) "" else " ✓"
                            updateProcessLog(identity, "vk_js_account_fallback_progress", "[VK JS] $text$suffix", 17, failed, if (failed) LogLevel.ERR else LogLevel.OK)
                        }
                        lineTrim.contains("[WRAP]") -> {
                            updateProcessLog(identity, "wrap_status", "[WRAP] Ключ вычислен ✓", 10, false, LogLevel.OK)
                        }
                        lineTrim.startsWith("[ПОТОКИ]") -> {
                            val text = lineTrim.substringAfter("[ПОТОКИ]").trim()
                            val isRepair = text.contains("не пришёл", true) ||
                                text.contains("частично", true) ||
                                text.contains("Перезапуск", true)
                            val (stableKey, priority) = when {
                                text.startsWith("Отправлен ping", true) -> "stream_probe_ping" to 56
                                text.startsWith("ACK получен", true) || text.startsWith("Все активные", true) -> "stream_probe_ack" to 57
                                text.contains("ChannelBind", true) -> "stream_probe_rebind" to 58
                                text.contains("проверяю", true) -> "stream_probe_all" to 58
                                text.contains("Перезапуск", true) -> "stream_probe_restart" to 59
                                else -> "stream_probe_${text.take(24).hashCode()}" to 59
                            }
                            updateProcessLog(identity, stableKey, "[ПОТОКИ] $text", priority, isRepair, if (isRepair) LogLevel.ERR else LogLevel.LOG)
                        }
                        lineTrim.contains("[TURN]") -> {
                            val text = lineTrim.substringAfter("[TURN]").trim()
                            if (text.contains("Используются TURN IP", true)) {
                                return@forEachLine
                            }
                            val turnError = TunnelLogPolicy.isTurnStreamFailure(lineTrim) || isError
                            val (stableKey, priority) = when {
                                turnError -> "turn_error_${text.hashCode()}" to 99
                                text.contains("CreatePermission", true) -> "turn_permission_status" to 52
                                text.contains("ChannelBind", true) || text.contains("Канал", true) -> "turn_channel_status" to 53
                                text.contains("готова к передаче", true) -> "turn_ready_status" to 54
                                text.contains("Refresh", true) -> "turn_refresh_status" to 55
                                else -> null to 0
                            }
                            if (stableKey != null) {
                                updateProcessLog(identity, stableKey, "[TURN] $text", priority, turnError, if (turnError) LogLevel.ERR else LogLevel.LOG)
                            }
                        }
                        lineTrim.contains("Рукопожатие...") ->
                            updateProcessLog(identity, "peer_handshake", "Рукопожатие...", 65, false)

                        isError -> {
                            val errorKey = when {
                                lineTrim.contains("lookup login.vk.ru", true) -> "err_vk_dns"
                                lineTrim.contains("connection refused") -> "err_conn_refused"
                                lineTrim.contains("timeout") -> "err_timeout"
                                lineTrim.contains("кредов") -> "err_creds"
                                lineTrim.contains("PEER") -> "err_peer"
                                else -> "general_error_" + lineTrim.take(15).hashCode()
                            }
                            val errorMessage = if (errorKey == "err_vk_dns") {
                                "[СЕТЬ] DNS до VK недоступен: login.vk.ru"
                            } else {
                                lineTrim
                            }
                            updateProcessLog(identity, errorKey, errorMessage, 99, true)
                        }
                    }
                }
            } catch (e: Exception) {
                if (
                    isCurrent(identity) &&
                    targetProcess.isAlive &&
                    !e.readableMessage().contains("read interrupted by close", ignoreCase = true)
                ) {
                    lastDiagnostic = e.readableMessage().take(240)
                }
            } finally {
                runCatching { reader.close() }
                startStopMutex.withLock {
                    if (processIdentity === identity && process === targetProcess) {
                        val lifecycleWantsRestart = lifecycleState.processEnded(identity.ticket)
                        process = null
                        processIdentity = null
                        nativeProcessPid = null
                        activeWorkers.value = 0
                        clearTrafficSpeed()
                        resetWorkerRecoveryState()
                        if (readerJob === coroutineContext[Job]) readerJob = null
                        val initialExitCode = runCatching {
                            if (targetProcess.waitFor(250, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                                targetProcess.exitValue()
                            } else {
                                null
                            }
                        }.getOrNull()
                        if (initialExitCode == null) {
                            terminateExactProcess(targetProcess, identity.shutdownGraceMs)
                        }
                        val exitCode = initialExitCode
                            ?: runCatching { targetProcess.exitValue() }.getOrNull()
                        // a fatal cause (a dead VK account, a wrong password) ends the run, whichever of this exit and
                        // handleCriticalError's stop takes the lock first (M-47)
                        val fatalCause = identity.fatal.cause
                        val shouldRestart = shouldRestartProcess(lifecycleWantsRestart, identity.fatal)
                        if (fatalCause != null) lastDiagnostic = fatalCause
                        val uptimeMs = processStartedAtMs
                            .takeIf { it > 0 }
                            ?.let { SystemClock.elapsedRealtime() - it }
                            ?: Long.MAX_VALUE
                        val restartContext = activeContext()
                        val restartParams = if (restartContext == null) {
                            null
                        } else {
                            try {
                                currentParams?.withNewSession(restartContext)
                            } catch (_: kotlin.coroutines.cancellation.CancellationException) {
                                null
                            } catch (e: Exception) {
                                updateLog(
                                    "restart_epoch_reserve_error",
                                    "Ошибка резервирования новой эпохи: ${e.readableMessage()}. Перезапуск с последней подтверждённой эпохой.",
                                    99,
                                    true,
                                )
                                currentParams
                            }
                        }
                        // B5e4: a core that exits before it is up, start after start (VK refusing an auto-JS account's
                        // calls, for one), ends the run with its last line instead of restarting forever; a restart
                        // reuses the run's calls (withNewSession keeps the hashes), so only the run's end ends them
                        val offline = pausedForNoNetwork.isPaused()
                        val plan = exitPlan(
                            wantsRestart = lifecycleWantsRestart,
                            // the cause the branches below read, before the suspending withNewSession (verify)
                            fatal = fatalCause != null,
                            canRestart = restartParams != null && restartContext != null,
                            uptimeMs = uptimeMs,
                            streak = crashRestartStreak,
                            offline = offline,
                            stableMs = CRASH_RESTART_STABLE_MS,
                        )
                        val nextStreak = plan.nextStreak
                        val crashLoop = plan.crashLoop
                        if (plan.endCalls) VkAutoCallsManager.finishActiveCalls()
                        if (fatalCause != null && lifecycleWantsRestart) {
                            updateLog(
                                "process_exit",
                                "✗ Rust-клиент завершился (код ${exitCode ?: "?"}). Причина: $fatalCause",
                                99,
                                true,
                            )
                            stopLocked(identity)
                        } else if (crashLoop) {
                            crashRestartStreak = 0
                            running.value = false
                            starting.value = false
                            stopping.value = false
                            updateLog(
                                "process_exit",
                                "✗ Rust-клиент не запускается: $nextStreak перезапусков подряд, остановлено." +
                                    lastDiagnostic?.let { " Причина: $it" }.orEmpty(),
                                99,
                                true,
                            )
                            recordRunFailure(StopCause.crashLoop(lastDiagnostic, exitCode))
                        } else if (shouldRestart && restartParams != null && restartContext != null) {
                            currentParams = restartParams
                            val reason = lastDiagnostic?.let { " Причина: $it" }.orEmpty()
                            updateLog(
                                "process_restart",
                                "✗ Rust-клиент завершился (код ${exitCode ?: "?"}). Перезапуск...$reason",
                                50,
                                true,
                            )
                            crashRestartStreak = nextStreak
                            val backoffShift = crashRestartStreak.coerceIn(1, 6) - 1
                            // offline the exit does not count, and the restart waits the longest: no restart every
                            // few seconds through an outage (verify N2); back online, it comes within a minute
                            val restartDelayMs = if (offline) {
                                CRASH_RESTART_MAX_DELAY_MS
                            } else {
                                (2_000L shl backoffShift).coerceAtMost(CRASH_RESTART_MAX_DELAY_MS)
                            }
                            scheduleRestartLocked(restartContext, restartParams, identity.ticket.epoch, restartDelayMs)
                            offlineRestart = restartJob?.takeIf { offline }
                                ?.let { OfflineRestart(it, restartContext.applicationContext, restartParams, identity.ticket.epoch) }
                        } else if (!lifecycleState.isDesiredRunning()) {
                            running.value = false
                        } else {
                            running.value = false
                            starting.value = false
                            stopping.value = false
                            updateLog(
                                "process_exit",
                                "✗ Rust-клиент завершился без перезапуска (код ${exitCode ?: "?"})." +
                                    lastDiagnostic?.let { " Причина: $it" }.orEmpty(),
                                99,
                                true,
                            )
                            recordRunFailure(lastDiagnostic ?: "Rust-клиент завершился (код ${exitCode ?: "?"})")
                        }
                    }
                }
            }
        }
    }

    private fun scheduleRestartLocked(
        context: Context,
        params: TunnelParams,
        epoch: Long,
        delayMs: Long,
    ) {
        restartJob?.cancel()
        restartJob = activeScope.launch {
            delay(delayMs)
            startStopMutex.withLock {
                if (!lifecycleState.canStart(epoch) || process != null) return@withLock
                restartJob = null
                startProcessLocked(context, params, epoch)
            }
        }
    }

    private fun handleCriticalError(message: String, identity: ProcessIdentity) {
        // before anything is launched: the process may exit at once, and its reader must see the cause (M-47)
        if (identity.fatal.record(withoutLogStickers(message))) recordRunFailure(message)
        updateProcessLog(identity, "circuit_breaker", "[СТОП] $message", -1, true)
        activeScope.launch {
            startStopMutex.withLock {
                if (!isCurrent(identity)) return@withLock
                stopLocked(identity)
            }
        }
    }

    private suspend fun TunnelParams.withNewSession(context: Context): TunnelParams {
        val store = activeSettingsStore ?: SettingsStore(context, ProfileScope.Run).also {
            activeSettingsStore = it
        }
        val proposed = if (generationId == Long.MAX_VALUE) Long.MAX_VALUE else generationId + 1
        val nextGen = store.reserveConnectionGeneration(proposed = proposed)
        return copy(
            generationId = nextGen,
            sessionSalt = newTunnelSessionId(),
        )
    }

    private fun terminateProcessLocked(
        identity: ProcessIdentity?,
        finishVkCalls: Boolean = false,
    ) {
        val targetProcess = identity?.process ?: process
        if (targetProcess == null) {
            process = null
            processIdentity = null
            nativeProcessPid = null
            activeWorkers.value = 0
            return
        }
        val attached = (identity == null || processIdentity === identity) && process === targetProcess
        val logReader = if (attached || process === targetProcess) readerJob else null
        val pidToKill = nativeProcessPid ?: processIdOf(targetProcess)
        if (attached || process === targetProcess) {
            process = null
            processIdentity = null
            nativeProcessPid = null
            vpnReady.value = false
            stopUptimeTimer()
            resetWorkerRecoveryState()
        }
        identity?.let { lifecycleState.releaseReservation(it.ticket) }
        logReader?.cancel()
        if (readerJob === logReader) {
            readerJob = null
        }
        val graceMs = identity?.shutdownGraceMs ?: 3_500L
        clearTrafficSpeed()
        terminateExactProcess(targetProcess, graceMs, finishVkCalls)
        pidToKill?.let { pid ->
            if (pid > 0 && targetProcess.isAlive) {
                runCatching { android.os.Process.killProcess(pid) }
            }
        }
        activeWorkers.value = 0
    }

    private fun terminateExactProcess(
        targetProcess: Process,
        shutdownGraceMs: Long,
        finishVkCalls: Boolean = false,
    ) {
        val forceWindowMs = minOf(300L, shutdownGraceMs)
        val gracefulWindowMs = (shutdownGraceMs - forceWindowMs).coerceAtLeast(0L)
        try {
            val command = if (finishVkCalls) "FINISH_VK_CALLS\nSTOP\n" else "STOP\n"
            targetProcess.outputStream.write(command.toByteArray())
            targetProcess.outputStream.flush()
        } catch (_: Exception) {}
        try {
            targetProcess.waitFor(gracefulWindowMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Exception) {}
        if (targetProcess.isAlive) {
            try { targetProcess.destroy() } catch (_: Exception) {}
            try { targetProcess.waitFor(forceWindowMs, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
        }
        if (targetProcess.isAlive) {
            try { targetProcess.destroyForcibly() } catch (_: Exception) {}
            try { targetProcess.waitFor(200L, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
        }
        if (targetProcess.isAlive) {
            processIdOf(targetProcess)?.let { pid ->
                if (pid > 0) {
                    runCatching { android.os.Process.killProcess(pid) }
                }
            }
        }
    }

    fun stop(finishVkCalls: Boolean = false, notifyController: Boolean = true) {
        val command = lifecycleCommand.incrementAndGet()
        stopping.value = true
        scope.launch {
            startStopMutex.withLock {
                if (lifecycleCommand.get() != command) return@withLock
                stopLocked(processIdentity, finishVkCalls, notifyController = notifyController)
            }
        }
    }

    suspend fun pauseForWifi() {
        lifecycleCommand.incrementAndGet()
        startStopMutex.withLock {
            autoPausedForWifi.value = true
            // a pause that cancels a pending switch keeps the pick for the resume (verify N1)
            switchTarget?.let { target ->
                sourceProfile = target
                activeContext()?.let { ctx -> scope.launch { runCatching { SettingsStore(ctx).saveRunProfile(target) } } }
            }
            // B5f2 (B5e4's backlog B2): the pause ends the run's VK calls, the core's own («Авто ВК») and the app's
            // («Авто API»), as a stop does; the resume makes new ones. The D6 pool is never the run's set
            stopLocked(processIdentity, finishVkCalls = true, preserveWifiAutoPause = true)
            VkAutoCallsManager.finishActiveCalls()
            starting.value = false
            updateLog(
                "wifi_auto_pause",
                "Ожидание. Автопауза при Wi-Fi",
                2,
                false,
                LogLevel.NET,
            )
        }
    }

    fun leaveWifiAutoPause() {
        autoPausedForWifi.value = false
    }

    private fun stopLocked(
        identity: ProcessIdentity?,
        finishVkCalls: Boolean = false,
        preserveWifiAutoPause: Boolean = false,
        notifyController: Boolean = true,
    ) {
        stopping.value = true
        switchTarget = null
        lifecycleState.requestStop()
        if (!preserveWifiAutoPause) autoPausedForWifi.value = false
        pausedForNoNetwork.reset()
        syncNetworkPaused()
        vpnReady.value = false
        tunconfThisProcess.value = false
        everConnected.value = false
        serverSilent.value = false
        targetWorkers.value = 0
        vpnRebuildPending.set(false)
        serverRoutingActive.value = false
        activeServerRoutingRule.value = null
        serverAssignedWorkers.value = null
        restartJob?.cancel()
        restartJob = null
        panelRestartJob?.cancel()
        panelRestartJob = null
        panelRestartPending.set(false)
        transportRestartPending.set(false)
        activeContext()?.let { ctx ->
            dispatchTunnelStop(
                preserveWifiAutoPause = preserveWifiAutoPause,
                notifyController = notifyController,
                stopVpn = {
                    val stopIntent = Intent(ctx, TunVpnService::class.java).apply {
                        action = "STOP"
                        stampVpnIntent(this, identity?.generation ?: -1L)
                    }
                    try { ctx.startService(stopIntent) } catch (_: Exception) {}
                },
                stopController = {
                    val stopIntent = Intent(ctx, TunnelService::class.java).apply { action = "STOP" }
                    try { ctx.startService(stopIntent) } catch (_: Exception) {}
                },
            )
        }
        terminateProcessLocked(identity, finishVkCalls)
        // a stop in a crash restart's backoff has no process to terminate: the uptime stops here too (review F1)
        stopUptimeTimer()
        running.value = false
        stopping.value = false
        activeWorkers.value = 0
        nativeProcessPid = null
        currentParams = null
        activeSettingsStore = null
        lastAppliedDns = null
        ManlCaptchaWebViewManager.cancelCaptcha()
    }

    fun reloadVpn() {
        val configStr = config.value?.trim() ?: return
        if (running.value && configStr.startsWith("TUNCONF:")) {
            activeScope.launch(Dispatchers.Main) {
                val parts = configStr.removePrefix("TUNCONF:").split(":", limit = 3)
                val clientIp = parts.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() } ?: return@launch
                val dns = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return@launch
                val generation = processIdentity?.generation ?: run {
                    // no process (a crash restart's backoff): its TUNCONF start rebuilds the interface instead (review F2)
                    vpnRebuildPending.set(true)
                    return@launch
                }
                activeContext()?.let { ctx ->
                    val vpnIntent = Intent(ctx, TunVpnService::class.java).apply {
                        action = "RELOAD_ROUTING"
                        stampVpnIntent(this, generation)
                        putExtra("client_ip", clientIp)
                        putExtra("dns", dns)
                        putExtra("force_rebuild", true)
                        putExtra("is_routing_reload", true)
                        putExtra("reason", "reload_vpn")
                    }
                    try { startTunVpnService(ctx, vpnIntent) } catch (_: Exception) {}
                }
            }
        }
    }

    private suspend fun handleCaptchaSolve(
        requestMode: String,
        redirectUri: String,
        sessionToken: String,
        identity: ProcessIdentity,
    ) {
        if (!isCurrent(identity)) return
        val ctx = activeContext() ?: run {
            writeCaptchaResult("error:context is null", identity)
            return
        }
        val mode = requestMode.lowercase()

        try {
            val token = when (mode) {
                "auto" -> solveSingleAutoWebViewCaptcha(redirectUri, sessionToken, identity)
                "manual" -> {
                    updateProcessLog(identity, "captcha_wv_step_1", "[КАПЧА WBV] Создание ручного WebView...", 5, false)
                    ManlCaptchaWebViewManager.solveCaptchaAsync(ctx, redirectUri, sessionToken)
                }
                else -> {
                    if (currentCaptchaSolveMethod == "auto") {
                        solveAutoWebViewCaptcha(ctx, redirectUri, sessionToken, identity)
                    } else {
                        updateProcessLog(identity, "captcha_wv_step_1", "[КАПЧА WBV] Создание ручного WebView...", 5, false)
                        ManlCaptchaWebViewManager.solveCaptchaAsync(ctx, redirectUri, sessionToken)
                    }
                }
            }
            updateProcessLog(identity, "captcha_wv_step_4", "[КАПЧА WBV] Капча решена ✓", 5, false)
            writeCaptchaResult(token, identity)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            updateProcessLog(identity, "captcha_wv_err", "[КАПЧА WBV] Таймаут WebView", 5, true)
            writeCaptchaResult("error:timeout", identity)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            updateProcessLog(identity, "captcha_wv_err", "[КАПЧА WBV] Отменено", 5, true)
            writeCaptchaResult("error:cancelled", identity)
        } catch (e: IllegalStateException) {
            val errorMsg = e.message ?: "WV state error"
            updateProcessLog(identity, "captcha_wv_err", "[КАПЧА WBV] $errorMsg", 5, true)
            writeCaptchaResult("error:$errorMsg", identity)
        } catch (e: Exception) {
            val errorMsg = e.message ?: "${e::class.simpleName}"
            if (errorMsg != "tunnel stopped") {
                updateProcessLog(identity, "captcha_wv_err", "[КАПЧА WBV] Ошибка — $errorMsg", 5, true)
            }
            writeCaptchaResult("error:$errorMsg", identity)
        }

        updateProcessLog(identity, "captcha_wv_step_6", "[КАПЧА WBV] Запрос капчи завершён", 5, false)
    }

    private suspend fun solveSingleAutoWebViewCaptcha(
        redirectUri: String,
        sessionToken: String,
        identity: ProcessIdentity,
    ): String {
        updateProcessLog(identity, "captcha_wv_step_1", "[КАПЧА WBV] Авто WebView попытка 10с...", 5, false)
        return CaptchaWebViewManager.solveCaptchaAsync(redirectUri, sessionToken) { step ->
            updateProcessLog(identity, "captcha_wv_auto_step", "[КАПЧА WBV] $step", 5, false)
        }
    }

    private suspend fun solveAutoWebViewCaptcha(
        ctx: Context,
        redirectUri: String,
        sessionToken: String,
        identity: ProcessIdentity,
    ): String {
        for (attempt in 1..2) {
            updateProcessLog(identity, "captcha_wv_step_1", "[КАПЧА WBV] Авто WebView попытка $attempt/2...", 5, false)
            try {
                return CaptchaWebViewManager.solveCaptchaAsync(redirectUri, sessionToken) { step ->
                    updateProcessLog(identity, "captcha_wv_auto_step", "[КАПЧА WBV] $step", 5, false)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                updateProcessLog(identity, "captcha_wv_timeout_$attempt", "[КАПЧА WBV] Авто таймаут 10с ($attempt/2)", 5, attempt == 2)
                if (attempt == 2) {
                    updateProcessLog(identity, "captcha_wv_fallback", "[КАПЧА WBV] 2 таймаута авто, открыт ручной WebView", 5, false)
                    return ManlCaptchaWebViewManager.solveCaptchaAsync(ctx, redirectUri, sessionToken)
                }
            } catch (e: IllegalStateException) {
                if (e.message == CaptchaWebViewManager.ERROR_SLIDER_DETECTED) {
                    updateProcessLog(identity, "captcha_wv_fallback", "[КАПЧА WBV] Обнаружен слайдер, открыт ручной WebView", 5, false)
                    return ManlCaptchaWebViewManager.solveCaptchaAsync(ctx, redirectUri, sessionToken)
                }
                throw e
            }
        }
        return ManlCaptchaWebViewManager.solveCaptchaAsync(ctx, redirectUri, sessionToken)
    }

    /** D23: invalid entries are skipped and named in the log; null only for a rule broken as a whole (the tunnel stops). */
    fun parseServerRoutingRuleJson(routingJsonStr: String): ServerRoutingRule? {
        val parsed = parseServerRoutingRuleChecked(routingJsonStr) ?: return null
        if (parsed.skipped.isNotEmpty()) {
            updateLog(
                "server_rule_skipped",
                "[СЕРВЕР] В правиле маршрутизации пропущены некорректные записи: ${describeSkippedEntries(parsed.skipped)}",
                95,
                isError = true,
            )
        }
        return parsed.rule
    }

    /**
     * Setting the server rule with its push, and a local push's last check with its write, hold this lock: a local push
     * read before a server rule arrived can then never land after it (B5d1 verify; callers run on several threads).
     */
    private val routingPushLock = Any()

    fun applyServerRoutingRule(newServerRule: ServerRoutingRule?, clientIp: String, dns: String) {
        val previousRule: ServerRoutingRule?
        synchronized(routingPushLock) {
            previousRule = activeServerRoutingRule.value
            activeServerRoutingRule.value = newServerRule
            serverRoutingActive.value = (newServerRule != null)
            if (newServerRule != null) pushDomainRouting(newServerRule.toUpdate())
        }

        activeContext()?.let { ctx ->
            if (newServerRule != null) {
                logEffectiveServerRouting(newServerRule)
                // the VPN's excludeRoutes come from ips/ipIsWhite too: a change there alone (App routing from the panel)
                // left stale routes until a reconnect
                if (vpnReady.value && vpnInputsDiffer(previousRule, newServerRule)) {
                    requestRoutingReload(ctx, clientIp, dns, "routing_changed")
                }
            } else if (previousRule != null) {
                activeScope.launch {
                    val store = SettingsStore(ctx, ProfileScope.Run)
                    // a rule that arrived again meanwhile has pushed and rebuilt for itself
                    if (!pushLocalUnlessServerRule(store.routingSnapshot.first().toUpdate())) return@launch
                    logEffectiveLocalRouting(store)
                    if (vpnReady.value) requestRoutingReload(ctx, clientIp, dns, "routing_cleared")
                }
            }
        }
    }

    private fun requestRoutingReload(ctx: Context, clientIp: String, dns: String, reason: String) {
        val reloadIntent = Intent(ctx, TunVpnService::class.java).apply {
            action = "RELOAD_ROUTING"
            stampVpnIntent(this, processIdentity?.generation ?: -1L)
            putExtra("client_ip", clientIp)
            putExtra("dns", dns)
            putExtra("reason", reason)
        }
        startTunVpnService(ctx, reloadIntent)
    }

    /** The whole domain and IP config to the core: the core replaces its config with each line (M-57). */
    internal fun pushDomainRouting(update: DomainRoutingUpdate) {
        val identity = processIdentity ?: return
        writeProcessCommand(identity, update.toCommand(), "domain_routing_err")
    }

    /** The local rules, read at once, to the core (no-op while a server rule is in force). */
    fun pushLocalRouting(ctx: Context) {
        if (serverRoutingActive.value) return
        activeScope.launch {
            val store = SettingsStore(ctx, ProfileScope.Run)
            if (pushLocalUnlessServerRule(store.routingSnapshot.first().toUpdate())) logEffectiveLocalRouting(store)
        }
    }

    /** The local rules to the core unless a server rule is in force by now; true if they were sent. */
    private fun pushLocalUnlessServerRule(update: DomainRoutingUpdate): Boolean = synchronized(routingPushLock) {
        if (serverRoutingActive.value) return false
        pushDomainRouting(update)
        true
    }

    /**
     * M-63: a validated import, written at once from the process-wide scope (leaving the screen never cuts it short),
     * then one VPN reload if the apps changed; the routing observer pushes the domains and IPs.
     */
    internal fun importRoutingRules(ctx: Context, plan: RoutingImportPlan) {
        val appContext = ctx.applicationContext
        scope.launch {
            // the screen's import edits the profile open in it (run-profile review F1)
            SettingsStore(appContext).applyRoutingImport(plan)
            if (plan.apps != null) reloadVpn()
        }
    }

    /** M-55: one line naming the rule in force; the key keeps one entry, replaced on each change. */
    private fun logEffectiveServerRouting(rule: ServerRoutingRule) {
        updateLog(
            "routing_effective",
            describeEffectiveRouting(
                source = "сервер",
                appsWhite = rule.isWhite,
                apps = rule.packages.size,
                domainsWhite = rule.domainsIsWhite,
                domains = rule.domains.size,
                ruZones = false,
                template = rule.templateDomains,
                ipsWhite = rule.ipIsWhite,
                ips = rule.ips.size,
            ),
            26,
            level = LogLevel.NET,
        )
    }

    private suspend fun logEffectiveLocalRouting(store: SettingsStore) {
        val routing = store.routingSnapshot.first()
        // «Все с VPN» (M-59) reads as a blacklist with nothing in it: «приложения — все с VPN» (review F3)
        val allApps = store.appRoutingAll.first()
        val appsWhite = !allApps && store.isWhitelist.first()
        val apps = if (allApps) 0 else (if (appsWhite) store.whitelistApps.first() else store.excludedApps.first())
            .split(",").count { it.isNotBlank() && it !in VpnPlan.VK_PACKAGES }
        fun count(list: String) = list.split(",").count { it.isNotBlank() }
        updateLog(
            "routing_effective",
            describeEffectiveRouting(
                source = "устройство",
                appsWhite = appsWhite,
                apps = apps,
                domainsWhite = routing.domainIsWhitelist,
                domains = count(routing.activeDomains),
                ruZones = routing.useRuZones,
                template = routing.useTemplate,
                ipsWhite = routing.ipIsWhitelist,
                ips = count(routing.activeIps),
            ),
            26,
            level = LogLevel.NET,
        )
    }

    private fun writeCaptchaResult(result: String, identity: ProcessIdentity) {
        writeProcessCommand(identity, "CAPTCHA_RESULT|$result", "captcha_write_err")
    }

    private fun writeProcessCommand(
        identity: ProcessIdentity,
        command: String,
        errorKey: String,
    ): Boolean {
        if (!isCurrent(identity) || !identity.process.isAlive) return false
        return try {
            synchronized(identity.inputLock) {
                identity.process.outputStream.write("$command\n".toByteArray(Charsets.UTF_8))
                identity.process.outputStream.flush()
            }
            true
        } catch (e: Exception) {
            updateProcessLog(identity, errorKey, "Ошибка записи в Rust: ${e.message}", 200, true)
            false
        }
    }

    fun clearLogs() {
        synchronized(pendingLogLock) {
            pendingLogs.clear()
        }
        unreadErrorCount.value = 0
        scope.launch(Dispatchers.Main) {
            logs.clear()
        }
        if (!running.value) {
            activeWorkers.value = 0
        }
    }

    fun startCooldown(millis: Long) {
        cooldownJob?.cancel()
        cooldownActive.value = true
        cooldownJob = scope.launch(Dispatchers.Main) {
            delay(millis)
            cooldownActive.value = false
        }
    }

    private fun Throwable.readableMessage(): String {
        val text = message ?: localizedMessage
        return if (text.isNullOrBlank()) this::class.java.simpleName else "${this::class.java.simpleName}: $text"
    }
}

data class TunnelParams(
    val peer: String,
    val vkHashes: String,
    val secondaryVkHash: String = "",
    val workersPerHash: Int,
    val port: Int,
    val sni: String = "",
    val connectionPassword: String = "",
    val protocol: String = "udp",
    val vkAuthMode: String = "vkcalls",
    val captchaMode: String = "auto",
    val captchaSolveMethod: String = "auto",
    val fingerprint: String = "firefox",
    val clientIds: String = CsqttConstants.Tunnel.DEFAULT_CLIENT_IDS,
    val obfsMode: String = CsqttConstants.Tunnel.DEFAULT_OBFS_MODE,
    val turnTransport: String = CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT,
    val dpiSplit: DpiSplitMode = DpiSplitMode.OFF,
    val srtpMode: String = CsqttConstants.SrtpMode.DEFAULT,
    val generationId: Long = 0,
    val sessionSalt: String = "",
    val allowHashRedistribution: Boolean = false,
    val vkHashMode: String = CsqttConstants.VkAutoHash.MODE_MANUAL,
    val vkAccessToken: String = "",
    val customTurnIps: String = "",
    val domainRoutingEnabled: Boolean = false,
    val domainIsWhitelist: Boolean = false,
    val excludeRuZones: Boolean = false,
    val templateDomains: Boolean = false,
    val directDomains: String = "",
    val ipRoutingEnabled: Boolean = false,
    val ipIsWhitelist: Boolean = false,
    val directIps: String = "",
)
