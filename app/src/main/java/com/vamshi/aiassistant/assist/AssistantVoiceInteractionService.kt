package com.vamshi.aiassistant.assist

import android.service.voice.VoiceInteractionService
import com.vamshi.aiassistant.DeviceToolResult

class AssistantVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        ClockVoiceBridge.service = this
    }

    override fun onShutdown() {
        if (ClockVoiceBridge.service === this) ClockVoiceBridge.service = null
        ClockVoiceBridge.pending?.result?.complete(DeviceToolResult("unknown", "The voice service stopped before Clock confirmation."))
        super.onShutdown()
    }
}
