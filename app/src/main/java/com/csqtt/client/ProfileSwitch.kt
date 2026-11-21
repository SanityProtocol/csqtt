package com.csqtt.client

/**
 * The operator (2026-11-21): a profile picked while the tunnel is on reconnects onto it, so the run's profile is always
 * the active one (B5h1's F2 / V1 class). A run paused on Wi-Fi resumes from the active profile anyway; a stopping one ends.
 */
internal object ProfileSwitch {
    fun reconnects(running: Boolean, starting: Boolean, stopping: Boolean, pausedOnWifi: Boolean): Boolean =
        (running || starting) && !stopping && !pausedOnWifi

    /** «Добавить профиль» makes an empty profile active: while the tunnel is on that could only stop the run. */
    fun addRefused(running: Boolean, starting: Boolean, stopping: Boolean, pausedOnWifi: Boolean): Boolean =
        running || starting || stopping || pausedOnWifi
}
