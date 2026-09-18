package com.vamshi.aiassistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private data class SessionAnswer(
        val sdp: String,
        val greetingInstructions: String,
        val greetingBegin: String,
    )

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handledToolCalls = mutableSetOf<String>()
    private val handledDelegations = mutableSetOf<String>()
    private val transcript = StringBuilder()
    private var lastTranscriptHuman: Boolean? = null
    private var selectedModel = AssistantModel.OPENAI
    private var greetingInstructions = ""
    private var greetingBegin = ""
    private var greetingInstructionEventId: String? = null
    private var sessionCuePlayed = false
    private var endCuePlayed = false
    private var farewellJob: Job? = null
    private var farewellArmedAt = 0L
    private var lastFarewellTranscriptAt = 0L
    private var farewellStarted = false
    private var previousAudioMode: Int? = null
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
        selectedModel = AssistantModelSettings.get(context)
        previousAudioMode = audioManager.mode
        // Communication mode enables WebRTC's echo cancellation and call-volume
        // behavior. Leave device selection to Android so wired and Bluetooth
        // headsets remain the preferred route when connected.
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
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
            greetingInstructions = answer.greetingInstructions
            greetingBegin = answer.greetingBegin
            setDescription(connection, SessionDescription(SessionDescription.Type.ANSWER, answer.sdp), local = false)
            started.await()
        }
    }

    fun setMuted(muted: Boolean) {
        // Disable the outgoing microphone track locally; playback remains active.
        microphone?.setEnabled(!muted)
        if (started.isCompleted && !closed.isCompleted) {
            sendCommand(JSONObject().put(
                "type",
                if (muted) "session.input_audio.mute" else "session.input_audio.unmute"
            ))
        }
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
                sessionCuePlayed = true
                LiveSessionCues.play(context, R.raw.session_start)
                if (greetingInstructions.isNotBlank()) {
                    greetingInstructionEventId = "greeting_instructions"
                    sendCommand(JSONObject()
                        .put("type", "session.instructions.append")
                        .put("event_id", greetingInstructionEventId)
                        .put("delegation_id", JSONObject.NULL)
                        .put("content", greetingInstructions))
                }
            }
            "session.input_transcript.delta", "session.output_transcript.delta" -> {
                val text = event.optString("delta")
                val human = event.getString("type") == "session.input_transcript.delta"
                if (!human && farewellJob != null) {
                    farewellStarted = true
                    lastFarewellTranscriptAt = SystemClock.elapsedRealtime()
                }
                if (text.isNotEmpty()) appendTranscript(human, text)
                if (text.isNotEmpty()) events.trySend(Event.Transcript(
                    human = human,
                    text = text,
                    startMs = event.getLong("start_ms"),
                    endMs = event.getLong("end_ms"),
                ))
            }
            "session.delegation.created" -> {
                val delegation = event.optJSONObject("delegation")
                val delegationId = delegation?.optString("id").orEmpty()
                if (selectedModel == AssistantModel.GROQ &&
                    delegation?.optString("target") == "client" &&
                    delegationId.isNotBlank() && handledDelegations.add(delegationId)) {
                    scope.launch {
                        delay(150)
                        handleClientDelegation(delegationId, transcript.toString())
                    }
                }
            }
            "session.closed" -> {
                closed.complete(Unit)
                if (!started.isCompleted) started.completeExceptionally(IllegalStateException("Live voice ended before connecting"))
                events.trySend(Event.Ended)
            }
            "session.input_audio.muted" -> LiveSessionCues.play(context, R.raw.mic_mute)
            "session.input_audio.unmuted" -> LiveSessionCues.play(context, R.raw.mic_unmute)
            "session.instructions.appended" -> {
                if (event.optString("client_event_id") == greetingInstructionEventId) {
                    greetingInstructionEventId = null
                    if (greetingBegin.isNotBlank()) {
                        sendCommand(JSONObject()
                            .put("type", "session.commentary.append")
                            .put("event_id", "greeting_begin")
                            .put("delegation_id", JSONObject.NULL)
                            .put("content", greetingBegin))
                    }
                }
            }
            "response.event" -> handleResponseEvent(event.optJSONObject("event"))
            "error" -> fail(event.optJSONObject("error")?.optString("message") ?: "Live voice failed")
        }
    }

    private fun appendTranscript(human: Boolean, text: String) {
        if (lastTranscriptHuman != human) {
            if (transcript.isNotEmpty()) transcript.append('\n')
            transcript.append(if (human) "User: " else "Assistant: ")
            lastTranscriptHuman = human
        }
        transcript.append(text)
        if (transcript.length > MAX_CLIENT_TRANSCRIPT_CHARS) {
            transcript.delete(0, transcript.length - MAX_CLIENT_TRANSCRIPT_CHARS)
        }
    }

    private suspend fun handleClientDelegation(delegationId: String, transcriptSnapshot: String) {
        var continuation: String? = null
        var toolResults: JSONObject? = null
        var shouldEndSession = false
        try {
            while (!stopping && !disposed) {
                val response = requestClientDelegation(
                    transcript = transcriptSnapshot,
                    continuation = continuation,
                    toolResults = toolResults,
                )
                when (response.getString("type")) {
                    "result" -> {
                        val content = response.getString("content")
                        val sent = sendCommand(JSONObject()
                            .put("type", "session.commentary.append")
                            .put("event_id", "client_result_$delegationId")
                            .put("delegation_id", delegationId)
                            .put("content", content))
                        if (!sent) fail("Unable to return the delegated result to live voice.")
                        else if (shouldEndSession) armCloseAfterFarewell()
                        return
                    }
                    "tools" -> {
                        continuation = response.getString("continuation")
                        val results = JSONObject()
                        val requests = response.getJSONArray("requests")
                        for (index in 0 until requests.length()) {
                            val request = requests.getJSONObject(index)
                            val name = request.getString("name")
                            val result = if (name == "end_session") {
                                shouldEndSession = true
                                DeviceToolResult(
                                    "succeeded",
                                    "End the session after one brief friendly goodbye.",
                                )
                            } else {
                                DeviceClockToolExecutor.execute(
                                    context,
                                    name,
                                    request.getJSONObject("arguments").toString(),
                                )
                            }
                            results.put(request.getString("callId"), result.toJson())
                        }
                        toolResults = results
                    }
                    else -> error("Unknown delegated response")
                }
            }
        } catch (error: Exception) {
            if (stopping || disposed) return
            val message = error.message ?: "Delegated work failed"
            sendCommand(JSONObject()
                .put("type", "session.commentary.append")
                .put("event_id", "client_error_$delegationId")
                .put("delegation_id", delegationId)
                .put("content", "I couldn't complete that request: $message"))
        }
    }

    private suspend fun requestClientDelegation(
        transcript: String,
        continuation: String?,
        toolResults: JSONObject?,
    ): JSONObject = withContext(Dispatchers.IO) {
        val connection = (
            URL(BackendSettings.endpoint(context, "api/live-delegate")).openConnection()
                as HttpURLConnection
            ).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-API-Key", BuildConfig.APP_API_KEY)
        }
        try {
            val request = JSONObject()
                .put("model", selectedModel.wireName)
                .put("transcript", transcript)
                .put("deviceTime", JSONObject()
                    .put("epochMillis", System.currentTimeMillis())
                    .put("timeZoneId", ZoneId.systemDefault().id))
            continuation?.let { request.put("continuation", it) }
            toolResults?.let { request.put("toolResults", it) }
            connection.outputStream.bufferedWriter().use { it.write(request.toString()) }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()
            check(status in 200..299) {
                json?.optString("error")?.takeIf { it.isNotBlank() }
                    ?: "Delegated work failed ($status)"
            }
            json ?: error("Invalid delegated response")
        } finally {
            connection.disconnect()
        }
    }

    private fun handleResponseEvent(event: JSONObject?) {
        if (event?.optString("type") != "response.output_item.done") return
        val item = event.optJSONObject("item") ?: return
        if (item.optString("type") != "function_call") return
        val callId = item.optString("call_id")
        val name = item.optString("name")
        if (callId.isBlank() || name.isBlank() || !handledToolCalls.add(callId)) return
        val arguments = item.optString("arguments", "{}")
        scope.launch {
            val output = when (name) {
                "get_device_time" -> {
                    val now = ZonedDateTime.now()
                    JSONObject()
                        .put("status", "succeeded")
                        .put("localDateTime", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                        .put("timeZoneId", now.zone.id)
                        .put("epochMillis", now.toInstant().toEpochMilli())
                }
                "end_session" -> JSONObject()
                    .put("status", "ending")
                    .put("instruction", "Say one brief friendly goodbye now. The session will close immediately afterward.")
                else -> DeviceClockToolExecutor.execute(context, name, arguments).toJson()
            }
            if (stopping || disposed) return@launch
            val resultSent = sendCommand(JSONObject()
                .put("type", "response.item.create")
                .put("event_id", "tool_result_$callId")
                .put("item", JSONObject()
                    .put("type", "function_call_output")
                    .put("call_id", callId)
                    .put("output", output.toString())))
            val continued = resultSent && sendCommand(JSONObject()
                .put("type", "response.create")
                .put("event_id", "continue_$callId"))
            if (!continued) {
                fail("Unable to return the delegated result to live voice.")
            } else if (name == "end_session") {
                armCloseAfterFarewell()
            }
        }
    }

    private fun armCloseAfterFarewell() {
        if (farewellJob != null) return
        farewellArmedAt = SystemClock.elapsedRealtime()
        lastFarewellTranscriptAt = farewellArmedAt
        farewellStarted = false
        farewellJob = scope.launch {
            while (!stopping && !disposed) {
                delay(300)
                val now = SystemClock.elapsedRealtime()
                val farewellFinished = farewellStarted && now - lastFarewellTranscriptAt >= 1_500
                if (farewellFinished || now - farewellArmedAt >= 9_000) {
                    close()
                    break
                }
            }
        }
    }

    private fun sendCommand(command: JSONObject): Boolean {
        val dataChannel = channel ?: return false
        if (dataChannel.state() != DataChannel.State.OPEN) return false
        return dataChannel.send(DataChannel.Buffer(
            ByteBuffer.wrap(command.toString().toByteArray(Charsets.UTF_8)), false
        ))
    }

    private fun playEndCue() {
        if (!sessionCuePlayed || endCuePlayed) return
        endCuePlayed = true
        LiveSessionCues.play(context, R.raw.session_end)
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
            scope.cancel()
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
                audioManager.mode = mode
            }
            playEndCue()
        }
    }

    private suspend fun createSession(sdp: String): SessionAnswer = withContext(Dispatchers.IO) {
        val connection = (URL(BackendSettings.endpoint(context, "api/live")).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 35_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-API-Key", BuildConfig.APP_API_KEY)
        }
        try {
            val request = JSONObject()
                .put("sdp", sdp)
                .put("model", selectedModel.wireName)
                .put("deviceTime", JSONObject()
                    .put("epochMillis", System.currentTimeMillis())
                    .put("timeZoneId", ZoneId.systemDefault().id))
            connection.outputStream.bufferedWriter().use { it.write(request.toString()) }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()
            check(status in 200..299) { json?.optString("error")?.takeIf { it.isNotBlank() } ?: "Unable to start live voice ($status)" }
            val transportSdp = json?.getJSONObject("transport")?.getString("sdp")
                ?: error("Missing voice session answer")
            val greeting = json?.optJSONObject("greeting")
                ?: error("Missing voice greeting configuration")
            SessionAnswer(
                sdp = transportSdp,
                greetingInstructions = greeting.getString("instructions"),
                greetingBegin = greeting.getString("begin"),
            )
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

    private companion object {
        const val MAX_CLIENT_TRANSCRIPT_CHARS = 24_000
    }
}
