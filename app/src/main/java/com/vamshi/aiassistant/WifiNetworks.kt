package com.vamshi.aiassistant

import android.content.Context
import kotlinx.coroutines.delay

/** A network in range; [netId] is set only when it is already saved on the phone. */
data class WifiNetwork(
    val ssid: String,
    val netId: Int?,
    val rssi: Int,
    val connected: Boolean,
    val secured: Boolean
) {
    /** 0..4 bars, using the usual dBm thresholds. */
    val level: Int get() = when {
        rssi >= -55 -> 4
        rssi >= -67 -> 3
        rssi >= -78 -> 2
        rssi >= -88 -> 1
        else -> 0
    }
}

/**
 * Lists networks in range and switches between them. Scanning uses
 * `cmd wifi` through Shizuku, which avoids the location permission a normal
 * app needs to read scan results. Switching runs [WifiSwitchMain] as the shell
 * user, because Android 10+ stops apps from joining networks they did not add.
 */
object WifiNetworks {
    // BSSID, frequency, "rssi(per-chain)", age, SSID, flags
    private val scanLine = Regex("""^\s*\S+\s+\d+\s+(-?\d+)\(.*?\)\s+[\d.]+\s+(.*?)\s+(\[.*)$""")
    private val savedLine = Regex("""^\s*(\d+)\s+(.+?)\s+(open|owe|wpa2|wpa3|\S+)\s*$""")

    suspend fun scan(): List<WifiNetwork> {
        ShizukuRadios.output(arrayOf("cmd", "wifi", "start-scan"))
        // Give the driver a moment to report; results are otherwise stale.
        delay(3000)
        val results = ShizukuRadios.output(arrayOf("cmd", "wifi", "list-scan-results")).orEmpty()
        val saved = savedNetworks()
        val connected = connectedSsid()

        return results.lineSequence()
            .mapNotNull { scanLine.matchEntire(it) }
            .mapNotNull { match ->
                val ssid = match.groupValues[2]
                if (ssid.isBlank()) return@mapNotNull null
                val flags = match.groupValues[3]
                WifiNetwork(
                    ssid = ssid,
                    netId = saved[ssid],
                    rssi = match.groupValues[1].toInt(),
                    connected = ssid == connected,
                    secured = listOf("PSK", "SAE", "EAP", "WEP").any { it in flags }
                )
            }
            // The same SSID shows up once per access point and band.
            .groupBy { it.ssid }
            .map { (_, aps) -> aps.maxBy { it.rssi } }
            .sortedWith(compareByDescending<WifiNetwork> { it.connected }.thenByDescending { it.rssi })
            .toList()
    }

    /** Saved networks as SSID to network id. */
    private fun savedNetworks(): Map<String, Int> =
        ShizukuRadios.output(arrayOf("cmd", "wifi", "list-networks")).orEmpty()
            .lineSequence()
            .drop(1)
            .mapNotNull { savedLine.matchEntire(it) }
            .associate { it.groupValues[2] to it.groupValues[1].toInt() }

    fun connectedSsid(): String? =
        Regex("""connected to "(.*)"""").find(
            ShizukuRadios.output(arrayOf("cmd", "wifi", "status")).orEmpty()
        )?.groupValues?.get(1)

    /** Switches to a saved network and reports whether the phone ended up on it. */
    suspend fun switchTo(context: Context, network: WifiNetwork): Boolean {
        val netId = network.netId ?: return false
        val apk = context.applicationInfo.sourceDir
        val reply = ShizukuRadios.output(
            arrayOf(
                "app_process",
                "-Djava.class.path=$apk",
                "/system/bin",
                WifiSwitchMain::class.java.name,
                netId.toString()
            )
        ).orEmpty().trim()
        if (reply != "ok") return false
        // Association plus DHCP takes a few seconds.
        repeat(8) {
            delay(1000)
            if (connectedSsid() == network.ssid) return true
        }
        return false
    }
}
