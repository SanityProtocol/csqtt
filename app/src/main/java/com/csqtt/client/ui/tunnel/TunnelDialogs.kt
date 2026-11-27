package com.csqtt.client.ui.tunnel

import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.csqtt.client.R
import com.csqtt.client.StreamZone
import com.csqtt.client.StreamsPlan
import com.csqtt.client.TunnelManager
import com.csqtt.client.ui.components.Btn
import com.csqtt.client.ui.components.BtnKind
import com.csqtt.client.ui.components.CheckRow
import com.csqtt.client.ui.components.DialogFrame
import com.csqtt.client.ui.components.TextBtn
import com.csqtt.client.ui.design.Csq
import com.csqtt.client.ui.design.Type
import kotlinx.coroutines.launch

/**
 * What a risk question is about: a mode that signs in to VK's unofficial API, or a count of streams
 * in the slider's orange or red zone (its value: the count picked, the most one hash would carry and whether the
 * profile could have more hashes, «60:60:1»).
 */
internal object RiskKind {
    const val HASH_MODE = "hash"
    const val WORK_MODE = "work"
    const val STREAMS = "streams"
}

/** The explainers the «?» of a setting opens. */
internal object InfoTopic {
    const val HASH_MODE = "hash"
    const val WORK_MODE = "work"
    const val TRANSPORT = "transport"
    const val STREAMS = "streams"
    const val SRTP = "srtp"
}

/**
 * The warning before «Авто ВК» or a count of streams above the recommended one is taken (the canvas,
 * F-RiskDialog): what is risked, a real «Отмена», and no timer. It is a route of the shell, with the choice it was
 * asked for in its argument, so the question and the choice are still there after the screen was made again (U4). The
 * choice is stored only on «Понятно».
 */
@Composable
internal fun RiskDialog(arg: String, close: () -> Unit) {
    val parts = arg.split('|')
    val profile = parts.getOrNull(0)?.toIntOrNull()
    val kind = parts.getOrNull(1)
    val value = parts.getOrNull(2).orEmpty()
    // a count of streams comes with the most one hash would carry of it, which says the zone it is in, and with
    // whether the profile has hashes of which it could have more (1) or one call that is the account's own (0)
    val streams = value.split(':').mapNotNull(String::toIntOrNull).takeIf { kind == RiskKind.STREAMS && it.size == 3 }
    val moreHashesHelp = streams?.get(2) == 1
    val streamsZone = streams?.let { StreamsPlan.zone(it[1]) }
    val known = kind in setOf(RiskKind.HASH_MODE, RiskKind.WORK_MODE) ||
        streamsZone == StreamZone.Orange || streamsZone == StreamZone.Red
    if (profile == null || !known || value.isEmpty()) {
        LaunchedEffect(Unit) { close() }
        return
    }
    val store = rememberProfileStore(profile)
    var dontRemind by rememberSaveable { mutableStateOf(false) }
    val danger = streamsZone == StreamZone.Red
    DialogFrame(
        title = stringResource(if (danger) R.string.risk_danger_title else R.string.risk_title), onDismiss = close, dismissOnOutside = false,
        actions = {
            TextBtn(stringResource(R.string.action_cancel), close)
            Btn(
                stringResource(R.string.action_understood),
                {
                    val remembered = dontRemind
                    // on the app's scope: the choice is stored even as the dialog goes
                    TunnelManager.scope.launch {
                        when (kind) {
                            RiskKind.HASH_MODE -> store.saveVkHashMode(value)
                            RiskKind.WORK_MODE -> store.saveVkAuthMode(value)
                            RiskKind.STREAMS -> streams?.let { store.saveWorkersPerHash(it[0]) }
                        }
                        if (remembered) store.saveAutoJsRiskAcknowledged(true)
                    }
                    close()
                },
                // the danger zone's «Понятно» is drawn as what it is
                kind = if (danger) BtnKind.Destroy else BtnKind.Primary, modifier = Modifier.widthIn(min = 140.dp),
            )
        },
    ) {
        Text(
            when {
                danger -> stringResource(
                    if (moreHashesHelp) R.string.risk_streams_danger else R.string.risk_streams_danger_plain, streams[1], StreamsPlan.ORANGE_PER_HASH,
                )
                streams != null -> stringResource(
                    if (moreHashesHelp) R.string.risk_streams_over else R.string.risk_streams_over_plain, streams[1], StreamsPlan.RECOMMENDED_PER_HASH,
                )
                else -> stringResource(R.string.risk_auto_vk)
            },
            style = Type.fact, color = Csq.colors.text,
        )
        // a count of streams is asked about every time it goes up into a zone: there is nothing to remember
        if (streams == null) CheckRow(stringResource(R.string.risk_dont_remind), dontRemind, { dontRemind = it })
    }
}

/** What a setting's choices mean, one paragraph a choice. */
@Composable
internal fun InfoDialog(topic: String, close: () -> Unit) {
    val (title, paragraphs) = when (topic) {
        InfoTopic.HASH_MODE -> R.string.info_hash_title to listOf(R.string.info_hash_manual, R.string.info_hash_auto_api, R.string.info_hash_auto_vk)
        InfoTopic.WORK_MODE -> R.string.info_work_title to listOf(R.string.info_work_captcha, R.string.info_work_auto, R.string.info_work_auto_vk)
        InfoTopic.TRANSPORT -> R.string.info_transport_title to listOf(R.string.info_transport_udp, R.string.info_transport_tcp, R.string.info_transport_when)
        InfoTopic.STREAMS -> R.string.info_streams_title to listOf(R.string.info_streams, R.string.info_streams_hashes, R.string.info_streams_server)
        InfoTopic.SRTP -> R.string.info_srtp_title to listOf(R.string.info_srtp_auto, R.string.info_srtp_dtls, R.string.info_srtp_wrap)
        else -> {
            LaunchedEffect(Unit) { close() }
            return
        }
    }
    DialogFrame(
        title = stringResource(title), onDismiss = close,
        actions = { Btn(stringResource(R.string.action_understood), close, kind = BtnKind.Primary, modifier = Modifier.widthIn(min = 140.dp)) },
    ) {
        for (paragraph in paragraphs) {
            // the one paragraph with numbers in it: where the zones end on a hash
            val text = if (paragraph == R.string.info_streams_hashes) {
                stringResource(paragraph, StreamsPlan.GREEN_PER_HASH, StreamsPlan.RECOMMENDED_PER_HASH, StreamsPlan.ORANGE_PER_HASH)
            } else {
                stringResource(paragraph)
            }
            Text(text, style = Type.fact, color = Csq.colors.text)
        }
    }
}
