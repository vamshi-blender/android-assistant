package com.vamshi.aiassistant.wakeword

import android.util.Log
import kotlin.math.sqrt

/**
 * Placeholder engine: never fires on its own, because no real model is wired
 * up yet. It exists so the rest of the pipeline (capture, foreground service,
 * notification, lock-screen routing, overlay launch) can be built and tested
 * now, and so the real engine is a drop-in later.
 *
 * It does one useful thing: it logs the input level roughly once a second, so
 * `adb logcat -s NovaWakeWord` confirms the microphone is actually delivering
 * audio. To exercise the launch path itself, use "Simulate wake word" on the
 * home screen or broadcast [WakeWordService.ACTION_SIMULATE_DETECTION].
 */
class StubWakeWordDetector : WakeWordDetector {

    override val sampleRate = 16_000
    override val frameSize = 512
    override val keyword = "wake word (stub)"

    private var framesSinceLog = 0

    override fun process(frame: ShortArray): Boolean {
        framesSinceLog += frame.size
        if (framesSinceLog >= sampleRate) {
            framesSinceLog = 0
            Log.d(TAG, "mic alive, input level (RMS) = ${"%.0f".format(rms(frame))}")
        }
        return false
    }

    override fun release() = Unit

    private fun rms(frame: ShortArray): Double {
        var sumOfSquares = 0.0
        for (sample in frame) {
            sumOfSquares += sample.toDouble() * sample.toDouble()
        }
        return sqrt(sumOfSquares / frame.size)
    }

    private companion object {
        const val TAG = "NovaWakeWord"
    }
}
