package com.vamshi.aiassistant.overlay

import android.app.KeyguardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.vamshi.aiassistant.ui.theme.AIAssistantTheme

/**
 * Lock-screen host for the assistant panel.
 *
 * TYPE_APPLICATION_OVERLAY windows are not drawn above the keyguard, so when
 * the device is locked [AssistantTrigger] routes here instead of to
 * [OverlayService]. This activity is transparent and declares
 * showWhenLocked/turnScreenOn, so it renders on top of the lock screen.
 *
 * Note: the keyguard is *not* dismissed. Anything sensitive the panel grows
 * later should first require unlock via [KeyguardManager.requestDismissKeyguard].
 */
class AssistantOverlayActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        setContent {
            AIAssistantTheme {
                OverlayContent(onClose = { finish() })
            }
        }
    }
}
