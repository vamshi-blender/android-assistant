package com.vamshi.aiassistant.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.vamshi.aiassistant.overlay.OverlayService

class AssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return AssistantSession(this)
    }
}

/**
 * Invoked by the system when the assist gesture (e.g. power-button long-press,
 * once this app is set as the default assistant) fires. We never show any
 * session UI of our own - we just launch the overlay and immediately finish.
 */
private class AssistantSession(private val appContext: Context) : VoiceInteractionSession(appContext) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        appContext.startService(Intent(appContext, OverlayService::class.java))
        finish()
    }
}
