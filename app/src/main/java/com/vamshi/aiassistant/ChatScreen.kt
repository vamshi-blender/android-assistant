package com.vamshi.aiassistant

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.vamshi.aiassistant.wakeword.WakeWordService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.sqrt

internal enum class ToolExecutionStatus { RUNNING, COMPLETED }

private const val MIC_MAX_SCALE = 1.5f
private const val MIC_LEVEL_FOR_MAX_SCALE = 0.35f
private const val MIC_LEVEL_ATTACK = 0.45f
private const val MIC_LEVEL_RELEASE = 0.18f
private const val MIC_THRESHOLD_EXIT_RATIO = 0.8f
private const val MIC_SCALE_ANIMATION_MS = 140

internal sealed interface AssistantActivityItem {
    val id: String

    data class ProgressUpdate(
        override val id: String,
        val content: String
    ) : AssistantActivityItem

    data class ToolExecution(
        override val id: String,
        val callId: String,
        val toolName: String,
        val argumentsJson: String,
        val status: ToolExecutionStatus,
        val outputPreview: String? = null
    ) : AssistantActivityItem
}

internal data class ChatMessage(
    val id: Long,
    val text: String,
    val isHuman: Boolean,
    val isError: Boolean = false,
    val isStreaming: Boolean = false,
    val activityItems: List<AssistantActivityItem> = emptyList(),
    val activityStartedAt: Long? = null,
    val activityCompletedAt: Long? = null,
    val isActivityExpanded: Boolean = true
)

internal fun appendTextDelta(
    current: String,
    delta: String,
    startsNewTextSegment: Boolean
): String {
    if (!startsNewTextSegment || current.isEmpty()) return current + delta
    val missingNewlines = (2 - current.takeLastWhile { it == '\n' }.length).coerceAtLeast(0)
    return current + "\n".repeat(missingNewlines) + delta
}

