package com.csqtt.client.ui.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.ProfileEntry
import com.csqtt.client.ProfileKind
import com.csqtt.client.R
import com.csqtt.client.SubscriptionCertRecord
import com.csqtt.client.SubscriptionFetchResult
import com.csqtt.client.SubscriptionMeta
import com.csqtt.client.SubscriptionPreview
import com.csqtt.client.SubscriptionSync
import com.csqtt.client.TunnelManager
import com.csqtt.client.copySensitive
import com.csqtt.client.normalizeSubscriptionUrl
import com.csqtt.client.noticeTime
import com.csqtt.client.ui.components.Btn
import com.csqtt.client.ui.components.BtnKind
import com.csqtt.client.ui.components.Card
import com.csqtt.client.ui.components.Cell
import com.csqtt.client.ui.components.Chip
import com.csqtt.client.ui.components.ChipKind
import com.csqtt.client.ui.components.DialogFrame
import com.csqtt.client.ui.components.Field
import com.csqtt.client.ui.components.IconBtn
import com.csqtt.client.ui.components.Menu
import com.csqtt.client.ui.components.Sheet
import com.csqtt.client.ui.components.SwitchRow
import com.csqtt.client.ui.components.TextBtn
import com.csqtt.client.ui.components.ValueBox
import com.csqtt.client.ui.components.ValueRow
import com.csqtt.client.ui.design.Csq
import com.csqtt.client.ui.design.Icons
import com.csqtt.client.ui.design.Shape
import com.csqtt.client.ui.design.Type
import com.csqtt.client.ui.design.pluralText
import com.csqtt.client.ui.shell.LocalNav
import com.csqtt.client.ui.shell.LocalOwners
import com.csqtt.client.ui.shell.LocalStore
import com.csqtt.client.ui.shell.Routes
import com.csqtt.client.ui.shell.SecretDrafts
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/*
 * A subscription's sheets (the canvas, F-SubImport, F-SubEdit, F-SubCertChanged). A subscription's URL may carry its
 * access token: it is never in the saved navigation (a route names a subscription by its key) and a URL being typed is
 * kept in memory only, like a password (§4.2 rule 2).
 */

/** A certificate's SHA-256 as it is shown: «3F:9A:0C:…:E1:C2», its first three and last two bytes. */
internal fun fingerprintShort(sha256: String): String {
    val bytes = sha256.trim().uppercase().chunked(2)
    return if (bytes.size <= 5) bytes.joinToString(":") else (bytes.take(3) + "…" + bytes.takeLast(2)).joinToString(":")
}

private fun hostOf(url: String): String = runCatching { URI(url.trim()).host }.getOrNull().orEmpty().ifEmpty { url.trim() }

private const val IMPORT_URL = "sub-import:url"
private const val EDIT_URL = "sub-edit:url"

/**
 * What «Загрузить» brought, with the switch it was loaded under: kept in memory only — the nodes' links hold their
 * passwords.
 */
private class Fetched(val url: String, val preview: SubscriptionPreview, val cert: SubscriptionCertRecord?, val allowHttp: Boolean)

/**
 * «Импорт подписки»: the link, then «Загрузить» shows what the subscription holds — its nodes, what was left out, and
 * the certificate that will be remembered for a server trusted by its own certificate — and «Импортировать» writes it.
 * Nothing is written before that. It works with the tunnel on (I15).
 */
