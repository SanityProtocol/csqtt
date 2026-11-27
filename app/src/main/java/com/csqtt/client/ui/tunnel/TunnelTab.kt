package com.csqtt.client.ui.tunnel

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.AddressIssue
import com.csqtt.client.ConnectionStartPolicy
import com.csqtt.client.CsqttConstants
import com.csqtt.client.LinkIssue
import com.csqtt.client.LinkNote
import com.csqtt.client.PasswordIssue
import com.csqtt.client.ProfileEntry
import com.csqtt.client.ProfileKind
import com.csqtt.client.ProfileModeAction
import com.csqtt.client.ProfileScope
import com.csqtt.client.R
import com.csqtt.client.ReceivedHashes
import com.csqtt.client.SettingsStore
import com.csqtt.client.TunnelAuthSnapshot
import com.csqtt.client.TunnelIssues
import com.csqtt.client.TunnelManager
import com.csqtt.client.VkHashValidationCodec
import com.csqtt.client.VkHashValidationStatus
import com.csqtt.client.draftStands
import com.csqtt.client.hashesAStartUses
import com.csqtt.client.linkNote
import com.csqtt.client.noticeTime
import com.csqtt.client.profileModeAction
import com.csqtt.client.shouldConfirmAutoJsMode
import com.csqtt.client.splitHostPort
import com.csqtt.client.tunnelIssues
import com.csqtt.client.ui.components.Btn
import com.csqtt.client.ui.components.BtnKind
import com.csqtt.client.ui.components.Card
import com.csqtt.client.ui.components.Chip
import com.csqtt.client.ui.components.ChipKind
import com.csqtt.client.ui.components.Field
import com.csqtt.client.ui.components.IconBtn
import com.csqtt.client.ui.components.SecretField
import com.csqtt.client.ui.components.Select
import com.csqtt.client.ui.components.SettingRow
import com.csqtt.client.ui.components.SwitchRow
import com.csqtt.client.ui.components.TextBtn
import com.csqtt.client.ui.design.Csq
import com.csqtt.client.ui.design.Icons
import com.csqtt.client.ui.design.Shape
import com.csqtt.client.ui.design.Type
import com.csqtt.client.ui.design.pluralText
import com.csqtt.client.ui.legacy.utils.CsqttLink
import com.csqtt.client.ui.legacy.utils.isValidTunnelPassword
import com.csqtt.client.ui.legacy.utils.parseCsqttLink
import com.csqtt.client.ui.profiles.copiedText
import com.csqtt.client.ui.shell.LocalNav
import com.csqtt.client.ui.shell.LocalOwners
import com.csqtt.client.ui.shell.LocalStore
import com.csqtt.client.ui.shell.NavModel
import com.csqtt.client.ui.shell.Routes
import com.csqtt.client.ui.shell.SecretDrafts
import com.csqtt.client.vkAuthModeForHashMode
import kotlinx.coroutines.launch

/** A profile's own store: what a screen of the «Туннель» family reads and writes is that profile's, whichever is open. */
@Composable
internal fun rememberProfileStore(profile: Int): SettingsStore {
    val context = LocalContext.current.applicationContext
    return remember(profile) { SettingsStore(context, ProfileScope.Fixed(profile)) }
}

/**
 * The form's two secrets while they are typed live in memory only ([SecretDrafts]): the password, and the link, which
 * holds one. A draft is the profile's the form was drawn for ([epoch]): the next profile to take the slot starts clean.
 */
internal fun passwordDraftKey(profile: Int, epoch: Int) = "tunnel:$profile:$epoch:password"

internal fun linkDraftKey(profile: Int, epoch: Int) = "tunnel:$profile:$epoch:link"

/** Where the stored value a secret's draft was typed over is kept, beside the draft (see [draftStands]). */
internal fun draftBaseKey(key: String) = "$key:base"

/** How many hashes of its own a profile holds: the fields of «Хеши звонков VK», and the «N из 6» of its button. */
internal const val OWN_HASH_FIELDS = 6

/** What a port field's text is read as when it is no number: no port the store can hold, so the draft stands. */
private const val NO_PORT = -1

/** The profile's own hashes as stored: one string, split by commas or spaces. */
internal fun storedHashes(raw: String): List<String> = raw.split(Regex("[,\\s\\n]+")).map(String::trim).filter(String::isNotEmpty)

