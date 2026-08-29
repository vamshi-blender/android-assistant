package com.vamshi.aiassistant.wakeword

import android.content.Context

/**
 * A wake-word engine. [WakeWordService] owns the microphone and pumps raw PCM
 * through whatever implementation [WakeWordDetectorFactory] hands back, so
 * swapping engines does not touch the capture, notification or launch code.
 *
 * To plug in a real engine (Porcupine, sherpa-onnx, ...):
 *  1. implement this interface, driving the engine from [process]
 *  2. return it from [WakeWordDetectorFactory.create]
 *
 * [process] is called on a dedicated capture thread, never the main thread.
 */
interface WakeWordDetector {

    /** Capture sample rate the engine expects, in Hz. AudioRecord is configured from this. */
    val sampleRate: Int

    /** Samples handed to [process] per call. Engines usually mandate an exact frame size. */
    val frameSize: Int

    /** Human-readable wake phrase, shown in the listening notification. */
    val keyword: String

    /**
     * Consume one frame of mono 16-bit PCM, exactly [frameSize] samples long.
     *
     * @return true if the wake word ended in this frame.
     */
    fun process(frame: ShortArray): Boolean

    /** Release native resources. The detector is not used again afterwards. */
    fun release()
}

/**
 * Chooses which engine the service runs. Swap the return value to change
 * engines; [StubWakeWordDetector] stays around as a no-model fallback for
 * testing the rest of the pipeline.
 */
object WakeWordDetectorFactory {
    fun create(context: Context): WakeWordDetector = SherpaWakeWordDetector(context)
}
