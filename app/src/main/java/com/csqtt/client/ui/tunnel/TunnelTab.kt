package com.csqtt.client.ui.tunnel

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
import com.csqtt.client.RunKey
import com.csqtt.client.RunUses
import com.csqtt.client.RunView
import com.csqtt.client.SettingsStore
import com.csqtt.client.TunnelAuthSnapshot
import com.csqtt.client.TunnelIssues
import com.csqtt.client.TunnelManager
import com.csqtt.client.VkHashValidationCodec
import com.csqtt.client.VkHashValidationStatus
import com.csqtt.client.draftLeft
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
import com.csqtt.client.ui.components.NowLine
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
import com.csqtt.client.ui.shell.LocalRun
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

/**
 * Where the draft an address or a port field left with is noted ([draftLeft]), under the profile and the epoch of its
 * form. The draft itself is in the tab's saved state, and that is written before the field's last commit runs when the
 * tab goes: the note is how that copy is known for what it is when it comes back. The notes are kept by the shell, in
 * the screen's saved state as the drafts are, so the two are restored together after the process was killed.
 */
internal fun addressLeftKey(profile: Int, epoch: Int) = "tunnel:$profile:$epoch:address:left"

internal fun portLeftKey(profile: Int, epoch: Int) = "tunnel:$profile:$epoch:port:left"

/** The epoch of the form a note was made for, or null for a key that is no note's. */
internal fun leftNoteEpoch(key: String): Int? = key.split(':').takeIf { it.size == 5 && it[0] == "tunnel" && it[4] == "left" }?.get(2)?.toIntOrNull()

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
    /**
     * The run, when the tunnel is on for this profile, and null when it is not (card p3-apply-bar). What is changed
     * here is read when a run starts, so under a running tunnel it is for the next connection: nothing is held (the
     * operator, 2026-11-27), the bar over the tabs says what applies the changes, and a row whose choice the run has
     * not taken says what it uses now ([now]). A change made for another profile is that profile's own next start, with
     * nothing to say.
     */
    val run: RunView?,
    val issues: TunnelIssues,
    val riskAcknowledged: Boolean,
    /** The calls in the owner's pool; null when this profile's run uses none. */
    val poolSize: Int?,
) {
    val autoHash: Boolean get() = authMode == CsqttConstants.VkAuth.MODE_AUTO_JS || hashMode != CsqttConstants.VkAutoHash.MODE_MANUAL
    val ownerInUse: Int get() = ownerHashes.count { it !in removedOwner }

    /** The tunnel is on for this profile. */
    val runsHere: Boolean get() = run != null

    /** The run has not taken this profile's [key]: it waits for a reconnect. */
    fun waits(key: RunKey): Boolean = run?.let { key in it.keys } == true

    /** What the run uses of [key]'s setting, when it has not taken the profile's — to be said as «Сейчас: …» — else null. */
    fun <T> now(key: RunKey, value: (RunUses) -> T): T? = run?.takeIf { key in it.keys }?.let { value(it.uses) }
}

/**
 * «Туннель» (the canvas, F-Tunnel and its states): the open profile's connection — its own server, or the link it
 * connects by — and under «Дополнительно» how the tunnel is made. There is no «Сохранить»: a field is stored when it is
 * left, a choice when it is made. [epoch] is the store's count of replaced profiles the tab was mounted under (the tab
 * is mounted anew when it moves). [left] holds the notes of the drafts that left with their screen ([addressLeftKey]),
 * kept by the shell. [validationRequest] grows each time a start was refused for this profile's settings (0: never for
 * this one): from then on what the form lacks is said, not only what it holds wrongly.
 */
