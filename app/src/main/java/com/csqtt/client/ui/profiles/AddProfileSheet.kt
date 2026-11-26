package com.csqtt.client.ui.profiles

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.csqtt.client.R
import com.csqtt.client.TunnelManager
import com.csqtt.client.ui.components.Card
import com.csqtt.client.ui.components.Cell
import com.csqtt.client.ui.components.Sheet
import com.csqtt.client.ui.design.Csq
import com.csqtt.client.ui.design.Icons
import com.csqtt.client.ui.legacy.utils.parseCsqttLink
import com.csqtt.client.ui.shell.LocalNav
import com.csqtt.client.ui.shell.LocalOwners
import com.csqtt.client.ui.shell.LocalStore
import com.csqtt.client.ui.shell.PendingLink
import com.csqtt.client.ui.shell.Routes
import com.csqtt.client.ui.shell.Tab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * «Добавить профиль» (the canvas, F-AddProfile): a QR code, a copied link, the user's own server by hand, a
 * subscription, and the way to a first server of one's own. Every one of them works with the tunnel on (I15).
 */
@Composable
internal fun AddProfileSheet(close: () -> Unit) {
    val nav = LocalNav.current
    val store = LocalStore.current
    val messages = LocalOwners.current.messages
    val context = LocalContext.current.applicationContext
    Sheet(onDismiss = close, title = stringResource(R.string.profiles_add), messages = messages) {
        Card(flush = true) {
            Way(Icons.qr, R.string.add_qr, R.string.add_qr_sub, divided = false) { nav.open(Routes.qrScanner()) }
            Way(Icons.clipboard, R.string.add_clipboard, R.string.add_clipboard_sub) {
                val copied = copiedText(context)
                if (parseCsqttLink(copied) == null) {
                    messages.show(context.getString(R.string.add_clipboard_empty))
                } else {
                    nav.closeRoutes(Routes.ADD_PROFILE)
                    nav.open(Routes.linkImport(PendingLink.put(context, copied)))
                }
            }
            Way(Icons.pencil, R.string.add_manual, R.string.add_manual_sub) {
                // «Вручную»: an empty profile that waits for the user's own server, opened in «Туннель». On the app's
                // scope: the profile is made, and the tab opened on it, whatever becomes of the sheet
                TunnelManager.scope.launch(Dispatchers.Main) {
                    val running = TunnelManager.runTargetProfile().takeIf { TunnelManager.connectionState.value.powerIsOn }
                    val added = store.addManualProfile()
                    if (added == null) {
                        messages.show(context.getString(R.string.profiles_none_free))
                        return@launch
                    }
                    // the tunnel stays on the profile it runs on, and says so: adding is not a pick
                    if (running != null && running != added) {
                        store.profileEntries.first().firstOrNull { it.index == running }
                            ?.let { messages.show(context.getString(R.string.home_run_stays, it.name)) }
                    }
                    nav.closeRoutes(Routes.ADD_PROFILE, Routes.PROFILES)
                    nav.select(Tab.Tunnel)
                }
            }
            Way(Icons.cloud, R.string.add_subscription, R.string.add_subscription_sub) { nav.open(Routes.subImport()) }
            // the way in for a first server: «Сервер» is in the bar only for a user who has one
            Way(Icons.server, R.string.add_server, R.string.add_server_sub) {
                nav.closeRoutes(Routes.ADD_PROFILE, Routes.PROFILES)
                nav.select(Tab.Server)
            }
        }
    }

}

/** The text that was copied last, trimmed; empty when there is none or it is not text. */
internal fun copiedText(context: Context): String {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = runCatching { clipboard?.primaryClip }.getOrNull()?.takeIf { it.itemCount > 0 } ?: return ""
    return clip.getItemAt(0).coerceToText(context)?.toString()?.trim().orEmpty()
}

@Composable
private fun Way(icon: ImageVector, title: Int, sub: Int, divided: Boolean = true, onClick: () -> Unit) {
    val colors = Csq.colors
    Cell(
        title = stringResource(title),
        sub = stringResource(sub),
        divided = divided,
        onClick = onClick,
        leading = {
            Box(Modifier.size(36.dp).background(colors.raised, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.text)
            }
        },
        trailing = { Icon(Icons.chevronRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.faint) },
    )
}