@Composable
fun ChatScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var draft by rememberSaveable { mutableStateOf("") }
    var conversationId by rememberSaveable { mutableStateOf<String?>(null) }
    var isStreaming by remember { mutableStateOf(false) }
    var isPreparingRecording by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var isTranscribing by remember { mutableStateOf(false) }
    var micAudioLevel by remember { mutableFloatStateOf(0f) }
    var micVoiceActive by remember { mutableStateOf(false) }
    var resumeWakeWordAfterRecording by remember { mutableStateOf(false) }
    var nextMessageId by remember { mutableStateOf(1L) }
    var streamJob by remember { mutableStateOf<Job?>(null) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val audioRecorder = remember { ChatAudioRecorder(context.applicationContext) }
    val voiceActivityConfig = remember {
        VoiceActivityConfig(audioThreshold = VoiceActivityConfig.DEFAULT_AUDIO_THRESHOLD)
    }

    fun restoreWakeWordListener() {
        if (resumeWakeWordAfterRecording) {
            resumeWakeWordAfterRecording = false
            WakeWordService.start(context)
        }
    }

    fun resetMicVisual() {
        micAudioLevel = 0f
        micVoiceActive = false
    }

    fun finishRecording(endRequest: RecordingEndRequest? = null) {
        if (!isRecording) return
        isRecording = false
        resetMicVisual()

        val recording = runCatching {
            audioRecorder.stop(alreadyStopped = endRequest?.recorderAlreadyStopped == true)
        }
            .onFailure { error ->
                Toast.makeText(
                    context,
                    error.message ?: "Unable to stop recording",
                    Toast.LENGTH_LONG
                ).show()
            }
            .getOrNull()
        restoreWakeWordListener()

        val endMessage = when (endRequest?.reason) {
            RecordingEndReason.NO_SPEECH -> "No speech detected"
            RecordingEndReason.MAX_DURATION ->
                "Recording stopped after ${voiceActivityConfig.maxRecordingDurationMs / 1_000} seconds"
            RecordingEndReason.FILE_SIZE ->
                "Recording stopped at ${ChatAudioRecorder.MAX_FILE_MEGABYTES} MB"
            RecordingEndReason.SILENCE, null -> null
        }
        endMessage?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }

        if (recording != null) {
            if (!recording.speechDetected) {
                recording.file.delete()
                if (endRequest?.reason != RecordingEndReason.NO_SPEECH) {
                    Toast.makeText(context, "No speech detected", Toast.LENGTH_SHORT).show()
                }
                return
            }

            isTranscribing = true
            scope.launch {
                try {
                    ChatApi.transcribeAudio(recording.file)
                        .onSuccess { transcript -> draft = transcript }
                        .onFailure { error ->
                            Toast.makeText(
                                context,
                                error.message ?: "Transcription failed",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                } finally {
                    recording.file.delete()
                    isTranscribing = false
                }
            }
        }
    }

    fun beginRecording() {
        if (isPreparingRecording || isRecording) return
        isPreparingRecording = true
        resetMicVisual()
        resumeWakeWordAfterRecording = WakeWordService.listening.value
        if (resumeWakeWordAfterRecording) WakeWordService.stop(context)

        runCatching {
            audioRecorder.start(
                config = voiceActivityConfig,
                onAudioLevel = { rawLevel ->
                    val level = rawLevel.coerceIn(0f, 1f)
                    val smoothing = if (level > micAudioLevel) {
                        MIC_LEVEL_ATTACK
                    } else {
                        MIC_LEVEL_RELEASE
                    }
                    micAudioLevel += (level - micAudioLevel) * smoothing
                    micVoiceActive = if (micVoiceActive) {
                        level >= voiceActivityConfig.audioThreshold * MIC_THRESHOLD_EXIT_RATIO
                    } else {
                        level >= voiceActivityConfig.audioThreshold
                    }
                },
                onEndRequested = ::finishRecording
            )
        }
            .onSuccess {
                isPreparingRecording = false
                isRecording = true
            }
            .onFailure { error ->
                isPreparingRecording = false
                restoreWakeWordListener()
                Toast.makeText(
                    context,
                    error.message ?: "Unable to start recording",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginRecording()
        else Toast.makeText(context, "Microphone permission is required", Toast.LENGTH_LONG).show()
    }

    fun newChat() {
        streamJob?.cancel()
        streamJob = null
        conversationId = null
        isStreaming = false
        messages.clear()
        draft = ""
    }

    fun updateAssistant(id: Long, update: (ChatMessage) -> ChatMessage) {
        val index = messages.indexOfFirst { it.id == id }
        if (index >= 0) messages[index] = update(messages[index])
    }

    DisposableEffect(Unit) {
        onDispose {
            streamJob?.cancel()
            audioRecorder.cancel()
            restoreWakeWordListener()
        }
    }
    LaunchedEffect(
        messages.size,
        messages.lastOrNull()?.text,
        messages.lastOrNull()?.activityItems?.size
    ) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    val micScaleTarget = if (isRecording && micVoiceActive) {
        val visualCeiling = maxOf(
            MIC_LEVEL_FOR_MAX_SCALE,
            voiceActivityConfig.audioThreshold + 0.01f
        )
        val visualRange = visualCeiling - voiceActivityConfig.audioThreshold
        val intensity = ((micAudioLevel - voiceActivityConfig.audioThreshold) / visualRange)
            .coerceIn(0f, 1f)
        1f + (MIC_MAX_SCALE - 1f) * sqrt(intensity)
    } else {
        1f
    }
    val micScale by animateFloatAsState(
        targetValue = micScaleTarget,
        animationSpec = tween(
            durationMillis = MIC_SCALE_ANIMATION_MS,
            easing = FastOutSlowInEasing
        ),
        label = "microphone audio level"
    )

    Column(modifier = modifier.fillMaxSize().imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Chat", style = MaterialTheme.typography.headlineSmall)
            IconButton(onClick = ::newChat) {
                Icon(
                    painter = painterResource(R.drawable.ic_huge_new_chat),
                    contentDescription = "New chat"
                )
            }
        }

        if (messages.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Start a new conversation", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(
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

        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Type a message") },
                modifier = Modifier.weight(1f),
                enabled = !isStreaming && !isPreparingRecording && !isRecording && !isTranscribing,
                maxLines = 4
            )
            Button(
                enabled = isRecording ||
                    (!isPreparingRecording && !isStreaming && !isTranscribing),
                onClick = {
                    if (isRecording) {
                        finishRecording()
                    } else if (
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        beginRecording()
                    } else {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .graphicsLayer {
                        scaleX = micScale
                        scaleY = micScale
                    }
                    .semantics {
                        contentDescription = if (isRecording) {
                            "Stop voice recording"
                        } else {
                            "Start voice recording"
                        }
                    },
                contentPadding = PaddingValues(0.dp),
                colors = if (isRecording) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Text("m")
            }
            Button(
                enabled = draft.isNotBlank() &&
                    !isStreaming &&
                    !isPreparingRecording &&
                    !isRecording &&
                    !isTranscribing,
                modifier = Modifier.size(48.dp),
                contentPadding = PaddingValues(0.dp),
                onClick = {
                    val userText = draft.trim()
                    draft = ""
                    messages += ChatMessage(nextMessageId++, userText, isHuman = true)
                    val assistantId = nextMessageId++
                    messages += ChatMessage(
                        id = assistantId,
                        text = "",
                        isHuman = false,
                        isStreaming = true
                    )
                    isStreaming = true

                    streamJob = scope.launch {
                        ChatApi.streamAssistantResponse(userText, conversationId).collect { event ->
                            when (event) {
                                is ChatStreamEvent.ConversationReady -> {
                                    conversationId = event.conversationId
                                }
                                is ChatStreamEvent.AssistantTextDelta -> {
                                    updateAssistant(assistantId) { current ->
                                        if (event.phase == AssistantOutputPhase.COMMENTARY) {
                                            val existingIndex = current.activityItems.indexOfFirst {
                                                it is AssistantActivityItem.ProgressUpdate &&
                                                    it.id == event.messageId
                                            }
                                            val updatedItems = current.activityItems.toMutableList()
                                            if (existingIndex < 0) {
                                                updatedItems += AssistantActivityItem.ProgressUpdate(
                                                    event.messageId,
                                                    event.delta
                                                )
                                            } else {
                                                val existing = updatedItems[existingIndex]
                                                    as AssistantActivityItem.ProgressUpdate
                                                updatedItems[existingIndex] = existing.copy(
                                                    content = appendTextDelta(
                                                        existing.content,
                                                        event.delta,
                                                        event.startsNewTextSegment
                                                    )
                                                )
                                            }
                                            current.copy(
                                                activityItems = updatedItems,
                                                activityStartedAt = current.activityStartedAt
                                                    ?: System.currentTimeMillis(),
                                                isActivityExpanded = true
                                            )
                                        } else {
                                            val isFirstFinalAnswerDelta = current.text.isEmpty()
                                            current.copy(
                                                text = appendTextDelta(
                                                    current.text,
                                                    event.delta,
                                                    event.startsNewTextSegment
                                                ),
                                                activityCompletedAt = if (
                                                    isFirstFinalAnswerDelta &&
                                                        current.activityItems.isNotEmpty()
                                                ) {
                                                    current.activityCompletedAt
                                                        ?: System.currentTimeMillis()
                                                } else {
                                                    current.activityCompletedAt
                                                },
                                                isActivityExpanded = if (
                                                    isFirstFinalAnswerDelta &&
                                                        current.activityItems.isNotEmpty()
                                                ) false else current.isActivityExpanded
                                            )
                                        }
                                    }
                                }
                                is ChatStreamEvent.ToolExecutionStarted -> {
                                    updateAssistant(assistantId) { current ->
                                        val toolExecution = AssistantActivityItem.ToolExecution(
                                            id = "tool-${event.callId}",
                                            callId = event.callId,
                                            toolName = event.toolName,
                                            argumentsJson = event.argumentsJson,
                                            status = ToolExecutionStatus.RUNNING
                                        )
                                        val existingIndex = current.activityItems.indexOfFirst {
                                            it is AssistantActivityItem.ToolExecution &&
                                                it.callId == event.callId
                                        }
                                        val updatedItems = current.activityItems.toMutableList()
                                        if (existingIndex < 0) updatedItems += toolExecution
                                        else updatedItems[existingIndex] = toolExecution
                                        current.copy(
                                            activityItems = updatedItems,
                                            activityStartedAt = current.activityStartedAt
                                                ?: event.startedAt,
                                            activityCompletedAt = null,
                                            isActivityExpanded = true
                                        )
                                    }
                                }
                                is ChatStreamEvent.ToolExecutionCompleted -> {
                                    updateAssistant(assistantId) { current ->
                                        val updatedItems = current.activityItems.map { item ->
                                            if (
                                                item is AssistantActivityItem.ToolExecution &&
                                                item.callId == event.callId
                                            ) {
                                                item.copy(
                                                    status = ToolExecutionStatus.COMPLETED,
                                                    outputPreview = event.outputPreview
                                                )
                                            } else item
                                        }
                                        current.copy(activityItems = updatedItems)
                                    }
                                }
                                is ChatStreamEvent.ClientToolRequested -> {
                                    DeviceClockToolExecutor.execute(
                                        context,
                                        event.toolName,
                                        event.argumentsJson
                                    ).onFailure { error ->
                                        Toast.makeText(
                                            context,
                                            error.message ?: "Clock action failed",
                                            Toast.LENGTH_LONG
                                        ).show()
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
                                    isStreaming = false
                                }
                                is ChatStreamEvent.ResponseError -> {
                                    updateAssistant(assistantId) { current ->
                                        current.copy(
                                            text = if (current.text.isBlank()) {
                                                event.message
                                            } else {
                                                current.text + "\n\n" + event.message
                                            },
                                            isError = true,
                                            isStreaming = false,
                                            activityCompletedAt = current.activityCompletedAt
                                                ?: System.currentTimeMillis()
                                        )
                                    }
                                    isStreaming = false
                                }
                            }
                        }
                        isStreaming = false
                    }
                }
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_huge_send),
                    contentDescription = "Send"
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, onToggleActivity: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (message.isHuman) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        if (message.isHuman) {
            MessageSurface(message)
        } else {
            Column(
                modifier = Modifier.widthIn(max = 340.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (message.activityItems.isNotEmpty()) {
                    AssistantActivityPanel(message = message, onToggle = onToggleActivity)
                }
                if (message.text.isNotEmpty()) {
                    MessageSurface(message)
                } else if (message.activityItems.isEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Thinking…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageSurface(message: ChatMessage) {
    Surface(
        color = when {
            message.isError -> MaterialTheme.colorScheme.errorContainer
            message.isHuman -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = when {
            message.isError -> MaterialTheme.colorScheme.onErrorContainer
            message.isHuman -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.widthIn(max = 300.dp)
    ) {
        Text(
            text = message.text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun AssistantActivityPanel(message: ChatMessage, onToggle: () -> Unit) {
    val isActivityRunning = message.isStreaming && message.text.isEmpty()
    var now by remember(message.activityStartedAt) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isActivityRunning) {
        while (isActivityRunning) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val elapsedSeconds = message.activityStartedAt?.let { startedAt ->
        ((message.activityCompletedAt ?: now) - startedAt)
            .coerceAtLeast(0) / 1_000
    }
    val label = buildString {
        append(if (isActivityRunning) "Working for" else "Worked for")
        elapsedSeconds?.let { append(" ${it}s") }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (isActivityRunning) Modifier else Modifier.clickable(onClick = onToggle)
                )
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isActivityRunning) {
                CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
            }
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
            if (!isActivityRunning) {
                Text(if (message.isActivityExpanded) "⌄" else "›")
            }
        }

        if (message.isActivityExpanded) {
            Column(
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                message.activityItems.forEach { item ->
                    when (item) {
                        is AssistantActivityItem.ProgressUpdate -> Text(
                            text = item.content,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        is AssistantActivityItem.ToolExecution -> ToolExecutionRow(item)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolExecutionRow(item: AssistantActivityItem.ToolExecution) {
    val hasDetails = item.argumentsJson.isNotBlank() ||
        !item.outputPreview.isNullOrBlank()
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
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (item.status == ToolExecutionStatus.RUNNING) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            } else {
                Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Text(
                text = item.toolName.split('_').joinToString(" ") { word ->
                    word.replaceFirstChar { it.uppercase() }
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            if (hasDetails) Text(if (detailsExpanded) "⌄" else "›")
        }

        if (detailsExpanded) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(start = 22.dp, top = 4.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    if (item.argumentsJson.isNotBlank()) {
                        DetailBlock("Arguments", item.argumentsJson)
                    }
                    if (
                        item.argumentsJson.isNotBlank() &&
                        !item.outputPreview.isNullOrBlank()
                    ) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }
                    item.outputPreview?.takeIf { it.isNotBlank() }?.let {
                        DetailBlock("Result", it)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailBlock(label: String, value: String) {
    Text(
        text = label.uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold
    )
    Text(
        text = value,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 16.sp
    )
}
