package com.csqtt.client.ui.tunnel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.CsqttConstants
import com.csqtt.client.R
import com.csqtt.client.ReceivedHashes
import com.csqtt.client.TunnelManager
import com.csqtt.client.VkHashCheck
import com.csqtt.client.VkHashValidationCodec
import com.csqtt.client.VkHashValidationStatus
import com.csqtt.client.ownHashRows
import com.csqtt.client.ui.components.Btn
import com.csqtt.client.ui.components.BtnKind
import com.csqtt.client.ui.components.Card
import com.csqtt.client.ui.components.Chip
import com.csqtt.client.ui.components.ChipKind
import com.csqtt.client.ui.components.Field
import com.csqtt.client.ui.components.IconBtn
import com.csqtt.client.ui.components.Sheet
import com.csqtt.client.ui.components.TextBtn
import com.csqtt.client.ui.design.Csq
import com.csqtt.client.ui.design.Icons
import com.csqtt.client.ui.design.Type
import com.csqtt.client.ui.legacy.utils.parseCsqttLink
import com.csqtt.client.ui.legacy.utils.stripVkUrlStatic
import com.csqtt.client.ui.profiles.copiedText
import com.csqtt.client.ui.shell.LocalOwners
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val FIELDS = OWN_HASH_FIELDS
private const val MIN_LENGTH = 16

/** What is stored of the fields: each hash once, links to calls cut down to their hash, what is too short left out. */
internal fun hashesToStore(fields: List<String>): String =
    fields.map { stripVkUrlStatic(it) }.filter { it.length >= MIN_LENGTH }.distinct().joinToString(",")

/**
 * «Хеши звонков VK» (the canvas, F-Hashes, F-HashesParticipant): the profile's own hashes, each with what the last
 * check said of it; for a participant, the owner's first — never shown, each to be taken out or put back — and the
 * participant's own beside them. The check runs above the screens ([VkHashCheck], U2): the sheet can be closed while it
 * runs. «Сохранить» stores the fields, and so does closing the sheet any other way than «Отмена»: a hash that was
 * pasted is not lost to a swipe.
 */
