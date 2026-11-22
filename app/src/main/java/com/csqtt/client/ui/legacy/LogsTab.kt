package com.csqtt.client.ui.legacy

import com.csqtt.client.copySensitive
import com.csqtt.client.shareLogFile
import com.csqtt.client.showRaisedToast
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.BuildConfig
import com.csqtt.client.DeployManager
import com.csqtt.client.LogBook
import com.csqtt.client.LogEntry
import com.csqtt.client.LogText
import com.csqtt.client.R
import com.csqtt.client.TunnelManager
import com.csqtt.client.ui.legacy.components.CsqttEmptyState
import com.csqtt.client.ui.legacy.components.CsqttScreen
import com.csqtt.client.ui.legacy.design.CsqttShapes
import com.csqtt.client.ui.legacy.design.CsqttSpacing
import com.csqtt.client.ui.legacy.design.CsqttTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** B5g: at most this often the tab copies the log while records keep coming; nothing while none do. */
private const val LOG_VIEW_REFRESH_MS = 250L

@Composable
fun LogsTab() {
    CsqttScreen {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = CsqttShapes.Card,
            color = CsqttTheme.extendedColors.terminalBackground,
            contentColor = CsqttTheme.extendedColors.terminalText,
        ) {
            LogsTerminal()
        }
    }
}

@Composable
private fun LogsTerminal() {
    val context = LocalContext.current
    // B5g: the log is read only while this tab is shown and the app is started; the book's version moves on every change,
    // and a StateFlow keeps only the latest one while the copy below waits, so a burst costs one copy per refresh
    val book = LogBook.app
    val currentLogs by remember {
        book.version.map { book.snapshot() }.onEach { delay(LOG_VIEW_REFRESH_MS) }
    }.collectAsStateWithLifecycle(initialValue = remember { book.snapshot() })
    val listState = rememberLazyListState()
    val copiedMessage = stringResource(R.string.logs_copied)
    val isDeploying by DeployManager.isDeploying.collectAsStateWithLifecycle()

    // follows the newest record unless the user dragged the list up to read; a drag that ends at the bottom follows
    // again. Only the user's drags decide it: the tab's own scroll to the end never does
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        var dragged = false
        launch { listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) dragged = true } }
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && dragged) {
                dragged = false
                follow = !listState.canScrollForward
            }
        }
    }
    LaunchedEffect(currentLogs, isDeploying) {
        if (currentLogs.isNotEmpty() && (follow || isDeploying) && !listState.isScrollInProgress) {
            listState.scrollToItem(currentLogs.lastIndex)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (currentLogs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CsqttEmptyState(
                    title = stringResource(R.string.logs_empty_title),
                    icon = Icons.Outlined.Terminal,
                    modifier = Modifier.padding(CsqttSpacing.Md),
                    contentColor = CsqttTheme.extendedColors.terminalText,
                    secondaryContentColor = CsqttTheme.extendedColors.terminalMuted,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = CsqttSpacing.Md, top = CsqttSpacing.Md, end = CsqttSpacing.Md, bottom = 64.dp),
                verticalArrangement = Arrangement.spacedBy(CsqttSpacing.Xs),
            ) {
                items(
                    items = currentLogs,
                    key = { it.id },
                    contentType = { if (it.separator) "log_section" else "log_line" },
                ) { entry ->
                    if (entry.separator) LogSection(entry) else LogLine(entry)
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { context.shareLogFile(exportText(currentLogs)) },
                enabled = currentLogs.isNotEmpty(),
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = stringResource(R.string.action_share),
                    tint = if (currentLogs.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(22.dp)
                )
            }

            IconButton(
                onClick = {
                    if (context.copySensitive("CSQTT Logs", exportText(currentLogs))) {
                        context.showRaisedToast(copiedMessage, Toast.LENGTH_SHORT)
                    }
                },
                enabled = currentLogs.isNotEmpty(),
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(R.string.action_copy),
                    tint = if (currentLogs.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(22.dp)
                )
            }

            IconButton(
                // nothing is left above to read: the next records are followed again (review F1)
                onClick = {
                    TunnelManager.clearLogs()
                    follow = true
                },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.action_clear),
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/** B5g: the copied or shared log, under a header that says what wrote it and when; no secret is in either. */
private fun exportText(entries: List<LogEntry>): String = LogText.export(
    entries,
    LogText.header(
        appVersion = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
        androidRelease = Build.VERSION.RELEASE,
        sdk = Build.VERSION.SDK_INT,
        device = "${Build.MANUFACTURER} ${Build.MODEL}",
        nowMs = System.currentTimeMillis(),
    ),
)
