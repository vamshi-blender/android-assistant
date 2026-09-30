package com.vamshi.aiassistant

import android.os.IBinder

/**
 * Entry point run as the shell user through `app_process` (started by
 * [WifiNetworks.switchTo] over Shizuku). Reaches the Wi-Fi system service
 * directly, which a normal app cannot do, and asks it to join a saved network
 * by id, so no password is needed.
 *
 * Prints `ok` on success; anything else on stdout is a diagnostic.
 */
object WifiSwitchMain {
    private const val SHELL_PACKAGE = "com.android.shell"

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val netId = args[0].toInt()
            val serviceManager = Class.forName("android.os.ServiceManager")
            val binder = serviceManager
                .getMethod("getService", String::class.java)
                .invoke(null, "wifi") as IBinder
            val wifi = Class.forName("android.net.wifi.IWifiManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)

            // The parameter list of enableNetwork differs between Android
            // releases, so fill it in by type: the id, "disable others", and
            // the calling package.
            val method = wifi.javaClass.methods.first { it.name == "enableNetwork" }
            val values = method.parameterTypes.map { type ->
                when (type) {
                    Int::class.javaPrimitiveType -> netId
                    Boolean::class.javaPrimitiveType -> true
                    String::class.java -> SHELL_PACKAGE
                    else -> error("Unexpected parameter ${type.name}")
                }
            }
            val accepted = method.invoke(wifi, *values.toTypedArray()) as? Boolean
            println(if (accepted == true) "ok" else "rejected")
        } catch (t: Throwable) {
            println("error: ${t.cause ?: t}")
        }
    }
}
