package com.vamshi.aiassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vamshi.aiassistant.overlay.OverlayService
import com.vamshi.aiassistant.ui.theme.AIAssistantTheme
import com.vamshi.aiassistant.wakeword.WakeWordService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AIAssistantTheme {
                var showChat by rememberSaveable { mutableStateOf(false) }
                BackHandler(enabled = showChat) { showChat = false }
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (showChat) {
                        ChatScreen(modifier = Modifier.padding(innerPadding))
                    } else {
                        HomeScreen(
                            onOpenChat = { showChat = true },
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HomeScreen(onOpenChat: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var backendTarget by remember { mutableStateOf(BackendSettings.get(context)) }

    fun selectBackend(target: BackendTarget) {
        BackendSettings.set(context, target)
        backendTarget = target
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(context)) {
            context.startService(Intent(context, OverlayService::class.java))
        }
    }

    val listening by WakeWordService.listening.collectAsState()

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            WakeWordService.start(context)
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "AI Assistant")
        Text(
            text = "Backend",
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BackendTarget.entries.forEach { target ->
                if (target == backendTarget) {
                    Button(onClick = { selectBackend(target) }) {
                        Text("✓ ${target.displayName}")
                    }
                } else {
                    OutlinedButton(onClick = { selectBackend(target) }) {
                        Text(target.displayName)
                    }
                }
            }
        }
        Text(
            text = backendTarget.baseUrl,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall
        )
        Button(
            onClick = onOpenChat,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text("Open Chat")
        }
        Button(
            onClick = {
                if (Settings.canDrawOverlays(context)) {
                    context.startService(Intent(context, OverlayService::class.java))
                } else {
                    overlayPermissionLauncher.launch(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
            },
            modifier = Modifier.padding(top = 12.dp)
        ) {
            Text("Show Overlay")
        }
        Button(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            modifier = Modifier.padding(top = 12.dp)
        ) {
            Text("Set as Default Assistant")
        }
        Button(
            onClick = {
                if (listening) {
                    WakeWordService.stop(context)
                } else {
                    val needed = buildList {
                        add(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }.filter {
                        ContextCompat.checkSelfPermission(context, it) !=
                            PackageManager.PERMISSION_GRANTED
                    }

                    if (needed.isEmpty()) {
                        WakeWordService.start(context)
                    } else {
                        micPermissionLauncher.launch(needed.toTypedArray())
                    }
                }
            },
            modifier = Modifier.padding(top = 12.dp)
        ) {
            // Deliberately not naming the phrase - it is defined in
            // keywords.txt and the notification reports it from there.
            Text(if (listening) "Stop Listening" else "Start Listening")
        }
    }
}
