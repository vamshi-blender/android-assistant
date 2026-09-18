package com.vamshi.aiassistant.overlay

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import com.vamshi.aiassistant.AssistantActivityItem
import com.vamshi.aiassistant.AssistantOutputPhase
import com.vamshi.aiassistant.ChatApi
import com.vamshi.aiassistant.ChatAudioRecorder
import com.vamshi.aiassistant.ChatMessage
import com.vamshi.aiassistant.ChatRecording
import com.vamshi.aiassistant.ChatStreamEvent
import com.vamshi.aiassistant.LiveSession
import com.vamshi.aiassistant.toolResultStatus
import com.vamshi.aiassistant.RecordingEndReason
import com.vamshi.aiassistant.RecordingEndRequest
import com.vamshi.aiassistant.ToolExecutionStatus
import com.vamshi.aiassistant.VoiceActivityConfig
import com.vamshi.aiassistant.appendTextDelta
import com.vamshi.aiassistant.overlay.composer.ComposerMode
import com.vamshi.aiassistant.overlay.composer.LiveComposerBar
import com.vamshi.aiassistant.overlay.composer.MessageComposer
import com.vamshi.aiassistant.overlay.composer.OverlayStyle
import com.vamshi.aiassistant.overlay.composer.rememberComposerState
import com.vamshi.aiassistant.wakeword.WakeWordService
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.pow

private const val SHOW_GRADIENT = false

