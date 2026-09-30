package com.vamshi.aiassistant

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private const val MAX_BRIGHTNESS = 255

@Composable
fun MoreScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var canWrite by remember { mutableStateOf(Settings.System.canWrite(context)) }
    var level by remember { mutableFloatStateOf(readBrightness(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        canWrite = Settings.System.canWrite(context)
        level = readBrightness(context)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        ConnectivityToggles()
        Text(
            text = "Brightness",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 32.dp)
        )
        Text(
            text = "${(level * 100).roundToInt()}%",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 16.dp)
        )
        if (canWrite) {
            Slider(
                value = level,
                onValueChange = {
                    level = it
                    writeBrightness(context, it)
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            )
        } else {
            Text(
                text = "Allow this app to modify system settings to control brightness.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp)
            )
            Button(
                onClick = {
                    permissionLauncher.launch(
                        Intent(
                            Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                },
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Text("Grant permission")
            }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.padding(top = 24.dp)) {
            Text("Back")
        }
    }
}

private fun readBrightness(context: android.content.Context): Float {
    val raw = Settings.System.getInt(
        context.contentResolver,
        Settings.System.SCREEN_BRIGHTNESS,
        MAX_BRIGHTNESS / 2
    )
    return (raw / MAX_BRIGHTNESS.toFloat()).coerceIn(0f, 1f)
}

private fun writeBrightness(context: android.content.Context, level: Float) {
    val resolver = context.contentResolver
    // Manual mode, otherwise auto-brightness overrides the chosen value.
    Settings.System.putInt(
        resolver,
        Settings.System.SCREEN_BRIGHTNESS_MODE,
        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
    )
    Settings.System.putInt(
        resolver,
        Settings.System.SCREEN_BRIGHTNESS,
        (level * MAX_BRIGHTNESS).roundToInt().coerceIn(1, MAX_BRIGHTNESS)
    )
}
