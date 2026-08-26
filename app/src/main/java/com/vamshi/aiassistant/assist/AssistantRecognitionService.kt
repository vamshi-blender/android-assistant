package com.vamshi.aiassistant.assist

import android.content.Intent
import android.speech.RecognitionService

/**
 * No-op recognition service. We don't need speech recognition, but the
 * VoiceInteractionService manifest metadata requires one to be declared.
 */
class AssistantRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {}
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
