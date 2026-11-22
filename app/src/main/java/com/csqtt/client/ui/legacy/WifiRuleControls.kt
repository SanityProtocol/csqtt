package com.csqtt.client.ui.legacy

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.csqtt.client.SettingsStore
import com.csqtt.client.TunnelManager
import com.csqtt.client.TunnelService
import com.csqtt.client.WifiRule
import com.csqtt.client.connectedWifiName
import com.csqtt.client.hasFineLocation
import com.csqtt.client.locationEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * B5f2: under «Автопауза при Wi-Fi» while it is on — where the pause acts, and in the two list rules what Android needs to
 * give the network's name and the networks with their checks. The full sheet is P3's.
 */
@Composable
internal fun WifiRuleControls(settingsStore: SettingsStore) {
    val stored by settingsStore.wifiRules.collectAsStateWithLifecycle(initialValue = null)
    val rules = stored ?: return
    if (!rules.enabled) return
    val context = LocalContext.current
    val access = rememberWifiNameAccess()
    // the phone's network, read once each time the sheet comes back to the front (a grant, the location settings)
    var current by remember { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, rules.rule, access.ok) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            current = if (rules.rule.usesNames) withContext(Dispatchers.IO) { connectedWifiName(context) } else null
        }
    }
    // the writes run on the app's scope: closing the sheet must not drop one (§4.2 rule 8)
    fun edit(block: suspend SettingsStore.() -> Unit) {
        TunnelManager.scope.launch { settingsStore.block() }
    }

    CompactDropdownSetting(
        title = "В каких сетях",
        selectedKey = rules.rule.stored,
        options = WifiRule.entries.map { it.stored to it.label },
        enabled = true,
        onSelected = { key ->
            val rule = WifiRule.fromStored(key)
            edit { saveWifiRule(rule) }
            // a rule that needs the name asks for what Android wants at once, in context
            if (rule.usesNames) access.request()
        },
        valueMaxWidth = 176.dp,
    )
    Text(
        text = rules.rule.sentence,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (!rules.rule.usesNames) return

    access.fixLabel?.let { label ->
        Text(
            text = if (!access.fine) {
                "Имя сети Android сообщает только приложению с доступом к точному местоположению. Без имени паузы нет."
            } else {
                "Без геолокации Android не сообщает имя сети. Без имени паузы нет."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(top = 4.dp),
        )
        TextButton(onClick = access.fix) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        rules.saved.sortedWith(String.CASE_INSENSITIVE_ORDER).forEach { name ->
            WifiNetworkRow(
                name = name,
                checked = name in rules.checked,
                here = name == current,
                onCheck = { checked -> edit { checkWifiNetwork(name, checked) } },
                onDelete = { edit { forgetWifiNetwork(name) } },
            )
        }
    }
    val unsaved = current?.takeIf { it !in rules.saved }
    if (rules.saved.isEmpty() && unsaved == null) {
        Text(
            text = "Сетей в списке нет. Подключите телефон к Wi-Fi, чтобы запомнить сеть.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    unsaved?.let { name ->
        TextButton(onClick = { edit { rememberWifiNetwork(name, rules.rule) } }) {
            Text(
                "Запомнить «$name»",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One saved network: its check (what it means follows the rule's sentence above), the phone's network marked. */
@Composable
private fun WifiNetworkRow(
    name: String,
    checked: Boolean,
    here: Boolean,
    onCheck: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheck),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            if (here) {
                Icon(
                    imageVector = Icons.Filled.Wifi,
                    contentDescription = "Телефон сейчас в этой сети",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = "Удалить «$name»",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** What Android needs before it gives a Wi-Fi's name, and the one action that fixes what is missing. */
internal class WifiNameAccess(
    val fine: Boolean,
    val locationOn: Boolean,
    val fixLabel: String?,
    val fix: () -> Unit,
    /** Android's request, when it may still ask (a rule that needs the name was just chosen). */
    val request: () -> Unit,
) {
    val ok: Boolean get() = fine && locationOn
}

/**
 * Re-read on every return to the screen (a grant, the location settings) and on every location switch while shown (the
 * quick settings leave the app resumed). The fix: Android's request for precise location; once Android refuses to ask
 * again, the app's settings; with the permission but location off, its settings.
 */
@Composable
internal fun rememberWifiNameAccess(): WifiNameAccess {
    val context = LocalContext.current
    var fine by remember { mutableStateOf(hasFineLocation(context)) }
    var locationOn by remember { mutableStateOf(locationEnabled(context)) }
    var deniedForGood by rememberSaveable { mutableStateOf(false) }
    // when the request went out, whether Android showed its rationale before it (review F5), and whether the fix button
    // asked (a pick in the dropdown never opens the settings: review N2)
    var askedAt by rememberSaveable { mutableLongStateOf(0L) }
    var rationaleBefore by rememberSaveable { mutableStateOf(false) }
    var askedByButton by rememberSaveable { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            fine = hasFineLocation(context)
            locationOn = locationEnabled(context)
        }
    }
    // review F4: location switched from the quick settings — the line follows, and the service reads the name again
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                locationOn = locationEnabled(context)
                TunnelService.onAppInFront(context)
            }
        }
        val registered = runCatching {
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(LocationManager.MODE_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.isSuccess
        onDispose { if (registered) runCatching { context.unregisterReceiver(receiver) } }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        fine = hasFineLocation(context)
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) return@rememberLauncherForActivityResult
        val activity = context.findActivity() ?: return@rememberLauncherForActivityResult
        val rationaleAfter = ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
        // Android stops asking after a second refusal: its rationale shown before the request and not after. Refused for
        // good earlier, it shows no dialog and answers at once — then the fix button opens the app's settings instead of
        // a dead tap. With the rationale shown before, Android always shows its dialog, so a fast answer there is a
        // person's. A dialog dismissed with Back changes nothing: Android asks again next time (review F5, N2)
        val noDialog = !rationaleBefore && SystemClock.elapsedRealtime() - askedAt < NO_DIALOG_ANSWER_MS
        if (!rationaleAfter && (rationaleBefore || noDialog)) {
            deniedForGood = true
            if (noDialog && askedByButton) openAppSettings(context)
        }
    }
    val ask = { byButton: Boolean ->
        rationaleBefore = context.findActivity()
            ?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.ACCESS_FINE_LOCATION) } == true
        askedByButton = byButton
        askedAt = SystemClock.elapsedRealtime()
        launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    return when {
        !fine -> WifiNameAccess(
            fine = false,
            locationOn = locationOn,
            fixLabel = "Разрешить доступ к местоположению",
            fix = { if (deniedForGood) openAppSettings(context) else ask(true) },
            request = { if (!deniedForGood) ask(false) },
        )
        !locationOn -> WifiNameAccess(
            fine = true,
            locationOn = false,
            fixLabel = "Включить геолокацию",
            fix = { runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } },
            request = {},
        )
        else -> WifiNameAccess(fine = true, locationOn = true, fixLabel = null, fix = {}, request = {})
    }
}

// no person answers Android's dialog this fast: an answer sooner means none was shown
private const val NO_DIALOG_ANSWER_MS = 300L

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