@Composable
internal fun HashesSheet(profileArg: String, close: () -> Unit) {
    val profile = profileArg.toIntOrNull()
    if (profile == null) {
        LaunchedEffect(Unit) { close() }
        return
    }
    val colors = Csq.colors
    val context = LocalContext.current.applicationContext
    val store = rememberProfileStore(profile)
    val stored by store.vkHashes.collectAsStateWithLifecycle(initialValue = null)
    val linkMode by store.csqttLinkMode.collectAsStateWithLifecycle(initialValue = false)
    val link by store.csqttLink.collectAsStateWithLifecycle(initialValue = "")
    val received by store.receivedHashes.collectAsStateWithLifecycle(initialValue = emptyList())
    val removed by store.linkHashesRemoved.collectAsStateWithLifecycle(initialValue = emptySet())
    val resultsJson by store.vkHashCheckResults.collectAsStateWithLifecycle(initialValue = "{}")
    val fingerprint by store.selectedFingerprint.collectAsStateWithLifecycle(initialValue = CsqttConstants.Tunnel.DEFAULT_FINGERPRINT)
    val clientIds by store.activeClientIds.collectAsStateWithLifecycle(initialValue = CsqttConstants.Tunnel.DEFAULT_CLIENT_IDS)
    val check by VkHashCheck.state.collectAsStateWithLifecycle()

    // the fields as typed: the stored hashes once the store has answered, then the user's edits. The user's own hashes
    // only: the owner's never enter the saved state
    val fields = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) { mutableStateListOf<String>() }
    LaunchedEffect(stored) {
        val raw = stored ?: return@LaunchedEffect
        if (fields.isEmpty()) fields.addAll((storedHashes(raw) + List(FIELDS) { "" }).take(FIELDS))
    }

    // a field that is edited forgets what the last check said of the hash that stood in it: entered again (its call
    // was started again), the hash is tried, and reads as not checked until it is
    val forgotten = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) { mutableStateListOf<String>() }
    // those of them a check was started for since. The check drops what the last one said of them itself, before it
    // asks (VkHashCheck.start): when it is done they are checked hashes again, shown and kept as it found them
    val rechecking = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) { mutableStateListOf<String>() }
    val checked = remember(resultsJson) { VkHashValidationCodec.decode(resultsJson) }
    val results = checked - forgotten.toSet()
    val ownerHashes = remember(linkMode, link, received) {
        if (linkMode) ReceivedHashes.ownerSet(parseCsqttLink(link)?.hashes.orEmpty(), received) else emptyList()
    }
    val ownerInUse = ownerHashes.filterNot { it in removed }
    val checking = (check as? VkHashCheck.State.Running)?.profile == profile
    val checkBusy = check is VkHashCheck.State.Running
    val toCheck = (ownerInUse + fields.map(String::trim)).filter { it.length >= MIN_LENGTH }.distinct()
    val last = (check as? VkHashCheck.State.Done)?.takeIf { it.profile == profile && it.hashes == toCheck }
    // waited for on the check itself, not on its state as drawn: a check that ends as the one before it did is no
    // change of that state to see
    LaunchedEffect(rechecking.size) {
        if (rechecking.isEmpty()) return@LaunchedEffect
        VkHashCheck.state.first { it !is VkHashCheck.State.Running }
        forgotten.removeAll(rechecking)
        rechecking.clear()
    }
    // on the app's scope: the fields are stored also when the sheet goes the same moment
    // (nothing to store before the fields were read: an empty list would wipe the stored hashes)
    val save = {
        if (fields.isNotEmpty()) {
            // not those a check is running for: it has dropped their old verdicts, and its own are not to follow them
            val gone = forgotten.toSet() - rechecking.toSet()
            TunnelManager.scope.launch {
                store.saveVkHashesOnly(hashesToStore(fields.toList()))
                store.forgetVkHashCheckResults(gone)
            }
        }
    }
    val edit = { index: Int, raw: String ->
        val next = stripVkUrlStatic(raw.filterNot(Char::isWhitespace))
        val previous = fields[index].trim()
        if (next != previous) {
            if (previous in checked && previous !in forgotten) forgotten.add(previous)
            fields[index] = next
        }
    }

    Sheet(
        onDismiss = {
            save()
            close()
        },
        title = stringResource(R.string.hashes_title),
        subtitle = stringResource(if (ownerHashes.isEmpty()) R.string.hashes_sub_owner else R.string.hashes_sub_participant),
        messages = LocalOwners.current.messages,
    ) {
        // the sheet is made at once, and its fields when the store has answered: windows stack in the order they are made
        if (fields.isEmpty()) return@Sheet
        if (ownerHashes.isNotEmpty()) {
            Section(stringResource(R.string.hashes_from_owner))
            Card(flush = true) {
                ownerHashes.forEachIndexed { index, hash ->
                    OwnerRow(index, hash in removed, results[hash], divided = index > 0) {
                        // taken out and put back at once: each is its own action, with its way back beside it
                        val next = if (hash in removed) removed - hash else removed + hash
                        TunnelManager.scope.launch { store.saveLinkHashesRemoved(next) }
                    }
                }
            }
            Section(stringResource(R.string.hashes_own))
        }
        val rows = if (ownerHashes.isEmpty()) FIELDS else ownHashRows(ownerInUse.size, fields)
        for (index in 0 until rows) {
            val value = fields[index]
            val first = fields.indexOfFirst { it.isNotBlank() && it == value }
            HashField(
                number = index + 1,
                main = index == 0 && ownerHashes.isEmpty(),
                value = value,
                status = results[value.trim()],
                checking = checking && value.trim().length >= MIN_LENGTH,
                error = when {
                    value.isNotBlank() && value.length < MIN_LENGTH -> stringResource(R.string.hashes_short)
                    value.isNotBlank() && first != index -> stringResource(R.string.hashes_duplicate)
                    else -> null
                },
                // a link to a call is cut down to its hash as it is typed or pasted
                onChange = { raw -> edit(index, raw) },
                paste = { copiedText(context).takeIf { it.isNotBlank() }?.let { edit(index, it) } },
            )
        }
        Text(
            if (ownerHashes.isEmpty()) stringResource(R.string.hashes_more)
            else stringResource(R.string.hashes_limit, CsqttConstants.Tunnel.CORE_VK_HASHES),
            style = Type.sub, color = colors.muted,
        )
        if (last != null && last.unchecked > 0) {
            Text(stringResource(R.string.hashes_unchecked, last.unchecked), style = Type.sub, color = colors.muted)
        }
        Btn(
            when {
                checking -> stringResource(R.string.hashes_checking)
                // one check at a time, whichever profile it is for
                checkBusy -> stringResource(R.string.hashes_check_busy)
                ownerHashes.isNotEmpty() -> stringResource(R.string.hashes_check_count, toCheck.size)
                else -> stringResource(R.string.hashes_check)
            },
            {
                val again = forgotten.filter { it in toCheck }.toSet()
                if (VkHashCheck.start(context, profile, toCheck, fingerprint, clientIds, enteredAgain = again)) rechecking.addAll(again)
            },
            modifier = Modifier.fillMaxWidth(), kind = BtnKind.Tonal, icon = if (checking) Icons.loader else Icons.activity,
            enabled = !checkBusy && toCheck.isNotEmpty(),
        )
        if (checking) {
            Text(
                stringResource(R.string.hashes_check_close), style = Type.sub, color = colors.muted, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Hairline()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            TextBtn(stringResource(R.string.action_cancel), close)
            Btn(
                stringResource(R.string.action_save),
                {
                    save()
                    close()
                },
                kind = BtnKind.Primary, modifier = Modifier.widthIn(min = 140.dp),
            )
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(text, style = Type.section, color = Csq.colors.muted, modifier = Modifier.padding(start = 4.dp, top = 2.dp).semantics { heading() })
}

/** One of the owner's hashes: its number, what the last check said, and never the hash itself (K9). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OwnerRow(index: Int, removed: Boolean, status: VkHashValidationStatus?, divided: Boolean, toggle: () -> Unit) {
    val colors = Csq.colors
    val (word, kind) = when {
        removed -> R.string.hashes_owner_off to ChipKind.Muted
        status == VkHashValidationStatus.Valid -> R.string.hashes_owner_ok to ChipKind.Ok
        status == VkHashValidationStatus.Invalid -> R.string.hashes_owner_bad to ChipKind.Danger
        else -> R.string.hashes_owner_new to ChipKind.Muted
    }
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp)
            .drawBehind { if (divided) drawLine(colors.lineSoft, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // the status goes under the row's name where the two do not fit side by side: it is never cut
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.hashes_owner_n, index + 1), style = Type.fact, color = colors.text)
                Chip(stringResource(word), kind = kind, small = true)
            }
            Text(
                stringResource(if (removed) R.string.hashes_owner_hidden else R.string.hashes_owner_masked),
                style = if (removed) Type.sub else Type.mono, color = if (removed) colors.muted else colors.faint,
            )
        }
        Btn(stringResource(if (removed) R.string.hashes_return else R.string.hashes_remove), toggle, small = true)
    }
}

/** One of the profile's own hashes: the field, its status beside its label, and a paste or a clear at its end. */
@Composable
private fun HashField(
    number: Int,
    main: Boolean,
    value: String,
    status: VkHashValidationStatus?,
    checking: Boolean,
    error: String?,
    onChange: (String) -> Unit,
    paste: () -> Unit,
) {
    val colors = Csq.colors
    Field(
        stringResource(if (main) R.string.hashes_field_main else R.string.hashes_field, number), value, onChange,
        placeholder = stringResource(R.string.hashes_placeholder), mono = true, error = error,
        // what the last check said, in a word beside the label: the colour never says it alone
        labelEnd = {
            if (value.length >= MIN_LENGTH && error == null) {
                when {
                    checking -> Chip(stringResource(R.string.hashes_checking), kind = ChipKind.Accent, small = true)
                    status == VkHashValidationStatus.Valid -> Chip(stringResource(R.string.hashes_ok), kind = ChipKind.Ok, small = true)
                    status == VkHashValidationStatus.Invalid -> Chip(stringResource(R.string.hashes_bad), kind = ChipKind.Danger, small = true)
                    else -> Chip(stringResource(R.string.hashes_new), kind = ChipKind.Muted, small = true)
                }
            }
        },
        trailing = {
            if (value.isEmpty()) {
                IconBtn(Icons.clipboard, stringResource(R.string.hashes_paste, number), paste, small = true, tint = colors.muted)
            } else {
                IconBtn(Icons.close, stringResource(R.string.hashes_clear, number), { onChange("") }, small = true, tint = colors.muted)
            }
        },
    )
}
