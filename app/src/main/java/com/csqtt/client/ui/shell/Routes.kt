package com.csqtt.client.ui.shell

import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.csqtt.client.AppOwners
import com.csqtt.client.SettingsStore

/** What the screens are given at the root, composed once: where the user is, the store, and what outlives the screens. */
internal val LocalNav = staticCompositionLocalOf<NavModel> { error("no NavModel") }
internal val LocalStore = staticCompositionLocalOf<SettingsStore> { error("no SettingsStore") }
internal val LocalOwners = staticCompositionLocalOf<AppOwners> { error("no AppOwners") }

/**
 * The overlays the shell can show, by route. The shell draws them at the root, outside the tabs (U6): an open sheet or
 * dialog is the same one on every tab and after a profile change. Each card adds its routes here.
 */
internal object Routes {
    const val LINK_IMPORT = "link-import"

    /** A csqtt:// link waiting for the user's answer (M-70, M-71). */
    fun linkImport(raw: String) = Dest(LINK_IMPORT, Layer.Dialog, raw)

    private val app: Map<String, @Composable (dest: Dest, close: () -> Unit) -> Unit> = mapOf(
        LINK_IMPORT to { dest, close -> LegacyLinkImport(dest.arg, close) },
    )

    /** Set by instrumented tests only: routes of their own beside the app's. */
    @Volatile
    @VisibleForTesting
    var test: Map<String, @Composable (dest: Dest, close: () -> Unit) -> Unit> = emptyMap()

    fun find(route: String): (@Composable (dest: Dest, close: () -> Unit) -> Unit)? = test[route] ?: app[route]
}
