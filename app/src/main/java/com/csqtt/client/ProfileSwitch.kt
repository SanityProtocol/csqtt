package com.csqtt.client

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
