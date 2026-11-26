package com.csqtt.client.ui.profiles

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.ProfileEntry
import com.csqtt.client.ProfileKind
import com.csqtt.client.R
import com.csqtt.client.TunnelManager
import com.csqtt.client.ui.components.Btn
import com.csqtt.client.ui.components.BtnKind
import com.csqtt.client.ui.components.DialogFrame
import com.csqtt.client.ui.components.Field
import com.csqtt.client.ui.components.TextBtn
import com.csqtt.client.ui.design.Csq
import com.csqtt.client.ui.design.Type
import com.csqtt.client.ui.legacy.utils.parseCsqttLink
import com.csqtt.client.ui.shell.LocalOwners
import com.csqtt.client.ui.shell.LocalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The profile a dialog is about, once the store has answered. A profile that is gone (deleted elsewhere while the
 * dialog waited, or a route another build saved) closes the dialog; null until then, and nothing is drawn.
 */
@Composable
private fun rememberProfile(profile: Int?, close: () -> Unit): ProfileEntry? {
    val entries by LocalStore.current.profileEntries.collectAsStateWithLifecycle(initialValue = null)
    val loaded = entries ?: return null
    val entry = loaded.firstOrNull { it.index == profile }
    if (entry == null) LaunchedEffect(Unit) { close() }
    return entry
}

/**
 * «Переименовать профиль» (the canvas, F-ProfileRename): any profile takes the name the user gives it, and the name it
 * came with is one tap away. An empty name is that name too.
 */
@Composable
internal fun ProfileRenameDialog(profile: Int?, close: () -> Unit) {
    val store = LocalStore.current
    val entry = rememberProfile(profile, close) ?: return
    // null until something is typed: the field shows the name as it stands
    var typed by rememberSaveable { mutableStateOf<String?>(null) }
    val name = typed ?: entry.name
    val linkHost = remember(entry.link) { parseCsqttLink(entry.link)?.host }
    val original = when {
        entry.kind == ProfileKind.Subscription -> stringResource(R.string.profile_rename_original_sub, entry.originalName)
        entry.kind == ProfileKind.Link && entry.originalName == linkHost -> stringResource(R.string.profile_rename_original_link, entry.originalName)
        else -> stringResource(R.string.profile_rename_original, entry.originalName)
    }
    DialogFrame(
        title = stringResource(R.string.profile_rename_title),
        onDismiss = close,
        actions = {
            TextBtn(stringResource(R.string.action_cancel), close)
            Btn(
                stringResource(R.string.action_save),
                {
                    // on the app's scope: the name is stored even if the dialog's own scope ends with it
                    TunnelManager.scope.launch { store.renameProfile(entry.index, name) }
                    close()
                },
                kind = BtnKind.Primary,
            )
        },
    ) {
        Field(stringResource(R.string.profile_rename_field), name, { typed = it }, hint = original)
        if (name.trim() != entry.originalName) {
            TextBtn(stringResource(R.string.profile_rename_revert), { typed = entry.originalName })
        }
    }
}

/**
 * «Удалить профиль?» (the canvas, F-ProfileDelete; I11: a local delete is confirmed by a dialog, never by typed text).
 * Until «Сервер» lists the server records (B4a), the record only this profile links goes with it, and the dialog says so.
 */
@Composable
internal fun ProfileDeleteDialog(profile: Int?, close: () -> Unit) {
    val store = LocalStore.current
    val context = LocalContext.current.applicationContext
    val messages = LocalOwners.current.messages
    val entry = rememberProfile(profile, close) ?: return
    val takesServerRecord by produceState(false, entry.index) { value = store.profileHoldsOnlyLinkToVps(entry.index) }
    DialogFrame(
        title = stringResource(R.string.profile_delete_title, entry.name),
        onDismiss = close,
        actions = {
            TextBtn(stringResource(R.string.action_cancel), close)
            Btn(
                stringResource(R.string.action_delete),
                {
                    TunnelManager.scope.launch(Dispatchers.Main) {
                        // the tunnel may have started on it while the question stood
                        if (TunnelManager.connectionState.value.powerIsOn && TunnelManager.runTargetProfile() == entry.index) {
                            messages.show(context.getString(R.string.profile_delete_running))
                        } else {
                            store.deleteProfile(entry.index)
                            messages.show(context.getString(R.string.profile_deleted, entry.name))
                        }
                    }
                    close()
                },
                kind = BtnKind.Destroy,
            )
        },
    ) {
        Text(stringResource(R.string.profile_delete_text), style = Type.body, color = Csq.colors.text)
        if (takesServerRecord) {
            Text(stringResource(R.string.profile_delete_server_record), style = Type.fact, color = Csq.colors.muted, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
