package com.csqtt.client.ui.tunnel

import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.PanelApi
import com.csqtt.client.R
import com.csqtt.client.RunKey
import com.csqtt.client.TunnelManager
import com.csqtt.client.VkAuthPayload
import com.csqtt.client.VkAuthPhase
import com.csqtt.client.VkAuthSession
import com.csqtt.client.VkAuthWebViewManager
import com.csqtt.client.VkHashValidationCodec
import com.csqtt.client.VkHashValidationStatus
import com.csqtt.client.VkPoolHandOver
import com.csqtt.client.VkPoolState
import com.csqtt.client.VkTokenParse
import com.csqtt.client.handOverNoticeLine
import com.csqtt.client.handOverNoticeText
import com.csqtt.client.ui.components.Btn
import com.csqtt.client.ui.components.BtnKind
import com.csqtt.client.ui.components.Card
import com.csqtt.client.ui.components.Cell
import com.csqtt.client.ui.components.Chip
import com.csqtt.client.ui.components.ChipKind
import com.csqtt.client.ui.components.DialogFrame
import com.csqtt.client.ui.components.Field
import com.csqtt.client.ui.components.IconBtn
import com.csqtt.client.ui.components.NowLine
import com.csqtt.client.ui.components.Sheet
import com.csqtt.client.ui.components.TextBtn
import com.csqtt.client.ui.design.Csq
import com.csqtt.client.ui.design.Icons
import com.csqtt.client.ui.design.Shape
import com.csqtt.client.ui.design.Type
import com.csqtt.client.ui.profiles.copiedText
import com.csqtt.client.ui.shell.LocalNav
import com.csqtt.client.ui.shell.LocalOwners
import com.csqtt.client.ui.shell.LocalRun
import com.csqtt.client.ui.shell.SecretDrafts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private fun tokenDraftKey(profile: Int) = "vk:$profile:token"

/** The pool as read from the store: null inside means the profile's run uses none; no [PoolRead] yet, not read yet. */
private class PoolRead(val pool: VkPoolState?)

/**
 * Whether a deletion waits for the tunnel to stop: while a tunnel is on or being started — on whichever profile, since
 * the accounts are the phone's and each profile's sheet lists them all, so the tunnel may be on the one to delete. The
 * run would go on using an account the phone no longer has. Not while it stops: the run is ending, and it ends its
 * calls with the token it holds.
 */
internal fun vkDeleteWaits(): Boolean =
    (TunnelManager.running.value || TunnelManager.starting.value) && !TunnelManager.stopping.value

