package com.vamshi.aiassistant.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.vamshi.aiassistant.overlay.composer.AmplitudeSource
import com.vamshi.aiassistant.overlay.composer.MessageComposer
import com.vamshi.aiassistant.overlay.composer.MicAmplitudeSource
import com.vamshi.aiassistant.overlay.composer.OverlayStyle
import com.vamshi.aiassistant.overlay.composer.rememberComposerState
import kotlinx.coroutines.launch

/**
 * Set true to bring back the bottom gradient. Kept as a switch rather than
 * deleted so it can be re-enabled without rebuilding the layout.
 */
private const val SHOW_GRADIENT = false

/** One turn in the conversation. */
data class OverlayMessage(
    val id: Long,
    val text: String,
    val fromUser: Boolean,
)

/**
 * The assistant panel. Shared by [OverlayService] (drawn into a
 * TYPE_APPLICATION_OVERLAY window when the device is unlocked) and
 * [AssistantOverlayActivity] (used on the lock screen, where overlay windows
 * are not allowed to draw).
 *
 * Conversation sits above a composer pinned to the bottom. Everything inside
 * the panel swallows taps; only the empty area above it closes the overlay.
 */
@Composable
fun OverlayContent(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val messages = remember { mutableStateListOf<OverlayMessage>() }
    val composer = rememberComposerState()
    val listState = rememberLazyListState()
    var nextId by remember { mutableStateOf(0L) }

    // Cold flow, built once. Collection only runs while recording, so the
    // microphone is not touched until the user asks for it.
    val amplitudes = remember { (MicAmplitudeSource(context) as AmplitudeSource).levels() }

    // Keep the newest turn in view as the mock reply streams in. Instant rather
    // than animated: deltas land every few dozen ms, and restarting an
    // animation that often just makes the list jitter.
    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // Tapping the empty area closes the overlay and consumes the tap -
            // it never reaches whatever is underneath, since this window owns
            // the whole screen.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose
            )
    ) {
        // Share of the space the conversation may take before it scrolls.
        // Derived from what is actually available rather than a fixed dp, so
        // the composer stays on screen when the keyboard shrinks the window.
        val conversationMaxHeight = maxHeight * CONVERSATION_HEIGHT_FRACTION
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // Responsive: full width on a phone, capped and centred on a
                // tablet rather than stretching edge to edge.
                .widthIn(max = 640.dp)
                .then(if (SHOW_GRADIENT) Modifier.background(bottomGradient()) else Modifier)
                // Swallow taps anywhere on the panel - composer, waveform,
                // transcription state and the message list all sit inside, so
                // none of them can bubble up and close the overlay.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (messages.isNotEmpty()) {
                // Same surface as the composer, so the conversation stays
                // readable over any wallpaper or app behind the overlay.
                LazyColumn(
                    state = listState,
                    // Grows with the conversation, then scrolls instead of
                    // pushing the composer off screen.
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = conversationMaxHeight)
                        .background(
                            OverlayStyle.Background,
                            RoundedCornerShape(OverlayStyle.Corner)
                        )
                        .border(
                            1.dp,
                            OverlayStyle.Border,
                            RoundedCornerShape(OverlayStyle.Corner)
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageRow(message)
                    }
                }
            }

            MessageComposer(
                state = composer,
                amplitudes = amplitudes,
                transcribe = { MockAssistant.transcribe() },
                onSend = { text ->
                    messages += OverlayMessage(nextId++, text, fromUser = true)

                    val replyId = nextId++
                    messages += OverlayMessage(replyId, "", fromUser = false)
                    scope.launch {
                        MockAssistant.streamReply { partial ->
                            val index = messages.indexOfFirst { it.id == replyId }
                            if (index >= 0) messages[index] = messages[index].copy(text = partial)
                        }
                    }
                },
                onLiveSession = {
                    // Live session is out of scope for this iteration.
                }
            )
        }
    }
}

@Composable
private fun MessageRow(message: OverlayMessage) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (message.fromUser) "You" else "Assistant",
            color = if (message.fromUser) UserLabel else AssistantLabel,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = message.text,
            color = if (message.fromUser) UserText else AssistantText,
            fontSize = 15.sp
        )
    }
}

private fun bottomGradient() = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to Color.Transparent,
        1.0f to Color.Black
    )
)

/** Cap, not a fixed height - the bubble only grows this far before scrolling. */
private const val CONVERSATION_HEIGHT_FRACTION = 0.45f

private val UserLabel = Color(0x99FFFFFF)
private val AssistantLabel = Color(0x9982CFFF)
private val UserText = Color(0xFFFFFFFF)
private val AssistantText = Color(0xFF9ED8FF)
