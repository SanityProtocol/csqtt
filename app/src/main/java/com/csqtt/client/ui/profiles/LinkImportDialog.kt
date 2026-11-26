package com.csqtt.client.ui.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.LinkImport
import com.csqtt.client.LinkImportState
import com.csqtt.client.R
import com.csqtt.client.TunnelManager
import com.csqtt.client.pickProfile
import com.csqtt.client.ui.components.Btn
import com.csqtt.client.ui.components.BtnKind
import com.csqtt.client.ui.components.DialogFrame
import com.csqtt.client.ui.components.Field
import com.csqtt.client.ui.components.TextBtn
import com.csqtt.client.ui.components.ValueBox
import com.csqtt.client.ui.components.ValueRow
import com.csqtt.client.ui.design.Csq
import com.csqtt.client.ui.design.Icons
import com.csqtt.client.ui.design.Shape
import com.csqtt.client.ui.design.Type
import com.csqtt.client.ui.legacy.utils.parseCsqttLink
import com.csqtt.client.ui.shell.LocalOwners
import com.csqtt.client.ui.shell.LocalStore
import com.csqtt.client.ui.shell.PendingLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The one question every csqtt:// link goes through, wherever it came from: the browser, a QR code, the clipboard
 * (M-70; the canvas, F-LinkImport and F-LinkImportDup). It works with the tunnel on. A link to a server no profile is on
 * becomes a new profile, which is never opened by itself; for a server a profile is already on, the user says whether
 * that profile is updated or a second one added. The profile the tunnel is on is never written under it.
 */
