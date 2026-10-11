package de.powerizzle.powerlink.ui

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import de.powerizzle.powerlink.Fix
import de.powerizzle.powerlink.Game
import de.powerizzle.powerlink.HostStatus
import de.powerizzle.powerlink.Link
import de.powerizzle.powerlink.LinkException
import de.powerizzle.powerlink.Medium
import de.powerizzle.powerlink.PowerLink
import de.powerizzle.powerlink.Readiness
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * Remembers what it takes to make each medium usable: asking for permissions, switching Bluetooth
 * or Wi-Fi on, opening settings once a permission was denied for good.
 */
private class Fixer(
    val request: (List<String>) -> Unit,
    val start: (Intent) -> Unit,
    val deniedForGood: (List<String>) -> Boolean,
)

@Composable
private fun rememberFixer(onResult: () -> Unit): Fixer {
    val context = LocalContext.current
    val asked = remember { HashSet<String>() }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onResult() }
    val activityResult = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onResult() }
    return remember {
        Fixer(
            request = { asked += it; perms.launch(it.toTypedArray()) },
            start = { runCatching { activityResult.launch(it) } },
            deniedForGood = { list ->
                val a = context.activity()
                // After one refusal Android may stop showing the dialog; then only the settings page helps.
                a != null && list.any { it in asked && !ActivityCompat.shouldShowRequestPermissionRationale(a, it) }
            },
        )
    }
}

private fun appSettings(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

private fun fixIntent(fix: Fix): Intent = when (fix) {
    Fix.BLUETOOTH -> Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
    Fix.WIFI -> if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
    Fix.LOCATION -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
}

/** Re-evaluated every second, since the player can flip switches in the system UI at any time. */
@Composable
private fun rememberTicker(): Int {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick++
        }
    }
    return tick
}

@Composable
private fun MediumRow(medium: Medium, status: String, ok: Boolean?, action: Pair<String, () -> Unit>?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(medium.icon, fontSize = 20.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(medium.label + when (ok) { true -> "  ✓"; false -> "  ✕"; null -> "" }, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(status, fontSize = 12.sp, lineHeight = 15.sp)
        }
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            Button(onClick = action.second) { Text(action.first, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun readinessAction(link: PowerLink, r: Readiness, fixer: Fixer): Pair<String, () -> Unit>? {
    val context = LocalContext.current
    return when (r) {
        is Readiness.NeedsPermission ->
            if (fixer.deniedForGood(r.permissions)) "Settings" to { fixer.start(appSettings(context)) }
            else "Allow" to { fixer.request(r.permissions) }
        is Readiness.TurnedOff -> "Turn on" to { fixer.start(fixIntent(r.fix)) }
        else -> null
    }
}

private fun readinessText(r: Readiness): String = when (r) {
    Readiness.Ready -> "Ready"
    is Readiness.NeedsPermission -> "Needs permission to find nearby phones."
    is Readiness.TurnedOff -> r.message
    is Readiness.Unsupported -> r.message
}

/**
 * Hosting panel: shows on which media guests can join right now and offers to fix the rest.
 * Media that become ready start automatically.
 */
@Composable
fun HostPanel(link: PowerLink, host: PowerLink.LinkHost, modifier: Modifier = Modifier) {
    val status by host.status.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    val fixer = rememberFixer { refresh++ }
    val tick = rememberTicker()
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text("Others can join via", fontWeight = FontWeight.Bold)
            for ((medium, s) in status.toSortedMap()) {
                tick + refresh // re-read discoverability and readiness regularly
                when (s) {
                    is HostStatus.Live -> {
                        val makeVisible = medium == Medium.BLUETOOTH && !link.bluetoothDiscoverable
                        val offline = medium == Medium.WIFI && s.detail.startsWith("Not on")
                        MediumRow(
                            medium,
                            s.detail + if (makeVisible) ". Phones that never paired with this one only see it while it's visible." else "",
                            if (offline) null else true,
                            if (makeVisible) "Make visible" to {
                                fixer.start(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
                            } else null,
                        )
                    }
                    is HostStatus.Starting -> MediumRow(medium, "Starting…", null, null)
                    is HostStatus.Failed -> MediumRow(medium, s.message, false, null)
                    is HostStatus.Waiting -> {
                        val r = link.readiness(medium)
                        MediumRow(medium, readinessText(r), if (r is Readiness.Unsupported) false else null, readinessAction(link, r, fixer))
                    }
                }
            }
            Text(
                "On a guest or public Wi-Fi, phones often can't see each other. Then use a hotspot, Bluetooth or Wi-Fi Direct.",
                fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Joining panel: lists games found on every ready medium, fixes media that aren't ready,
 * and offers typing an address. Calls [onJoined] with the open link once connected.
 */
@Composable
fun JoinPanel(
    link: PowerLink,
    onJoined: (Link, Game?) -> Unit,
    modifier: Modifier = Modifier,
    media: Set<Medium> = Medium.entries.toSet(),
) {
    var refresh by remember { mutableIntStateOf(0) }
    val fixer = rememberFixer { refresh++ }
    val tick = rememberTicker()
    val games by remember(refresh) { link.scan(media) }.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var joining by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var address by remember { mutableStateOf("") }

    fun attempt(label: String, block: suspend () -> Link, game: Game?) {
        if (joining != null) return
        joining = label
        error = null
        scope.launch {
            try {
                onJoined(block(), game)
            } catch (e: LinkException) {
                error = e.message
            } catch (e: Exception) {
                error = "Couldn't connect: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                joining = null
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val notReady = media.map { it to link.readiness(it).also { tick + refresh } }.filter { it.second != Readiness.Ready }
        if (notReady.isNotEmpty()) {
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("Search more ways", fontWeight = FontWeight.Bold)
                    for ((m, r) in notReady) {
                        MediumRow(m, readinessText(r), if (r is Readiness.Unsupported) false else null, readinessAction(link, r, fixer))
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(if (games.isEmpty()) "Looking for games…" else "Games nearby (looking for more…)", fontWeight = FontWeight.Bold)
        }
        for (g in games) {
            Card(Modifier.fillMaxWidth().clickable(enabled = joining == null && g.open) { attempt(g.name, { link.join(g) }, g) }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(g.medium.icon, fontSize = 20.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(g.name, fontWeight = FontWeight.Bold)
                        Text(listOf(g.medium.label, g.info, if (g.open) "" else "already playing").filter { it.isNotEmpty() }.joinToString(" · "), fontSize = 12.sp)
                    }
                    if (joining == g.name) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
        if (media.contains(Medium.BLUETOOTH) && games.none { it.medium == Medium.BLUETOOTH } && link.readiness(Medium.BLUETOOTH) == Readiness.Ready) {
            Text(
                "Host not listed under Bluetooth? Tap “Make visible” on the host's phone.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        if (Medium.WIFI in media) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    address, { address = it }, Modifier.weight(1f), singleLine = true,
                    label = { Text("Or type the host's address") },
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { attempt(address, { link.joinAddress(address) }, null) }, enabled = address.isNotBlank() && joining == null) {
                    Text("Join")
                }
            }
        }
    }
}