@Composable
internal fun TunnelTab(
    auth: TunnelAuthSnapshot,
    epoch: Int,
    left: MutableMap<String, String>,
    validationRequest: Int,
    moreOpen: Boolean,
    onMoreOpen: (Boolean) -> Unit,
) {
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
    val pool by store.vkPoolState.collectAsStateWithLifecycle(initialValue = null)
    val running by TunnelManager.running.collectAsStateWithLifecycle()
    // the run, when it is this profile's: read from the shell's one state of it, so the form follows the run's profile
    // whenever that changes
    val run = LocalRun.current?.takeIf { it.profile == profile }

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
    val addressNote = addressLeftKey(profile, epoch)
    val portNote = portLeftKey(profile, epoch)
    val addressLeft = left[addressNote]
    val portLeft = left[portNote]
    val passwordKey = passwordDraftKey(profile, epoch)
    val linkKey = linkDraftKey(profile, epoch)
    val passwordDraft = SecretDrafts.typed(passwordKey)
    val passwordBase = SecretDrafts.typed(draftBaseKey(passwordKey))
    val linkDraft = SecretDrafts.typed(linkKey)
    val linkBase = SecretDrafts.typed(draftBaseKey(linkKey))
    var required by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(validationRequest) { if (validationRequest > 0) required = true }

    val linkMode = auth.csqttLinkMode
    // a draft that left with its screen, written then, is not shown when the screen is back (draftLeft)
    val address = addressDraft?.takeUnless { draftLeft(it, addressLeft) } ?: peer
    val portText = portDraft?.takeUnless { draftLeft(it, portLeft) } ?: port.toString()
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
    val issues = tunnelIssues(
        linkMode = linkMode, address = address, port = portText, password = password, link = linkText,
        linkValid = shownLink != null, hashesReady = hashesReady, required = required,
    )
    val view = TunnelView(
        profile = profile, epoch = epoch, store = store, linkMode = linkMode, hashMode = hashMode, authMode = authMode,
        storedAuthMode = savedAuthMode, vkTokenActive = vkTokenActive, vkUserId = vkUserId, ownerHashes = ownerHashes,
        removedOwner = removedOwner, linkTokenActive = linkTokenActive, ownCount = ownCount,
        startHashes = hashesAStartUses(ownerHashes.filterNot { it in removedOwner } + own, results).size, results = results,
        running = running, run = run,
        issues = issues, riskAcknowledged = riskAcknowledged,
        poolSize = pool?.pool?.size,
    )

    // The modes a link pins are stored as they are in force, so the run reads what the screen shows. Not while the
    // tunnel is on for this profile: a start corrects them itself, and a correction written under a running tunnel
    // would read as a change of the user's that waits for a reconnect (card p3-apply-bar)
    LaunchedEffect(linkMode, ownerHashes.isNotEmpty(), linkTokenActive, savedAuthMode, savedHashMode, run != null) {
        if (!linkMode || run != null) return@LaunchedEffect
        when {
            ownerHashes.isNotEmpty() && savedAuthMode == CsqttConstants.VkAuth.MODE_AUTO_JS -> store.saveVkAuthMode(CsqttConstants.VkAuth.MODE_CALLS)
            linkTokenActive && savedHashMode == CsqttConstants.VkAutoHash.MODE_MANUAL -> store.saveVkHashMode(CsqttConstants.VkAutoHash.MODE_AUTO_API)
        }
    }
    // the password is typed inside the kit's field: the value it was typed over is noted once it has a draft
    if (passwordDraft != null && passwordBase == null) SideEffect { SecretDrafts.type(draftBaseKey(passwordKey), auth.connectionPassword) }
    // a draft goes once it no longer stands ([draftStands]): the store holds what it says — it was written when the
    // field was left — or holds what another screen put there since (a deploy's end writes the server it installed, a
    // link is taken by QR). And it goes when it left with its screen ([draftLeft]): written then, it is not the field's
    // any more, whatever the store holds by now. From then on the field follows the store again
    LaunchedEffect(addressDraft, addressBase, peer, addressLeft) {
        if (addressDraft != null && (draftLeft(addressDraft, addressLeft) || !draftStands(addressDraft, addressBase, peer))) {
            addressDraft = null
            addressBase = null
        }
        if (addressDraft == null && addressLeft != null) left.remove(addressNote)
    }
    LaunchedEffect(portDraft, portBase, port, portLeft) {
        val typed = portDraft
        if (typed != null && (draftLeft(typed, portLeft) || !draftStands(typed.toIntOrNull() ?: NO_PORT, portBase, port))) {
            portDraft = null
            portBase = null
        }
        if (portDraft == null && portLeft != null) left.remove(portNote)
    }
    LaunchedEffect(passwordDraft, passwordBase, auth.connectionPassword) {
        if (passwordDraft != null && !draftStands(passwordDraft, passwordBase, auth.connectionPassword)) SecretDrafts.clear(passwordKey)
    }
    LaunchedEffect(linkDraft, linkBase, auth.csqttLink) {
        if (linkDraft != null && !draftStands(linkDraft, linkBase, auth.csqttLink)) SecretDrafts.clear(linkKey)
    }

    val modeAction = profileModeAction(emptyMode, field = if (linkMode) linkText else address)
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
            // under a running tunnel the marks are a reconnect's: «Переподключить» was refused for what the form holds
            if (required && issues.any) InvalidBanner(reconnect = view.runsHere)
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
                    // an edit is typed over what the field shows — the stored value, also where a draft that left
                    // with its screen is still on its way out — and is a new draft: no write was issued for it
                    onAddress = {
                        if (addressDraft == null || draftLeft(addressDraft, addressLeft)) addressBase = peer
                        addressDraft = it.filterNot(Char::isWhitespace)
                        left.remove(addressNote)
                    },
                    onPort = {
                        if (portDraft == null || draftLeft(portDraft, portLeft)) portBase = port
                        portDraft = it.filter(Char::isDigit).take(5)
                        left.remove(portNote)
                    },
                    // a commit writes only a draft that still stands, and writes it once: one the store already holds,
                    // one typed over a value the store no longer holds and one that left with its screen before are
                    // dropped, and nothing is written. When it is the screen that goes ([leaving]) the draft is noted
                    // as it stands, written or dropped: it is dealt with, and the copy of it in the tab's saved state
                    // does not come back with the screen
                    commitAddress = { leaving ->
                        val text = addressDraft
                        when {
                            text == null -> Unit
                            draftLeft(text, left[addressNote]) || !draftStands(text, addressBase, peer) -> {
                                if (leaving) left[addressNote] = text
                                addressDraft = null
                                addressBase = null
                            }
                            else -> {
                                // «203.0.113.5:46000» is the two it holds, each in its own field
                                val apart = splitHostPort(text)
                                if (leaving) {
                                    left[addressNote] = text
                                    // the port it brings is the port from now on: a draft of the port field goes with it
                                    if (apart != null) portDraft?.let { left[portNote] = it }
                                } else if (apart != null) {
                                    addressDraft = apart.first
                                    portDraft = null
                                    portBase = null
                                }
                                TunnelManager.scope.launch { store.saveFormAddress(apart?.first ?: text, apart?.second, epoch) }
                            }
                        }
                    },
                    commitPort = { leaving ->
                        val text = portDraft
                        val typed = text?.toIntOrNull()
                        when {
                            text == null -> Unit
                            // an emptied field left as it is means "as it was"
                            text.isEmpty() || draftLeft(text, left[portNote]) ||
                                !draftStands(typed ?: NO_PORT, portBase, port) -> {
                                if (leaving) left[portNote] = text
                                portDraft = null
                                portBase = null
                            }
                            typed != null && typed in 1..65535 -> {
                                if (leaving) left[portNote] = text
                                TunnelManager.scope.launch { store.saveFormPort(typed, epoch) }
                            }
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
 * [commit] is told whether it is the screen that goes (the app left, the tab or the screen gone) and not the focus
 * alone: a draft written then is written for good, and is not the field's when the screen is back ([draftLeft]).
 */
@Composable
internal fun Modifier.commitWhenLeft(commit: (leaving: Boolean) -> Unit): Modifier {
    val run by rememberUpdatedState(commit)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) run(true) }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            run(true)
        }
    }
    return onFocusChanged { if (!it.hasFocus) run(false) }
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
private fun InvalidBanner(reconnect: Boolean) {
    val colors = Csq.colors
    Row(
        Modifier.fillMaxWidth().clip(Shape.card).background(colors.dangerSoft).padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.alert, contentDescription = null, modifier = Modifier.size(22.dp), tint = colors.danger)
        Text(
            stringResource(if (reconnect) R.string.tunnel_reconnect_refused else R.string.tunnel_invalid_banner),
            style = Type.fact, color = colors.text,
        )
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
    commitAddress: (leaving: Boolean) -> Unit,
    commitPort: (leaving: Boolean) -> Unit,
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
                now = view.now(RunKey.ADDRESS) { it.address },
            )
            Field(
                stringResource(R.string.tunnel_port), port, onPort,
                modifier = Modifier.weight(1f).commitWhenLeft(commitPort),
                placeholder = CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT.toString(),
                error = if (view.issues.port) stringResource(R.string.tunnel_port_wrong) else null,
                keyboard = done.copy(keyboardType = KeyboardType.Number), onDone = { focus.clearFocus() },
                now = view.now(RunKey.PORT) { it.port.toString() },
            )
        }
        SecretField(
            stringResource(R.string.tunnel_password), key, stored = storedPassword,
            modifier = Modifier.commitWhenLeft { leaving ->
                val typed = SecretDrafts.typed(key)
                when {
                    typed == null -> Unit
                    // an emptied field left as it is means "as it was": a stored password is never erased by a field.
                    // And a draft that no longer stands is dropped, not written (see the address)
                    typed.isEmpty() || !draftStands(typed, SecretDrafts.typed(draftBaseKey(key)), storedPassword) -> SecretDrafts.clear(key)
                    isValidTunnelPassword(typed) -> {
                        TunnelManager.scope.launch { store.saveFormPassword(typed, epoch) }
                        // written as its screen goes, the draft goes with it (see draftLeft): kept in memory, it stood
                        // again once the store was put back to the password it was typed over, and was written over it
                        if (leaving) SecretDrafts.clear(key)
                    }
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
            // a password is never shown: the tunnel is on the one before
            now = if (view.waits(RunKey.PASSWORD)) stringResource(R.string.now_password) else null,
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
    val commitLink = { leaving: Boolean ->
        val typed = SecretDrafts.typed(key)
        when {
            typed == null -> Unit
            // a draft that no longer stands — the link the profile already holds, pasted or typed again, among them —
            // is dropped and nothing is written: an import would put back the owner's hashes that were taken out and
            // drop the set the server sent
            !draftStands(typed, SecretDrafts.typed(draftBaseKey(key)), storedLink) -> SecretDrafts.clear(key)
            // M-72: a link is written whole or not at all; an emptied field clears it
            typed.isEmpty() || parseCsqttLink(typed) != null -> {
                TunnelManager.scope.launch { store.saveFormLink(typed, epoch) }
                // written as its screen goes, the draft goes with it (see the password)
                if (leaving) SecretDrafts.clear(key)
            }
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
            // a link holds the password: it is named, not shown
            now = if (view.waits(RunKey.LINK)) stringResource(R.string.now_link) else null,
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
                            commitLink(false)
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
                now = view.hashesNow(),
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
            view.now(RunKey.ACCEPT_HASHES) { it.acceptServerHashes }?.let { NowLine(stringResource(if (it) R.string.now_on else R.string.now_off)) }
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
                        stringResource(R.string.tunnel_note_sign_in), { openVkSignIn(nav, view.profile) },
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
    // the mode the run is in, where the mode in force here is another
    view.now(RunKey.HASH_MODE) { it.hashMode }?.takeIf { it != view.hashMode }?.let { NowLine(hashModeName(it), end = true) }
    if (!view.autoHash) {
        Btn(
            if (view.ownCount > 0) stringResource(R.string.tunnel_hashes_button, view.ownCount, OWN_HASH_FIELDS)
            else stringResource(R.string.tunnel_hashes_add),
            { nav.open(Routes.hashes(view.profile)) },
            modifier = Modifier.fillMaxWidth(), icon = Icons.link, kind = if (view.issues.hashes) BtnKind.Danger else BtnKind.Plain,
        )
        if (view.issues.hashes) Text(stringResource(R.string.tunnel_hashes_missing), style = Type.sub, color = colors.danger)
        view.hashesNow()?.let { NowLine(it) }
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
            now = view.now(RunKey.ACCOUNT) { it }?.let { runAccountName(it) },
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
    // the store checks again that the profile is still empty
    TextBtn(stringResource(text), { TunnelManager.scope.launch { view.store.switchEmptyProfileMode(linkMode) } })
}

@Composable
internal fun Hairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Csq.colors.lineSoft))
}

/**
 * A row that leads on: what it opens, a line about it, a chevron. [danger] marks what a refused start asked for. [now]:
 * what the running tunnel uses of what the row leads to, where that is not what the row says.
 */
@Composable
internal fun NavRow(title: String, sub: String?, danger: Boolean = false, now: String? = null, onClick: () -> Unit) {
    val colors = Csq.colors
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body, color = colors.text)
            if (sub != null) Text(sub, style = Type.sub, color = if (danger) colors.danger else colors.muted)
            if (now != null) NowLine(now)
        }
        Icon(Icons.chevronRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.muted)
    }
}