@Composable
internal fun SubImportSheet(close: () -> Unit) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val messages = LocalOwners.current.messages
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val colors = Csq.colors
    var allowHttp by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var load by remember { mutableStateOf<Job?>(null) }
    val loading = load != null
    var fetched by remember { mutableStateOf<Fetched?>(null) }
    val url = SecretDrafts[IMPORT_URL]
    val needUrl = stringResource(R.string.sub_error_url)
    val done = {
        SecretDrafts.clear(IMPORT_URL)
        close()
    }
    // what was loaded, and what is still loading, belongs to the link and the switch as they were
    fun changed() {
        load?.cancel()
        load = null
        fetched = null
        error = null
    }

    Sheet(onDismiss = done, title = stringResource(R.string.sub_import_title), messages = messages) {
        Field(
            stringResource(R.string.sub_url), url, { SecretDrafts[IMPORT_URL] = it; changed() }, mono = true, error = error,
            trailing = {
                IconBtn(Icons.clipboard, stringResource(R.string.sub_paste), {
                    SecretDrafts[IMPORT_URL] = copiedText(context)
                    changed()
                }, small = true, tint = colors.muted)
            },
        )
        SwitchRow(stringResource(R.string.sub_allow_http), allowHttp, { allowHttp = it; changed() }, sub = stringResource(R.string.sub_allow_http_sub))
        val shown = fetched
        if (shown != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.sub_found, shown.preview.title.ifBlank { stringResource(R.string.profile_subscription) }, shown.preview.nodes.size),
                    style = Type.section, color = colors.muted, modifier = Modifier.weight(1f),
                )
                Chip(stringResource(R.string.sub_loaded), kind = ChipKind.Ok, small = true)
            }
            Card(flush = true) {
                shown.preview.nodes.forEachIndexed { position, node ->
                    val streams = node.power?.let { pluralText(R.plurals.profile_streams, it) }
                    Cell(
                        title = node.name.ifBlank { node.host },
                        sub = listOfNotNull("${node.host}:${node.port}", streams).joinToString(" · "),
                        divided = position > 0,
                    )
                }
            }
            if (shown.preview.skipped > 0) {
                Text(pluralText(R.plurals.sub_skipped, shown.preview.skipped), style = Type.sub, color = colors.muted, modifier = Modifier.padding(horizontal = 4.dp))
            }
            shown.cert?.let { cert ->
                Row(
                    Modifier.fillMaxWidth().clip(Shape.context).background(colors.raised).padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.lock, contentDescription = null, modifier = Modifier.padding(top = 2.dp).size(18.dp), tint = colors.muted)
                    Column {
                        Text(stringResource(R.string.sub_cert_will_be_kept), style = Type.fact, color = colors.text)
                        Text(stringResource(R.string.sub_cert_sha, fingerprintShort(cert.sha256)), style = Type.mono, color = colors.muted)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            TextBtn(stringResource(R.string.action_cancel), done)
            if (shown == null) {
                Btn(
                    stringResource(if (loading) R.string.sub_loading else R.string.sub_load),
                    {
                        val typed = url.trim()
                        when {
                            typed.isEmpty() -> error = needUrl
                            else -> {
                                error = null
                                val marked = allowHttp
                                // on the sheet's own scope: a load nobody waits for any more writes nothing and is
                                // dropped; an edit of the link or the switch cancels it
                                load = scope.launch {
                                    val (kept, result) = SubscriptionSync.preview(store, typed, marked)
                                    load = null
                                    when (result) {
                                        is SubscriptionFetchResult.Success -> fetched = Fetched(kept, result.preview, result.cert, marked)
                                        is SubscriptionFetchResult.Failure -> error = result.message
                                    }
                                }
                            }
                        }
                    },
                    kind = BtnKind.Primary, enabled = !loading,
                )
            } else {
                Btn(
                    stringResource(R.string.sub_import),
                    {
                        // on the app's scope: what the user confirmed is written whatever becomes of the sheet
                        TunnelManager.scope.launch(Dispatchers.Main) {
                            messages.show(SubscriptionSync.importPreviewed(store, shown.url, shown.preview, shown.cert, shown.allowHttp).message)
                        }
                        nav.closeRoutes(Routes.ADD_PROFILE)
                        done()
                    },
                    kind = BtnKind.Primary,
                )
            }
        }
    }
}

