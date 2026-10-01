package com.vamshi.aiassistant

import android.content.Context

/** Sends a tool request from the backend to the executor that owns it. */
object DeviceTools {
    suspend fun execute(context: Context, toolName: String, argumentsJson: String): DeviceToolResult =
        if (toolName in DeviceSettingsToolExecutor.toolNames) {
            DeviceSettingsToolExecutor.execute(context, toolName, argumentsJson)
        } else {
            DeviceClockToolExecutor.execute(context, toolName, argumentsJson)
        }
}
