package com.vamshi.aiassistant

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed interface ChatStreamEvent {
    data class ConversationReady(val conversationId: String) : ChatStreamEvent
    data class AssistantTextDelta(
        val delta: String,
        val messageId: String,
        val phase: AssistantOutputPhase,
        val startsNewTextSegment: Boolean
    ) : ChatStreamEvent
    data class ToolExecutionStarted(
        val callId: String,
        val toolName: String,
        val argumentsJson: String,
        val startedAt: Long
    ) : ChatStreamEvent
    data class ToolExecutionCompleted(
        val callId: String,
        val outputPreview: String?,
        val completedAt: Long
    ) : ChatStreamEvent
    data class ClientToolRequested(
        val toolName: String,
        val argumentsJson: String
    ) : ChatStreamEvent
    data object ResponseCompleted : ChatStreamEvent
    data class ResponseError(val message: String) : ChatStreamEvent
}

enum class AssistantOutputPhase { COMMENTARY, FINAL_ANSWER }

object ChatApi {
    // Localhost is forwarded to the development machine with `adb reverse`.
    // Replace this with the Vercel HTTPS URL for production.
    private const val CHAT_URL = "http://localhost:3000/api/chat"
    private const val TRANSCRIBE_URL = "http://localhost:3000/api/transcribe"

    suspend fun transcribeAudio(audioFile: File): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(TRANSCRIBE_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 60_000
                doOutput = true
                setRequestProperty("Content-Type", "audio/mp4")
                setRequestProperty("Accept", "application/json")
                setFixedLengthStreamingMode(audioFile.length())
            }

            try {
                audioFile.inputStream().use { input ->
                    connection.outputStream.use { output -> input.copyTo(output) }
                }

                val responseCode = connection.responseCode
                val responseBody = (if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                })?.bufferedReader()?.use { it.readText() }.orEmpty()

                val responseJson = if (responseBody.isBlank()) JSONObject() else JSONObject(responseBody)
                if (responseCode !in 200..299) {
                    error(responseJson.optString("error", "Transcription failed ($responseCode)"))
                }

                responseJson.optString("text").trim().takeIf { it.isNotEmpty() }
                    ?: error("The transcription was empty")
            } finally {
                connection.disconnect()
            }
        }
    }

    fun streamAssistantResponse(
        userMessage: String,
        conversationId: String?
    ): Flow<ChatStreamEvent> = channelFlow {
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(CHAT_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 0
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "text/event-stream")
                }

                val requestJson = JSONObject().put("message", userMessage).apply {
                    conversationId?.let { put("conversationId", it) }
                    put(
                        "deviceTime",
                        JSONObject()
                            .put("epochMillis", System.currentTimeMillis())
                            .put("timeZoneId", ZoneId.systemDefault().id)
                    )
                }
                connection.outputStream.bufferedWriter().use { it.write(requestJson.toString()) }

                if (connection.responseCode !in 200..299) {
                    val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    send(ChatStreamEvent.ResponseError(error ?: "Server error ${connection.responseCode}"))
                    return@withContext
                }

                var eventName = ""
                val dataLines = mutableListOf<String>()
                connection.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        when {
                            line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                            line.startsWith("data:") -> dataLines += line.removePrefix("data:").trim()
                            line.isBlank() && eventName.isNotEmpty() -> {
                                val data = JSONObject(dataLines.joinToString("\n"))
                                when (eventName) {
                                    "conversation.ready" -> send(
                                        ChatStreamEvent.ConversationReady(data.getString("conversationId"))
                                    )
                                    "assistant.text.delta" -> send(
                                        ChatStreamEvent.AssistantTextDelta(
                                            delta = data.getString("delta"),
                                            messageId = data.optString("messageId", "assistant"),
                                            phase = if (data.optString("phase") == "commentary") {
                                                AssistantOutputPhase.COMMENTARY
                                            } else {
                                                AssistantOutputPhase.FINAL_ANSWER
                                            },
                                            startsNewTextSegment = data.optBoolean(
                                                "startsNewTextSegment",
                                                false
                                            )
                                        )
                                    )
                                    "tool.execution.started" -> send(
                                        ChatStreamEvent.ToolExecutionStarted(
                                            callId = data.getString("callId"),
                                            toolName = data.getString("name"),
                                            argumentsJson = data.optJSONObject("arguments")
                                                ?.takeIf { it.length() > 0 }
                                                ?.toString(2)
                                                ?: "",
                                            startedAt = data.optLong("startedAt", System.currentTimeMillis())
                                        )
                                    )
                                    "tool.execution.completed" -> send(
                                        ChatStreamEvent.ToolExecutionCompleted(
                                            callId = data.getString("callId"),
                                            outputPreview = data.optString("output")
                                                .takeIf { it.isNotEmpty() },
                                            completedAt = data.optLong("completedAt", System.currentTimeMillis())
                                        )
                                    )
                                    "client.tool.requested" -> send(
                                        ChatStreamEvent.ClientToolRequested(
                                            toolName = data.getString("name"),
                                            argumentsJson = data.optJSONObject("arguments")
                                                ?.toString()
                                                ?: "{}"
                                        )
                                    )
                                    "response.completed" -> send(ChatStreamEvent.ResponseCompleted)
                                    "response.error" -> send(
                                        ChatStreamEvent.ResponseError(
                                            data.optString("message", "Unknown error")
                                        )
                                    )
                                }
                                eventName = ""
                                dataLines.clear()
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                send(
                    ChatStreamEvent.ResponseError(
                        error.message ?: "Unable to reach the AI backend"
                    )
                )
            } finally {
                connection?.disconnect()
            }
        }
    }
}
