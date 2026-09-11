package com.vamshi.aiassistant.assist

import android.content.Intent
import android.os.Bundle
import com.vamshi.aiassistant.DeviceToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** All access is on the main dispatcher. A missing callback is never success. */
internal object ClockVoiceBridge {
    private val mutex = Mutex()
    var service: AssistantVoiceInteractionService? = null
    var pending: Pending? = null
        private set

    class Pending(val id: String, val intent: Intent) {
        val result = CompletableDeferred<DeviceToolResult>()
        var finishSession: (() -> Unit)? = null
    }

    suspend fun execute(intent: Intent): DeviceToolResult = mutex.withLock {
        val voiceService = service ?: return@withLock DeviceToolResult(
            "failed", "Set this app as the default digital assistant to perform confirmed Clock actions."
        )
        val request = Pending(UUID.randomUUID().toString(), intent)
        pending = request
        try {
            voiceService.showSession(Bundle().apply { putString("clockRequestId", request.id) }, 0)
            withTimeoutOrNull(25_000) { request.result.await() }
                ?: DeviceToolResult("unknown", "The Clock app did not confirm completion within 25 seconds. The action may have happened; do not retry automatically.")
        } finally {
            pending = null
            request.finishSession?.invoke()
        }
    }
}
