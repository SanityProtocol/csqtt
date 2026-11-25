package com.csqtt.client.ui.shell

import android.content.Intent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.CSQTTTypography
import com.csqtt.client.CsqttConstants
import com.csqtt.client.DeploySettingsSnapshot
import com.csqtt.client.ProfileSwitch
import com.csqtt.client.SettingsStore
import com.csqtt.client.TunnelAuthSnapshot
import com.csqtt.client.TunnelManager
import com.csqtt.client.TunnelService
import com.csqtt.client.ui.design.Csq
import com.csqtt.client.ui.legacy.ConnectionTab
import com.csqtt.client.ui.legacy.DeployTab
import com.csqtt.client.ui.legacy.ExceptionsTab
import com.csqtt.client.ui.legacy.FloatingToolbar
import com.csqtt.client.ui.legacy.LogsTab
import com.csqtt.client.ui.legacy.SettingsTab
import com.csqtt.client.ui.legacy.design.CsqttDarkExtendedColors
import com.csqtt.client.ui.legacy.design.CsqttLightExtendedColors
import com.csqtt.client.ui.legacy.design.CsqttMaterialShapes
import com.csqtt.client.ui.legacy.design.LocalCsqttExtendedColors
import com.csqtt.client.ui.legacy.dialogs.LinkImportDialog
import kotlinx.coroutines.launch

/*
 * Classic's screens inside the new shell (P3-PLAN §1.1). Each tab's card replaces its screen here and deletes it; B7
 * deletes this file with what is left.
 */

/** Classic's screens keep what they were drawn for until they go: a pinned font scale, their Material shapes and type. */
@Composable
internal fun LegacyFrame(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val pinned = remember(density.density) { Density(density.density, 1f) }
    CompositionLocalProvider(
        LocalCsqttExtendedColors provides if (Csq.colors.isDark) CsqttDarkExtendedColors else CsqttLightExtendedColors,
        LocalDensity provides pinned,
    ) {
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme, typography = CSQTTTypography, shapes = CsqttMaterialShapes,
            content = content,
        )
    }
}

/** A tab's classic screen. A form's saved state is keyed on its profile, so one profile's draft never reaches another. */
@Composable
internal fun LegacyTab(tab: Tab, onVkAuthRequested: () -> Unit) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val forms = rememberSaveableStateHolder()
    val auth by store.tunnelAuthSnapshot.collectAsStateWithLifecycle(initialValue = TunnelAuthSnapshot())
    // the epoch changes when a slot's profile is replaced (delete, add, restore): the old profile's edited form must not
    // come back, and autosave, into the new one
    val epoch by store.profileEpoch.collectAsStateWithLifecycle(initialValue = 0)
    var tunnelValidationRequest by rememberSaveable { mutableIntStateOf(0) }

    LegacyFrame {
        when (tab) {
            Tab.Home -> if (auth.isLoaded) {
                forms.SaveableStateProvider("connection:${auth.profile}:$epoch") {
                    ConnectionTab(
                        settingsStore = store,
                        tunnelAuthSettings = auth,
                        onInvalidConfiguration = {
                            nav.select(Tab.Tunnel)
                            tunnelValidationRequest += 1
                        },
                    )
                }
            } else {
                Spacer(Modifier.fillMaxSize())
            }
            Tab.Tunnel -> if (auth.isLoaded) {
                forms.SaveableStateProvider("tunnel:${auth.profile}:$epoch") {
                    SettingsTab(
                        settingsStore = store,
                        tunnelAuthSettings = auth,
                        validationRequest = tunnelValidationRequest,
                        onVkAuthRequested = onVkAuthRequested,
                    )
                }
            } else {
                Spacer(Modifier.fillMaxSize())
            }
            Tab.Server -> {
                val deploy by store.deploySettingsSnapshot.collectAsStateWithLifecycle(initialValue = DeploySettingsSnapshot())
                forms.SaveableStateProvider("deploy:${deploy.profile}:${deploy.epoch}") { DeployTab(store, deploy) }
            }
            Tab.Logs -> forms.SaveableStateProvider("logs") { LogsTab() }
            Tab.Routes -> forms.SaveableStateProvider("routes") { LegacyRoutes(store) }
        }
    }
}

