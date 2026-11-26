package com.csqtt.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.Messages
import com.csqtt.client.R
import com.csqtt.client.ui.design.Csq
import com.csqtt.client.ui.design.Icons
import com.csqtt.client.ui.design.Ink
import com.csqtt.client.ui.design.Shape
import com.csqtt.client.ui.design.Type
import kotlinx.coroutines.delay

/**
 * A sheet from the bottom edge: a list to pick from, a form. Back, the scrim, a drag down and ✕ all call [onDismiss].
 * A sheet is a window over the activity's, where the app's short messages are drawn: with [messages] given, the sheet
 * on top shows them itself, at its foot (an «Отменить» must be within reach of the list it undoes).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Sheet(
    onDismiss: () -> Unit,
    title: String? = null,
    subtitle: String? = null,
    messages: Messages? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Csq.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = Shape.sheet,
        containerColor = colors.surface,
        contentColor = colors.text,
        tonalElevation = 0.dp,
        scrimColor = colors.scrim,
        dragHandle = {
            Box(Modifier.padding(top = 8.dp, bottom = 4.dp).size(36.dp, 4.dp).background(colors.line, RoundedCornerShape(2.dp)))
        },
    ) {
        Box(contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (title != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(title, style = Type.dialogTitle, color = colors.text, modifier = Modifier.semantics { heading() })
                            if (subtitle != null) Text(subtitle, style = Type.sub, color = colors.muted)
                        }
                        IconBtn(Icons.close, stringResource(R.string.action_close), onDismiss, small = true)
                    }
                }
                content()
            }
            if (messages != null) MessageHost(messages, Modifier.padding(bottom = 16.dp))
        }
    }
}

/**
 * A question in the middle of the screen. Its [actions] end the row on the right: «Отмена», then the one that acts;
 * where they do not fit side by side they go one under the other. A question that would be lost with its dialog is not
 * closed by a tap beside it ([dismissOnOutside] false); Back and «Отмена» still close it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DialogFrame(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    dismissOnOutside: Boolean = true,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Csq.colors
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = dismissOnOutside),
    ) {
        // the window dims what is behind it by the theme's scrim, not by the platform's default
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val dim = colors.scrim.alpha
        SideEffect { window?.setDimAmount(dim) }
        Column(
            modifier = Modifier
                .padding(16.dp)
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .clip(Shape.dialog)
                .background(colors.surface)
                .border(1.dp, colors.line, Shape.dialog)
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column {
                Text(title, style = Type.dialogTitle, color = colors.text, modifier = Modifier.semantics { heading() })
                if (subtitle != null) Text(subtitle, style = Type.sub, color = colors.muted)
            }
            content()
            if (actions != null) {
                FlowRow(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically,
                ) { actions() }
            }
        }
    }
}

/** A screen of its own over the tabs («Настройки»): the way back at the top, its title, and what it holds. */
@Composable
internal fun ScreenFrame(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = Csq.colors
    Box(modifier.fillMaxSize().background(colors.bg).windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.defaultMinSize(minHeight = 48.dp), contentAlignment = Alignment.CenterStart) {
                IconBtn(Icons.arrowLeft, stringResource(R.string.action_back), onBack)
            }
            Text(title, style = Type.title, color = colors.text, modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() })
            content()
        }
    }
}

/**
 * The app's short messages, over the tab bar, where a system toast would cover it. The message is the owner's
 * ([Messages]), so it is the same one on every tab; TalkBack reads it when it appears.
 */
@Composable
internal fun MessageHost(messages: Messages, modifier: Modifier = Modifier) {
    val message by messages.current.collectAsStateWithLifecycle()
    val shown = message ?: return
    // the owner's clock: a message raised while no screen drew it has less time left, or none
    val left = remember(shown.id) { messages.remaining(shown) }
    // someone who asked the system for more time to read and to act gets it
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(shown.id) {
        val asked = accessibility?.calculateRecommendedTimeoutMillis(left, containsText = true, containsControls = shown.action != null) ?: left
        delay(if (left > 0) maxOf(left, asked) else 0)
        messages.dismiss(shown.id)
    }
    if (left <= 0) return
    Row(
        modifier = modifier
            .padding(horizontal = 12.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .shadow(6.dp, Shape.message)
            .background(Ink.messageBg, Shape.message)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(shown.text, style = Type.message, color = Color.White, modifier = Modifier.weight(1f))
        shown.action?.let { action ->
            Text(
                action, style = Type.messageAction, color = Ink.messageAction,
                modifier = Modifier.clickable(role = Role.Button) { messages.act(shown.id) },
            )
        }
    }
}