/**
 * What the «Туннель» tab's cards are given: one profile as stored, and what follows from it. [hashMode] and [authMode]
 * are the modes in force, which a participant's link can pin; [storedAuthMode] is what the store holds.
 */
internal class TunnelView(
    val profile: Int,
    /** The epoch the form was drawn under: what its writes and its drafts are told apart by once the slot is another's. */
    val epoch: Int,
    val store: SettingsStore,
    val linkMode: Boolean,
    val hashMode: String,
    val authMode: String,
    val storedAuthMode: String,
    val vkTokenActive: Boolean,
    val vkUserId: String,
    /** The owner's set a participant connects with: what the server last sent, else the link's own. */
    val ownerHashes: List<String>,
    val removedOwner: Set<String>,
    /** An old link that carries its owner's VK token, in use. */
    val linkTokenActive: Boolean,
    /** The profile's own hashes that count: long enough, not known dead. */
    val ownCount: Int,
    /** How many hashes a start would connect with ([hashesAStartUses]): what the «Потоки» zones are counted over. */
    val startHashes: Int,
    val results: Map<String, VkHashValidationStatus>,
    val running: Boolean,
    val issues: TunnelIssues,
    val riskAcknowledged: Boolean,
    val tcpAcknowledged: Boolean,
    /** The calls in the owner's pool; null when this profile's run uses none. */
    val poolSize: Int?,
) {
    val autoHash: Boolean get() = authMode == CsqttConstants.VkAuth.MODE_AUTO_JS || hashMode != CsqttConstants.VkAutoHash.MODE_MANUAL
    val ownerInUse: Int get() = ownerHashes.count { it !in removedOwner }
}

/**
 * «Туннель» (the canvas, F-Tunnel and its states): the open profile's connection — its own server, or the link it
 * connects by — and under «Дополнительно» how the tunnel is made. There is no «Сохранить»: a field is stored when it is
 * left, a choice when it is made. [epoch] is the store's count of replaced profiles the tab was mounted under (the tab
 * is mounted anew when it moves). [validationRequest] grows each time a start was refused for this profile's settings
 * (0: never for this one): from then on what the form lacks is said, not only what it holds wrongly.
 */