/** The square at the head of a row of the pool's calls. An account's row has none: its choice is the row's own tint. */
@Composable
private fun RowMark(content: @Composable () -> Unit) {
    Box(Modifier.size(32.dp).background(Csq.colors.raised, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { content() }
}

/**
 * «Аккаунты VK» (the canvas, F-VkAccounts; M-79, D30): the accounts signed in on this phone, the one this profile is
 * on, and the ways to another — a sign-in, or a permanent token pasted by hand. What is done here is done at once:
 * «Готово» only closes. With the tunnel on for this profile the run keeps the account it started with (RunKeeps), so
 * another one taken here is for the next connection: the sheet says which account the tunnel is on now, under the
 * list, and the bar on its foot offers the reconnect (card p3-apply-bar). A deletion waits for the stop of a tunnel on
 * any profile ([vkDeleteWaits]).
 */
@Composable
internal fun VkAccountsSheet(profileArg: String, close: () -> Unit) {
    val profile = profileArg.toIntOrNull()
    if (profile == null) {
        LaunchedEffect(Unit) { close() }
        return
    }
    val colors = Csq.colors
    val nav = LocalNav.current
    val messages = LocalOwners.current.messages
    val context = LocalContext.current.applicationContext
    val store = rememberProfileStore(profile)
    // null until the store has answered: «ещё не входили» is not said of accounts not yet read (§4.2 rule 3)
    val storedAccounts by store.vkAccounts.collectAsStateWithLifecycle(initialValue = null)
    val storedToken by store.vkAccessToken.collectAsStateWithLifecycle(initialValue = null)
    val storedUserId by store.vkAccessTokenUserId.collectAsStateWithLifecycle(initialValue = "")
    val userId = storedUserId
    // the account the run is on, while it is not the one this profile holds: null when nothing of it waits
    val runAccount = LocalRun.current?.takeIf { it.waits(profile, RunKey.ACCOUNT) }?.uses
    val key = tokenDraftKey(profile)
    val typed = SecretDrafts[key]
    var tokenError by rememberSaveable { mutableStateOf<String?>(null) }
    // the account asked to be deleted, until the question is answered: its id, or "" for a token that has none
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    val badToken = stringResource(R.string.vk_token_bad)
    val expiringToken = stringResource(R.string.vk_token_expiring)
    // a token being typed is dropped when the sheet is left, not when the screen is made again under it
    val leave = {
        SecretDrafts.clear(key)
        close()
    }

    /** Whether an account may be deleted now; says why not. */
    fun mayDelete(): Boolean = !vkDeleteWaits().also { waits -> if (waits) messages.show(context.getString(R.string.vk_delete_running)) }

    fun useToken(text: String) {
        val parsed = VkTokenParse.parse(text)
        when {
            parsed == null -> tokenError = badToken
            // D30: an expiring token would stop working within a day, mid-use
            parsed.expiresIn > 0L -> tokenError = expiringToken
            else -> {
                tokenError = null
                SecretDrafts.clear(key)
                TunnelManager.scope.launch(Dispatchers.Main) {
                    store.saveVkAccessToken(parsed.token, parsed.userId, 0L)
                    messages.show(context.getString(R.string.vk_token_saved))
                }
            }
        }
    }

    // The sheet is made at once and its content when the store has answered: a sheet made later than the sign-in opened
    // over it would lie over that sign-in once the screen is made again (windows stack in the order they are made).
    // The two answers are read here and handed in
    val accountsNow = storedAccounts
    val tokenNow = storedToken
    Sheet(
        onDismiss = leave, title = stringResource(R.string.vk_accounts_title), subtitle = stringResource(R.string.vk_accounts_sub),
        messages = messages,
    ) {
        val accounts = accountsNow ?: return@Sheet
        val token = tokenNow ?: return@Sheet
        // a token pasted by hand may name no account: the profile is on it all the same
        val unlisted = token.isNotBlank() && userId !in accounts
        if (accounts.isEmpty() && !unlisted) {
            Text(stringResource(R.string.vk_accounts_none), style = Type.fact, color = colors.muted)
        } else {
            Card(flush = true) {
                if (unlisted) {
                    val name = stringResource(R.string.vk_account_pasted_title)
                    // The chosen account is the tinted row, with no mark before it and no word after it (the operator,
                    // 2026-11-28: «just row selection with no first part», and of «выбран»: «only one account is
                    // allowed to be selected, so if the row is selected - why do we need that word»). The rows under
                    // this one say «selected» to TalkBack as the choices they are (Cell). This row is no choice to
                    // tap, so it says it itself — as one item with its name: a state on a row that does not gather
                    // its texts is on nothing TalkBack stops at. Its «Удалить» stays an item of its own
                    Cell(
                        // a token pasted by hand names no account; one whose account was since taken off the list
                        // (from another profile's sheet) still names its id
                        title = name,
                        modifier = Modifier.semantics(mergeDescendants = true) { selected = true },
                        sub = if (userId.isEmpty()) stringResource(R.string.vk_account_pasted) else stringResource(R.string.vk_account_unlisted, userId),
                        selected = true,
                        trailing = {
                            IconBtn(Icons.trash, stringResource(R.string.vk_account_delete, name), { if (mayDelete()) deleting = "" }, small = true, tint = colors.muted)
                        },
                    )
                }
                accounts.forEachIndexed { index, id ->
                    val name = stringResource(R.string.vk_account_n, index + 1)
                    val selected = token.isNotBlank() && id == userId
                    Cell(
                        title = name, sub = stringResource(R.string.vk_account_id, id), selected = selected, divided = index > 0 || unlisted,
                        onClick = {
                            if (!selected) {
                                TunnelManager.scope.launch(Dispatchers.Main) {
                                    if (!store.selectVkAccount(id)) messages.show(context.getString(R.string.vk_account_unreadable))
                                }
                            }
                        },
                        trailing = {
                            IconBtn(Icons.trash, stringResource(R.string.vk_account_delete, name), { if (mayDelete()) deleting = id }, small = true, tint = colors.muted)
                        },
                    )
                }
            }
        }
        // the tunnel keeps the account it started with: which one that is, while this profile holds another
        runAccount?.let { used ->
            val listed = accounts.indexOf(used.accountId)
            NowLine(if (listed >= 0) stringResource(R.string.now_account_n, listed + 1, used.accountId) else runAccountName(used))
        }
        Btn(
            stringResource(if (accounts.isEmpty() && !unlisted) R.string.vk_sign_in_first else R.string.vk_sign_in_other),
            { openVkSignIn(nav, profile) },
            modifier = Modifier.fillMaxWidth(), icon = Icons.user,
            kind = if (token.isBlank()) BtnKind.Primary else BtnKind.Plain,
        )
        Field(
            stringResource(R.string.vk_token_field), typed,
            {
                SecretDrafts[key] = it.trim()
                tokenError = null
            },
            placeholder = stringResource(R.string.vk_token_placeholder), mono = true, error = tokenError,
            hint = stringResource(R.string.vk_token_hint).takeIf { tokenError == null },
            trailing = {
                // a paste is the whole token at once: taken there and then
                IconBtn(Icons.clipboard, stringResource(R.string.action_paste), { useToken(copiedText(context)) }, small = true, tint = colors.muted)
            },
        )
        if (typed.isNotEmpty()) Btn(stringResource(R.string.vk_token_use), { useToken(typed) }, kind = BtnKind.Tonal, modifier = Modifier.fillMaxWidth())
        Hairline()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Btn(stringResource(R.string.action_done), leave, kind = BtnKind.Primary, modifier = Modifier.widthIn(min = 140.dp))
        }
    }

    deleting?.let { id ->
        DialogFrame(
            title = stringResource(R.string.vk_delete_title), onDismiss = { deleting = null },
            actions = {
                TextBtn(stringResource(R.string.action_cancel), { deleting = null })
                Btn(
                    stringResource(R.string.action_delete),
                    {
                        deleting = null
                        // asked again: the tunnel may have been started while the question was open
                        if (mayDelete()) {
                            // the profile's own account goes with its VK session in the app, so that the next sign-in
                            // does not quietly bring the same account back
                            if (id.isEmpty() || id == userId) VkAuthWebViewManager.clearVkSessionCookies()
                            TunnelManager.scope.launch(Dispatchers.Main) {
                                if (id.isEmpty()) store.clearVkAccessToken() else store.removeVkAccount(id)
                                messages.show(context.getString(R.string.vk_account_removed))
                            }
                        }
                    },
                    kind = BtnKind.Destroy,
                )
            },
        ) {
            Text(stringResource(R.string.vk_delete_text), style = Type.fact, color = colors.text)
        }
    }
}

/**
 * The sign-in to VK (the canvas, F-VkSignIn), in a window of its own: VK's page needs the height and the keyboard. It
 * is a route of the shell, so it is still there after the screen was made again (U4); the page then starts over, with
 * VK's own session kept in its cookies. The token it ends on is stored for the profile it was opened for.
 */
@Composable
internal fun VkSignInDialog(profileArg: String, close: () -> Unit) {
    val profile = profileArg.toIntOrNull()
    if (profile == null) {
        LaunchedEffect(Unit) { close() }
        return
    }
    val colors = Csq.colors
    val context = LocalContext.current
    val app = context.applicationContext
    val messages = LocalOwners.current.messages
    val lifecycleOwner = LocalLifecycleOwner.current
    val store = rememberProfileStore(profile)
    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var phase by remember { mutableStateOf(VkAuthPhase.LOGIN) }

    val webView = remember { VkAuthWebViewManager.acquire(context) }
    val session = remember {
        VkAuthSession(
            onUiReady = { ready = true },
            onToken = { payload: VkAuthPayload ->
                close()
                // on the app's scope: the token VK gave is stored whatever becomes of the window
                TunnelManager.scope.launch(Dispatchers.Main) {
                    // only a permanent token reaches here (M-82 / D30: an expiring one is refused at sign-in). Under a
                    // running tunnel it is stored for the next connection: the run keeps the account it started with,
                    // and the bar says what waits
                    store.saveVkAccessToken(payload.token, payload.userId, payload.expiresIn)
                    messages.show(app.getString(R.string.vk_token_saved))
                }
            },
            onError = { error = it },
            onPhaseChange = { phase = it },
        )
    }
    DisposableEffect(session, webView, lifecycleOwner) {
        session.attach(webView)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> session.pause()
                Lifecycle.Event.ON_START -> session.resume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            session.stop()
            VkAuthWebViewManager.release(webView)
        }
    }
    val pageAlpha by animateFloatAsState(if (ready && error == null) 1f else 0f, label = "vk-page")

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        val dim = colors.scrim.alpha
        SideEffect { window?.setDimAmount(dim) }
        // the page's fields stay above the keyboard
        DisposableEffect(window) {
            val before = window?.attributes?.softInputMode
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            onDispose { if (before != null) window.setSoftInputMode(before) }
        }
        Box(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(16.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxSize().clip(Shape.dialog).background(colors.surface).border(1.dp, colors.line, Shape.dialog)
                    .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(if (phase == VkAuthPhase.TOKEN) R.string.vk_sign_in_token else R.string.vk_sign_in_title),
                        style = Type.dialogTitle, color = colors.text, modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    IconBtn(Icons.close, stringResource(R.string.action_close), close, small = true)
                }
                if (phase == VkAuthPhase.LOGIN) Text(stringResource(R.string.vk_sign_in_note), style = Type.sub, color = colors.muted)
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    AndroidView(
                        factory = { ctx ->
                            FrameLayout(ctx).apply {
                                isFocusable = false
                                isFocusableInTouchMode = false
                                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                                val radius = 16f * ctx.resources.displayMetrics.density
                                outlineProvider = object : ViewOutlineProvider() {
                                    override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
                                }
                                clipToOutline = true
                                addView(webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                                post { webView.requestFocus() }
                            }
                        },
                        modifier = Modifier.fillMaxSize().alpha(pageAlpha),
                    )
                    if (!ready && error == null) CircularProgressIndicator(color = colors.accent)
                    error?.let { message ->
                        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(message, style = Type.body, color = colors.danger, textAlign = TextAlign.Center)
                            Btn(stringResource(R.string.action_close), close, kind = BtnKind.Primary)
                        }
                    }
                }
            }
        }
    }
}

