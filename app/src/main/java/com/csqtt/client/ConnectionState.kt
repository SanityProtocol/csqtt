package com.csqtt.client

/**
 * One connection state for the app, the notification, the QS tile and the widget (M-43, plan §4.3). It is derived from
 * what the tunnel reports, never stored: «Подключено» needs a TUNCONF from the running process, the VPN interface and
 * live workers, not a started process (amurcanov #45).
 */
sealed class ConnectionState {
    data object Idle : ConnectionState()
    data object Preparing : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val workers: Int) : ConnectionState()
    data object Reconnecting : ConnectionState()
    data object WaitingForNetwork : ConnectionState()
    data object PausedOnWifi : ConnectionState()
    data object Stopping : ConnectionState()
    data object CaptchaNeeded : ConnectionState()
    data object ServerNotResponding : ConnectionState()
    data class Failed(val reason: String) : ConnectionState()

    /** The power button, the tile and the widget are «on»: a tap stops. */
    val powerIsOn: Boolean
        get() = this !is Idle && this !is Failed && this !is Stopping

    /**
     * P3 §1.9: the app is at work for the tunnel, a start or a repair, which the phone's sleep must not cut short
     * (TunnelLockPolicy). Waiting for a network, for a captcha's answer or for a Wi-Fi pause to end is not work.
     */
    val isWork: Boolean
        get() = this is Preparing || this is Connecting || this is Reconnecting || this is ServerNotResponding ||
            this is Stopping

    /**
     * The state's word: the one place that names a state. Home takes it through [connectionVisuals]; the notification, the
     * tile and the widget read it here.
     */
    val label: String
        get() = when (this) {
            Idle -> "Отключено"
            Preparing, Connecting -> "Подключение…"
            is Connected -> "Подключено"
            Reconnecting -> "Переподключение…"
            WaitingForNetwork -> "Ожидание сети"
            PausedOnWifi -> "Пауза Wi-Fi"
            Stopping -> "Остановка…"
            CaptchaNeeded -> "Нужна капча"
            ServerNotResponding -> "Сервер не отвечает"
            // a run that failed is off: its cause is said beside the state, never in its place
            is Failed -> "Отключено"
        }
}

internal data class ConnectionInputs(
    /** A run is on: from the process start to the stop, through a crash restart's backoff (TunnelManager.running). */
    val running: Boolean = false,
    /** The service prepares a start (hashes, network) before the process exists. */
    val starting: Boolean = false,
    val stopping: Boolean = false,
    val pausedOnWifi: Boolean = false,
    val waitingForNetwork: Boolean = false,
    /**
     * A captcha episode is open (B5h2): from the core's ask until it is solved, the run connects or gets credentials, or
     * it stops. It outlives one ask, as the core asks again after its captcha lock.
     */
    val captchaNeeded: Boolean = false,
    /** The running process sent its TUNCONF (a crash restart's retained one does not count). */
    val tunconf: Boolean = false,
    val vpnReady: Boolean = false,
    val activeWorkers: Int = 0,
    /** This run was connected at least once. */
    val everConnected: Boolean = false,
    /** A stream is ready but the server never answered GETCONF (M-2). */
    val serverSilent: Boolean = false,
    /**
     * P3 §1.9: the core is at rest, and the tunnel had streams when it went to rest ([TunnelRest.connected]). The phone
     * slept and nothing has wanted the tunnel since, so streams the relay dropped meanwhile are made again only when
     * the phone sends a packet or the screen comes on.
     */
    val dormant: Boolean = false,
    /** The first fatal cause of the last run, until the next start. */
    val failure: String? = null,
)

internal fun deriveConnectionState(input: ConnectionInputs): ConnectionState = with(input) {
    when {
        stopping -> ConnectionState.Stopping
        pausedOnWifi -> ConnectionState.PausedOnWifi
        !running && !starting -> failure?.let { ConnectionState.Failed(it) } ?: ConnectionState.Idle
        waitingForNetwork -> ConnectionState.WaitingForNetwork
        running && tunconf && vpnReady && activeWorkers > 0 -> ConnectionState.Connected(activeWorkers)
        // a tunnel at rest repairs itself when it is needed: it is a working tunnel, whatever its stream count (§1.9)
        running && tunconf && vpnReady && dormant && everConnected -> ConnectionState.Connected(activeWorkers)
        // a silent server is not hidden by a captcha that cannot help it (review F4)
        serverSilent -> ConnectionState.ServerNotResponding
        // after «Подключено»: a captcha asked for more workers does not hide a working tunnel (B5h2)
        captchaNeeded -> ConnectionState.CaptchaNeeded
        running && everConnected -> ConnectionState.Reconnecting
        !running -> ConnectionState.Preparing
        else -> ConnectionState.Connecting
    }
}

/** The first fatal cause of a run (M-47): retry chatter or a second error never replaces it. */
internal class FirstFatal {
    @Volatile
    var cause: String? = null
        private set

    @Synchronized
    fun record(cause: String): Boolean {
        if (this.cause != null) return false
        this.cause = cause.trim().ifEmpty { "ошибка" }
        return true
    }
}

/** A process that ended with a fatal cause is never restarted, whichever of the stop and the exit runs first (M-47). */
internal fun shouldRestartProcess(lifecycleWantsRestart: Boolean, fatal: FirstFatal): Boolean =
    lifecycleWantsRestart && fatal.cause == null

/**
 * Intents to TunVpnService carry (process generation, sequence). One made for an older process, or overtaken by a newer
 * one, is dropped: a START delivered after its run stopped would bring the TUN back with no core behind it, and a STOP
 * delivered after the next run's START would tear the new one down.
 */
internal class VpnIntentGate {
    private var nextSequence = 0L
    private var lastStartApplied = 0L

    @Synchronized
    fun stamp(): Long = ++nextSequence

    /**
     * [currentGeneration] is the generation of the running process (null: none) and [runWanted] whether a run is on.
     * A START or RELOAD is applied only for the current process of a wanted run (two of one process may arrive in
     * either order: both are current); a STOP unless a START made after it was applied already.
     */
    @Synchronized
    fun accept(
        isStop: Boolean,
        generation: Long,
        sequence: Long,
        currentGeneration: Long?,
        runWanted: Boolean,
    ): Boolean {
        if (isStop) return sequence > lastStartApplied
        if (!runWanted || currentGeneration != generation) return false
        lastStartApplied = maxOf(lastStartApplied, sequence)
        return true
    }
}

/** The tunnel notification's title (M-89): the state, and the live stream count once connected (none at rest, §1.9). */
internal fun tunnelNotificationTitle(state: ConnectionState): String = when (state) {
    is ConnectionState.Connected -> if (state.workers > 0) "Подключено • Потоков: ${state.workers}" else state.label
    else -> state.label
}

/** The traffic line is shown only while it means something: connected, or reconnecting after a connection. */
internal fun showsTraffic(state: ConnectionState): Boolean =
    state is ConnectionState.Connected || state is ConnectionState.Reconnecting

/** The run's start ([startedAtMs], elapsedRealtime) while it runs; [frozenSeconds] after it stopped; neither before. */
data class UptimeMark(val startedAtMs: Long = 0L, val frozenSeconds: Long? = null)