@Composable
internal fun TunnelTab(auth: TunnelAuthSnapshot, epoch: Int, validationRequest: Int, moreOpen: Boolean, onMoreOpen: (Boolean) -> Unit) {
    val profile = auth.profile
    val store = rememberProfileStore(profile)
    val entries by LocalStore.current.profileEntries.collectAsStateWithLifecycle(initialValue = emptyList())

    // null until the store has answered: nothing is drawn, and nothing corrected, from a default (§4.2 rule 3)
    val storedPeer by store.peer.collectAsStateWithLifecycle(initialValue = null)
    val storedPort by store.serverPeerPort.collectAsStateWithLifecycle(initialValue = null)
    val storedHashMode by store.vkHashMode.collectAsStateWithLifecycle(initialValue = null)
    val storedToken by store.vkAccessToken.collectAsStateWithLifecycle(initialValue = null)
    val storedAuthMode by store.vkAuthMode.collectAsStateWithLifecycle(initialValue = null)
    val hashesRaw by store.vkHashes.collectAsStateWithLifecycle(initialValue = "")
    val resultsJson by store.vkHashCheckResults.collectAsStateWithLifecycle(initialValue = "{}")
    val received by store.receivedHashes.collectAsStateWithLifecycle(initialValue = emptyList())
    val removedOwner by store.linkHashesRemoved.collectAsStateWithLifecycle(initialValue = emptySet())
    val vkUserId by store.vkAccessTokenUserId.collectAsStateWithLifecycle(initialValue = "")
    val emptyMode by store.emptyProfileMode.collectAsStateWithLifecycle(initialValue = null)
    val riskAcknowledged by store.autoJsRiskAcknowledged.collectAsStateWithLifecycle(initialValue = false)
    val tcpAcknowledged by store.tcpTransportRiskAcknowledged.collectAsStateWithLifecycle(initialValue = false)
    val pool by store.vkPoolState.collectAsStateWithLifecycle(initialValue = null)
    val running by TunnelManager.running.collectAsStateWithLifecycle()
    val starting by TunnelManager.starting.collectAsStateWithLifecycle()
    val stopping by TunnelManager.stopping.collectAsStateWithLifecycle()
    val paused by TunnelManager.autoPausedForWifi.collectAsStateWithLifecycle()

    val peer = storedPeer
    val port = storedPort
    val savedHashMode = storedHashMode
    val token = storedToken
    val savedAuthMode = storedAuthMode
    if (peer == null || port == null || savedHashMode == null || token == null || savedAuthMode == null) {
        Spacer(Modifier.fillMaxSize())
        return
    }

    // the drafts: a field shows the store's value until it is edited, and each draft is kept with the stored value it
    // was typed over (its base). The two that hold a secret are never in the saved state, their bases neither
    var addressDraft by rememberSaveable { mutableStateOf<String?>(null) }
    var addressBase by rememberSaveable { mutableStateOf<String?>(null) }
    var portDraft by rememberSaveable { mutableStateOf<String?>(null) }
    var portBase by rememberSaveable { mutableStateOf<Int?>(null) }
    val passwordKey = passwordDraftKey(profile, epoch)
    val linkKey = linkDraftKey(profile, epoch)
    val passwordDraft = SecretDrafts.typed(passwordKey)
    val passwordBase = SecretDrafts.typed(draftBaseKey(passwordKey))
    val linkDraft = SecretDrafts.typed(linkKey)
    val linkBase = SecretDrafts.typed(draftBaseKey(linkKey))
    var required by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(validationRequest) { if (validationRequest > 0) required = true }

    val linkMode = auth.csqttLinkMode
    val address = addressDraft ?: peer
    val portText = portDraft ?: port.toString()
    val password = passwordDraft ?: auth.connectionPassword
    val linkText = linkDraft ?: auth.csqttLink
    val shownLink = remember(linkText) { parseCsqttLink(linkText) }
    val storedLink = remember(auth.csqttLink) { parseCsqttLink(auth.csqttLink) }

    val results = remember(resultsJson) { VkHashValidationCodec.decode(resultsJson) }
    val own = remember(hashesRaw) { storedHashes(hashesRaw) }
    val ownCount = remember(own, results) { VkHashValidationCodec.active(own, results).size }
    val ownerHashes = if (linkMode) ReceivedHashes.ownerSet(storedLink?.hashes.orEmpty(), received) else emptyList()
    val linkTokenActive = linkMode && storedLink?.vkAccessToken?.isNotEmpty() == true && received.isEmpty()
    // a participant's link pins the modes it cannot run with: its hashes rule «Авто ВК» out, its owner's token the
    // manual hashes
    val authMode = if (linkMode && ownerHashes.isNotEmpty() && savedAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS) {
        CsqttConstants.VkAuth.MODE_CALLS
    } else {
        savedAuthMode
    }
    val hashMode = if (linkTokenActive && savedHashMode == CsqttConstants.VkAutoHash.MODE_MANUAL) {
        CsqttConstants.VkAutoHash.MODE_AUTO_API
    } else {
        savedHashMode
    }
    val autoHash = authMode == CsqttConstants.VkAuth.MODE_AUTO_JS || hashMode != CsqttConstants.VkAutoHash.MODE_MANUAL
    val vkTokenActive = token.isNotBlank()
    val hashesReady = ConnectionStartPolicy.hasReadyVkCredentials(
        linkMode = linkMode,
        linkHashCount = ownerHashes.count { it !in removedOwner },
        linkTokenPresent = linkTokenActive,
        autoHashMode = autoHash,
        storedTokenPresent = vkTokenActive,
        manualHashCount = ownCount,
    )
    // running, starting, stopping or paused on Wi-Fi: what a change of the profile's kind would land in at a restart
    val busy = running || starting || stopping || paused
    val issues = tunnelIssues(
        linkMode = linkMode, address = address, port = portText, password = password, link = linkText,
        linkValid = shownLink != null, hashesReady = hashesReady, required = required,
    )
    val view = TunnelView(
        profile = profile, epoch = epoch, store = store, linkMode = linkMode, hashMode = hashMode, authMode = authMode,
        storedAuthMode = savedAuthMode, vkTokenActive = vkTokenActive, vkUserId = vkUserId, ownerHashes = ownerHashes,
        removedOwner = removedOwner, linkTokenActive = linkTokenActive, ownCount = ownCount,
        startHashes = hashesAStartUses(ownerHashes.filterNot { it in removedOwner } + own, results).size, results = results,
        running = running, issues = issues, riskAcknowledged = riskAcknowledged, tcpAcknowledged = tcpAcknowledged,
        poolSize = pool?.pool?.size,
    )

    // the modes a link pins are stored as they are in force, so the run reads what the screen shows
    LaunchedEffect(linkMode, ownerHashes.isNotEmpty(), linkTokenActive, savedAuthMode, savedHashMode) {
        if (!linkMode) return@LaunchedEffect
        when {
            ownerHashes.isNotEmpty() && savedAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS -> store.saveVkAuthMode(CsqttConstants.VkAuth.MODE_CALLS)
            linkTokenActive && savedHashMode == CsqttConstants.VkAutoHash.MODE_MANUAL -> store.saveVkHashMode(CsqttConstants.VkAutoHash.MODE_AUTO_API)
        }
    }
    // the password is typed inside the kit's field: the value it was typed over is noted once it has a draft
    if (passwordDraft != null && passwordBase == null) SideEffect { SecretDrafts.type(draftBaseKey(passwordKey), auth.connectionPassword) }
    // a draft goes once it no longer stands ([draftStands]): the store holds what it says — it was written when the
    // field was left — or holds what another screen put there since (a deploy's end writes the server it installed, a
    // link is taken by QR). From then on the field follows the store again
    LaunchedEffect(addressDraft, addressBase, peer) {
        if (addressDraft != null && !draftStands(addressDraft, addressBase, peer)) {
            addressDraft = null
            addressBase = null
        }
    }
    LaunchedEffect(portDraft, portBase, port) {
        val typed = portDraft
        if (typed != null && !draftStands(typed.toIntOrNull() ?: NO_PORT, portBase, port)) {
            portDraft = null
            portBase = null
        }
    }
    LaunchedEffect(passwordDraft, passwordBase, auth.connectionPassword) {
        if (passwordDraft != null && !draftStands(passwordDraft, passwordBase, auth.connectionPassword)) SecretDrafts.clear(passwordKey)
    }
    LaunchedEffect(linkDraft, linkBase, auth.csqttLink) {
        if (linkDraft != null && !draftStands(linkDraft, linkBase, auth.csqttLink)) SecretDrafts.clear(linkKey)
    }

    val modeAction = profileModeAction(emptyMode, field = if (linkMode) linkText else address, tunnelBusy = busy)
    val entry = entries.firstOrNull { it.index == profile }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.tab_tunnel), style = Type.title, color = Csq.colors.text,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp).semantics { heading() },
            )
            if (entry != null) ProfileContext(entry)
            if (required && issues.any) InvalidBanner()
            if (linkMode) {
                if (shownLink != null) LinkNoteCard(view)
                LinkCard(
                    view = view, linkText = linkText, storedLink = auth.csqttLink, shown = shownLink, modeAction = modeAction,
                    onLinkChange = {
                        if (SecretDrafts.typed(linkKey) == null) SecretDrafts.type(draftBaseKey(linkKey), auth.csqttLink)
                        SecretDrafts.type(linkKey, it.filterNot(Char::isWhitespace))
                    },
                )
            } else {
                OwnCard(
                    view = view, address = address, port = portText, storedPassword = auth.connectionPassword, modeAction = modeAction,
                    onAddress = {
                        if (addressDraft == null) addressBase = peer
                        addressDraft = it.filterNot(Char::isWhitespace)
                    },
                    onPort = {
                        if (portDraft == null) portBase = port
                        portDraft = it.filter(Char::isDigit).take(5)
                    },
                    // a commit writes only a draft that still stands: one the store already holds, or one typed over
                    // a value the store no longer holds, is dropped and nothing is written
                    commitAddress = {
                        val text = addressDraft
                        when {
                            text == null -> Unit
                            !draftStands(text, addressBase, peer) -> {
                                addressDraft = null
                                addressBase = null
                            }
                            else -> {
                                // «203.0.113.5:46000» is the two it holds, each in its own field
                                val apart = splitHostPort(text)
                                if (apart != null) {
                                    addressDraft = apart.first
                                    portDraft = null
                                    portBase = null
                                }
                                TunnelManager.scope.launch { store.saveFormAddress(apart?.first ?: text, apart?.second, epoch) }
                            }
                        }
                    },
                    commitPort = {
                        val text = portDraft
                        val typed = text?.toIntOrNull()
                        when {
                            text == null -> Unit
                            // an emptied field left as it is means "as it was"
                            text.isEmpty() || !draftStands(typed ?: NO_PORT, portBase, port) -> {
                                portDraft = null
                                portBase = null
                            }
                            typed != null && typed in 1..65535 -> TunnelManager.scope.launch { store.saveFormPort(typed, epoch) }
                        }
                    },
                )
            }
            MoreCard(view, open = moreOpen, onOpen = onMoreOpen)
        }
    }
}

