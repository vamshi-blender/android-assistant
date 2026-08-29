package com.vamshi.aiassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceActivityDetectorTest {
    private val config = VoiceActivityConfig(audioThreshold = 0.1f)

    @Test
    fun discardsWhenNoSpeechIsDetectedWithinInitialWindow() {
        val detector = VoiceActivityDetector(config)

        assertEquals(VoiceActivityOutcome.CONTINUE, detector.observe(0.09f, 4_999L))
        assertEquals(VoiceActivityOutcome.DISCARD_NO_SPEECH, detector.observe(0.09f, 5_000L))
        assertFalse(detector.speechDetected)
    }

    @Test
    fun thresholdValueCountsAsSpeech() {
        val detector = VoiceActivityDetector(config)

        assertEquals(VoiceActivityOutcome.CONTINUE, detector.observe(0.1f, 4_999L))
        assertTrue(detector.speechDetected)
    }

    @Test
    fun transcribesAfterThreeContinuousSecondsOfSilence() {
        val detector = VoiceActivityDetector(config)

        detector.observe(0.2f, 1_000L)
        assertEquals(VoiceActivityOutcome.CONTINUE, detector.observe(0.05f, 2_000L))
        assertEquals(VoiceActivityOutcome.CONTINUE, detector.observe(0.05f, 4_999L))
        assertEquals(
            VoiceActivityOutcome.TRANSCRIBE_AFTER_SILENCE,
            detector.observe(0.05f, 5_000L)
        )
    }

    @Test
    fun resumedSpeechResetsSilenceTimer() {
        val detector = VoiceActivityDetector(config)

        detector.observe(0.2f, 1_000L)
        detector.observe(0.05f, 2_000L)
        detector.observe(0.2f, 4_000L)
        assertEquals(VoiceActivityOutcome.CONTINUE, detector.observe(0.05f, 5_000L))
        assertEquals(VoiceActivityOutcome.CONTINUE, detector.observe(0.05f, 7_999L))
        assertEquals(
            VoiceActivityOutcome.TRANSCRIBE_AFTER_SILENCE,
            detector.observe(0.05f, 8_000L)
        )
    }

    @Test
    fun maximumDurationDoesNotResetDuringPauses() {
        val detector = VoiceActivityDetector(config)

        detector.observe(0.2f, 1_000L)
        detector.observe(0.05f, 28_000L)
        assertEquals(
            VoiceActivityOutcome.TRANSCRIBE_AT_MAX_DURATION,
            detector.observe(0.2f, 30_000L)
        )
    }
}
