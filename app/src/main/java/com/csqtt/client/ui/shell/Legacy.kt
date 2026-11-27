package com.csqtt.client.ui.shell

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import com.csqtt.client.HomeLayout
import com.csqtt.client.SettingsStore
import com.csqtt.client.TunnelAuthSnapshot
import com.csqtt.client.TunnelManager
import com.csqtt.client.pickProfile
import com.csqtt.client.refusalMarksStand
import com.csqtt.client.ui.design.Csq
import com.csqtt.client.ui.home.HomeTab
import com.csqtt.client.ui.legacy.DeployTab
import com.csqtt.client.ui.legacy.ExceptionsTab
import com.csqtt.client.ui.legacy.FloatingToolbar
import com.csqtt.client.ui.legacy.LogsTab
import com.csqtt.client.ui.legacy.design.CsqttDarkExtendedColors
import com.csqtt.client.ui.legacy.design.CsqttLightExtendedColors
import com.csqtt.client.ui.legacy.design.CsqttMaterialShapes
import com.csqtt.client.ui.legacy.design.LocalCsqttExtendedColors
import com.csqtt.client.ui.tunnel.TunnelTab
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

/** A tab's screen: the new design's where its card is done, else classic's in its frame. */
@Composable
internal fun LegacyTab(tab: Tab, homeLayout: HomeLayout) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val forms = rememberSaveableStateHolder()
    val auth by store.tunnelAuthSnapshot.collectAsStateWithLifecycle(initialValue = TunnelAuthSnapshot())
    // the epoch changes when a slot's profile is replaced (delete, add, restore): the old profile's edited form must not
    // come back, and be stored, into the new one
    val epoch by store.profileEpoch.collectAsStateWithLifecycle(initialValue = 0)
    // a start refused for a profile's settings opens «Туннель» on what that profile lacks: the count of them, and
    // whose the last one was — the slot, and the profile that held it then (when it was added, whether it held
    // anything). The marks are that profile's (refusalMarksStand): they stay whatever is added or deleted beside it,
    // another profile's form is not full of errors for it, and neither is the next profile to take the slot
    var tunnelValidationRequest by rememberSaveable { mutableIntStateOf(0) }
    var tunnelValidationProfile by rememberSaveable { mutableIntStateOf(-1) }
    var tunnelValidationAdded by rememberSaveable { mutableLongStateOf(0L) }
    var tunnelValidationInUse by rememberSaveable { mutableStateOf(false) }
    val marked = auth.isLoaded && tunnelValidationProfile == auth.profile
    val marksStand = marked && refusalMarksStand(tunnelValidationAdded, tunnelValidationInUse, auth.addedAt, auth.inUse)
    LaunchedEffect(marked, marksStand, auth.inUse) {
        when {
            !marked -> Unit
            // seen gone from its slot, the refused profile takes its marks with it for good: what is typed into the
            // emptied slot next is another profile
            !marksStand -> tunnelValidationProfile = -1
            // the empty form a start was refused for holds something now: from here on, the slot seen empty is the
            // profile deleted
            auth.inUse -> tunnelValidationInUse = true
        }
    }
    // «Дополнительно» of «Туннель» stays open from one profile to the next: it is how the screen is read, not a profile's
    var tunnelMoreOpen by rememberSaveable { mutableStateOf(false) }

    // «Главная» and «Туннель» are the new design's (B1, B3): outside classic's frame, with the system's font scale
    if (tab == Tab.Home || tab == Tab.Tunnel) {
        when {
            !auth.isLoaded -> Spacer(Modifier.fillMaxSize())
            // one Home for every profile: a pick in its list changes what it shows, it does not make the screen again
            tab == Tab.Home -> forms.SaveableStateProvider("home") {
                HomeTab(
                    auth = auth,
                    layout = homeLayout,
                    onInvalidConfiguration = {
                        nav.select(Tab.Tunnel)
                        tunnelValidationProfile = auth.profile
                        tunnelValidationAdded = auth.addedAt
                        tunnelValidationInUse = auth.inUse
                        tunnelValidationRequest += 1
                    },
                )
            }
            // a form's saved state is its profile's: one profile's draft never reaches another
            else -> forms.SaveableStateProvider("tunnel:${auth.profile}:$epoch") {
                TunnelTab(
                    auth, epoch,
                    validationRequest = if (marksStand) tunnelValidationRequest else 0,
                    moreOpen = tunnelMoreOpen, onMoreOpen = { tunnelMoreOpen = it },
                )
            }
        }
        return
    }
    LegacyFrame {
        when (tab) {
            Tab.Home, Tab.Tunnel -> Unit
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
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activeProfile by store.activeProfile.collectAsStateWithLifecycle(initialValue = 0)
    val autoPauseOnWifi by store.autoPauseOnWifi.collectAsStateWithLifecycle(initialValue = false)
    LegacyFrame {
        FloatingToolbar(
            settingsStore = store,
            activeProfile = activeProfile,
            onActiveProfileChange = { profile -> scope.launch { pickProfile(context, store, profile, activeProfile) } },
            currentTheme = themeMode,
            onThemeChange = { mode -> scope.launch { store.saveThemeMode(mode) } },
            autoPauseOnWifi = autoPauseOnWifi,
            onAutoPauseOnWifiChange = { enabled -> scope.launch { store.saveAutoPauseOnWifi(enabled) } },
            onOpenServer = onOpenServer,
            onAddProfile = { nav.open(Routes.addProfile()) },
        )
    }
}