/**
 * Runs [commit] when the field this is on is left: its focus lost, the app left, or the screen gone. A field of the
 * tunnel form is stored then and not while it is typed: a half-typed address or password in the store is what a running
 * tunnel's next restart would connect with. On the app's scope, so the last field typed before a tab change is stored.
 */
@Composable
internal fun Modifier.commitWhenLeft(commit: () -> Unit): Modifier {
    val run by rememberUpdatedState(commit)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) run() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            run()
        }
    }
    return onFocusChanged { if (!it.hasFocus) run() }
}

/** The profile the tab is on, and the way to another (the canvas's context row): it opens «Профили». */
@Composable
private fun ProfileContext(entry: ProfileEntry) {
    val colors = Csq.colors
    val nav = LocalNav.current
    val host = remember(entry.link) { parseCsqttLink(entry.link)?.host.orEmpty() }
    val line = when (entry.kind) {
        ProfileKind.OwnServer ->
            if (entry.peer.isEmpty()) stringResource(R.string.tunnel_ctx_own_empty) else stringResource(R.string.tunnel_ctx_own, entry.peer)
        ProfileKind.Link ->
            if (host.isEmpty()) stringResource(R.string.tunnel_ctx_link_empty) else stringResource(R.string.tunnel_ctx_link, host)
        ProfileKind.Subscription ->
            if (entry.subscriptionTitle.isEmpty()) stringResource(R.string.tunnel_ctx_sub_plain)
            else stringResource(R.string.tunnel_ctx_sub, entry.subscriptionTitle)
    }
    val description = stringResource(R.string.tunnel_ctx_description, entry.name)
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).clip(Shape.context).background(colors.surface)
            .border(1.dp, colors.line, Shape.context)
            .clickable(role = Role.Button, onClickLabel = description) { nav.open(Routes.profiles()) }
            .padding(start = 14.dp, top = 10.dp, end = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (entry.kind == ProfileKind.OwnServer) Icons.server else Icons.link, contentDescription = null,
            modifier = Modifier.size(22.dp), tint = colors.muted,
        )
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = Type.bodyStrong, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = Type.sub, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.chevrons, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.muted)
    }
}

