package com.csqtt.client

import android.content.Context
import android.content.Intent

/**
 * The operator (2026-11-21): a run stays on its profile until the user picks another one. A pick of another profile
 * while the tunnel is on reconnects onto it; «Добавить профиль» opens the new profile for editing and leaves the run where
 * it is; a run paused on Wi-Fi resumes on its own profile. The run's profile is the persisted run_profile (a Run-scoped
 * store reads it); TunnelManager.sourceProfile is its in-memory copy, with switchTarget a pick still under way.
 */
internal object ProfileSwitch {
    /** Running or starting: a pick reconnects it. A Wi-Fi-paused run takes the pick at its resume; a stopping one ends. */
    fun runIsOn(running: Boolean, starting: Boolean, stopping: Boolean, pausedOnWifi: Boolean): Boolean =
        (running || starting) && !stopping && !pausedOnWifi

    /** A pick reconnects only onto a profile the run is not on: picking the run's own profile back just selects it. */
    fun pickReconnects(runOn: Boolean, picked: Int, runProfile: Int): Boolean = runOn && picked != runProfile
}

/**
 * A pick of [profile] in a list of profiles, [active] being the one open now: it becomes the open one; a run on another
 * profile reconnects onto it, and a run paused on Wi-Fi takes it at its resume.
 */
internal suspend fun pickProfile(context: Context, store: SettingsStore, profile: Int, active: Int) {
    if (profile != active) store.saveActiveProfile(profile)
    val runOn = ProfileSwitch.runIsOn(
        TunnelManager.running.value, TunnelManager.starting.value,
        TunnelManager.stopping.value, TunnelManager.autoPausedForWifi.value,
    )
    // against where the run is going: a switch still pending counts as done (verify N1)
    if (ProfileSwitch.pickReconnects(runOn, profile, TunnelManager.runTargetProfile())) {
        TunnelManager.switchTarget = profile
        runCatching {
            context.startService(
                Intent(context, TunnelService::class.java).setAction(CsqttConstants.General.ACTION_SWITCH_PROFILE),
            )
        }
    } else if (TunnelManager.autoPausedForWifi.value) {
        // a run paused on Wi-Fi resumes on the pick (its run_profile)
        TunnelManager.sourceProfile = profile
        store.saveRunProfile(profile)
    }
}
