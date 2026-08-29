package com.vamshi.aiassistant

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File

enum class RecordingEndReason { NO_SPEECH, SILENCE, MAX_DURATION, FILE_SIZE }

data class RecordingEndRequest(
    val reason: RecordingEndReason,
    /** MediaRecorder automatically stops itself for configured hard limits. */
    val recorderAlreadyStopped: Boolean
)

data class ChatRecording(val file: File, val speechDetected: Boolean)

/** Records a short chat utterance as an AAC-encoded M4A file. */
class ChatAudioRecorder(private val context: Context) {
    private val monitorHandler = Handler(Looper.getMainLooper())

    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var voiceActivityDetector: VoiceActivityDetector? = null
    private var monitorAudioLevel: Runnable? = null
    private var endRequested = false

    fun start(
        config: VoiceActivityConfig,
        onAudioLevel: (Float) -> Unit,
        onEndRequested: (RecordingEndRequest) -> Unit
    ) {
        check(recorder == null) { "A recording is already in progress" }

        val output = File.createTempFile("chat-recording-", ".m4a", context.cacheDir)
        val newRecorder = createMediaRecorder()
        val detector = VoiceActivityDetector(config)

        try {
            newRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(16_000)
                setAudioEncodingBitRate(64_000)
                setMaxDuration(config.maxRecordingDurationMs.toInt())
                setMaxFileSize(MAX_FILE_BYTES)
                setOutputFile(output.absolutePath)
                setOnInfoListener { source, what, _ ->
                    // Capture the final interval before a hard limit so speech
                    // beginning just before the cutoff is not missed.
                    detector.observe(
                        readNormalizedAudioLevel(source),
                        config.maxRecordingDurationMs
                    )
                    when (what) {
                        MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ->
                            requestEnd(
                                source,
                                RecordingEndReason.MAX_DURATION,
                                recorderAlreadyStopped = true,
                                onEndRequested
                            )
                        MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED ->
                            requestEnd(
                                source,
                                RecordingEndReason.FILE_SIZE,
                                recorderAlreadyStopped = true,
                                onEndRequested
                            )
                    }
                }
                prepare()
                start()
            }
            recorder = newRecorder
            recordingFile = output
            voiceActivityDetector = detector
            endRequested = false
            startAudioLevelMonitoring(
                newRecorder,
                detector,
                config,
                onAudioLevel,
                onEndRequested
            )
        } catch (error: Exception) {
            newRecorder.release()
            output.delete()
            clearMonitoringState()
            throw error
        }
    }

    fun stop(alreadyStopped: Boolean = false): ChatRecording {
        val activeRecorder = checkNotNull(recorder) { "No recording is in progress" }
        val output = checkNotNull(recordingFile)
        val speechDetected = voiceActivityDetector?.speechDetected == true

        recorder = null
        recordingFile = null
        activeRecorder.setOnInfoListener(null)
        clearMonitoringState()
        try {
            if (!alreadyStopped) activeRecorder.stop()
        } catch (error: RuntimeException) {
            output.delete()
            throw IllegalStateException("The recording was too short. Please try again.", error)
        } finally {
            activeRecorder.release()
        }

        check(output.length() > 0L) { "The recording is empty. Please try again." }
        return ChatRecording(output, speechDetected)
    }

    fun cancel() {
        val activeRecorder = recorder
        val output = recordingFile
        recorder = null
        recordingFile = null
        activeRecorder?.setOnInfoListener(null)
        clearMonitoringState()

        if (activeRecorder != null) {
            runCatching { activeRecorder.stop() }
            activeRecorder.release()
        }
        output?.delete()
    }

    private fun startAudioLevelMonitoring(
        source: MediaRecorder,
        detector: VoiceActivityDetector,
        config: VoiceActivityConfig,
        onAudioLevel: (Float) -> Unit,
        onEndRequested: (RecordingEndRequest) -> Unit
    ) {
        val recordingStartedAt = SystemClock.elapsedRealtime()
        val monitor = object : Runnable {
            override fun run() {
                if (recorder !== source || endRequested) return

                val normalizedLevel = readNormalizedAudioLevel(source)
                onAudioLevel(normalizedLevel)
                val elapsed = SystemClock.elapsedRealtime() - recordingStartedAt

                when (detector.observe(normalizedLevel, elapsed)) {
                    VoiceActivityOutcome.CONTINUE ->
                        monitorHandler.postDelayed(this, config.sampleIntervalMs)
                    VoiceActivityOutcome.DISCARD_NO_SPEECH ->
                        requestEnd(
                            source,
                            RecordingEndReason.NO_SPEECH,
                            recorderAlreadyStopped = false,
                            onEndRequested
                        )
                    VoiceActivityOutcome.TRANSCRIBE_AFTER_SILENCE ->
                        requestEnd(
                            source,
                            RecordingEndReason.SILENCE,
                            recorderAlreadyStopped = false,
                            onEndRequested
                        )
                    VoiceActivityOutcome.TRANSCRIBE_AT_MAX_DURATION ->
                        requestEnd(
                            source,
                            RecordingEndReason.MAX_DURATION,
                            recorderAlreadyStopped = false,
                            onEndRequested
                        )
                }
            }
        }

        monitorAudioLevel = monitor
        monitorHandler.post(monitor)
    }

    private fun requestEnd(
        source: MediaRecorder,
        reason: RecordingEndReason,
        recorderAlreadyStopped: Boolean,
        onEndRequested: (RecordingEndRequest) -> Unit
    ) {
        if (recorder !== source || endRequested) return
        endRequested = true
        monitorAudioLevel?.let(monitorHandler::removeCallbacks)
        onEndRequested(RecordingEndRequest(reason, recorderAlreadyStopped))
    }

    private fun readNormalizedAudioLevel(source: MediaRecorder): Float = runCatching {
        source.maxAmplitude.toFloat() / MAX_AMPLITUDE
    }.getOrDefault(0f)

    private fun clearMonitoringState() {
        monitorAudioLevel?.let(monitorHandler::removeCallbacks)
        monitorAudioLevel = null
        voiceActivityDetector = null
        endRequested = false
    }

    @Suppress("DEPRECATION")
    private fun createMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }

    companion object {
        const val MAX_FILE_MEGABYTES = 5

        private const val MAX_AMPLITUDE = 32_767f
        private const val MAX_FILE_BYTES = MAX_FILE_MEGABYTES * 1024L * 1024L
    }
}