/** A hash mode's name, as its row's choices name it. */
@Composable
internal fun hashModeName(mode: String): String = stringResource(
    when (mode) {
        CsqttConstants.VkAutoHash.MODE_AUTO_API -> R.string.tunnel_hash_auto_api
        CsqttConstants.VkAutoHash.MODE_AUTO_JS -> R.string.tunnel_hash_auto_vk
        else -> R.string.tunnel_hash_manual
    },
)

/**
 * The VK account the run is on, for «Сейчас: …»: its id when it is one of the signed-in accounts; a token pasted by
 * hand names none; a pool can serve with no account at all.
 */
@Composable
internal fun runAccountName(uses: RunUses): String = when {
    !uses.hasAccount -> stringResource(R.string.now_account_none)
    uses.accountId.isEmpty() -> stringResource(R.string.now_account_old)
    else -> stringResource(R.string.now_account_id, uses.accountId)
}

/** «Сейчас: прежние хеши», where the profile's own hashes or the owner's taken out are not the ones the run started with. */
@Composable
private fun TunnelView.hashesNow(): String? =
    if (waits(RunKey.OWN_HASHES) || waits(RunKey.OWNER_HASHES)) stringResource(R.string.now_hashes) else null

/**
 * Opens the VK sign-in for [profile]; the last session's cookies go first, so that the sign-in can be to another
 * account. With the tunnel on too: a run makes its VK calls with the token it started with, never with these cookies
 * (card p3-vk-run-account), and the account signed in to is stored for the next connection.
 */
internal fun openVkSignIn(nav: NavModel, profile: Int) {
    com.csqtt.client.VkAuthWebViewManager.clearVkSessionCookies { nav.open(Routes.vkSignIn(profile)) }
}