@Composable
private fun LegacyRoutes(store: SettingsStore) {
    val excluded by store.excludedApps.collectAsStateWithLifecycle(initialValue = SettingsStore.cachedExcludedApps ?: "")
    val whitelist by store.whitelistApps.collectAsStateWithLifecycle(initialValue = SettingsStore.cachedWhitelistApps ?: "")
    val showSystemApps by store.showSystemApps.collectAsStateWithLifecycle(initialValue = false)
    val isWhitelist by store.isWhitelist.collectAsStateWithLifecycle(initialValue = SettingsStore.cachedIsWhitelist ?: false)
    val serverRoutingActive by TunnelManager.serverRoutingActive.collectAsStateWithLifecycle()
    ExceptionsTab(
        settingsStore = store,
        savedExcluded = excluded,
        savedWhitelist = whitelist,
        showSystemApps = showSystemApps,
        isWhitelist = isWhitelist,
        serverRoutingActive = serverRoutingActive,
    )
}

/**
 * What classic's main screen did beside drawing: the pause after a stop before the next start, and «Логи» read. At the
 * root, beside the shell, so it runs whatever is on screen.
 */
@Composable
internal fun LegacyEffects() {
    val tab = LocalNav.current.tab
    val running by TunnelManager.running.collectAsStateWithLifecycle()
    val pausedOnWifi by TunnelManager.autoPausedForWifi.collectAsStateWithLifecycle()
    var wasRunning by remember { mutableStateOf(running) }
    LaunchedEffect(running, pausedOnWifi) {
        if (wasRunning && !running && !pausedOnWifi) {
            TunnelManager.startCooldown(CsqttConstants.Timeouts.VPN_PERMISSION_COOLDOWN_MS)
        }
        wasRunning = running
    }
    LaunchedEffect(tab) {
        if (tab == Tab.Logs) TunnelManager.clearUnreadErrors()
    }
}

/**
 * Classic's header buttons and the «Настройки» dialog they open, in the shell's header: composed once, so the dialog no
 * longer closes with the tab it was opened on (U6). [onOpenServer] is the way to «Сервер» for a user without one.
 */
@Composable
internal fun LegacyHeaderActions(themeMode: String, onOpenServer: (() -> Unit)?) {
    val store = LocalStore.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth by store.tunnelAuthSnapshot.collectAsStateWithLifecycle(initialValue = TunnelAuthSnapshot())
    val activeProfile by store.activeProfile.collectAsStateWithLifecycle(initialValue = 0)
    val fingerprint by store.selectedFingerprint.collectAsStateWithLifecycle(initialValue = CsqttConstants.Tunnel.DEFAULT_FINGERPRINT)
    val autoPauseOnWifi by store.autoPauseOnWifi.collectAsStateWithLifecycle(initialValue = false)
    LegacyFrame {
        FloatingToolbar(
            settingsStore = store,
            csqttLinkMode = auth.csqttLinkMode,
            activeProfile = activeProfile,
            onActiveProfileChange = { profile ->
                scope.launch {
                    if (profile != activeProfile) store.saveActiveProfile(profile)
                    // the operator (2026-11-21): a pick of a profile the run is not on reconnects onto it; a run
                    // paused on Wi-Fi takes it at its resume
                    val runOn = ProfileSwitch.runIsOn(
                        TunnelManager.running.value, TunnelManager.starting.value,
                        TunnelManager.stopping.value, TunnelManager.autoPausedForWifi.value,
                    )
                    // against where the run is going: a switch still pending counts as done (verify N1)
                    if (ProfileSwitch.pickReconnects(runOn, profile, TunnelManager.runTargetProfile())) {
                        TunnelManager.switchTarget = profile
                        runCatching {
                            context.startService(
                                Intent(context, TunnelService::class.java)
                                    .setAction(CsqttConstants.General.ACTION_SWITCH_PROFILE),
                            )
                        }
                    } else if (TunnelManager.autoPausedForWifi.value) {
                        // a run paused on Wi-Fi resumes on the pick (its run_profile)
                        TunnelManager.sourceProfile = profile
                        store.saveRunProfile(profile)
                    }
                }
            },
            currentTheme = themeMode,
            onThemeChange = { mode -> scope.launch { store.saveThemeMode(mode) } },
            activeFingerprint = fingerprint,
            onFingerprintChange = { value -> scope.launch { store.saveFingerprint(value) } },
            autoPauseOnWifi = autoPauseOnWifi,
            onAutoPauseOnWifiChange = { enabled -> scope.launch { store.saveAutoPauseOnWifi(enabled) } },
            onOpenServer = onOpenServer,
        )
    }
}

/** The csqtt:// link question (M-70): classic's dialog on the shell's route until B2 draws the new one. */
@Composable
internal fun LegacyLinkImport(raw: String, close: () -> Unit) {
    val store = LocalStore.current
    LegacyFrame { LinkImportDialog(settingsStore = store, raw = raw, onDone = close) }
}