/** A real, short-lived chat session for both overlay hosts. */
@Composable
fun OverlayContent(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val composer = rememberComposerState()
    val listState = rememberLazyListState()
    val audioRecorder = remember { ChatAudioRecorder(context.applicationContext) }
    val voiceActivityConfig = remember {
        VoiceActivityConfig(audioThreshold = VoiceActivityConfig.DEFAULT_AUDIO_THRESHOLD)
    }
    val amplitudes = remember {
        MutableSharedFlow<Float>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    }

    var nextId by remember { mutableStateOf(1L) }
    var conversationId by remember { mutableStateOf<String?>(null) }
    var isStreaming by remember { mutableStateOf(false) }
    var streamJob by remember { mutableStateOf<Job?>(null) }
    var preparedRecording by remember { mutableStateOf<ChatRecording?>(null) }
    var resumeWakeWordAfterRecording by remember { mutableStateOf(false) }
    var liveSessionActive by remember { mutableStateOf(false) }
    var resumeWakeWordAfterLiveSession by remember { mutableStateOf(false) }
    var liveMuted by remember { mutableStateOf(false) }
    var awaitingLivePermission by remember { mutableStateOf(false) }

    fun updateAssistant(id: Long, update: (ChatMessage) -> ChatMessage) {
        val index = messages.indexOfFirst { it.id == id }
        if (index >= 0) messages[index] = update(messages[index])
    }

    var liveSession by remember { mutableStateOf<LiveSession?>(null) }
    var liveStatus by remember { mutableStateOf("Connecting…") }

    fun beginLiveSession() {
        if (isStreaming || composer.mode != ComposerMode.EDITING || liveSession != null) return
        if (!MicPermissionActivity.hasPermission(context)) {
            awaitingLivePermission = true
            MicPermissionActivity.request(context)
            return
        }
        resumeWakeWordAfterLiveSession = WakeWordService.listening.value
        if (resumeWakeWordAfterLiveSession) WakeWordService.stop(context)
        liveMuted = false
        liveSessionActive = true
    }

    LaunchedEffect(Unit) {
        MicPermissionActivity.results.collect { granted ->
            if (awaitingLivePermission) {
                awaitingLivePermission = false
                if (granted) beginLiveSession()
            }
        }
    }

    LaunchedEffect(liveSessionActive) {
        if (!liveSessionActive) return@LaunchedEffect
        val session = LiveSession(context.applicationContext)
        liveSession = session
        liveStatus = "Connecting…"
        // Separate timestamp groups keep overlapping speakers in their own rows.
        val captions = LiveCaptions()
        val rowIds = mutableMapOf<Long, Long>()
        try {
            session.connect()
            for (event in session.events) {
                when (event) {
                    LiveSession.Event.Connected -> liveStatus = "Listening"
                    is LiveSession.Event.Transcript -> {
                        val caption = captions.append(event.human, event.text, event.startMs, event.endMs)
                        val messageId = rowIds.getOrPut(caption.id) {
                            val id = nextId++
                            messages += ChatMessage(id, "", isHuman = caption.human)
                            id
                        }
                        updateAssistant(messageId) { it.copy(text = caption.text) }
                    }
                    is LiveSession.Event.Error -> error(event.message)
                    LiveSession.Event.Ended -> break
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Toast.makeText(context, error.message ?: "Unable to start live voice", Toast.LENGTH_LONG).show()
        } finally {
            liveStatus = "Ending…"
            withContext(NonCancellable) { session.close() }
            liveSession = null
            liveMuted = false
            liveSessionActive = false
            if (resumeWakeWordAfterLiveSession) {
                resumeWakeWordAfterLiveSession = false
                WakeWordService.start(context)
            }
        }
    }

    fun restoreWakeWordListener() {
        if (resumeWakeWordAfterRecording) {
            resumeWakeWordAfterRecording = false
            WakeWordService.start(context)
        }
    }

    fun stopRecorder(endRequest: RecordingEndRequest? = null): ChatRecording? {
        val recording = runCatching {
            audioRecorder.stop(alreadyStopped = endRequest?.recorderAlreadyStopped == true)
        }.onFailure { error ->
            Toast.makeText(
                context,
                error.message ?: "Unable to stop recording",
                Toast.LENGTH_LONG
            ).show()
        }.getOrNull()
        restoreWakeWordListener()
        return recording
    }

    fun handleAutomaticRecordingEnd(request: RecordingEndRequest) {
        val recording = stopRecorder(request)
        if (request.reason == RecordingEndReason.NO_SPEECH) {
            recording?.file?.delete()
            composer.cancelRecording()
            Toast.makeText(context, "No speech detected", Toast.LENGTH_SHORT).show()
            return
        }

        if (request.reason == RecordingEndReason.MAX_DURATION) {
            Toast.makeText(
                context,
                "Recording stopped after ${voiceActivityConfig.maxRecordingDurationMs / 1_000} seconds",
                Toast.LENGTH_SHORT
            ).show()
        }
        if (request.reason == RecordingEndReason.FILE_SIZE) {
            Toast.makeText(
                context,
                "Recording stopped at ${ChatAudioRecorder.MAX_FILE_MEGABYTES} MB",
                Toast.LENGTH_SHORT
            ).show()
        }

        if (recording?.speechDetected == true) {
            preparedRecording = recording
            composer.confirmRecording()
        } else {
            recording?.file?.delete()
            composer.cancelRecording()
            Toast.makeText(context, "No speech detected", Toast.LENGTH_SHORT).show()
        }
    }

    fun beginRecording(): Boolean {
        if (isStreaming || composer.mode != ComposerMode.EDITING) return false
        preparedRecording?.file?.delete()
        preparedRecording = null
        resumeWakeWordAfterRecording = WakeWordService.listening.value
        if (resumeWakeWordAfterRecording) WakeWordService.stop(context)

        return runCatching {
            audioRecorder.start(
                config = voiceActivityConfig,
                // MediaRecorder exposes a raw peak ratio. Shape it for display
                // the same way the earlier overlay meter shaped microphone
                // input; the recorder still feeds the unmodified value to VAD.
                onAudioLevel = { amplitudes.tryEmit(shapeWaveformLevel(it)) },
                onEndRequested = ::handleAutomaticRecordingEnd
            )
        }.fold(
            onSuccess = { true },
            onFailure = { error ->
                restoreWakeWordListener()
                Toast.makeText(
                    context,
                    error.message ?: "Unable to start recording",
                    Toast.LENGTH_LONG
                ).show()
                false
            }
        )
    }

    fun cancelRecording() {
        audioRecorder.cancel()
        preparedRecording?.file?.delete()
        preparedRecording = null
        restoreWakeWordListener()
    }

    DisposableEffect(Unit) {
        onDispose {
            streamJob?.cancel()
            audioRecorder.cancel()
            preparedRecording?.file?.delete()
            restoreWakeWordListener()
        }
    }

    LaunchedEffect(
        messages.size,
        messages.lastOrNull()?.text,
        messages.lastOrNull()?.activityItems?.size
    ) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose
            )
    ) {
        val conversationMaxHeight = maxHeight * CONVERSATION_HEIGHT_FRACTION
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .then(if (SHOW_GRADIENT) Modifier.background(bottomGradient()) else Modifier)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (messages.isNotEmpty()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = conversationMaxHeight)
                        .background(OverlayStyle.Background, RoundedCornerShape(OverlayStyle.Corner))
                        .border(1.dp, OverlayStyle.Border, RoundedCornerShape(OverlayStyle.Corner))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        OverlayMessageRow(
                            message = message,
                            onToggleActivity = {
                                updateAssistant(message.id) { current ->
                                    current.copy(isActivityExpanded = !current.isActivityExpanded)
                                }
                            }
                        )
                    }
                }
            }

            if (liveSessionActive || liveSession != null) {
                LiveComposerBar(
                    muted = liveMuted,
                    status = if (liveMuted && liveStatus == "Listening") "Muted" else liveStatus,
                    onMuteToggle = {
                        liveMuted = !liveMuted
                        liveSession?.setMuted(liveMuted)
                    },
                    onEnd = {
                        liveSessionActive = false
                    }
                )
                return@Column
            }

            MessageComposer(
                state = composer,
                amplitudes = amplitudes,
                onStartRecording = ::beginRecording,
                onCancelRecording = ::cancelRecording,
                onLiveSession = ::beginLiveSession,
                transcribe = {
                    val recording = preparedRecording ?: stopRecorder()
                    preparedRecording = null
                    if (recording == null) error("Unable to finish recording")
                    if (!recording.speechDetected) {
                        recording.file.delete()
                        error("No speech detected")
                    }
                    try {
                        ChatApi.transcribeAudio(context, recording.file).getOrThrow()
                    } finally {
                        recording.file.delete()
                    }
                },
                onTranscriptionError = { error ->
                    Toast.makeText(
                        context,
                        error.message ?: "Transcription failed",
                        Toast.LENGTH_LONG
                    ).show()
                },
                onSend = { text ->
                    if (isStreaming) {
                        Toast.makeText(
                            context,
                            "Wait for the current response",
                            Toast.LENGTH_SHORT
                        ).show()
                        false
                    } else {
                        messages += ChatMessage(nextId++, text, isHuman = true)
                        val assistantId = nextId++
                        messages += ChatMessage(
                            id = assistantId,
                            text = "",
                            isHuman = false,
                            isStreaming = true
                        )
                        isStreaming = true
                        streamJob = scope.launch {
                            try {
                                ChatApi.streamAssistantResponse(text, conversationId, context).collect { event ->
                                    when (event) {
                                        is ChatStreamEvent.ConversationReady -> {
                                            conversationId = event.conversationId
                                        }
                                        is ChatStreamEvent.AssistantTextDelta -> {
                                            updateAssistant(assistantId) { current ->
                                                if (event.phase == AssistantOutputPhase.COMMENTARY) {
                                                    val index = current.activityItems.indexOfFirst {
                                                        it is AssistantActivityItem.ProgressUpdate &&
                                                            it.id == event.messageId
                                                    }
                                                    val activity = current.activityItems.toMutableList()
                                                    if (index < 0) {
                                                        activity += AssistantActivityItem.ProgressUpdate(
                                                            event.messageId,
                                                            event.delta
                                                        )
                                                    } else {
                                                        val existing = activity[index]
                                                            as AssistantActivityItem.ProgressUpdate
                                                        activity[index] = existing.copy(
                                                            content = appendTextDelta(
                                                                existing.content,
                                                                event.delta,
                                                                event.startsNewTextSegment
                                                            )
                                                        )
                                                    }
                                                    current.copy(
                                                        activityItems = activity,
                                                        activityStartedAt = current.activityStartedAt
                                                            ?: System.currentTimeMillis(),
                                                        isActivityExpanded = true
                                                    )
                                                } else {
                                                    val firstFinalDelta = current.text.isEmpty()
                                                    current.copy(
                                                        text = appendTextDelta(
                                                            current.text,
                                                            event.delta,
                                                            event.startsNewTextSegment
                                                        ),
                                                        activityCompletedAt = if (
                                                            firstFinalDelta &&
                                                            current.activityItems.isNotEmpty()
                                                        ) {
                                                            current.activityCompletedAt
                                                                ?: System.currentTimeMillis()
                                                        } else current.activityCompletedAt,
                                                        isActivityExpanded = if (
                                                            firstFinalDelta &&
                                                            current.activityItems.isNotEmpty()
                                                        ) false else current.isActivityExpanded
                                                    )
                                                }
                                            }
                                        }
                                        is ChatStreamEvent.ToolExecutionStarted -> {
                                            updateAssistant(assistantId) { current ->
                                                val tool = AssistantActivityItem.ToolExecution(
                                                    id = "tool-${event.callId}",
                                                    callId = event.callId,
                                                    toolName = event.toolName,
                                                    argumentsJson = event.argumentsJson,
                                                    status = ToolExecutionStatus.RUNNING
                                                )
                                                val index = current.activityItems.indexOfFirst {
                                                    it is AssistantActivityItem.ToolExecution &&
                                                        it.callId == event.callId
                                                }
                                                val activity = current.activityItems.toMutableList()
                                                if (index < 0) activity += tool else activity[index] = tool
                                                current.copy(
                                                    activityItems = activity,
                                                    activityStartedAt = current.activityStartedAt
                                                        ?: event.startedAt,
                                                    activityCompletedAt = null,
                                                    isActivityExpanded = true
                                                )
                                            }
                                        }
                                        is ChatStreamEvent.ToolExecutionCompleted -> {
                                            updateAssistant(assistantId) { current ->
                                                current.copy(
                                                    activityItems = current.activityItems.map { item ->
                                                        if (
                                                            item is AssistantActivityItem.ToolExecution &&
                                                            item.callId == event.callId
                                                        ) {
                                                            item.copy(
                                                                status = toolResultStatus(event.outputPreview),
                                                                outputPreview = event.outputPreview
                                                            )
                                                        } else item
                                                    }
                                                )
                                            }
                                        }
                                        ChatStreamEvent.ResponseCompleted -> {
                                            updateAssistant(assistantId) { current ->
                                                current.copy(
                                                    isStreaming = false,
                                                    activityCompletedAt = if (
                                                        current.activityItems.isNotEmpty()
                                                    ) {
                                                        current.activityCompletedAt
                                                            ?: System.currentTimeMillis()
                                                    } else null
                                                )
                                            }
                                        }
                                        is ChatStreamEvent.ResponseError -> {
                                            updateAssistant(assistantId) { current ->
                                                current.copy(
                                                    text = if (current.text.isBlank()) {
                                                        event.message
                                                    } else current.text + "\n\n" + event.message,
                                                    isError = true,
                                                    isStreaming = false,
                                                    activityCompletedAt = current.activityCompletedAt
                                                        ?: System.currentTimeMillis()
                                                )
                                            }
                                        }
                                    }
                                }
                            } finally {
                                updateAssistant(assistantId) { it.copy(isStreaming = false) }
                                isStreaming = false
                            }
                        }
                        true
                    }
                }
            )
        }
    }
}

