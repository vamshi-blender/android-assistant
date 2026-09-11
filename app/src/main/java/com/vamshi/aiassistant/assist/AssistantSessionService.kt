package com.vamshi.aiassistant.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.vamshi.aiassistant.DeviceToolResult
import com.vamshi.aiassistant.overlay.AssistantTrigger

class AssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistantSession(this)
}

private class AssistantSession(private val appContext: Context) : VoiceInteractionSession(appContext) {
    private var clockRequest: ClockVoiceBridge.Pending? = null

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        setUiEnabled(false)
        val id = args?.getString("clockRequestId")
        if (id == null) {
            AssistantTrigger.launch(appContext)
            finish()
            return
        }
        val request = ClockVoiceBridge.pending?.takeIf { it.id == id }
        if (request == null) { finish(); return }
        if (clockRequest === request) return
        clockRequest = request
        request.finishSession = { finish() }
        try {
            startVoiceActivity(request.intent)
        } catch (error: Exception) {
            complete("failed", error.message ?: "The Clock app does not support confirmed voice actions.")
        }
    }

    private fun complete(status: String, message: String) {
        clockRequest?.result?.complete(DeviceToolResult(status, message))
    }

    override fun onRequestCompleteVoice(request: CompleteVoiceRequest) {
        complete("succeeded", request.voicePrompt?.toString() ?: "The Clock app confirmed completion.")
        request.sendCompleteResult(Bundle())
    }

    override fun onRequestAbortVoice(request: AbortVoiceRequest) {
        complete("failed", request.voicePrompt?.toString() ?: "The Clock app could not complete the action.")
        request.sendAbortResult(Bundle())
    }

    override fun onRequestPickOption(request: PickOptionRequest) {
        complete("requires_user_action", "More than one alarm matches. Specify its time or label.")
        request.cancel()
    }

    override fun onRequestConfirmation(request: ConfirmationRequest) {
        complete("requires_user_action", "The Clock app requires user confirmation. Open Clock to complete this action.")
        request.cancel()
    }

    override fun onTaskFinished(intent: Intent?, taskId: Int) {
        super.onTaskFinished(intent, taskId)
        complete("unknown", "The Clock app closed without confirming the action. Do not retry automatically.")
    }

    override fun onDestroy() {
        complete("unknown", "The Clock session ended before confirmation.")
        super.onDestroy()
    }
}