/** Said over the form once a start was refused for what it holds (the canvas, F-TunnelInvalid). */
@Composable
private fun InvalidBanner() {
    val colors = Csq.colors
    Row(
        Modifier.fillMaxWidth().clip(Shape.card).background(colors.dangerSoft).padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.alert, contentDescription = null, modifier = Modifier.size(22.dp), tint = colors.danger)
        Text(stringResource(R.string.tunnel_invalid_banner), style = Type.fact, color = colors.text)
    }
}

/** The user's own server: where it is, the password its panel shows, and the hashes of the calls it is reached through. */
@Composable
private fun OwnCard(
    view: TunnelView,
    address: String,
    port: String,
    storedPassword: String,
    modeAction: ProfileModeAction?,
    onAddress: (String) -> Unit,
    onPort: (String) -> Unit,
    commitAddress: () -> Unit,
    commitPort: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val key = passwordDraftKey(view.profile, view.epoch)
    val store = view.store
    val epoch = view.epoch
    val done = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false)
    Card {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(
                stringResource(R.string.tunnel_address), address, onAddress,
                modifier = Modifier.weight(1.7f).commitWhenLeft(commitAddress),
                placeholder = stringResource(R.string.tunnel_address_hint),
                error = when (view.issues.address) {
                    AddressIssue.Missing -> stringResource(R.string.tunnel_address_missing)
                    AddressIssue.HasPort -> stringResource(R.string.tunnel_address_has_port)
                    AddressIssue.Local -> stringResource(R.string.tunnel_address_local)
                    null -> null
                },
                keyboard = done.copy(keyboardType = KeyboardType.Uri), onDone = { focus.clearFocus() },
            )
            Field(
                stringResource(R.string.tunnel_port), port, onPort,
                modifier = Modifier.weight(1f).commitWhenLeft(commitPort),
                placeholder = CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT.toString(),
                error = if (view.issues.port) stringResource(R.string.tunnel_port_wrong) else null,
                keyboard = done.copy(keyboardType = KeyboardType.Number), onDone = { focus.clearFocus() },
            )
        }
        SecretField(
            stringResource(R.string.tunnel_password), key, stored = storedPassword,
            modifier = Modifier.commitWhenLeft {
                val typed = SecretDrafts.typed(key)
                when {
                    typed == null -> Unit
                    // an emptied field left as it is means "as it was": a stored password is never erased by a field.
                    // And a draft that no longer stands is dropped, not written (see the address)
                    typed.isEmpty() || !draftStands(typed, SecretDrafts.typed(draftBaseKey(key)), storedPassword) -> SecretDrafts.clear(key)
                    isValidTunnelPassword(typed) -> TunnelManager.scope.launch { store.saveFormPassword(typed, epoch) }
                }
            },
            placeholder = stringResource(R.string.tunnel_password_hint),
            error = when (view.issues.password) {
                PasswordIssue.Missing -> stringResource(R.string.tunnel_password_missing)
                PasswordIssue.Rule -> stringResource(R.string.tunnel_password_rule)
                null -> null
            },
            onDone = { focus.clearFocus() },
            // the password holds no space: a pasted one's stray space or line end is dropped, as the address's is
            accept = { it.filterNot(Char::isWhitespace) },
        )
        Hairline()
        HashControls(view)
        if (modeAction == ProfileModeAction.USE_LINK) ModeAction(view, R.string.tunnel_use_link, linkMode = true)
    }
}