/** A subscription a sheet or a dialog is about: its URL, what is kept of it, and its nodes. */
private class Subscription(val url: String, val meta: SubscriptionMeta, val nodes: List<ProfileEntry>, val others: Set<String>) {
    val title: String get() = meta.title.ifBlank { nodes.firstOrNull()?.subscriptionTitle.orEmpty() }
}

/**
 * The subscription [key] names (a route's argument), once the store has answered. One that is gone — deleted, or its
 * link changed — closes what asked for it; null until then, and nothing is drawn.
 */
@Composable
private fun rememberSubscription(key: String, close: () -> Unit): Subscription? {
    val store = LocalStore.current
    val metas by store.subscriptionMeta.collectAsStateWithLifecycle(initialValue = null)
    val entries by store.profileEntries.collectAsStateWithLifecycle(initialValue = null)
    val loadedMetas = metas ?: return null
    val loadedEntries = entries ?: return null
    val nodes = loadedEntries.filter { it.kind == ProfileKind.Subscription }
    val known = loadedMetas.keys + nodes.map { it.subscriptionUrl }
    val url = known.firstOrNull { Routes.subscriptionKey(it) == key }
    if (url == null) {
        LaunchedEffect(Unit) { close() }
        return null
    }
    return Subscription(url, loadedMetas[url] ?: SubscriptionMeta(url = url), nodes.filter { it.subscriptionUrl == url }, known - url)
}

/** Whether the tunnel is on one of [subscription]'s nodes: those are not deleted from under it. */
private fun tunnelOn(subscription: Subscription): Boolean =
    TunnelManager.connectionState.value.powerIsOn && subscription.nodes.any { it.index == TunnelManager.runTargetProfile() }

/**
 * «Подписка» (F-SubEdit): its name, its link, how often it refreshes by itself, the certificate its server is trusted
 * by, when it was last refreshed and how that went; «Обновить сейчас» and «Удалить».
 */