@Composable
internal fun LinkImportDialog(id: String, close: () -> Unit) {
    val store = LocalStore.current
    val messages = LocalOwners.current.messages
    val context = LocalContext.current.applicationContext
    val colors = Csq.colors
    val raw = remember(id) { PendingLink.get(context, id) }
    val link = remember(raw) { raw?.let(::parseCsqttLink) }
    if (raw == null || link == null) {
        // answered already, or replaced by a newer link: nothing left to ask
        LaunchedEffect(id) { close() }
        return
    }
    val done = {
        PendingLink.clear(context, id)
        close()
    }

    // busy as everywhere else in the app: a starting run and a Wi-Fi pause too
    val running by TunnelManager.running.collectAsStateWithLifecycle()
    val starting by TunnelManager.starting.collectAsStateWithLifecycle()
    val stopping by TunnelManager.stopping.collectAsStateWithLifecycle()
    val paused by TunnelManager.autoPausedForWifi.collectAsStateWithLifecycle()
    val busy = running || starting || stopping || paused
    val state by produceState<LinkImportState?>(null, raw, busy) {
        value = store.linkImportState(LinkImport.endpoint(link.host, link.port), TunnelManager.runTargetProfile().takeIf { busy })
    }
    val known = state
    val plan = known?.let(LinkImport::plan)
    var name by rememberSaveable { mutableStateOf(link.host) }
    // «Обновить» is the first choice where it can be taken; null until the user picks
    var picked by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val update = plan != null && plan.same != null && (picked ?: plan.update) && plan.update
    val possible = plan != null && (if (update) plan.update else plan.add)

    fun import() {
        val planned = plan ?: return
        val target = if (update) planned.same else planned.addInto
        // on the app's scope: closing the dialog never cuts the write short
        TunnelManager.scope.launch(Dispatchers.Main) {
            // the tunnel may have started on that profile while the question stood
            if (target != null && LinkImport.tunnelBusy() && TunnelManager.runTargetProfile() == target) {
                messages.show(context.getString(R.string.link_tunnel_on_profile))
                return@launch
            }
            val open = store.activeProfile.first()
            val written = store.importLink(raw, target, if (update) "" else name)
            when {
                written == null -> messages.show(context.getString(R.string.profiles_none_free))
                update -> messages.show(context.getString(R.string.link_updated, written.second))
                // the profile that was open and empty took the link: it is the selected one already
                written.first == open -> messages.show(context.getString(R.string.link_added, written.second))
                // a new profile is never opened by itself: one tap on the message picks it
                else -> messages.show(context.getString(R.string.link_added, written.second), context.getString(R.string.link_pick)) {
                    TunnelManager.scope.launch(Dispatchers.Main) { pickProfile(context, store, written.first, store.activeProfile.first()) }
                }
            }
        }
        done()
    }

    DialogFrame(
        title = stringResource(R.string.link_import_title),
        onDismiss = done,
        // a link opened from the browser would be lost with the dialog
        dismissOnOutside = false,
        actions = {
            TextBtn(stringResource(R.string.action_cancel), done)
            if (plan != null) {
                Btn(
                    if (update) stringResource(R.string.link_update, known.sameName) else stringResource(R.string.action_add),
                    ::import, kind = BtnKind.Primary, enabled = possible,
                )
            }
        },
    ) {
        if (plan != null && plan.same == null && plan.add) {
            Field(stringResource(R.string.link_profile_name), name, { name = it })
        }
        ValueBox {
            ValueRow(stringResource(R.string.link_server), "${link.host}:${link.port}", mono = true, divided = false)
            link.power?.let { ValueRow(stringResource(R.string.link_streams), it.toString()) }
            ValueRow(
                stringResource(R.string.link_hashes),
                when {
                    link.hashes.isNotEmpty() -> stringResource(R.string.link_hashes_owner, link.hashes.size)
                    link.vkAccessToken.isNotEmpty() -> stringResource(R.string.link_hashes_token)
                    else -> stringResource(R.string.link_hashes_none)
                },
            )
        }
        if (plan == null) return@DialogFrame
        if (plan.same != null) {
            Row(
                Modifier.fillMaxWidth().clip(Shape.context).background(colors.warnSoft).padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.info, contentDescription = null, modifier = Modifier.size(20.dp), tint = colors.warn)
                Text(stringResource(R.string.link_same_server, known.sameName), style = Type.fact, color = colors.text)
            }
            Column(Modifier.fillMaxWidth().clip(Shape.context).border(1.dp, colors.line, Shape.context).selectableGroup()) {
                Choice(
                    stringResource(R.string.link_update, known.sameName),
                    stringResource(if (plan.update) R.string.link_update_sub else R.string.link_update_running),
                    selected = update, enabled = plan.update, divided = false,
                ) { picked = true }
                Choice(
                    stringResource(R.string.link_add_separately),
                    if (plan.add) stringResource(R.string.link_add_separately_sub, known.sameName) else stringResource(R.string.profiles_none_free),
                    selected = !update, enabled = plan.add,
                ) { picked = false }
            }
        } else if (!plan.add) {
            Text(stringResource(R.string.profiles_none_free_hint), style = Type.fact, color = colors.danger)
        }
        // said only while it is true and not obvious: the tunnel is on, and this import leaves it where it is
        if (possible && known.runProfile != null && !update) {
            Text(stringResource(R.string.home_run_stays, known.runName), style = Type.sub, color = colors.muted)
        }
    }
}

/** One of two answers, all in sight; a choice that cannot be taken says why in its second line. */
@Composable
private fun Choice(title: String, sub: String, selected: Boolean, enabled: Boolean, divided: Boolean = true, onPick: () -> Unit) {
    val colors = Csq.colors
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp)
            .drawBehind { if (divided) drawLine(colors.lineSoft, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onPick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.padding(top = 2.dp).alpha(if (enabled) 1f else 0.45f).size(20.dp)
                .border(2.dp, if (selected) colors.accent else colors.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(10.dp).background(colors.accent, CircleShape))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = if (selected) Type.bodyStrong else Type.body, color = if (enabled) colors.text else colors.muted)
            Text(sub, style = Type.sub, color = colors.muted)
        }
    }
}