/** A participant's profile: the link it connects by, what the link says, and the hashes it brings. */
@Composable
private fun LinkCard(
    view: TunnelView,
    linkText: String,
    storedLink: String,
    shown: CsqttLink?,
    modeAction: ProfileModeAction?,
    onLinkChange: (String) -> Unit,
) {
    val colors = Csq.colors
    val nav = LocalNav.current
    val focus = LocalFocusManager.current
    val context = LocalContext.current.applicationContext
    val messages = LocalOwners.current.messages
    val store = view.store
    val profile = view.profile
    val epoch = view.epoch
    val key = linkDraftKey(profile, epoch)
    // null until the store has answered: the switch is drawn as stored
    val storedAccept by store.acceptServerHashes.collectAsStateWithLifecycle(initialValue = null)
    val storedReceivedAt by store.receivedHashesAt.collectAsStateWithLifecycle(initialValue = null)
    val commitLink = {
        val typed = SecretDrafts.typed(key)
        when {
            typed == null -> Unit
            // a draft that no longer stands — the link the profile already holds, pasted or typed again, among them —
            // is dropped and nothing is written: an import would put back the owner's hashes that were taken out and
            // drop the set the server sent
            !draftStands(typed, SecretDrafts.typed(draftBaseKey(key)), storedLink) -> SecretDrafts.clear(key)
            // M-72: a link is written whole or not at all; an emptied field clears it
            typed.isEmpty() || parseCsqttLink(typed) != null -> TunnelManager.scope.launch { store.saveFormLink(typed, epoch) }
        }
        Unit
    }
    Card {
        Field(
            stringResource(R.string.tunnel_link), linkText, onLinkChange,
            modifier = Modifier.commitWhenLeft(commitLink),
            placeholder = stringResource(R.string.tunnel_link_hint), mono = true,
            error = when (view.issues.link) {
                LinkIssue.Missing -> stringResource(R.string.tunnel_link_missing)
                LinkIssue.Invalid -> stringResource(R.string.tunnel_link_invalid)
                null -> null
            },
            keyboard = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
            onDone = { focus.clearFocus() },
            trailing = {
                Row {
                    IconBtn(Icons.clipboard, stringResource(R.string.action_paste), {
                        // a paste is the whole link at once: stored there and then
                        val copied = copiedText(context)
                        if (parseCsqttLink(copied) == null) {
                            messages.show(context.getString(R.string.add_clipboard_empty))
                        } else {
                            if (SecretDrafts.typed(key) == null) SecretDrafts.type(draftBaseKey(key), storedLink)
                            SecretDrafts.type(key, copied)
                            commitLink()
                        }
                    }, small = true, tint = colors.muted)
                    IconBtn(Icons.qr, stringResource(R.string.tunnel_link_scan), { nav.open(Routes.qrScanner()) }, small = true, tint = colors.muted)
                }
            },
        )
        if (shown != null) {
            Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tunnel_server), style = Type.fact, color = colors.muted, modifier = Modifier.weight(1f))
                Text("${shown.host}:${shown.port}", style = Type.mono, color = colors.text)
            }
        }
        if (view.ownerHashes.isNotEmpty()) {
            // the owner's hashes, never shown, with the participant's own beside them, in one sheet
            NavRow(
                stringResource(R.string.tunnel_owner_hashes),
                stringResource(R.string.tunnel_owner_hashes_sub, view.ownerInUse, view.ownCount),
                danger = view.issues.hashes,
            ) { nav.open(Routes.hashes(profile)) }
        } else if (!view.linkTokenActive && shown != null) {
            Hairline()
            HashControls(view)
        }
        val accept = storedAccept
        val receivedAt = storedReceivedAt
        if (shown != null && accept != null && receivedAt != null) {
            Hairline()
            SwitchRow(
                stringResource(R.string.tunnel_accept_hashes), accept,
                { TunnelManager.scope.launch { store.saveAcceptServerHashes(it) } },
                sub = when {
                    !accept -> stringResource(R.string.tunnel_accept_off)
                    receivedAt > 0L -> stringResource(
                        R.string.tunnel_accept_at, remember(receivedAt) { noticeTime(receivedAt) },
                        pluralText(R.plurals.hashes_count, view.ownerHashes.size),
                    )
                    else -> stringResource(R.string.tunnel_accept_wait)
                },
            )
            Text(stringResource(R.string.tunnel_accept_hint), style = Type.sub, color = colors.muted)
        }
        if (modeAction == ProfileModeAction.ENTER_SERVER) ModeAction(view, R.string.tunnel_enter_server, linkMode = false)
    }
}

