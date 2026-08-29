package com.vamshi.aiassistant.wakeword

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.vamshi.aiassistant.BuildConfig
import com.vamshi.aiassistant.MainActivity
import com.vamshi.aiassistant.overlay.AssistantTrigger

/**
 * Always-on wake-word listener.
 *
 * Android exposes no usable hotword API to non-privileged apps
 * (AlwaysOnHotwordDetector needs signature-level permissions and an
 * OEM-enrolled keyphrase), so this holds the microphone itself in a
 * foreground service and pumps PCM through a [WakeWordDetector].
 *
 * Deliberately does *not* request audio focus - music keeps playing while we
 * listen, which matters since the assistant is meant to control media.
 */
class WakeWordService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false
    private var captureThread: Thread? = null
    private var detector: WakeWordDetector? = null

    /** Debug-only hook so the launch path can be exercised without a real engine. */
    private var simulateReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (running) return START_STICKY

        if (!hasMicPermission()) {
            Log.w(TAG, "RECORD_AUDIO not granted, refusing to start")
            stopSelf()
            return START_NOT_STICKY
        }

        // Loading the ONNX models can fail with an Error rather than an
        // Exception (a missing native library surfaces as UnsatisfiedLinkError),
        // so catch broadly instead of taking down the process.
        val engine = try {
            WakeWordDetectorFactory.create(this)
        } catch (t: Throwable) {
            Log.e(TAG, "could not load wake-word engine", t)
            stopSelf()
            return START_NOT_STICKY
        }
        detector = engine

        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(engine.keyword),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (e: Exception) {
            // Android 12+ blocks starting a microphone FGS from the background.
            Log.e(TAG, "could not enter foreground, stopping", e)
            stopSelf()
            return START_NOT_STICKY
        }

        registerSimulateReceiver()

        running = true
        _listening.value = true
        captureThread = Thread({ captureLoop(engine) }, "nova-wake-word").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        _listening.value = false
        captureThread?.join(CAPTURE_SHUTDOWN_TIMEOUT_MS)
        captureThread = null

        detector?.release()
        detector = null

        simulateReceiver?.let { unregisterReceiver(it) }
        simulateReceiver = null

        super.onDestroy()
    }

    /** Runs on the capture thread for the whole life of the service. */
    private fun captureLoop(engine: WakeWordDetector) {
        var recorder = openRecorder(engine) ?: run {
            stopSelf()
            return
        }

        val frame = ShortArray(engine.frameSize)
        // Frames must be handed to the engine at exactly frameSize, but a read
        // can come back short, so fill across iterations rather than per-read.
        var filled = 0
        var failures = 0

        try {
            recorder.startRecording()
            while (running) {
                val read = try {
                    recorder.read(frame, filled, frame.size - filled)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "read threw, treating as dead device", e)
                    AudioRecord.ERROR_DEAD_OBJECT
                }

                when {
                    read > 0 -> {
                        failures = 0
                        filled += read
                        if (filled == frame.size) {
                            filled = 0
                            if (engine.process(frame)) onWakeWordDetected()
                        }
                    }

                    // A zero-length read is legal and transient (it is not an
                    // error), so it must not end capture. Previously any
                    // non-positive read killed the loop for good while the
                    // notification still claimed to be listening.
                    read == 0 -> {
                        failures++
                        Thread.sleep(IDLE_READ_BACKOFF_MS)
                    }

                    read == AudioRecord.ERROR_DEAD_OBJECT -> {
                        Log.w(TAG, "audio device died, reopening")
                        runCatching { recorder.stop() }
                        recorder.release()
                        recorder = openRecorder(engine) ?: break
                        recorder.startRecording()
                        filled = 0
                        failures = 0
                        continue
                    }

                    else -> {
                        failures++
                        Log.w(TAG, "AudioRecord.read error $read (strike $failures)")
                        Thread.sleep(ERROR_READ_BACKOFF_MS)
                    }
                }

                if (failures > MAX_CONSECUTIVE_READ_FAILURES) {
                    Log.e(TAG, "no audio after $failures attempts, giving up")
                    break
                }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }

        // Reaching here with `running` still set means capture died on its own.
        // Stop the service too, so the notification cannot advertise a
        // listener that is no longer running.
        if (running) {
            Log.e(TAG, "capture ended unexpectedly, stopping service")
            stopSelf()
        }
    }

    private fun openRecorder(engine: WakeWordDetector): AudioRecord? {
        val minBufferSize = AudioRecord.getMinBufferSize(
            engine.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) {
            Log.e(TAG, "unsupported capture format, sampleRate=${engine.sampleRate}")
            return null
        }

        val recorder = try {
            AudioRecord(
                // VOICE_RECOGNITION skips the aggressive tuning applied to
                // other sources, which is what wake-word engines expect.
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                engine.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                // Generous headroom: if inference briefly falls behind
                // realtime the surplus is buffered instead of dropped, and
                // dropped audio in the middle of the phrase means a miss.
                maxOf(minBufferSize * 4, engine.frameSize * BYTES_PER_SAMPLE * BUFFER_FRAMES)
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "microphone denied", e)
            return null
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialise")
            recorder.release()
            return null
        }
        return recorder
    }

    private var lastDetectionAt = 0L

    private fun onWakeWordDetected() {
        // Ignore repeat hits while the panel is already coming up.
        val now = SystemClock.elapsedRealtime()
        if (now - lastDetectionAt < DETECTION_DEBOUNCE_MS) return
        lastDetectionAt = now

        Log.i(TAG, "wake word detected")
        mainHandler.post { AssistantTrigger.launch(this) }
    }

    private fun hasMicPermission() = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun registerSimulateReceiver() {
        if (!BuildConfig.DEBUG) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.i(TAG, "simulated detection")
                onWakeWordDetected()
            }
        }
        // Exported so `adb shell am broadcast` can reach it - the only way to
        // test the lock-screen path, where no in-app button is reachable.
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(ACTION_SIMULATE_DETECTION),
            ContextCompat.RECEIVER_EXPORTED
        )
        simulateReceiver = receiver
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Wake word listening",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while the assistant is listening for its wake word."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(keyword: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Listening for “$keyword”")
        .setContentText("Tap to open the assistant")
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .addAction(
            0,
            "Stop",
            PendingIntent.getService(
                this,
                1,
                Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    companion object {
        const val ACTION_STOP = "com.vamshi.aiassistant.action.STOP_WAKE_WORD"
        const val ACTION_SIMULATE_DETECTION = "com.vamshi.aiassistant.action.SIMULATE_WAKE"

        private val _listening = MutableStateFlow(false)

        /** Whether the listener is currently capturing, for the UI to reflect. */
        val listening: StateFlow<Boolean> = _listening.asStateFlow()

        private const val TAG = "NovaWakeWord"
        private const val CHANNEL_ID = "wake_word"
        private const val NOTIFICATION_ID = 1001

        private const val BYTES_PER_SAMPLE = 2
        private const val BUFFER_FRAMES = 16
        private const val DETECTION_DEBOUNCE_MS = 2_000L
        private const val CAPTURE_SHUTDOWN_TIMEOUT_MS = 1_000L

        private const val IDLE_READ_BACKOFF_MS = 5L
        private const val ERROR_READ_BACKOFF_MS = 20L
        private const val MAX_CONSECUTIVE_READ_FAILURES = 100

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WakeWordService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}
