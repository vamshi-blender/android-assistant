package com.vamshi.aiassistant

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lists Wi-Fi networks in range. Tapping a saved one switches to it; tapping
 * any other opens the system Wi-Fi panel, where Android shows its own
 * password popup (a specific network cannot be deep-linked on Android 11).
 */
@Composable
fun WifiNetworksSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var networks by remember { mutableStateOf<List<WifiNetwork>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val ready = ShizukuRadios.hasPermission()

    fun refresh() {
        if (busy || !ShizukuRadios.hasPermission()) return
        scope.launch {
            busy = true
            networks = withContext(Dispatchers.IO) { WifiNetworks.scan() }
            busy = false
        }
    }

    fun switchTo(network: WifiNetwork) {
        if (busy || network.connected) return
        scope.launch {
            busy = true
            val ok = withContext(Dispatchers.IO) { WifiNetworks.switchTo(context, network) }
            Toast.makeText(
                context,
                if (ok) "Connected to ${network.ssid}" else "Could not switch to ${network.ssid}",
                Toast.LENGTH_SHORT
            ).show()
            busy = false
            refresh()
        }
    }

    fun onTap(network: WifiNetwork) {
        if (network.netId != null) {
            switchTo(network)
        } else {
            context.startActivity(
                Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    LaunchedEffect(ready) { refresh() }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(enabled = ready && !busy) { refresh() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Wi-Fi networks nearby",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp), strokeWidth = 2.dp)
            } else if (ready) {
                Text("Rescan", style = MaterialTheme.typography.labelLarge)
            }
        }

        if (!ready) {
            Text(
                text = "Allow Shizuku access (tap the Wi-Fi switch) to list networks here.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clickable {
                        if (ShizukuRadios.isRunning()) {
                            ShizukuRadios.requestPermission()
                        } else {
                            context.startActivity(
                                Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
            )
        } else if (networks.isEmpty() && !busy) {
            Text(
                text = "No networks found.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        networks.forEach { network ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTap(network) }
                    .padding(vertical = 8.dp)
            ) {
                Text(network.ssid, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = listOfNotNull(
                        if (network.connected) "Connected" else null,
                        if (network.netId != null && !network.connected) "Saved" else null,
                        if (network.secured) "Secured" else "Open",
                        "Signal ${network.level}/4"
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
