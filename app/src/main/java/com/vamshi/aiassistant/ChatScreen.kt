package com.vamshi.aiassistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private data class ChatMessage(
    val id: Long,
    val text: String,
    val isHuman: Boolean,
    val isError: Boolean = false
)

@Composable
fun ChatScreen(modifier: Modifier = Modifier) {
    var draft by rememberSaveable { mutableStateOf("") }
    var conversationId by rememberSaveable { mutableStateOf<String?>(null) }
    var isStreaming by remember { mutableStateOf(false) }
    var nextMessageId by remember { mutableStateOf(1L) }
    var streamJob by remember { mutableStateOf<Job?>(null) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun newChat() {
        streamJob?.cancel()
        streamJob = null
        conversationId = null
        isStreaming = false
        messages.clear()
        draft = ""
    }

    DisposableEffect(Unit) { onDispose { streamJob?.cancel() } }
    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

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
                items(messages, key = { it.id }) { message -> MessageBubble(message) }
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
                enabled = !isStreaming,
                maxLines = 4
            )
            Button(
                enabled = draft.isNotBlank() && !isStreaming,
                onClick = {
                    val userText = draft.trim()
                    draft = ""
                    messages += ChatMessage(nextMessageId++, userText, isHuman = true)
                    val assistantId = nextMessageId++
                    messages += ChatMessage(assistantId, "", isHuman = false)
                    isStreaming = true

                    streamJob = scope.launch {
                        ChatApi.streamMessage(userText, conversationId).collect { event ->
                            when (event) {
                                is ChatEvent.Session -> conversationId = event.conversationId
                                is ChatEvent.Delta -> {
                                    val index = messages.indexOfFirst { it.id == assistantId }
                                    if (index >= 0) {
                                        messages[index] = messages[index].copy(
                                            text = messages[index].text + event.text
                                        )
                                    }
                                }
                                ChatEvent.Done -> isStreaming = false
                                is ChatEvent.Error -> {
                                    val index = messages.indexOfFirst { it.id == assistantId }
                                    if (index >= 0) {
                                        val prefix = messages[index].text.takeIf { it.isNotBlank() }
                                            ?.plus("\n\n") ?: ""
                                        messages[index] = messages[index].copy(
                                            text = prefix + event.message,
                                            isError = true
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
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text("Send")
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (message.isHuman) Alignment.CenterEnd else Alignment.CenterStart
    ) {
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
                text = message.text.ifEmpty { "Thinking..." },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
    }
}
