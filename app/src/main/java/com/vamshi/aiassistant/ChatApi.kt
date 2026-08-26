package com.vamshi.aiassistant

import java.net.HttpURLConnection
import java.net.URL
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed interface ChatEvent {
    data class Session(val conversationId: String) : ChatEvent
    data class Delta(val text: String) : ChatEvent
    data object Done : ChatEvent
    data class Error(val message: String) : ChatEvent
}

object ChatApi {
    // Localhost is forwarded to the development machine with `adb reverse`.
    // Replace this with the Vercel HTTPS URL for production.
    private const val CHAT_URL = "http://localhost:3000/api/chat"

    fun streamMessage(message: String, conversationId: String?): Flow<ChatEvent> = channelFlow {
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

                val requestJson = JSONObject().put("message", message).apply {
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
                    send(ChatEvent.Error(error ?: "Server error ${connection.responseCode}"))
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
                                    "session" -> send(ChatEvent.Session(data.getString("conversationId")))
                                    "delta" -> send(ChatEvent.Delta(data.getString("text")))
                                    "done" -> send(ChatEvent.Done)
                                    "error" -> send(ChatEvent.Error(data.optString("message", "Unknown error")))
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
                send(ChatEvent.Error(error.message ?: "Unable to reach the AI backend"))
            } finally {
                connection?.disconnect()
            }
        }
    }
}
