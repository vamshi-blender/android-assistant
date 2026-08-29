package com.vamshi.aiassistant.overlay

import kotlinx.coroutines.delay

/**
 * Stand-ins for the services this overlay will eventually talk to.
 *
 * Every function here is a suspending call with the same shape its real
 * counterpart will have, so swapping in `ChatApi` and the transcription
 * endpoint later is a substitution rather than a redesign:
 *
 *  - [transcribe] becomes the audio upload; cancelling the caller's coroutine
 *    already cancels it, which is how the X button aborts.
 *  - [streamReply] becomes the SSE stream from `/api/chat`; it already emits
 *    progressive chunks, so the UI needs no change when the deltas turn real.
 */
object MockAssistant {

    private val TRANSCRIPTS = listOf(
        "today's meeting",
        "the deployment status",
        "what is on my calendar tomorrow",
        "set a timer for ten minutes",
    )

    private val REPLIES = listOf(
        "Here is a short summary of what you asked about. This is placeholder text standing in for a real streamed answer, long enough to show the conversation area scrolling.",
        "Sure — I can help with that. This mock reply arrives a few words at a time, the same way the real streamed response will.",
        "Got it. Once the backend is wired up this text will come from the agent instead of from a hard-coded list.",
    )

    /** Mock speech-to-text. Cancellable: the delay is a suspension point. */
    suspend fun transcribe(): String {
        delay(1_400)
        return TRANSCRIPTS.random()
    }

    /**
     * Mock streamed reply. Emits growing prefixes of the answer so the caller
     * can render it as it arrives.
     */
    suspend fun streamReply(onDelta: (String) -> Unit) {
        val words = REPLIES.random().split(" ")
        val built = StringBuilder()
        for (word in words) {
            if (built.isNotEmpty()) built.append(' ')
            built.append(word)
            onDelta(built.toString())
            delay(45)
        }
    }
}
