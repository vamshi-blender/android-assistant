package com.vamshi.aiassistant.overlay.composer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext

/**
 * Supplies normalised 0..1 audio levels for [Waveform] to draw.
 *
 * Kept behind an interface so the waveform never knows whether it is showing a
 * real microphone or a stand-in, and so a future real recorder (one that also
 * writes a file for transcription) can be dropped in without touching the UI.
 */
interface AmplitudeSource {
    /** Emits until collection stops. Emission rate should be >= ~20Hz. */
    fun levels(): Flow<Float>
}

/**
 * Live microphone level.
 *
 * Only reads amplitude - it deliberately does not record to a file, because
 * this iteration has no transcription to feed. Falls back to [MockAmplitudeSource]
 * if the microphone cannot be opened, which is a real possibility here: the
 * wake-word service holds an AudioRecord for as long as it is listening, and
 * most devices will not grant a second concurrent capture to the same app.
 */
class MicAmplitudeSource(private val context: Context) : AmplitudeSource {

    override fun levels(): Flow<Float> = flow {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "no RECORD_AUDIO permission, using synthetic levels")
            emitAll(MockAmplitudeSource().levels())
            return@flow
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, FRAME * 2 * 4)
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        }.getOrNull()

        if (recorder == null) {
            // Most likely the wake-word listener already owns the microphone.
            Log.w(TAG, "microphone unavailable, using synthetic levels")
            emitAll(MockAmplitudeSource().levels())
            return@flow
        }

        val buffer = ShortArray(FRAME)
        try {
            recorder.startRecording()
            while (currentCoroutineContext().isActive) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    delay(POLL_MS)
                    continue
                }
                emit(normalise(buffer, read))
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * RMS, boosted and soft-compressed. The low exponent lifts quiet room tone
     * well clear of the idle floor while leaving the ceiling alone (1^n == 1),
     * so normal speech still reaches full height. Same shaping as the reference
     * web implementation, so the bars read identically.
     */
    private fun normalise(buffer: ShortArray, count: Int): Float {
        var sumSquares = 0.0
        for (i in 0 until count) {
            val deviation = buffer[i] / 32768.0
            sumSquares += deviation * deviation
        }
        val rms = sqrt(sumSquares / count)
        return min(1.0, (rms * GAIN).pow(COMPRESSION)).toFloat()
    }

    private companion object {
        const val TAG = "OverlayComposer"
        const val SAMPLE_RATE = 16_000
        const val FRAME = 1024
        const val POLL_MS = 50L
        const val GAIN = 9.0
        const val COMPRESSION = 0.45
    }
}

/**
 * Plausible-looking levels for when there is no microphone to read - a slow
 * swell with jitter, so the waveform still animates rather than flat-lining.
 */
class MockAmplitudeSource : AmplitudeSource {
    override fun levels(): Flow<Float> = flow {
        var t = 0.0
        while (currentCoroutineContext().isActive) {
            val swell = (sin(t) + 1.0) / 2.0
            val jitter = abs(sin(t * 7.3)) * 0.35
            emit((0.15 + swell * 0.5 + jitter).coerceIn(0.0, 1.0).toFloat())
            t += 0.18
            delay(50)
        }
    }
}
