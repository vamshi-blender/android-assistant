package com.vamshi.aiassistant

import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Runs the phone-settings tools the assistant can call: `get_device_status`,
 * `set_device_setting` and `switch_wifi_network`. Wi-Fi, mobile data and
 * airplane mode go through Shizuku ([ShizukuRadios]); Bluetooth is switched by
 * the app itself on Android 11 and older; brightness uses the "modify system
 * settings" permission. These mirror what the More page does.
 */
object DeviceSettingsToolExecutor {
    val toolNames = setOf("get_device_status", "set_device_setting", "switch_wifi_network")

    private const val MAX_BRIGHTNESS = 255
    private const val MAX_LISTED_NETWORKS = 8

    // The backend rejects tool messages over 2000 characters.
    private const val MESSAGE_LIMIT = 1900

    // Gives the tool result time to reach the model before the radio drops.
    private const val DEFERRED_DELAY_MS = 3000L

    // Outlives the tool call so a deferred change still happens after it returns.
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private enum class Setting(val wire: String, val label: String) {
        WIFI("wifi", "Wi-Fi"),
        MOBILE_DATA("mobile_data", "Mobile data"),
        BLUETOOTH("bluetooth", "Bluetooth"),
        AIRPLANE_MODE("airplane_mode", "Airplane mode");

        val radio: ShizukuRadios.Radio
            get() = when (this) {
                WIFI -> ShizukuRadios.Radio.WIFI
                MOBILE_DATA -> ShizukuRadios.Radio.MOBILE_DATA
                BLUETOOTH -> ShizukuRadios.Radio.BLUETOOTH
                AIRPLANE_MODE -> ShizukuRadios.Radio.AIRPLANE
            }
    }