/** What a link is short of, said before the link itself (the canvas, F-TunnelLinkStates). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinkNoteCard(view: TunnelView) {
    val colors = Csq.colors
    val nav = LocalNav.current
    val context = LocalContext.current.applicationContext
    val messages = LocalOwners.current.messages
    val note = linkNote(
        view.ownerHashes, view.removedOwner, view.results, view.linkTokenActive,
        ownReady = view.ownCount > 0 || (view.autoHash && view.vkTokenActive),
    ) ?: return
    val (title, chip, kind, text) = when (note) {
        LinkNote.OwnerToken -> Quad(R.string.tunnel_note_token_title, R.string.tunnel_from_link, ChipKind.Accent, R.string.tunnel_note_token)
        LinkNote.NeedsHashes -> Quad(R.string.tunnel_note_need_title, R.string.tunnel_note_need_chip, ChipKind.Warn, R.string.tunnel_note_need)
        LinkNote.Outdated -> Quad(R.string.tunnel_note_old_title, R.string.tunnel_note_old_chip, ChipKind.Danger, R.string.tunnel_note_old)
    }
    Card(gap = 6.dp) {
        FlowRow(
            Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(title), style = Type.bodyStrong, color = colors.text)
            Chip(stringResource(chip), kind = kind)
        }
        Text(stringResource(text), style = Type.sub, color = colors.text)
        if (note != LinkNote.OwnerToken) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn(stringResource(R.string.tunnel_note_own_hashes), { nav.open(Routes.hashes(view.profile)) }, small = true)
                if (note == LinkNote.NeedsHashes) {
                    Btn(
                        stringResource(R.string.tunnel_note_sign_in), { openVkSignIn(context, nav, messages, view.profile) },
                        kind = BtnKind.Tonal, small = true,
                    )
                }
            }
        }
    }
}

private data class Quad(val title: Int, val chip: Int, val kind: ChipKind, val text: Int)

/**
 * Where the hashes of the VK calls come from, and the way to them: the user's own in the manual mode, a VK account in
 * the two that make them; for the user's own server in «Авто API», the pool of calls the server hands out.
 */