@Composable
private fun OverlayMessageRow(message: ChatMessage, onToggleActivity: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            text = if (message.isHuman) "You" else "Assistant",
            color = if (message.isHuman) UserLabel else AssistantLabel,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )

        if (message.isHuman) {
            Text(text = message.text, color = UserText, fontSize = 15.sp)
            return@Column
        }

        if (message.activityItems.isNotEmpty()) {
            OverlayActivityPanel(message, onToggleActivity)
        }
        if (message.text.isNotEmpty()) {
            Text(
                text = message.text,
                color = if (message.isError) ErrorText else AssistantText,
                fontSize = 15.sp
            )
        } else if (message.activityItems.isEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = AssistantText,
                    strokeWidth = 2.dp
                )
                Text("Thinking…", color = AssistantText, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun OverlayActivityPanel(message: ChatMessage, onToggle: () -> Unit) {
    val running = message.isStreaming && message.text.isEmpty()
    var now by remember(message.activityStartedAt) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val elapsedSeconds = message.activityStartedAt?.let { startedAt ->
        ((message.activityCompletedAt ?: now) - startedAt).coerceAtLeast(0) / 1_000
    }
    val label = buildString {
        append(if (running) "Working for" else "Worked for")
        elapsedSeconds?.let { append(" ${it}s") }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (running) Modifier else Modifier.clickable(onClick = onToggle))
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    color = AssistantLabel,
                    strokeWidth = 2.dp
                )
            }
            Text(
                text = label,
                color = AssistantLabel,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            if (!running) {
                Text(
                    text = if (message.isActivityExpanded) "⌄" else "›",
                    color = AssistantLabel,
                    fontSize = 15.sp
                )
            }
        }

        if (message.isActivityExpanded) {
            Column(
                modifier = Modifier.padding(start = 4.dp, bottom = 3.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                message.activityItems.forEach { item ->
                    when (item) {
                        is AssistantActivityItem.ProgressUpdate -> Text(
                            text = item.content,
                            color = CommentaryText,
                            fontSize = 13.sp
                        )
                        is AssistantActivityItem.ToolExecution -> OverlayToolRow(item)
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayToolRow(item: AssistantActivityItem.ToolExecution) {
    val hasDetails = item.argumentsJson.isNotBlank() || !item.outputPreview.isNullOrBlank()
    var detailsExpanded by rememberSaveable(item.callId) { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (hasDetails) Modifier.clickable { detailsExpanded = !detailsExpanded }
                    else Modifier
                )
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            if (item.status == ToolExecutionStatus.RUNNING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    color = AssistantLabel,
                    strokeWidth = 2.dp
                )
            } else {
                Text(
                    text = if (item.status == ToolExecutionStatus.COMPLETED) "\u2713"
                        else if (item.status == ToolExecutionStatus.FAILED) "Failed" else "Unconfirmed",
                    color = if (item.status == ToolExecutionStatus.COMPLETED) ToolComplete else CommentaryText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
            Text(
                text = item.toolName.split('_').joinToString(" ") { word ->
                    word.replaceFirstChar { it.uppercase() }
                },
                color = CommentaryText,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            if (hasDetails) {
                Text(
                    text = if (detailsExpanded) "⌄" else "›",
                    color = AssistantLabel,
                    fontSize = 14.sp
                )
            }
        }

        if (detailsExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 19.dp, top = 3.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                if (item.argumentsJson.isNotBlank()) {
                    OverlayDetailBlock("Arguments", item.argumentsJson)
                }
                item.outputPreview?.takeIf { it.isNotBlank() }?.let {
                    OverlayDetailBlock("Result", it)
                }
            }
        }
    }
}

@Composable
private fun OverlayDetailBlock(label: String, content: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = AssistantLabel, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        Text(
            text = content,
            color = CommentaryText,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

private fun bottomGradient() = Brush.verticalGradient(
    colorStops = arrayOf(0.0f to Color.Transparent, 1.0f to Color.Black)
)

private const val CONVERSATION_HEIGHT_FRACTION = 0.45f

private fun shapeWaveformLevel(rawLevel: Float): Float {
    val aboveFloor = (
        (rawLevel.coerceIn(0f, 1f) - WAVEFORM_NOISE_FLOOR) /
            (1f - WAVEFORM_NOISE_FLOOR)
        ).coerceAtLeast(0f)
    return (aboveFloor * WAVEFORM_GAIN)
        .coerceAtMost(1f)
        .pow(WAVEFORM_COMPRESSION)
}

private const val WAVEFORM_GAIN = 22f
private const val WAVEFORM_COMPRESSION = 0.40f
private const val WAVEFORM_NOISE_FLOOR = 0.006f

private val UserLabel = Color(0x99FFFFFF)
private val AssistantLabel = Color(0x9982CFFF)
private val UserText = Color(0xFFFFFFFF)
private val AssistantText = Color(0xFF9ED8FF)
private val CommentaryText = Color(0xCCFFFFFF)
private val ToolComplete = Color(0xFF82CFFF)
private val ErrorText = Color(0xFFFFA8A8)
