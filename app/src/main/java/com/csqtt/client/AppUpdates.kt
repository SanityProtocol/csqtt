package com.csqtt.client

import android.content.Context
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * M-91 (B5g): the update check's owner, above the screens (APP-PLAN §4.2 rule 8). The hourly check while the app is on
 * screen and «Проверить обновления» both run here, and an offer stays in [offer] until it is answered, so a recreate (a
 * theme or a locale change) or leaving «Инфо» loses neither the offer nor the button's check (the lost-prompt audit's
 * B6). The answers' store writes run in the app's scope too.
 */
internal object AppUpdates {
    private const val POSTPONE_MS = 24L * 60L * 60L * 1000L
    private const val CHECK_FAILED = "Не удалось проверить"

    private val offerState = MutableStateFlow<AppReleaseInfo?>(null)
    /** A newer release the user has not answered yet: the dialog. */
    val offer: StateFlow<AppReleaseInfo?> = offerState
    private val checkingState = MutableStateFlow(false)
    /** The button's check is running. */
    val checking: StateFlow<Boolean> = checkingState

    /** The hourly check: a newer release is offered unless the user postponed that version. */
    suspend fun checkAutomatic(store: SettingsStore, localVersion: String, reason: String) {
        val checkedAt = System.currentTimeMillis()
        val known = store.updateLatestVersion.first()
        val result = fetchLatestRelease(localVersion)
        val latest = result.getOrNull()
        store.saveUpdateState(checkedAt, latest?.versionTag ?: known.takeIf { result.isFailure }.orEmpty(),
            if (result.isFailure) CHECK_FAILED else "")
        if (latest == null || !isNewerVersion(localVersion, latest.versionTag)) return
        val postponed = store.updatePostponeVersion.first() == latest.versionTag &&
            checkedAt < store.updatePostponeUntil.first()
        Log.i("CSQTT", "Update check: local=$localVersion remote=${latest.versionTag} postponed=$postponed reason=$reason")
        if (!postponed) offer(store, latest, checkedAt)
    }

    /** «Проверить обновления»: in the app's scope, so leaving «Инфо» does not cancel it; a postponed version is offered. */
    fun checkNow(context: Context, store: SettingsStore, localVersion: String) {
        if (!checkingState.compareAndSet(expect = false, update = true)) return
        val app = context.applicationContext
        TunnelManager.scope.launch {
            try {
                val checkedAt = System.currentTimeMillis()
                val known = store.updateLatestVersion.first()
                val result = fetchLatestRelease(localVersion)
                val latest = result.getOrNull()
                store.saveUpdateState(checkedAt, latest?.versionTag ?: known.takeIf { result.isFailure }.orEmpty(),
                    if (result.isFailure) CHECK_FAILED else "")
                val message = when {
                    result.isFailure -> failureMessage(known)
                    latest == null -> "Опубликованных версий пока нет"
                    isNewerVersion(localVersion, latest.versionTag) -> null.also { offer(store, latest, checkedAt) }
                    else -> "У вас уже последняя версия: ${latest.versionTag}"
                }
                if (message != null) withContext(Dispatchers.Main) { app.showRaisedToast(message, Toast.LENGTH_SHORT) }
            } finally {
                checkingState.value = false
            }
        }
    }

    private fun failureMessage(known: String): String {
        val waitMs = updateCooldownRemainingMs()
        return when {
            waitMs > 0 -> "GitHub ограничил проверки: попробуйте через ${(waitMs + 59_999) / 60_000} мин"
            known.isNotBlank() -> "$CHECK_FAILED. Последняя известная версия: $known"
            else -> "$CHECK_FAILED обновления"
        }
    }

    private suspend fun offer(store: SettingsStore, release: AppReleaseInfo, at: Long) {
        store.saveUpdateDialogShown(release.versionTag, at)
        offerState.value = release
    }

    /** «Позже»: the dialog closes now; this version waits a day. */
    fun postpone(store: SettingsStore, release: AppReleaseInfo) {
        offerState.value = null
        TunnelManager.scope.launch {
            val now = System.currentTimeMillis()
            store.saveUpdatePostpone(version = release.versionTag, until = now + POSTPONE_MS)
            store.saveUpdateDialogAction(release.versionTag, UPDATE_DIALOG_ACTION_POSTPONED, now)
        }
    }

    /** «Обновить»: the dialog closes now; the caller opens the release page. */
    fun accept(store: SettingsStore, release: AppReleaseInfo) {
        offerState.value = null
        TunnelManager.scope.launch {
            store.saveUpdateDialogAction(release.versionTag, UPDATE_DIALOG_ACTION_UPDATE, System.currentTimeMillis())
        }
    }
}
