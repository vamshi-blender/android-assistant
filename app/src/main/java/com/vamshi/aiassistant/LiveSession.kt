package com.vamshi.aiassistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One audio-only Live call. Public methods and events are consumed on the main thread. */
class LiveSession(private val context: Context) {
    sealed interface Event {
        data object Connected : Event
        data class Transcript(val human: Boolean, val text: String, val startMs: Long, val endMs: Long) : Event
        data class Error(val message: String) : Event
        data object Ended : Event
    }

    val events = Channel<Event>(Channel.UNLIMITED)
    private val main = Handler(Looper.getMainLooper())
    private val iceReady = CompletableDeferred<Unit>()
    private val started = CompletableDeferred<Unit>()
    private val closed = CompletableDeferred<Unit>()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var previousAudioMode: Int? = null
    private var previousSpeakerphone = false
    private var factory: PeerConnectionFactory? = null
    private var audioModule: JavaAudioDeviceModule? = null
    private var audioSource: AudioSource? = null
    private var microphone: AudioTrack? = null
    private var peer: PeerConnection? = null
    private var channel: DataChannel? = null
    private var stopping = false
    private var disposed = false

    suspend fun connect() {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microphone permission is required for live voice"
        }
        check(BuildConfig.APP_API_KEY.isNotBlank()) { "APP_API_KEY is not configured in this app build" }
        previousAudioMode = audioManager.mode
        @Suppress("DEPRECATION")
        run {
            previousSpeakerphone = audioManager.isSpeakerphoneOn
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = true
        }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        val module = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        audioModule = module
        val connectionFactory = PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory()
        factory = connectionFactory
        val connection = checkNotNull(connectionFactory.createPeerConnection(
            PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            },
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                    if (state == PeerConnection.IceGatheringState.COMPLETE) iceReady.complete(Unit)
                }
                override fun onIceCandidate(candidate: IceCandidate?) = Unit
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
                override fun onAddStream(stream: MediaStream?) = Unit
                override fun onRemoveStream(stream: MediaStream?) = Unit
                override fun onDataChannel(dataChannel: DataChannel?) = Unit
                override fun onRenegotiationNeeded() = Unit
                override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                    if (state == PeerConnection.PeerConnectionState.FAILED) post {
                        fail("Live voice connection was lost. Please start a new conversation.")
                    }
                }
            }
        )) { "Unable to initialize voice connection" }
        peer = connection
        val source = connectionFactory.createAudioSource(MediaConstraints())
        audioSource = source
        val track = connectionFactory.createAudioTrack("live-microphone", source)
        microphone = track
        connection.addTrack(track, listOf("live-audio"))
        channel = connection.createDataChannel("oai-events", DataChannel.Init()).also { dataChannel ->
            dataChannel.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit
                override fun onStateChange() {
                    val state = dataChannel.state()
                    if (state == DataChannel.State.CLOSED) post {
                        if (!stopping && !closed.isCompleted) fail("Live voice disconnected. Please try again.")
                    }
                }
                override fun onMessage(buffer: DataChannel.Buffer) {
                    if (buffer.binary) return
                    val bytes = ByteArray(buffer.data.remaining())
                    buffer.data.get(bytes)
                    post {
                        runCatching { handleEvent(JSONObject(String(bytes, Charsets.UTF_8))) }
                            .onFailure { fail("Received an invalid live voice event") }
                    }
                }
            })
        }
        withTimeout(45_000) {
            val offer = createOffer(connection)
            setDescription(connection, offer, local = true)
            withTimeout(10_000) { iceReady.await() }
            val answer = createSession(checkNotNull(connection.localDescription).description)
            setDescription(connection, SessionDescription(SessionDescription.Type.ANSWER, answer), local = false)
            started.await()
        }
    }

    fun setMuted(muted: Boolean) {
        // Disable the outgoing microphone track locally; playback remains active.
        microphone?.setEnabled(!muted)
    }

    private fun post(action: () -> Unit) {
        main.post { if (!disposed) action() }
    }

    private fun fail(message: String) {
        if (stopping) return
        if (!started.isCompleted) started.completeExceptionally(IllegalStateException(message))
        events.trySend(Event.Error(message))
    }

    private fun handleEvent(event: JSONObject) {
        when (event.optString("type")) {
            "session.started" -> {
                started.complete(Unit)
                events.trySend(Event.Connected)
            }
            "session.input_transcript.delta", "session.output_transcript.delta" -> {
                val text = event.optString("delta")
                if (text.isNotEmpty()) events.trySend(Event.Transcript(
                    human = event.getString("type") == "session.input_transcript.delta",
                    text = text,
                    startMs = event.getLong("start_ms"),
                    endMs = event.getLong("end_ms"),
                ))
            }
            "session.closed" -> {
                closed.complete(Unit)
                if (!started.isCompleted) started.completeExceptionally(IllegalStateException("Live voice ended before connecting"))
                events.trySend(Event.Ended)
            }
            "error" -> fail(event.optJSONObject("error")?.optString("message") ?: "Live voice failed")
        }
    }

    /** Keep the event channel alive for finalization, then release microphone and native resources. */
    suspend fun close() {
        if (disposed) return
        stopping = true
        microphone?.setEnabled(false)
        try {
            val dataChannel = channel
            if (started.isCompleted && !started.isCancelled && !closed.isCompleted && dataChannel?.state() == DataChannel.State.OPEN) {
                val sent = dataChannel.send(DataChannel.Buffer(
                    ByteBuffer.wrap("{\"type\":\"session.close\"}".toByteArray()), false
                ))
                if (!sent || withTimeoutOrNull(3_000) { closed.await() } == null) {
                    Log.w("LiveSession", "Closed transport without confirmed final session usage")
                }
            }
        } finally {
            disposed = true
            channel?.unregisterObserver()
            channel?.close()
            peer?.close()
            channel?.dispose()
            peer?.dispose()
            microphone?.dispose()
            audioSource?.dispose()
            factory?.dispose()
            audioModule?.release()
            events.close()
            previousAudioMode?.let { mode ->
                @Suppress("DEPRECATION")
                run { audioManager.isSpeakerphoneOn = previousSpeakerphone }
                audioManager.mode = mode
            }
        }
    }

    private suspend fun createSession(sdp: String): String = withContext(Dispatchers.IO) {
        val connection = (URL(BackendSettings.endpoint(context, "api/live")).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 35_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-API-Key", BuildConfig.APP_API_KEY)
        }
        try {
            connection.outputStream.bufferedWriter().use { it.write(JSONObject().put("sdp", sdp).toString()) }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()
            check(status in 200..299) { json?.optString("error")?.takeIf { it.isNotBlank() } ?: "Unable to start live voice ($status)" }
            json?.getJSONObject("transport")?.getString("sdp") ?: error("Missing voice session answer")
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun createOffer(connection: PeerConnection): SessionDescription = suspendCancellableCoroutine { continuation ->
        connection.createOffer(object : DescriptionObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                if (continuation.isActive) continuation.resume(description)
            }
            override fun onCreateFailure(message: String) {
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
            }
        }, MediaConstraints())
    }

    private suspend fun setDescription(connection: PeerConnection, description: SessionDescription, local: Boolean): Unit =
        suspendCancellableCoroutine { continuation ->
            val observer = object : DescriptionObserver() {
                override fun onSetSuccess() {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                override fun onSetFailure(message: String) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
                }
            }
            if (local) connection.setLocalDescription(observer, description)
            else connection.setRemoteDescription(observer, description)
        }

    private open class DescriptionObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(message: String) = Unit
        override fun onSetFailure(message: String) = Unit
    }
}
