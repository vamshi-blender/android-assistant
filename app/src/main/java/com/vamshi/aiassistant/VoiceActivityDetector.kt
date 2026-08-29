package com.vamshi.aiassistant

data class VoiceActivityConfig(
    /** Normalized peak amplitude from 0.0 (silence) to 1.0 (maximum). */
    val audioThreshold: Float = DEFAULT_AUDIO_THRESHOLD,
    val initialDetectionWindowMs: Long = 5_000L,
    val silenceTimeoutMs: Long = 3_000L,
    val maxRecordingDurationMs: Long = 30_000L,
    val sampleIntervalMs: Long = 100L
) {
    init {
        require(audioThreshold in 0f..1f) { "audioThreshold must be between 0 and 1" }
        require(initialDetectionWindowMs > 0L)
        require(silenceTimeoutMs > 0L)
        require(maxRecordingDurationMs >= initialDetectionWindowMs)
        require(maxRecordingDurationMs <= Int.MAX_VALUE)
        require(sampleIntervalMs > 0L)
    }

    companion object {
        const val DEFAULT_AUDIO_THRESHOLD = 0.04f
    }
}

enum class VoiceActivityOutcome {
    CONTINUE,
    DISCARD_NO_SPEECH,
    TRANSCRIBE_AFTER_SILENCE,
    TRANSCRIBE_AT_MAX_DURATION
}

/** Pure state machine for deciding when a voice recording should end. */
class VoiceActivityDetector(private val config: VoiceActivityConfig) {
    var speechDetected: Boolean = false
        private set

    private var silenceStartedAtMs: Long? = null

    fun observe(normalizedAudioLevel: Float, elapsedMs: Long): VoiceActivityOutcome {
        val elapsed = elapsedMs.coerceAtLeast(0L)
        val speechIsActive = normalizedAudioLevel >= config.audioThreshold

        if (speechIsActive) {
            speechDetected = true
            silenceStartedAtMs = null
        }

        if (!speechDetected) {
            return if (elapsed >= config.initialDetectionWindowMs) {
                VoiceActivityOutcome.DISCARD_NO_SPEECH
            } else {
                VoiceActivityOutcome.CONTINUE
            }
        }

        // The maximum duration is measured from the beginning of the recording
        // and never resets when speech resumes after a pause.
        if (elapsed >= config.maxRecordingDurationMs) {
            return VoiceActivityOutcome.TRANSCRIBE_AT_MAX_DURATION
        }

        if (!speechIsActive) {
            val silenceStarted = silenceStartedAtMs ?: elapsed.also {
                silenceStartedAtMs = it
            }
            if (elapsed - silenceStarted >= config.silenceTimeoutMs) {
                return VoiceActivityOutcome.TRANSCRIBE_AFTER_SILENCE
            }
        }

        return VoiceActivityOutcome.CONTINUE
    }
}
