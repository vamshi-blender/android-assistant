package com.vamshi.aiassistant.overlay

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent

/**
 * Single entry point for "show the assistant", used by every trigger:
 * the power-button assist gesture, the wake word, and the in-app button.
 *
 * Picks the right presentation for the current lock state, since an overlay
 * window cannot draw above the keyguard.
 */
object AssistantTrigger {

    fun launch(context: Context) {
        val keyguardManager = context.getSystemService(KeyguardManager::class.java)

        if (keyguardManager?.isKeyguardLocked == true) {
            // Background activity starts are normally blocked, but holding
            // SYSTEM_ALERT_WINDOW (which we already need for the overlay)
            // exempts us.
            context.startActivity(
                Intent(context, AssistantOverlayActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } else {
            context.startService(Intent(context, OverlayService::class.java))
        }
    }
}
