package com.vamshi.aiassistant

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Toggle(
    val label: String,
    val enabled: Boolean,
    val settingsAction: String,
    val radio: ShizukuRadios.Radio
)

private fun readToggles(context: Context): List<Toggle> {
    val resolver = context.contentResolver
    // Status reads must never take the screen down, so each one is guarded.
    val wifiOn = runCatching {
        context.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
    }.getOrDefault(false)
    // The global setting avoids BluetoothAdapter, which needs a runtime
    // permission on newer Android versions.
    val bluetoothOn = runCatching {
        Settings.Global.getInt(resolver, "bluetooth_on", 0) == 1
    }.getOrDefault(false)
    return listOf(
        Toggle("Wi-Fi", wifiOn, Settings.Panel.ACTION_WIFI, ShizukuRadios.Radio.WIFI),
        Toggle(
            "Mobile data",
            Settings.Global.getInt(resolver, "mobile_data", 0) == 1,
            Settings.Panel.ACTION_INTERNET_CONNECTIVITY,
            ShizukuRadios.Radio.MOBILE_DATA
        ),
        Toggle("Bluetooth", bluetoothOn, Settings.ACTION_BLUETOOTH_SETTINGS, ShizukuRadios.Radio.BLUETOOTH),
        Toggle(
            "Airplane mode",
            Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1,
            Settings.ACTION_AIRPLANE_MODE_SETTINGS,
            ShizukuRadios.Radio.AIRPLANE
        )
    )
}

/**
 * Toggles go through Shizuku when it is running and permitted. Otherwise the
 * row falls back to the matching system panel, since Android does not let
 * normal apps flip these radios themselves.
 */
@Composable
fun ConnectivityToggles() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var toggles by remember { mutableStateOf(readToggles(context)) }

    fun onToggle(toggle: Toggle) {
        when {
            // Shell access to Bluetooth is blocked on ColorOS, but on Android 11
            // and older the app can switch it itself with BLUETOOTH_ADMIN.
            toggle.radio == ShizukuRadios.Radio.BLUETOOTH && canSwitchBluetooth() -> scope.launch {
                withContext(Dispatchers.IO) { setBluetooth(context, !toggle.enabled) }
                delay(1500)
                toggles = readToggles(context)
            }
            ShizukuRadios.hasPermission() -> scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    ShizukuRadios.set(toggle.radio, !toggle.enabled)
                }
                if (!ok) {
                    // Some vendors (ColorOS blocks Bluetooth) deny the shell this
                    // radio, so hand over to the system screen instead.
                    Toast.makeText(
                        context,
                        "${toggle.label} can't be changed directly on this phone",
                        Toast.LENGTH_SHORT
                    ).show()
                    context.startActivity(
                        Intent(toggle.settingsAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                // Radios take a moment to settle before the state reads back.
                delay(1500)
                toggles = readToggles(context)
            }
            ShizukuRadios.isRunning() -> ShizukuRadios.requestPermission()
            else -> context.startActivity(
                Intent(toggle.settingsAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    DisposableEffect(context) {
        val activity = context as? ComponentActivity
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) toggles = readToggles(context)
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    Text(text = "Connectivity", style = MaterialTheme.typography.titleLarge)
    toggles.forEach { toggle ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(toggle.label, modifier = Modifier.weight(1f))
            Switch(
                checked = toggle.enabled,
                onCheckedChange = { onToggle(toggle) }
            )
        }
        if (toggle.radio == ShizukuRadios.Radio.WIFI && toggle.enabled) {
            WifiNetworksSection()
        }
    }
}

private fun canSwitchBluetooth() = Build.VERSION.SDK_INT <= Build.VERSION_CODES.R

@Suppress("DEPRECATION", "MissingPermission")
private fun setBluetooth(context: Context, on: Boolean) {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
    runCatching { if (on) adapter.enable() else adapter.disable() }
}
