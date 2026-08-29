package com.vamshi.aiassistant.wakeword

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * Wake-word detector backed by sherpa-onnx keyword spotting.
 *
 * Runs the streaming zipformer KWS model trained on GigaSpeech entirely
 * on-device - no network, no API key. The phrase itself lives in
 * [KEYWORDS_ASSET] as BPE tokens, not plain text; see README.md ("Changing the
 * wake word") before editing it.
 */
class SherpaWakeWordDetector(context: Context) : WakeWordDetector {

    override val sampleRate = 16_000

    // 32ms per frame. The spotter buffers internally, so this only controls
    // how often we hand it audio, not the model's own chunk size.
    override val frameSize = 512

    // Read from the asset rather than hardcoded, so the notification always
    // names whatever is actually active - editing keywords.txt is enough, and
    // the label cannot drift out of sync with the phrases being listened for.
    override val keyword = readActivePhrases(context)

    private val spotter = KeywordSpotter(
        assetManager = context.assets,
        config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$MODEL_DIR/encoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                    decoder = "$MODEL_DIR/decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                    joiner = "$MODEL_DIR/joiner-epoch-12-avg-2-chunk-16-left-64.onnx",
                ),
                tokens = "$MODEL_DIR/tokens.txt",
                modelType = "zipformer2",
                numThreads = 1,
            ),
            keywordsFile = KEYWORDS_ASSET,
            // Raise keywordsThreshold toward 1.0 for fewer false wakes,
            // lower it for easier triggering. keywordsScore biases the
            // decoder toward the phrase.
            keywordsScore = 1.5f,
            keywordsThreshold = 0.25f,
        )
    )

    private var stream: OnlineStream = spotter.createStream()

    /** Scratch buffer reused every frame to keep the capture loop allocation-free. */
    private val samples = FloatArray(frameSize)

    private var samplesSinceLog = 0
    private var peakSinceLog = 0.0f

    override fun process(frame: ShortArray): Boolean {
        // sherpa-onnx wants normalised float samples in [-1, 1).
        for (i in frame.indices) {
            samples[i] = frame[i] / 32768.0f
        }
        logInputLevel()
        stream.acceptWaveform(samples, sampleRate)

        // Which phrase matched, not just that one did: several are active at
        // once so the log has to say which, otherwise there is no way to tell
        // them apart when tuning.
        var matched = ""
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            val hit = spotter.getResult(stream).keyword
            if (hit.isNotEmpty()) matched = hit
        }

        if (matched.isNotEmpty()) {
            Log.i(TAG, "matched wake phrase: $matched")
            // Clear decoder state so the next utterance starts clean and the
            // same audio cannot match twice.
            spotter.reset(stream)
            return true
        }
        return false
    }

    override fun release() {
        stream.release()
        spotter.release()
    }

    /**
     * Once a second, report the loudest sample seen. Without this there is no
     * way to tell a dead microphone apart from a phrase the model simply did
     * not match - both look identical (silence in the log).
     *
     * Speaking at a normal distance should show peak well above 0.05; a peak
     * pinned near 1.0 means clipping, which also hurts recognition.
     */
    private fun logInputLevel() {
        for (s in samples) {
            val magnitude = if (s < 0) -s else s
            if (magnitude > peakSinceLog) peakSinceLog = magnitude
        }
        samplesSinceLog += samples.size
        if (samplesSinceLog >= sampleRate) {
            Log.d(TAG, "listening, peak level = ${"%.3f".format(peakSinceLog)}")
            samplesSinceLog = 0
            peakSinceLog = 0.0f
        }
    }

    private companion object {
        const val TAG = "NovaWakeWord"
        const val MODEL_DIR = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
        const val KEYWORDS_ASSET = "$MODEL_DIR/keywords.txt"

        /**
         * Pull the human-readable phrases out of keywords.txt for display.
         *
         * Each line is BPE tokens followed by optional `:score`, `#threshold`
         * and `@DISPLAY_NAME` suffixes; only the last is meaningful to a
         * reader, so lines without one are skipped rather than showing raw
         * tokens.
         */
        fun readActivePhrases(context: Context): String = runCatching {
            context.assets.open(KEYWORDS_ASSET).bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    line.substringAfter('@', missingDelimiterValue = "")
                        .trim()
                        .takeIf { it.isNotEmpty() }
                        ?.replace('_', ' ')
                }.toList()
            }
        }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(" / ")
            ?: "wake word"
    }
}
