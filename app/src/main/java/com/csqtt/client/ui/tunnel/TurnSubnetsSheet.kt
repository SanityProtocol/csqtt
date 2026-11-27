package com.csqtt.client.ui.tunnel

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.R
import com.csqtt.client.TunnelManager
import com.csqtt.client.TurnSubnetRegistry
import com.csqtt.client.measureSubnetRtt
import com.csqtt.client.ui.components.Btn
import com.csqtt.client.ui.components.BtnKind
import com.csqtt.client.ui.components.Card
import com.csqtt.client.ui.components.Cell
import com.csqtt.client.ui.components.MsPill
import com.csqtt.client.ui.components.RadioMark
import com.csqtt.client.ui.components.Seg
import com.csqtt.client.ui.components.Sheet
import com.csqtt.client.ui.components.TextBtn
import com.csqtt.client.ui.design.Csq
import com.csqtt.client.ui.design.Icons
import com.csqtt.client.ui.design.Type
import com.csqtt.client.ui.design.pluralText
import com.csqtt.client.ui.shell.LocalOwners
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ALL = "ALL"
private val Limits = listOf(ALL, "1", "2", "4", "9")

/**
 * «TURN-подсети» (the canvas, F-TurnSubnets): through which of VK's servers the tunnel goes — any that VK names, or
 * one subnet and so many of its addresses. A subnet with fewer servers than the count asked for cannot be picked.
 * «Проверить пинг» measures each, for as long as the sheet is open. A choice is stored on «Сохранить» and on any other
 * way of closing but «Отмена», as the hashes sheet stores its fields.
 */
@Composable
internal fun TurnSubnetsSheet(profileArg: String, close: () -> Unit) {
    val profile = profileArg.toIntOrNull()
    if (profile == null) {
        LaunchedEffect(Unit) { close() }
        return
    }
    val colors = Csq.colors
    val store = rememberProfileStore(profile)
    val scope = rememberCoroutineScope()
    // null until picked: the sheet shows what is stored
    val storedSubnet by store.selectedTurnSubnet.collectAsStateWithLifecycle(initialValue = null)
    val storedLimit by store.turnSubnetIpLimit.collectAsStateWithLifecycle(initialValue = null)
    var pickedSubnet by rememberSaveable { mutableStateOf<String?>(null) }
    var pickedLimit by rememberSaveable { mutableStateOf<String?>(null) }
    // read here, where they are collected, and handed to the sheet's content; the sheet itself is made at once (see
    // VkAccountsSheet for both)
    val subnetNow = pickedSubnet ?: storedSubnet
    val limitNow = pickedLimit ?: storedLimit

    // a subnet's ping as last measured; -1 for one that did not answer. Kept while the sheet is open
    val pings = remember { mutableStateMapOf<String, Int>() }
    var pinging by remember { mutableStateOf<String?>(null) }

    val count = limitNow?.toIntOrNull()
    // a stored id may be an older name of a subnet
    val chosenNow = subnetNow?.let { id -> TurnSubnetRegistry.getSubnet(id)?.takeIf { count == null || it.count >= count } }
    // «Сохранить» stores the choice, and so does closing the sheet any other way than «Отмена», as «Хеши звонков VK»
    // does: a choice made is not lost to a swipe. Nothing was picked, nothing is written. On the app's scope: the
    // choice is stored even as the sheet goes
    val save = {
        val limit = limitNow
        if (limit != null && (pickedSubnet != null || pickedLimit != null)) {
            val subnet = chosenNow?.id.orEmpty()
            TunnelManager.scope.launch { store.saveSelectedTurnSubnet(subnet, ipLimit = limit) }
        }
    }

    Sheet(
        onDismiss = {
            save()
            close()
        },
        title = stringResource(R.string.turn_title), subtitle = stringResource(R.string.turn_sub),
        messages = LocalOwners.current.messages,
    ) {
        if (subnetNow == null) return@Sheet
        val limit = limitNow ?: return@Sheet
        val chosen = chosenNow
        Card(flush = true, modifier = Modifier.selectableGroup()) {
            Cell(
                title = stringResource(R.string.turn_all), sub = stringResource(R.string.turn_all_sub), selected = chosen == null,
                onClick = { pickedSubnet = "" }, leading = { RadioMark(chosen == null) },
            )
            for (item in TurnSubnetRegistry.subnets) {
                val fits = count == null || item.count >= count
                Cell(
                    title = item.title,
                    sub = if (fits) pluralText(R.plurals.turn_servers_pool, item.count)
                    else stringResource(R.string.turn_too_small, pluralText(R.plurals.turn_servers, item.count)),
                    selected = chosen?.id == item.id, divided = true,
                    onClick = if (fits) ({ pickedSubnet = item.id }) else null,
                    modifier = Modifier.alpha(if (fits) 1f else 0.5f),
                    leading = { RadioMark(chosen?.id == item.id) },
                    trailing = {
                        val ping = pings[item.id]
                        when {
                            pinging == item.id -> Turning()
                            ping != null -> MsPill(ping.takeIf { it > 0 })
                        }
                    },
                )
            }
        }
        Text(stringResource(R.string.turn_ip_count), style = Type.label, color = colors.muted)
        Seg(Limits.map { if (it == ALL) stringResource(R.string.turn_ip_all) else it }, Limits.indexOf(limit).coerceAtLeast(0), { index ->
            val next = Limits[index]
            pickedLimit = next
            // a count is a count of one subnet's addresses: with none picked, or one too small for it, the first that
            // has as many is taken
            val wanted = next.toIntOrNull()
            if (wanted != null && (chosen == null || chosen.count < wanted)) {
                TurnSubnetRegistry.subnets.firstOrNull { it.count >= wanted }?.let { pickedSubnet = it.id }
            }
        })
        Text(stringResource(R.string.turn_note), style = Type.sub, color = colors.muted)
        Btn(
            stringResource(if (pinging != null) R.string.turn_pinging else R.string.turn_ping),
            {
                // in the sheet's own scope: a measurement is for this sheet, and ends with it
                scope.launch {
                    pings.clear()
                    for (item in TurnSubnetRegistry.subnets) {
                        pinging = item.id
                        pings[item.id] = withContext(Dispatchers.IO) { measureSubnetRtt(item.ips) }?.roundToInt()?.coerceAtLeast(1) ?: -1
                    }
                    pinging = null
                }
            },
            modifier = Modifier.fillMaxWidth(), kind = BtnKind.Tonal, icon = Icons.activity, enabled = pinging == null,
        )
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

/** A subnet being measured. */
@Composable
private fun Turning() {
    val turn by rememberInfiniteTransition(label = "ping").animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "turn")
    Icon(Icons.loader, contentDescription = null, modifier = Modifier.size(18.dp).rotate(turn), tint = Csq.colors.accentText)
}
