package com.vamshi.aiassistant

import android.content.Context

enum class BackendTarget(val displayName: String, val baseUrl: String) {
    LOCAL("Local", "http://localhost:3000"),
    VERCEL("Vercel", "https://ai-assistant-backend-green.vercel.app");
}

object BackendSettings {
    private const val PREFERENCES_NAME = "backend_settings"
    private const val TARGET_KEY = "target"

    fun get(context: Context): BackendTarget {
        val stored = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(TARGET_KEY, null)
        return BackendTarget.entries.firstOrNull { it.name == stored } ?: BackendTarget.LOCAL
    }

    fun set(context: Context, target: BackendTarget) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(TARGET_KEY, target.name)
            .apply()
    }

    fun endpoint(context: Context, path: String): String =
        "${get(context).baseUrl}/${path.trimStart('/')}"
}