/**
 * «Пул звонков VK» (the canvas, F-VkPool; D6): the calls the owner's account keeps for its server's participants, what
 * the server's panel last took, and the two things to do with them. The pool fills itself at every connect: this sheet
 * shows it, and does the same on demand.
 */
@Composable
internal fun VkPoolSheet(profileArg: String, close: () -> Unit) {
    val profile = profileArg.toIntOrNull()
    if (profile == null) {
        LaunchedEffect(Unit) { close() }
        return
    }
    val colors = Csq.colors
    val messages = LocalOwners.current.messages
    val context = LocalContext.current.applicationContext
    val store = rememberProfileStore(profile)
    // read here and handed to the sheet's content, which is made at once (see VkAccountsSheet). The pool comes wrapped,
    // so that «пулом не пользуется» is not said of a pool not yet read
    val read by remember(store) { store.vkPoolState.map { PoolRead(it) } }.collectAsStateWithLifecycle(initialValue = null)
    val storedToken by store.vkAccessToken.collectAsStateWithLifecycle(initialValue = "")
    val resultsJson by store.vkHashCheckResults.collectAsStateWithLifecycle(initialValue = "{}")
    val results = remember(resultsJson) { VkHashValidationCodec.decode(resultsJson) }
    // above the screens: calls being made are shown as being made after the screen was made again too
    val working by VkPoolHandOver.working.collectAsStateWithLifecycle()
    val loaded = read
    val state = loaded?.pool
    val token = storedToken

    fun refresh(oneMore: Boolean) {
        val pool = state?.pool.orEmpty()
        when {
            token.isBlank() -> messages.show(context.getString(R.string.vk_pool_no_token))
            oneMore && pool.size >= PanelApi.MAX_DEFAULT_HASHES ->
                messages.show(context.getString(R.string.vk_pool_full, PanelApi.MAX_DEFAULT_HASHES))
            else -> {
                val dead = pool.filterTo(HashSet()) { results[it] == VkHashValidationStatus.Invalid }
                // on the app's scope: calls being made are kept, and handed over, whatever becomes of the sheet
                TunnelManager.scope.launch(Dispatchers.Main) {
                    val done = runCatching { VkPoolHandOver.refresh(context, store, profile, dead, oneMore) }.getOrNull()
                    // what happened is said as it happened: a call that was asked for and not made is not answered
                    // with «переданы серверу»
                    val said = when {
                        done == null -> null
                        done.tokenRefused -> context.getString(R.string.vk_pool_token_refused)
                        oneMore && done.made == 0 -> context.getString(R.string.vk_pool_none_made)
                        done.handOver != null -> handOverNoticeText(done.handOver.second)
                        else -> context.getString(R.string.vk_pool_none_made)
                    }
                    said?.let(messages::show)
                }
            }
        }
    }

    Sheet(onDismiss = close, title = stringResource(R.string.vk_pool_title), messages = messages) {
        val pool = state
        when {
            loaded == null -> Unit
            pool == null -> Text(stringResource(R.string.vk_pool_unused), style = Type.fact, color = colors.muted)
            else -> {
                if (pool.pool.isEmpty()) {
                    Text(stringResource(R.string.vk_pool_empty), style = Type.fact, color = colors.muted)
                } else {
                    Card(flush = true) {
                        pool.pool.forEachIndexed { index, hash ->
                            val dead = results[hash] == VkHashValidationStatus.Invalid
                            Cell(
                                // a call is named by the end of its hash: enough to tell two apart, not enough to join it
                                title = stringResource(R.string.vk_pool_call, index + 1, hash.takeLast(4)),
                                sub = stringResource(if (hash in pool.sent) R.string.vk_pool_sent else R.string.vk_pool_not_sent),
                                divided = index > 0,
                                leading = { RowMark { Icon(Icons.phone, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.muted) } },
                                trailing = {
                                    Chip(
                                        stringResource(if (dead) R.string.vk_pool_dead else R.string.vk_pool_live),
                                        kind = if (dead) ChipKind.Warn else ChipKind.Ok, small = true,
                                    )
                                },
                            )
                        }
                    }
                }
                val handOver = pool.handOver
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (handOver?.ok == false) Icons.alert else Icons.checkCircle, contentDescription = null, modifier = Modifier.size(18.dp),
                        tint = when (handOver?.ok) {
                            true -> colors.ok
                            false -> colors.danger
                            null -> colors.faint
                        },
                    )
                    Text(
                        handOver?.let { remember(it) { handOverNoticeLine(it) } } ?: stringResource(R.string.vk_pool_never_sent),
                        style = Type.fact, color = colors.text, modifier = Modifier.weight(1f),
                    )
                }
                Text(stringResource(R.string.vk_pool_note), style = Type.sub, color = colors.muted)
                // of one height, whichever label takes two lines on a narrow screen
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn(
                        stringResource(R.string.vk_pool_create), { refresh(oneMore = true) },
                        modifier = Modifier.weight(1f).fillMaxHeight(), kind = BtnKind.Tonal, icon = Icons.plus, enabled = !working,
                    )
                    Btn(
                        stringResource(if (working) R.string.vk_pool_working else R.string.vk_pool_refresh), { refresh(oneMore = false) },
                        modifier = Modifier.weight(1f).fillMaxHeight(), icon = if (working) Icons.loader else Icons.refresh, enabled = !working,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextBtn(stringResource(R.string.action_close), close)
        }
    }
}