@Composable
internal fun SubEditSheet(key: String, close: () -> Unit) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val messages = LocalOwners.current.messages
    val context = LocalContext.current.applicationContext
    val colors = Csq.colors
    val subscription = rememberSubscription(key, close) ?: return
    val meta = subscription.meta
    // null until changed: the fields show what is stored
    var typedTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var pickedInterval by rememberSaveable { mutableStateOf<Int?>(null) }
    var urlError by rememberSaveable { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    // the link field is its draft once it was edited — an emptied one too; until then, the stored link. The draft is in
    // memory only (the link may carry the subscription's token) and goes with the sheet
    var linkEdited by remember { mutableStateOf(false) }
    val title = typedTitle ?: subscription.title
    val url = if (linkEdited) SecretDrafts[EDIT_URL] else subscription.url
    val interval = pickedInterval ?: meta.autoUpdateMinutes
    val needUrl = stringResource(R.string.sub_error_url)
    val linkTaken = stringResource(R.string.sub_link_taken)
    val linkCopied = stringResource(R.string.sub_link_copied)
    DisposableEffect(Unit) { onDispose { SecretDrafts.clear(EDIT_URL) } }

    Sheet(
        onDismiss = close,
        title = stringResource(R.string.sub_edit_title),
        subtitle = pluralText(R.plurals.sub_nodes, subscription.nodes.size),
        messages = messages,
    ) {
        Field(stringResource(R.string.sub_name), title, { typedTitle = it })
        Field(
            stringResource(R.string.sub_link), url,
            {
                SecretDrafts[EDIT_URL] = it
                linkEdited = true
                urlError = null
            },
            mono = true, error = urlError,
            trailing = {
                IconBtn(Icons.copy, stringResource(R.string.sub_copy_link), {
                    // marked as sensitive for the system's clipboard preview, as the app's other secrets (M-173)
                    if (context.copySensitive("csqtt", url)) messages.show(linkCopied)
                }, small = true, tint = colors.muted)
            },
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sub_auto_update), style = Type.body, color = colors.text, modifier = Modifier.weight(1f))
            IntervalSelect(interval) { pickedInterval = it }
        }
        ValueBox {
            var divided = false
            meta.trusted?.let {
                ValueRow(stringResource(R.string.sub_cert), fingerprintShort(it.sha256), mono = true, divided = false)
                divided = true
            }
            ValueRow(stringResource(R.string.sub_updated), subscriptionUpdatedValue(meta), divided = divided)
            val outcome = when {
                meta.lastError.isNotEmpty() -> meta.lastError
                meta.lastSkipped.isNotEmpty() -> meta.lastSkipped
                meta.lastUpdatedAt > 0L -> stringResource(R.string.sub_outcome_ok)
                else -> null
            }
            outcome?.let { ValueRow(stringResource(R.string.sub_outcome), it, danger = meta.lastError.isNotEmpty()) }
        }
        // of one height, whichever label takes two lines on a narrow screen
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn(
                stringResource(if (refreshing) R.string.sub_refreshing else R.string.sub_refresh_now),
                {
                    refreshing = true
                    // on the app's scope: a refresh that was asked for ends, and its outcome is kept
                    TunnelManager.scope.launch(Dispatchers.Main) {
                        val outcome = SubscriptionSync.refresh(store, subscription.url)
                        refreshing = false
                        if (outcome.refused != null) nav.open(Routes.subCert(subscription.url)) else messages.show(outcome.message)
                    }
                },
                modifier = Modifier.weight(1f).fillMaxHeight(), icon = Icons.refresh, enabled = !refreshing,
            )
            Btn(
                stringResource(R.string.action_delete),
                {
                    if (tunnelOn(subscription)) messages.show(context.getString(R.string.sub_delete_running))
                    else nav.open(Routes.subDelete(subscription.url))
                },
                modifier = Modifier.weight(1f).fillMaxHeight(), kind = BtnKind.Danger, icon = Icons.trash,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            TextBtn(stringResource(R.string.action_close), close)
            Btn(
                stringResource(R.string.action_save),
                {
                    // a link typed without its scheme is the same link the import sheet takes
                    val nextUrl = normalizeSubscriptionUrl(url, meta.allowHttp)
                    if (nextUrl.isEmpty() || runCatching { URI(nextUrl).host }.getOrNull().isNullOrBlank()) {
                        urlError = needUrl
                    } else if (nextUrl in subscription.others) {
                        // two subscriptions under one link would become one, with the other's trusted certificate lost
                        urlError = linkTaken
                    } else {
                        // on the app's scope, and the link last: the name and the interval are kept under the link as it was
                        TunnelManager.scope.launch {
                            if (title.trim() != subscription.title) store.renameSubscription(subscription.url, subscription.title, title)
                            if (interval != meta.autoUpdateMinutes) store.saveSubscriptionAutoUpdate(subscription.url, interval)
                            if (nextUrl != subscription.url) store.changeSubscriptionUrl(subscription.url, nextUrl)
                        }
                        close()
                    }
                },
                kind = BtnKind.Primary,
            )
        }
    }
}

@Composable
private fun subscriptionUpdatedValue(meta: SubscriptionMeta): String =
    if (meta.lastUpdatedAt > 0L) remember(meta.lastUpdatedAt) { noticeTime(meta.lastUpdatedAt) }
    else stringResource(R.string.sub_never_updated_value)

/** «Автообновление»: one of the intervals, as a menu under its current value. As a choice it starts with a capital. */
@Composable
private fun IntervalSelect(minutes: Int, onPick: (Int) -> Unit) {
    val colors = Csq.colors
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        Btn(subscriptionIntervalLabel(minutes).replaceFirstChar { it.titlecase() }, { open = true }, small = true, icon = Icons.chevronDown)
        Menu(open, { open = false }) {
            for (choice in SubscriptionIntervals) {
                DropdownMenuItem(
                    modifier = Modifier.semantics { selected = choice == minutes },
                    text = { Text(subscriptionIntervalLabel(choice).replaceFirstChar { it.titlecase() }, style = Type.body) },
                    trailingIcon = {
                        if (choice == minutes) Icon(Icons.check, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.muted)
                    },
                    onClick = {
                        open = false
                        onPick(choice)
                    },
                )
            }
        }
    }
}

