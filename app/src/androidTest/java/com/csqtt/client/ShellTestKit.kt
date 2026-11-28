package com.csqtt.client

import android.os.Bundle
import android.os.Parcel
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.csqtt.client.ui.components.DialogFrame
import com.csqtt.client.ui.components.Field
import com.csqtt.client.ui.components.ScreenFrame
import com.csqtt.client.ui.components.SecretField
import com.csqtt.client.ui.components.Sheet
import com.csqtt.client.ui.components.TextBtn
import com.csqtt.client.ui.shell.Dest
import com.csqtt.client.ui.shell.Layer
import com.csqtt.client.ui.shell.Routes
import com.csqtt.client.ui.shell.SecretDrafts

/**
 * What the shell's lifecycle tests share. The shell has no screen, sheet or dialog of its own yet (each comes with its
 * card), so the tests give it one of each, built from the same frames the cards will use, through the real activity.
 */
internal object ShellTestKit {
    val screen = Dest("test-screen", Layer.Screen)
    val sheet = Dest("test-sheet", Layer.Sheet)
    val dialog = Dest("test-dialog", Layer.Dialog)

    const val SCREEN_TITLE = "Тестовый экран"
    const val SHEET_TITLE = "Тестовый лист"
    const val DIALOG_TITLE = "Тестовый вопрос"
    const val NAME_LABEL = "Название"
    const val SECRET_LABEL = "Пароль"
    const val SECRET_KEY = "test-sheet:password"

    fun installRoutes() {
        Routes.test = mapOf(
            screen.route to { _, close -> ScreenFrame(SCREEN_TITLE, onBack = close) { Text("Содержимое экрана") } },
            sheet.route to { _, close ->
                Sheet(onDismiss = close, title = SHEET_TITLE) {
                    var name by rememberSaveable { mutableStateOf("") }
                    Field(NAME_LABEL, name, { name = it })
                    SecretField(SECRET_LABEL, SECRET_KEY)
                }
            },
            dialog.route to { _, close ->
                DialogFrame(DIALOG_TITLE, onDismiss = close, actions = { TextBtn("Отмена", close) }) { Text("Текст вопроса") }
            },
        )
    }

    fun removeRoutes() {
        Routes.test = emptyMap()
        SecretDrafts.clear("test-sheet:")
    }

    /** Android's notifications prompt, asked at a connect (P3 §1.9), would stop the activity (and its UI) mid-test. */
    fun noSystemPromptsOverTheApp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pkg = instrumentation.targetContext.packageName
        instrumentation.uiAutomation.executeShellCommand("pm grant $pkg android.permission.POST_NOTIFICATIONS").close()
    }

    /** Runs [command] as the shell user and waits for it to end. */
    fun shell(command: String) {
        val out = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes() }
    }

    /**
     * The activity again from its saved state alone: the view models go before the recreate, so the new activity builds
     * its [com.csqtt.client.ui.shell.NavModel] from what was saved. It is as near to a process death as one process gets,
     * and no nearer: whatever only a new process changes (the hash an overlay's saved state is found by) is pinned in
     * NavModelTest instead.
     */
    fun ActivityScenario<MainActivity>.recreateFromSavedStateAlone() {
        onActivity { it.viewModelStore.clear() }
        recreate()
    }

    /** Everything the activity would save right now, as the bytes the system keeps. */
    fun ActivityScenario<MainActivity>.savedStateBytes(): ByteArray {
        var bytes = ByteArray(0)
        onActivity { activity ->
            val state = Bundle()
            InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(activity, state)
            val parcel = Parcel.obtain()
            try {
                parcel.writeBundle(state)
                bytes = parcel.marshall()
            } finally {
                parcel.recycle()
            }
        }
        return bytes
    }

    /** Whether [text] is anywhere in [bytes]: a Parcel writes its strings as UTF-16. */
    fun holds(bytes: ByteArray, text: String): Boolean {
        val needle = text.toByteArray(Charsets.UTF_16LE)
        return (0..bytes.size - needle.size).any { at -> needle.indices.all { bytes[at + it] == needle[it] } }
    }

    /**
     * The tunnel on for [profile] as the manager would say it, with no tunnel: a run that took the profile as it is
     * stored now, on the VK account [account]. The screens read nothing else of a run.
     */
    fun tunnelOn(profile: Int, account: String = "") {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val read = kotlinx.coroutines.runBlocking { SettingsStore(context, ProfileScope.Fixed(profile)).runRead() }
        val settings = read.settings
        TunnelManager.applyRunIdentity(TunnelManager.RunIdentity(profile, null, emptyList()))
        TunnelManager.fakeRun(
            read,
            TunnelParams(
                peer = "${read.source.address}:${read.source.port}", vkHashes = read.source.ownHashes.joinToString(","),
                workersPerHash = settings.workersPerHash, port = 0, connectionPassword = read.source.password,
                vkAuthMode = settings.vkAuthMode, captchaMode = settings.captchaMode, captchaSolveMethod = settings.captchaSolveMethod,
                fingerprint = settings.fingerprint, turnTransport = settings.turnTransport, dpiSplit = settings.dpiSplit,
                srtpMode = settings.srtpMode, vkHashMode = read.hashMode, vkAccessToken = account, customTurnIps = settings.customTurnIps,
            ),
        )
        TunnelManager.running.value = true
    }

    /** No tunnel again, whatever a test left of one. */
    fun tunnelOff() {
        TunnelManager.running.value = false
        TunnelManager.starting.value = false
        TunnelManager.stopping.value = false
        TunnelManager.reconnecting.value = false
        TunnelManager.switchTarget = null
        TunnelManager.fakeRun(null, null)
        TunnelManager.applyRunIdentity(TunnelManager.RunIdentity(-1, null, emptyList()))
    }

    const val BAR = "Изменения применятся после переподключения"
    const val RECONNECT = "Переподключить"
    const val RECONNECTING = "Переподключение…"

    /** A store with one link profile and no server, or with an own-server profile beside it. */
    fun backup(withServer: Boolean): String {
        val server = if (withServer) """, {"index": 4, "peer": "203.0.113.40", "csqtt_link_mode": false}""" else ""
        return """{"format": "${StoreBackup.FORMAT}", "version": ${StoreBackup.VERSION}, "vps": [],
            "profiles": [{"index": 0, "csqtt_link_mode": true}$server], "settings": {"active_profile": 0}}"""
    }
}