    suspend fun execute(context: Context, toolName: String, argumentsJson: String): DeviceToolResult =
        withContext(Dispatchers.IO) {
            try {
                val app = context.applicationContext
                val arguments = JSONObject(argumentsJson.ifBlank { "{}" })
                when (toolName) {
                    "get_device_status" -> status(app, arguments.optBoolean("includeWifiNetworks", false))
                    "set_device_setting" -> setSetting(app, arguments)
                    "switch_wifi_network" -> switchWifi(app, arguments)
                    else -> error("Unsupported device settings tool: $toolName")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                DeviceToolResult("failed", error.message ?: "Device settings action failed")
            }
        }

    // ---- get_device_status -------------------------------------------------

    private suspend fun status(context: Context, includeNetworks: Boolean): DeviceToolResult {
        val shizukuReady = ShizukuRadios.hasPermission()
        val wifiOn = isOn(context, Setting.WIFI)
        val wifi = JSONObject().put("enabled", wifiOn)
        if (wifiOn && shizukuReady) {
            WifiNetworks.connectedSsid()?.let { wifi.put("connectedNetwork", it) }
        }
        val json = JSONObject()
            .put("wifi", wifi)
            .put("mobileDataEnabled", isOn(context, Setting.MOBILE_DATA))
            .put("bluetoothEnabled", isOn(context, Setting.BLUETOOTH))
            .put("airplaneModeEnabled", isOn(context, Setting.AIRPLANE_MODE))
            .put(
                "brightness",
                JSONObject()
                    .put("percent", brightnessPercent(context))
                    .put("autoBrightness", isAutoBrightness(context))
                    .put("canChange", Settings.System.canWrite(context)),
            )
            // Without Shizuku, Wi-Fi, mobile data and airplane mode cannot be changed.
            .put("remoteControlReady", shizukuReady)

        if (includeNetworks) {
            if (!shizukuReady || !wifiOn) {
                json.put(
                    "wifiNetworksUnavailable",
                    if (!wifiOn) "Wi-Fi is off" else "Shizuku access is not available",
                )
            } else {
                val networks = WifiNetworks.scan().take(MAX_LISTED_NETWORKS).map {
                    JSONObject()
                        .put("ssid", it.ssid)
                        .put("saved", it.netId != null)
                        .put("secured", it.secured)
                        .put("signal", it.level)
                        .put("connected", it.connected)
                }
                return DeviceToolResult("succeeded", fitNetworks(json, networks))
            }
        }
        return DeviceToolResult("succeeded", json.toString())
    }

    /** Drops the weakest networks until the message fits the backend limit. */
    private fun fitNetworks(json: JSONObject, networks: List<JSONObject>): String {
        var count = networks.size
        while (true) {
            json.put("wifiNetworks", JSONArray(networks.take(count)))
            val text = json.toString()
            if (text.length <= MESSAGE_LIMIT || count == 0) return text
            count--
        }
    }

    // ---- set_device_setting -------------------------------------------------

    private suspend fun setSetting(context: Context, arguments: JSONObject): DeviceToolResult {
        val name = arguments.optString("setting")
        if (name == "brightness") return setBrightness(context, arguments)
        val setting = Setting.entries.firstOrNull { it.wire == name }
            ?: return DeviceToolResult(
                "failed",
                "Unknown setting \"$name\". Use wifi, mobile_data, bluetooth, airplane_mode or brightness.",
            )
        if (!arguments.has("enabled") || arguments.isNull("enabled")) {
            return DeviceToolResult("failed", "enabled (true or false) is required for $name.")
        }
        val target = arguments.getBoolean("enabled")
        val word = if (target) "on" else "off"

        if (isOn(context, setting) == target) {
            return DeviceToolResult("succeeded", "${setting.label} is already $word.")
        }
        if (!appCanSwitch(setting) && !ShizukuRadios.hasPermission()) return shizukuUnavailable()

        if (cutsConnection(context, setting, target)) {
            background.launch {
                delay(DEFERRED_DELAY_MS)
                apply(context, setting, target)
            }
            return DeviceToolResult(
                "succeeded",
                "${setting.label} will be turned $word in about 3 seconds. This was scheduled " +
                    "and is not verified yet; the connection may drop and interrupt this conversation.",
            )
        }

        val sent = apply(context, setting, target)
        if (awaitState(context, setting, target)) {
            return DeviceToolResult("succeeded", "${setting.label} is now $word.")
        }
        return if (sent) {
            DeviceToolResult(
                "unknown",
                "The request to turn ${setting.label} $word was sent, but it has not changed yet. " +
                    "Check the status before trying again.",
            )
        } else {
            DeviceToolResult(
                "failed",
                "This phone did not allow turning ${setting.label} $word from the app.",
            )
        }
    }

    private fun setBrightness(context: Context, arguments: JSONObject): DeviceToolResult {
        val level = if (arguments.has("level") && !arguments.isNull("level")) arguments.optInt("level", -1) else -1
        if (level !in 0..100) {
            return DeviceToolResult("failed", "level (0 to 100) is required for brightness.")
        }
        if (!Settings.System.canWrite(context)) {
            return DeviceToolResult(
                "requires_user_action",
                "The app needs the \"Modify system settings\" permission. Ask the user to open More in the app and tap Grant permission in the Brightness section.",
            )
        }
        val wasAuto = isAutoBrightness(context)
        val resolver = context.contentResolver
        // Manual mode, otherwise auto-brightness overrides the chosen value.
        Settings.System.putInt(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        )
        Settings.System.putInt(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS,
            (level / 100f * MAX_BRIGHTNESS).roundToInt().coerceIn(1, MAX_BRIGHTNESS),
        )
        val note = if (wasAuto) " Auto-brightness was turned off." else ""
        return DeviceToolResult("succeeded", "Brightness is now ${brightnessPercent(context)}%.$note")
    }

    // ---- switch_wifi_network ------------------------------------------------

    private suspend fun switchWifi(context: Context, arguments: JSONObject): DeviceToolResult {
        val ssid = arguments.optString("ssid").trim()
        if (ssid.isEmpty()) return DeviceToolResult("failed", "ssid is required.")
        if (!isOn(context, Setting.WIFI)) {
            return DeviceToolResult("failed", "Wi-Fi is off. Turn it on first.")
        }
        if (!ShizukuRadios.hasPermission()) return shizukuUnavailable()

        val networks = WifiNetworks.scan()
        val match = networks.firstOrNull { it.ssid == ssid }
            ?: networks.firstOrNull { it.ssid.equals(ssid, ignoreCase = true) }
            ?: return DeviceToolResult(
                "failed",
                "No network named \"$ssid\" is in range. In range: " +
                    networks.take(MAX_LISTED_NETWORKS).joinToString { it.ssid }.ifEmpty { "none" } + ".",
            )
        if (match.connected) {
            return DeviceToolResult("succeeded", "Already connected to \"${match.ssid}\".")
        }
        if (match.netId == null) {
            return DeviceToolResult(
                "requires_user_action",
                "\"${match.ssid}\" is not saved on this phone, so it needs a password. Ask the user to join it from Android's Wi-Fi settings.",
            )
        }
        return if (WifiNetworks.switchTo(context, match)) {
            DeviceToolResult("succeeded", "Connected to \"${match.ssid}\".")
        } else {
            DeviceToolResult(
                "failed",
                "Could not connect to \"${match.ssid}\". Its saved password may be out of date or the signal too weak.",
            )
        }
    }

    // ---- helpers ------------------------------------------------------------

    private fun shizukuUnavailable(): DeviceToolResult {
        if (ShizukuRadios.isRunning()) {
            ShizukuRadios.requestPermission()
            return DeviceToolResult(
                "requires_user_action",
                "Shizuku needs the user's approval. Ask them to allow this app in the Shizuku prompt on the phone, then try again.",
            )
        }
        return DeviceToolResult(
            "requires_user_action",
            "Shizuku is not running on the phone. Ask the user to open the Shizuku app and start it, then try again.",
        )
    }

    /** Bluetooth can be switched by the app itself on Android 11 and older. */
    private fun appCanSwitch(setting: Setting) =
        setting == Setting.BLUETOOTH && Build.VERSION.SDK_INT <= Build.VERSION_CODES.R

    /** True when the change would take away the connection this conversation is using. */
    private fun cutsConnection(context: Context, setting: Setting, target: Boolean): Boolean = when {
        setting == Setting.AIRPLANE_MODE && target -> true
        setting == Setting.WIFI && !target -> !isOn(context, Setting.MOBILE_DATA)
        setting == Setting.MOBILE_DATA && !target -> !activeNetworkIsWifi(context)
        else -> false
    }

    private fun activeNetworkIsWifi(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun apply(context: Context, setting: Setting, on: Boolean): Boolean {
        if (appCanSwitch(setting)) {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
            return runCatching { if (on) adapter.enable() else adapter.disable() }.getOrDefault(false)
        }
        return ShizukuRadios.set(setting.radio, on)
    }

    private suspend fun awaitState(context: Context, setting: Setting, target: Boolean): Boolean {
        repeat(12) {
            if (isOn(context, setting) == target) return true
            delay(500)
        }
        return isOn(context, setting) == target
    }

    private fun isOn(context: Context, setting: Setting): Boolean {
        val resolver = context.contentResolver
        return runCatching {
            when (setting) {
                Setting.WIFI ->
                    context.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
                Setting.MOBILE_DATA -> Settings.Global.getInt(resolver, "mobile_data", 0) == 1
                Setting.BLUETOOTH -> Settings.Global.getInt(resolver, "bluetooth_on", 0) == 1
                Setting.AIRPLANE_MODE ->
                    Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
            }
        }.getOrDefault(false)
    }

    private fun isAutoBrightness(context: Context): Boolean =
        Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC

    private fun brightnessPercent(context: Context): Int {
        val raw = Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            MAX_BRIGHTNESS / 2,
        )
        return (raw / MAX_BRIGHTNESS.toFloat() * 100).roundToInt().coerceIn(0, 100)
    }
}