/** «Удалить подписку?»: a local delete, confirmed by a dialog (I11). The subscription goes whole, with its nodes. */
@Composable
internal fun SubDeleteDialog(key: String, close: () -> Unit) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val messages = LocalOwners.current.messages
    val context = LocalContext.current.applicationContext
    val subscription = rememberSubscription(key, close) ?: return
    val name = subscription.title.ifBlank { hostOf(subscription.url) }
    DialogFrame(
        title = stringResource(R.string.sub_delete_title, name),
        onDismiss = close,
        actions = {
            TextBtn(stringResource(R.string.action_cancel), close)
            Btn(
                stringResource(R.string.action_delete),
                {
                    TunnelManager.scope.launch(Dispatchers.Main) {
                        // the tunnel may have started on one of its nodes while the question stood
                        if (tunnelOn(subscription)) {
                            messages.show(context.getString(R.string.sub_delete_running))
                        } else {
                            store.clearSubscription(subscription.url)
                            messages.show(context.getString(R.string.sub_deleted, name))
                        }
                    }
                    nav.closeRoutes(Routes.SUB_EDIT)
                    close()
                },
                kind = BtnKind.Destroy,
            )
        },
    ) {
        Text(
            stringResource(R.string.sub_delete_text, pluralText(R.plurals.sub_nodes, subscription.nodes.size)),
            style = Type.body, color = Csq.colors.text,
        )
    }
}

/**
 * «Сертификат подписки изменился» (F-SubCertChanged; the same question as a server's changed SSH key): the server
 * showed another certificate than the trusted one, the refresh was turned away, and the subscription stays as it was
 * until the user answers. «Доверять новому» trusts exactly the certificate shown here, and refreshes.
 */
@Composable
internal fun SubCertChangedDialog(key: String, close: () -> Unit) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val messages = LocalOwners.current.messages
    val context = LocalContext.current.applicationContext
    val colors = Csq.colors
    val subscription = rememberSubscription(key, close) ?: return
    val refused = subscription.meta.refused
    if (refused == null) {
        // answered, or a later refresh worked: nothing to ask
        LaunchedEffect(Unit) { close() }
        return
    }
    DialogFrame(
        title = stringResource(R.string.sub_cert_changed_title),
        onDismiss = close,
        dismissOnOutside = false,
        actions = {
            TextBtn(stringResource(R.string.action_cancel), close)
            Btn(
                stringResource(R.string.sub_cert_trust),
                {
                    // on the app's scope: the answer is kept, and the refresh it allows runs, whatever becomes of the dialog
                    TunnelManager.scope.launch(Dispatchers.Main) {
                        val outcome = SubscriptionSync.trustAndRefresh(store, subscription.url, refused.sha256)
                        when {
                            outcome == null -> messages.show(context.getString(R.string.sub_cert_changed_again))
                            outcome.refused != null -> nav.open(Routes.subCert(subscription.url))
                            else -> messages.show(outcome.message)
                        }
                    }
                    close()
                },
                kind = BtnKind.Primary,
            )
        },
    ) {
        Text(stringResource(R.string.sub_cert_changed_text, hostOf(subscription.url)), style = Type.body, color = colors.text)
        Text(stringResource(R.string.sub_cert_changed_warning), style = Type.fact, color = colors.muted)
        ValueBox {
            ValueRow(
                stringResource(R.string.sub_cert_was),
                subscription.meta.trusted?.let { fingerprintShort(it.sha256) } ?: stringResource(R.string.ping_none_figure),
                mono = true, divided = false,
            )
            ValueRow(stringResource(R.string.sub_cert_now), fingerprintShort(refused.sha256), mono = true)
        }
    }
}
