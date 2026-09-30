package com.vamshi.aiassistant

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Flips Wi-Fi, mobile data, Bluetooth and airplane mode through Shizuku, which
 * runs the same shell commands an `adb shell` session would. Normal apps are
 * not allowed to change these radios themselves.
 */
object ShizukuRadios {
    const val REQUEST_CODE = 4242

    enum class Radio(val commands: (Boolean) -> List<Array<String>>) {
        WIFI({ on -> listOf(arrayOf("svc", "wifi", if (on) "enable" else "disable")) }),
        MOBILE_DATA({ on -> listOf(arrayOf("svc", "data", if (on) "enable" else "disable")) }),
        BLUETOOTH({ on -> listOf(arrayOf("svc", "bluetooth", if (on) "enable" else "disable")) }),
        AIRPLANE({ on ->
            listOf(arrayOf("cmd", "connectivity", "airplane-mode", if (on) "enable" else "disable"))
        })
    }

    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = isRunning() && runCatching {
        !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission() {
        if (isRunning()) runCatching { Shizuku.requestPermission(REQUEST_CODE) }
    }

    /** Returns true when every command exited with status 0. */
    fun set(radio: Radio, on: Boolean): Boolean =
        radio.commands(on).all { run(it) == 0 }

    /** Runs a shell command and returns its stdout, or null if it could not run. */
    fun output(command: Array<String>): String? = runCatching {
        val process = start(command)
        // Drain stdout before waiting, or a chatty command can block on a full pipe.
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor()
        text
    }.getOrNull()

    private fun run(command: Array<String>): Int = runCatching {
        start(command).waitFor()
    }.getOrDefault(-1)

    // Shizuku.newProcess is private since API 13, but it is the supported
    // route for one-off commands without writing an AIDL user service.
    private fun start(command: Array<String>): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply { isAccessible = true }
        return method.invoke(null, command, null, null) as Process
    }
}
