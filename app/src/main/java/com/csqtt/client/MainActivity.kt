package com.csqtt.client

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.csqtt.client.ui.design.CsqTheme
import com.csqtt.client.ui.design.isDarkMode
import com.csqtt.client.ui.design.systemBars
import com.csqtt.client.ui.legacy.AppUpdateDialog
import com.csqtt.client.ui.legacy.dialogs.VkAuthDialog
import com.csqtt.client.ui.legacy.utils.parseCsqttLink
import com.csqtt.client.ui.home.HomeLayoutSwitch
import com.csqtt.client.ui.shell.FirstFrame
import com.csqtt.client.ui.shell.LegacyEffects
import com.csqtt.client.ui.shell.LegacyFrame
import com.csqtt.client.ui.shell.LegacyHeaderActions
import com.csqtt.client.ui.shell.LegacyTab
import com.csqtt.client.ui.shell.LocalNav
import com.csqtt.client.ui.shell.LocalOwners
import com.csqtt.client.ui.shell.LocalStore
import com.csqtt.client.ui.shell.NavModel
import com.csqtt.client.ui.shell.PendingLink
import com.csqtt.client.ui.shell.Routes
import com.csqtt.client.ui.shell.Shell
import com.csqtt.client.ui.shell.Tab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val appSettingsStore by lazy { SettingsStore(applicationContext) }
    private val owners get() = (application as CsqttApplication).owners

    /** Where the user is (§4.2 rule 2): it outlives a recreate, and a process death through its saved state. */
    internal lateinit var nav: NavModel
        private set
    private var showVkAuthDialog by mutableStateOf(false)

    companion object {
        @Volatile
        var activeActivities = 0
        val isForeground: Boolean
            get() = activeActivities > 0
        // B5f: the system-stop check runs once per process
        @Volatile
        private var systemStopChecked = false
        private const val SYSTEM_STOP_GRACE_MS = 5_000L
    }

    override fun onStart() {
        super.onStart()
        activeActivities++
        ManlCaptchaWebViewManager.checkAndShowPendingCaptcha(this)
        checkSystemStop()
    }

    /**
     * B5f (rule 10): a run Android ended and never restarted (a battery saver, a reboot) leaves its flag set. Once per
     * process, a few seconds after the screen starts — a sticky restart Android has pending comes up as a start by then —
     * a flag still set with no run says why the tunnel is off. The flag stays: a restart that still comes clears the cause.
     */
    private fun checkSystemStop() {
        if (systemStopChecked) return
        systemStopChecked = true
        val app = applicationContext
        val store = appSettingsStore
        // on the process's scope: a Back or a recreate within the grace must not lose the check (review F3)
        TunnelManager.scope.launch(Dispatchers.Main) {
            delay(SYSTEM_STOP_GRACE_MS)
            val was = runCatching { store.tunnelWasRunningState() }.getOrNull() ?: return@launch
            val runOn = TunnelManager.running.value || TunnelManager.starting.value || TunnelManager.stopping.value ||
                TunnelManager.autoPausedForWifi.value
            StopCause.systemStop(
                wasRunning = was.running,
                savedBoot = was.boot,
                bootNow = store.bootCount(),
                savedUpdate = was.update,
                updateNow = store.appUpdateTime(),
                userStopped = lastExitWasTheUsers(app),
                runOn = runOn,
                failure = TunnelManager.failure.value,
            )?.let { TunnelManager.recordRunFailure(it, app) }
        }
    }

    /** Android's own «Остановить» (API 30+ records why the last process ended; review F2). */
    private fun lastExitWasTheUsers(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return runCatching {
            val reason = context.getSystemService(android.app.ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()?.reason
            reason == android.app.ApplicationExitInfo.REASON_USER_REQUESTED ||
                reason == android.app.ApplicationExitInfo.REASON_USER_STOPPED
        }.getOrDefault(false)
    }

    // B5f2: back in front (a permission dialog and the location settings return here too) while a run uses a list rule,
    // the service re-reads the Wi-Fi's name; nothing happens for «В любой» or without a run
    override fun onResume() {
        super.onResume()
        TunnelService.onAppInFront(this)
    }

    // The composition is kept while the app is in the background (§4.2 rule 1): Compose pauses recomposition while the
    // activity is stopped, and every flow it shows is collected with the lifecycle, so dropping it saved nothing and
    // lost every open dialog and the flows' loaded values.
    override fun onStop() {
        activeActivities = (activeActivities - 1).coerceAtLeast(0)
        // B5g: the app leaves the screen: the log's tail is kept, if it changed since it was last kept
        LogTail.requestSave()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val discardStaleUiState = UpdateLaunchGuard.consumeStaleSavedState(this)
        super.onCreate(if (discardStaleUiState) null else savedInstanceState)
        nav = ViewModelProvider(this, viewModelFactory { initializer { NavModel(createSavedStateHandle()) } })[NavModel::class.java]

        // M-71: the launching intent's link is read once. A recreate (theme, locale, process death) finds the link still
        // waiting for its answer in the restored navigation instead of reading the intent again, which asked twice.
        if (savedInstanceState == null || discardStaleUiState) {
            // nothing is restored, so no question stands: a link kept for one that was never answered (the app swiped
            // away with the dialog open) is not left lying in storage
            PendingLink.drop(this)
            importCsqttLink(intent)
        }

        TunnelManager.initObservers(this)

        // M-94: the first frame is drawn in the theme last shown, its system bars with it; the store answers later
        val firstFrame = FirstFrame(this)
        val systemDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        systemBars(isDarkMode(firstFrame.themeMode, systemDark))

        // Subscriptions refresh while the app is open
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    SubscriptionSync.refreshDue(appSettingsStore)
                    delay(30_000L)
                }
            }
        }

        setContent {
            // null until the store has answered: the first frame's values stand in, and nothing is corrected from a
            // default (§4.2 rule 3)
            val storedTheme: String? by appSettingsStore.themeMode.collectAsStateWithLifecycle(initialValue = null)
            val storedHasServer: Boolean? by appSettingsStore.hasServer.collectAsStateWithLifecycle(
                initialValue = SettingsStore.cachedHasServer,
            )
            val storedLayout: HomeLayout? by appSettingsStore.homeLayout.collectAsStateWithLifecycle(
                initialValue = SettingsStore.cachedHomeLayout,
            )
            val themeMode = storedTheme ?: firstFrame.themeMode
            val hasServer = storedHasServer ?: firstFrame.hasServer
            // Home's layout is right in the first frame too (P3-PLAN §1.3a)
            val homeLayout = storedLayout ?: firstFrame.homeLayout
            LaunchedEffect(storedTheme, storedHasServer, storedLayout) {
                val theme = storedTheme ?: return@LaunchedEffect
                firstFrame.keep(theme, storedHasServer ?: return@LaunchedEffect, storedLayout ?: return@LaunchedEffect)
            }
            val dark = isDarkMode(themeMode, isSystemInDarkTheme())
            LaunchedEffect(dark) { systemBars(dark) }

            CsqTheme(themeMode) {
                CompositionLocalProvider(LocalNav provides nav, LocalStore provides appSettingsStore, LocalOwners provides owners) {
                    Shell(
                        hasServer = hasServer,
                        headerActions = {
                            if (nav.tab == Tab.Home) HomeLayoutSwitch(homeLayout)
                            LegacyHeaderActions(themeMode, onOpenServer = if (hasServer) null else ({ nav.select(Tab.Server) }))
                        },
                        tabContent = { tab -> LegacyTab(tab, homeLayout, onVkAuthRequested = ::requestVkAuth) },
                    )
                    LegacyFrame { LegacyDialogs() }
                    LegacyEffects()
                    UpdateCheckSchedule()
                }
            }
        }
    }

    /** M-79: no account change while the tunnel runs, starts or stops; the old session's VK cookies go first, so a sign-in can pick another account. */
    private fun requestVkAuth() {
        if (vkAccountChangeBlocked()) {
            showRaisedToast(VkTokenPolicy.CHANGE_WHILE_RUNNING, Toast.LENGTH_SHORT)
        } else {
            // the dialog opens once the old session's cookies are gone (review F1)
            VkAuthWebViewManager.clearVkSessionCookies { showVkAuthDialog = true }
        }
    }

    /** Classic's dialogs the activity itself asks with, until their cards replace them. */
    @Composable
    private fun LegacyDialogs() {
        if (showVkAuthDialog) {
            val onToken: (VkAuthPayload) -> Unit = { payload ->
                showVkAuthDialog = false
                lifecycleScope.launch {
                    // a tunnel started while the sign-in was open keeps its account (M-79)
                    if (vkAccountChangeBlocked()) {
                        showRaisedToast(VkTokenPolicy.CHANGE_WHILE_RUNNING, Toast.LENGTH_SHORT)
                        return@launch
                    }
                    // only a permanent token reaches here (M-82 / D30: an expiring one is refused at sign-in)
                    appSettingsStore.saveVkAccessToken(payload.token, payload.userId, payload.expiresIn)
                    showRaisedToast("Аккаунт VK подключён", Toast.LENGTH_SHORT)
                }
            }
            VkAuthDialog(onToken = onToken, onDismiss = { showVkAuthDialog = false })
        }
        // M-91: the offer lives in AppUpdates, so a recreate shows it again until it is answered (lost-prompt audit B6)
        val pendingRelease by AppUpdates.offer.collectAsStateWithLifecycle()
        pendingRelease?.let { release ->
            AppUpdateDialog(
                release = release,
                onPostpone = {
                    AppUpdates.postpone(appSettingsStore, release)
                    // a toast, over classic's «Настройки» when the check was started there; the shell's own message
                    // is drawn under a dialog (B6b moves the toasts, once «Настройки» is the shell's)
                    showRaisedToast(getString(R.string.update_postponed), Toast.LENGTH_SHORT)
                },
                onUpdate = {
                    AppUpdates.accept(appSettingsStore, release)
                    openReleaseUrl(release.releaseUrl)
                },
            )
        }
    }

    /** P1's hourly update check (M-91), only while the app is on screen (§4.2 rule 1). */
    @Composable
    private fun UpdateCheckSchedule() {
        val settingsStore = appSettingsStore
        val currentVersion = remember { "v${BuildConfig.VERSION_NAME.removePrefix("v")}" }
        val updateCheckIntervalHours by settingsStore.updateCheckIntervalHours.collectAsStateWithLifecycle(
            initialValue = DEFAULT_UPDATE_CHECK_INTERVAL_HOURS,
        )
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        LaunchedEffect(updateCheckIntervalHours, lifecycleOwner) {
            if (CsqttConstants.Update.CHECK_DISABLED || updateCheckIntervalHours == UPDATE_CHECK_NEVER) return@LaunchedEffect

            val intervalMillis = updateIntervalHoursToMillis(updateCheckIntervalHours)
                ?: updateIntervalHoursToMillis(DEFAULT_UPDATE_CHECK_INTERVAL_HOURS)
                ?: 60L * 60L * 1000L

            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    val waitMs = updateCheckDelayMs(System.currentTimeMillis(), settingsStore.updateLastCheckAt.first(), intervalMillis)
                    if (waitMs > 0) delay(waitMs)
                    if (isActive) AppUpdates.checkAutomatic(settingsStore, currentVersion, if (waitMs > 0) "periodic" else "startup")
                }
            }
        }
    }

    private fun openReleaseUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).apply { addCategory(Intent.CATEGORY_BROWSABLE) })
        } catch (_: Exception) {
            showRaisedToast(getString(R.string.link_not_opened), Toast.LENGTH_SHORT)
        }
    }

    private fun vkAccountChangeBlocked(): Boolean =
        TunnelManager.running.value || TunnelManager.starting.value || TunnelManager.stopping.value

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importCsqttLink(intent)
    }

    private fun importCsqttLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val raw = intent.dataString ?: return
        // the link is a question (M-70): it stays in the navigation until the user answers it, a newer link in its place
        if (parseCsqttLink(raw) == null) {
            showRaisedToast(getString(R.string.link_invalid), Toast.LENGTH_SHORT)
        } else {
            nav.open(Routes.linkImport(PendingLink.put(this, raw.trim())))
        }
    }
}