@Composable
private fun HashControls(view: TunnelView) {
    val colors = Csq.colors
    val nav = LocalNav.current
    val manual = CsqttConstants.VkAutoHash.MODE_MANUAL
    SettingRow(stringResource(R.string.tunnel_hash_mode), onInfo = { nav.open(Routes.info(InfoTopic.HASH_MODE)) }) {
        Select(
            selected = view.hashMode,
            options = listOf(
                manual to stringResource(R.string.tunnel_hash_manual),
                CsqttConstants.VkAutoHash.MODE_AUTO_API to stringResource(R.string.tunnel_hash_auto_api),
                CsqttConstants.VkAutoHash.MODE_AUTO_JS to stringResource(R.string.tunnel_hash_auto_vk),
            ),
            onSelect = { requestHashMode(view, nav, it) },
            description = stringResource(R.string.tunnel_hash_mode),
        )
    }
    if (!view.autoHash) {
        Btn(
            if (view.ownCount > 0) stringResource(R.string.tunnel_hashes_button, view.ownCount, OWN_HASH_FIELDS)
            else stringResource(R.string.tunnel_hashes_add),
            { nav.open(Routes.hashes(view.profile)) },
            modifier = Modifier.fillMaxWidth(), icon = Icons.link, kind = if (view.issues.hashes) BtnKind.Danger else BtnKind.Plain,
        )
        if (view.issues.hashes) Text(stringResource(R.string.tunnel_hashes_missing), style = Type.sub, color = colors.danger)
    } else {
        NavRow(
            stringResource(R.string.tunnel_account),
            when {
                view.issues.hashes -> stringResource(R.string.tunnel_account_missing)
                !view.vkTokenActive -> stringResource(R.string.tunnel_account_off)
                view.vkUserId.isNotEmpty() -> stringResource(R.string.tunnel_account_on, view.vkUserId)
                else -> stringResource(R.string.tunnel_account_on_plain)
            },
            danger = view.issues.hashes,
        ) { nav.open(Routes.vkAccounts(view.profile)) }
        if (view.poolSize != null && view.hashMode == CsqttConstants.VkAutoHash.MODE_AUTO_API) {
            Btn(
                stringResource(R.string.tunnel_pool_button, view.poolSize), { nav.open(Routes.vkPool(view.profile)) },
                modifier = Modifier.fillMaxWidth(), icon = Icons.phone,
            )
        }
    }
}

/** «Авто ВК» is asked about before it is taken, once (the canvas, F-RiskDialog); any other mode is stored at once. */
private fun requestHashMode(view: TunnelView, nav: NavModel, mode: String) {
    val nextAuth = vkAuthModeForHashMode(mode, view.storedAuthMode)
    if (shouldConfirmAutoJsMode(view.storedAuthMode, nextAuth, view.riskAcknowledged)) {
        nav.open(Routes.risk(view.profile, RiskKind.HASH_MODE, mode))
    } else {
        TunnelManager.scope.launch { view.store.saveVkHashMode(mode) }
    }
}

/** The empty profile's one way to the other kind: a text action under the field it is the alternative to. */
@Composable
private fun ModeAction(view: TunnelView, text: Int, linkMode: Boolean) {
    TextBtn(stringResource(text), {
        // checked again at the tap; the store checks again that the profile is still empty
        val busy = TunnelManager.running.value || TunnelManager.starting.value || TunnelManager.stopping.value ||
            TunnelManager.autoPausedForWifi.value
        if (!busy) TunnelManager.scope.launch { view.store.switchEmptyProfileMode(linkMode) }
    })
}

@Composable
internal fun Hairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Csq.colors.lineSoft))
}

/** A row that leads on: what it opens, a line about it, a chevron. [danger] marks what a refused start asked for. */
@Composable
internal fun NavRow(title: String, sub: String?, danger: Boolean = false, onClick: () -> Unit) {
    val colors = Csq.colors
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body, color = colors.text)
            if (sub != null) Text(sub, style = Type.sub, color = if (danger) colors.danger else colors.muted)
        }
        Icon(Icons.chevronRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.muted)
    }
}

/**
 * Opens the VK sign-in for [profile]. Not while the tunnel runs, starts or stops (M-79); the last session's cookies go
 * first, so that the sign-in can be to another account.
 */
internal fun openVkSignIn(context: Context, nav: NavModel, messages: com.csqtt.client.Messages, profile: Int) {
    if (vkChangeBlocked()) {
        messages.show(context.getString(R.string.vk_change_running))
    } else {
        com.csqtt.client.VkAuthWebViewManager.clearVkSessionCookies { nav.open(Routes.vkSignIn(profile)) }
    }
}
