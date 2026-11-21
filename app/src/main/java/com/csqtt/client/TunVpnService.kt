package com.csqtt.client

import android.content.Intent
import android.app.NotificationManager
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TunVpnService : VpnService() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val vpnMutex = Mutex()
    private val interfaceLock = Any()
    private var vpnInterface: ParcelFileDescriptor? = null
    private var sendJob: Job? = null
    @Volatile
    private var activeClientIp: String? = null
    @Volatile
    private var activeDns: String? = null
    /** The server rule the current interface was built with (M-58): reported with vpnReady so a rule that moved on is seen. */
    @Volatile
    private var interfaceRule: ServerRoutingRule? = null
    /** The plan the current interface was built from: the same plan again reuses it. */
    @Volatile
    private var interfacePlan: VpnPlan? = null
    @Volatile
    private var recoveryAttempts = 0
    @Volatile
    private var stopRequested = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile
    private var serviceDestroyed = false

    companion object {
        private const val TAG = "TunVPN"

        @Volatile
        var instance: TunVpnService? = null
            private set

        @Volatile
        var tunFd: Int = -1
            private set
    }

    // LOW_LATENCY Wi-Fi lock is active only with the screen on, so the deprecated HIGH_PERF mode is kept for the background VPN.
    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        // «Экономия батареи» in force: no CPU wake lock (the Wi-Fi lock stays)
        if (!BatterySaver.active.value && wakeLock?.isHeld != true) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "csqtt:tun_vpn_service")?.apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L)
            }
        }
        if (wifiLock?.isHeld != true) {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "csqtt:tun_vpn_wifi")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    /** «Экономия батареи» came into force (let the CPU sleep) or ended (hold it again while the VPN runs). */
    fun onBatterySaverChanged(save: Boolean) {
        if (save) {
            runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
            wakeLock = null
        } else if (!stopRequested && !serviceDestroyed) {
            acquireLocks()
        }
    }

    private fun releaseLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
        wifiLock = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        acquireLocks()
        getSystemService(NotificationManager::class.java)
            .deleteNotificationChannel(CsqttConstants.Notifications.VPN_CHANNEL_ID)
        Log.d(TAG, "TunVpnService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acquireLocks()
        if (intent == null) {
            if (TunnelManager.running.value && !stopRequested) {
                Log.i(TAG, "TunVpnService restarted by Android with null intent, recovering active VPN")
                val ip = activeClientIp
                val dns = activeDns
                if (ip != null && dns != null) {
                    serviceScope.launch {
                        startVpn(ip, dns, forceRebuild = false, reason = "android_restart")
                    }
                } else {
                    TunnelManager.reloadVpn()
                }
                return START_STICKY
            }
            stopSelf()
            return START_NOT_STICKY
        }

        // an intent made for an older tunnel process, or overtaken by a newer one, is dropped (M-43)
        if (!TunnelManager.acceptsVpnIntent(intent)) {
            Log.i(TAG, "Dropped a stale ${intent.action} for generation ${intent.getLongExtra("vpn_generation", -1L)}")
            // no run is on and no interface is up: nothing else will use this service, so it lets its locks go
            val idle = synchronized(interfaceLock) { vpnInterface == null }
            if (idle && !TunnelManager.running.value && !stopRequested) {
                releaseLocks()
                stopSelf()
                return START_NOT_STICKY
            }
            return START_STICKY
        }

        when (intent.action) {
            "START" -> {
                stopRequested = false
                val clientIp = intent.getStringExtra("client_ip") ?: activeClientIp ?: return START_NOT_STICKY
                val dns = intent.getStringExtra("dns") ?: activeDns ?: return START_NOT_STICKY
                val forceRebuild = intent.getBooleanExtra("force_rebuild", false)
                val isRoutingReload = intent.getBooleanExtra("is_routing_reload", false)
                val reason = intent.getStringExtra("reason") ?: "start"
                val mtu = intent.getIntExtra("mtu", CsqttConstants.Vpn.DEFAULT_MTU)
                if (!forceRebuild) recoveryAttempts = 0
                serviceScope.launch {
                    startVpn(clientIp, dns, forceRebuild, isRoutingReload, reason, mtu)
                }
            }
            "RELOAD_ROUTING" -> {
                val clientIp = intent.getStringExtra("client_ip") ?: activeClientIp ?: return START_NOT_STICKY
                val dns = intent.getStringExtra("dns") ?: activeDns ?: return START_NOT_STICKY
                val reason = intent.getStringExtra("reason") ?: "routing_reload"
                val mtu = intent.getIntExtra("mtu", CsqttConstants.Vpn.DEFAULT_MTU)
                serviceScope.launch {
                    startVpn(clientIp, dns, forceRebuild = true, isRoutingReload = true, reason = reason, mtu = mtu)
                }
            }
            "STOP" -> {
                stopRequested = true
                serviceScope.launch {
                    vpnMutex.withLock { stopVpn() }
                    releaseLocks()
                    stopSelf()
                }
            }
        }

        return START_STICKY
    }

    private suspend fun startVpn(
        clientIp: String,
        dns: String,
        forceRebuild: Boolean,
        isRoutingReload: Boolean = false,
        reason: String = "unknown",
        mtu: Int = CsqttConstants.Vpn.DEFAULT_MTU,
    ) {
        vpnMutex.withLock {
        try {
            val serverRule = TunnelManager.activeServerRoutingRule.value
            // B5d2: every decision about the interface in one pure plan; an equal plan reuses the interface
            val plan = VpnPlan.of(
                sdk = Build.VERSION.SDK_INT,
                ownPackage = applicationContext.packageName,
                clientIp = clientIp,
                serverDns = dns,
                serverRule = serverRule,
                settings = SettingsStore(applicationContext).vpnSettings.first(),
                installed = ::isPackageInstalled,
            )

            val existing = synchronized(interfaceLock) {
                vpnInterface?.takeIf {
                    interfacePlan == plan &&
                        TunRecoveryPolicy.shouldReuse(
                            hasInterface = true,
                            descriptorValid = it.fileDescriptor.valid(),
                            serviceDestroyed = serviceDestroyed,
                            forceRebuild = forceRebuild,
                            activeClientIp = activeClientIp,
                            activeDns = activeDns,
                            requestedClientIp = clientIp,
                            requestedDns = dns,
                        )
                }
            }
            if (existing != null) {
                TunnelManager.updateLog(
                    "vpn_reuse",
                    "[TUN] VPN-интерфейс переиспользован без пересборки (причина вызова: $reason)",
                    24,
                )
                sendTunFd(pfd = existing, previousInterface = null, dns = plan.serverDnsForLog(dns), isRoutingReload = isRoutingReload, builtRule = interfaceRule)
                return
            }

            plan.failure?.let {
                failVpn(it)
                return
            }
            if (plan.dnsOverrideRefused) {
                TunnelManager.updateLog(
                    "vpn_dns_override_refused",
                    "[TUN] DNS устройства не принят (нужны 1–2 адреса IPv4; частный — только с обходом LAN): работает DNS сервера",
                    40,
                    isError = true,
                )
            }

            if (VpnService.prepare(this) != null) {
                failVpn("разрешение Android VPN не выдано")
                return
            }

            val builder = Builder()
                .setSession("CSQTT")
                .setMtu(mtu)
                .addAddress(clientIp, 32)
            // IPv4 only, never allowFamily(AF_INET6): Android blocks IPv6 for the tunnelled apps (M-66 / D25)
            plan.routes.forEach { builder.addRoute(it.address, it.length) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                plan.excludedRoutes.forEach { prefix ->
                    try {
                        builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName(prefix.address), prefix.length))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to exclude route $prefix", e)
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            var validDnsServers = 0
            plan.dnsServers.forEach { dnsServer ->
                try {
                    builder.addDnsServer(dnsServer)
                    if (dnsServer in plan.tunnelledDns) builder.addRoute(dnsServer, 32)
                    validDnsServers++
                } catch (e: Exception) {
                    Log.w(TAG, "Invalid DNS server: $dnsServer", e)
                }
            }
            if (validDnsServers == 0) {
                failVpn("сервер передал некорректный DNS: $dns")
                return
            }

            if (plan.allowedApps.isNotEmpty()) {
                var includedCount = 0
                plan.allowedApps.forEach { pkg ->
                    try {
                        builder.addAllowedApplication(pkg)
                        includedCount++
                    } catch (error: Exception) {
                        Log.w(TAG, "Unable to include $pkg in VPN", error)
                    }
                }
                if (includedCount == 0) {
                    failVpn("Android не разрешил добавить выбранные приложения в whitelist")
                    return
                }
            } else {
                // CSQTT itself comes first: its own traffic carries the tunnel, so failing to exclude it fails the VPN
                try {
                    builder.addDisallowedApplication(plan.disallowedApps.first())
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to exclude CSQTT UID from its VPN", e)
                    failVpn("Android не разрешил исключить CSQTT из собственного VPN")
                    return
                }
                plan.disallowedApps.drop(1).forEach { pkg ->
                    try { builder.addDisallowedApplication(pkg) } catch (_: Exception) {}
                }
            }

            val pfd = builder.establish()
            if (pfd == null) {
                Log.e(TAG, "VPN permission not granted")
                failVpn("Android не создал VPN-интерфейс; проверьте разрешение VPN и Always-on VPN")
                return
            }

            var previousInterface: ParcelFileDescriptor? = null
            val accepted = synchronized(interfaceLock) {
                if (serviceDestroyed) {
                    false
                } else {
                    previousInterface = vpnInterface
                    vpnInterface = pfd
                    tunFd = pfd.fd
                    activeClientIp = clientIp
                    activeDns = dns
                    interfaceRule = serverRule
                    interfacePlan = plan
                    true
                }
            }
            if (!accepted) {
                pfd.close()
                return
            }
            sendJob?.cancel()
            if (!isRoutingReload && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                runCatching { setUnderlyingNetworks(null) }
            }
            Log.d(TAG, "VPN interface established: IP=$clientIp DNS=$dns fd=$tunFd (reason: $reason)")
            if (plan.dnsOverridden) {
                TunnelManager.updateLog("vpn_dns_override", "[TUN] DNS устройства вместо DNS сервера: ${plan.dnsServers.joinToString()}", 32, level = LogLevel.OK)
            }
            if (plan.lanBypass) {
                TunnelManager.updateLog("vpn_lan_bypass", "[TUN] Локальная сеть — напрямую, мимо туннеля (обход LAN)", 32, level = LogLevel.NET)
            }

            sendTunFd(pfd, previousInterface, plan.serverDnsForLog(dns), isRoutingReload, builtRule = serverRule)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN: ${e.message}", e)
            failVpn(e.message ?: e.javaClass.simpleName)
        }
        }
    }

    private fun sendTunFd(
        pfd: ParcelFileDescriptor,
        previousInterface: ParcelFileDescriptor? = null,
        dns: String? = null,
        isRoutingReload: Boolean = false,
        builtRule: ServerRoutingRule? = null,
    ) {
        sendJob?.cancel()
        sendJob = serviceScope.launch {
            var success = false
            for (i in 1..60) {
                val socket = android.net.LocalSocket()
                try {
                    socket.connect(android.net.LocalSocketAddress("csqtt_tun_uds", android.net.LocalSocketAddress.Namespace.ABSTRACT))
                    socket.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
                    socket.outputStream.write(1)
                    socket.outputStream.flush()
                    socket.soTimeout = 3_000
                    if (socket.inputStream.read() != 1) {
                        throw IllegalStateException("Rust-клиент не подтвердил TUN-интерфейс")
                    }
                    Log.d(TAG, "Sent TUN fd to Rust client successfully")
                    success = true
                    recoveryAttempts = 0
                    if (previousInterface !== pfd) {
                        runCatching { previousInterface?.close() }
                    }
                    if (isRoutingReload) {
                        TunnelManager.onRoutingUpdated(builtRule)
                    } else {
                        TunnelManager.onVpnInterfaceReady(dns, builtRule)
                    }
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.d(TAG, "Failed to connect to Rust client UDS, retrying: ${e.message}")
                    delay(500)
                } finally {
                    runCatching { socket.close() }
                }
            }
            if (!success) {
                Log.e(TAG, "Could not send TUN fd to Rust client after 60 retries")
                if (!dns.isNullOrBlank()) {
                    TunnelManager.updateLog(
                        "vpn_dns_delivery_error_$dns",
                        "[SERVER] DNS не применён: ${dnsProfileName(dns)} — Rust-клиент не принял TUN-интерфейс",
                        99,
                        true,
                    )
                }
                recoverAfterFdFailure(pfd)
            }
        }
    }

    private suspend fun recoverAfterFdFailure(failedInterface: ParcelFileDescriptor) {
        val recoveryConfig = synchronized(interfaceLock) {
            if (vpnInterface !== failedInterface || serviceDestroyed) {
                null
            } else {
                runCatching { vpnInterface?.close() }
                vpnInterface = null
                tunFd = -1
                val config = activeClientIp?.let { ip -> activeDns?.let { dns -> ip to dns } }
                activeClientIp = null
                activeDns = null
                config
            }
        } ?: return
        TunnelManager.onVpnInterfaceStopped()
        if (recoveryAttempts >= 3) {
            TunnelManager.onVpnRecoverableFailure(
                "не удалось передать TUN fd Rust-клиенту после повторных попыток",
                VpnFailureKind.TunFdDeliveryFailed,
            )
            delay(2000L)
            recoveryAttempts = 0
        } else {
            recoveryAttempts++
            delay(500L * recoveryAttempts)
        }
        if (!stopRequested && !serviceDestroyed) {
            startVpn(recoveryConfig.first, recoveryConfig.second, forceRebuild = true, reason = "fd_delivery_failed")
        }
    }

    private fun failVpn(message: String) {
        stopRequested = true
        stopVpn()
        releaseLocks()
        TunnelManager.onVpnTerminalFailure(message, VpnFailureKind.TunEstablishFailed)
    }

    private fun stopVpn() {
        sendJob?.cancel()
        sendJob = null
        synchronized(interfaceLock) {
            try {
                vpnInterface?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing VPN interface: ${e.message}")
            }
            vpnInterface = null
            tunFd = -1
            activeClientIp = null
            activeDns = null
        }
        TunnelManager.onVpnInterfaceStopped()
        TunnelManager.updateLog("vpn_stop", "[TUN] VPN-интерфейс закрыт вызовом stopVpn()", 22)
        Log.d(TAG, "VPN interface closed")
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun refreshUnderlyingNetworks() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            runCatching { setUnderlyingNetworks(null) }
        }
    }

    override fun onDestroy() {
        val unexpected = !stopRequested && TunnelManager.running.value
        serviceDestroyed = true
        serviceJob.cancel()
        stopVpn()
        releaseLocks()
        instance = null
        if (unexpected) {
            TunnelManager.onVpnRecoverableFailure(
                "VPN-служба была остановлена Android",
                VpnFailureKind.ServiceDestroyed,
            )
        }
        super.onDestroy()
    }

    override fun onRevoke() {
        if (serviceDestroyed) return
        stopRequested = true
        serviceDestroyed = true
        serviceJob.cancel()
        stopVpn()
        releaseLocks()
        instance = null
        TunnelManager.onVpnTerminalFailure(
            "разрешение Android VPN было отозвано",
            VpnFailureKind.PermissionRevoked,
        )
        stopSelf()
    }
}
