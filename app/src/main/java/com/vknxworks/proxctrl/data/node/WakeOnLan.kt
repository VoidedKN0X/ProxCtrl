package com.vknxworks.proxctrl.data.node

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Wake-on-LAN settings for one server's node: the node's MAC and where to send the packet. */
data class WolConfig(
    val mac: String,
    val broadcast: String = WakeOnLan.DEFAULT_BROADCAST,
    val port: Int = WakeOnLan.DEFAULT_PORT,
)

/**
 * Wake-on-LAN from the phone: a "magic packet" (6 × 0xFF, then the MAC 16 times) sent as a UDP
 * broadcast. It only reaches the node when the phone is on the same network (or the broadcast
 * address is forwarded). The settings are plain preferences per server; nothing here is secret.
 */
object WakeOnLan {
    const val DEFAULT_BROADCAST = "255.255.255.255"
    const val DEFAULT_PORT = 9
    private const val PREFS = "wake_on_lan"

    private val MAC = Regex("^([0-9A-Fa-f]{2})([-:]?)([0-9A-Fa-f]{2})(\\2[0-9A-Fa-f]{2}){4}$")

    /** "AA:BB:CC:DD:EE:FF", "aa-bb-…" or "aabbccddeeff" → "AA:BB:CC:DD:EE:FF", or null when invalid. */
    fun normalizeMac(input: String): String? {
        val t = input.trim()
        if (!MAC.matches(t)) return null
        val hex = t.filter { it.isLetterOrDigit() }.uppercase()
        return hex.chunked(2).joinToString(":")
    }

    fun isValidPort(port: Int) = port in 1..65535

    fun load(context: Context, serverId: String): WolConfig? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mac = p.getString("$serverId.mac", null)?.takeIf { it.isNotBlank() } ?: return null
        return WolConfig(
            mac = mac,
            broadcast = p.getString("$serverId.broadcast", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_BROADCAST,
            port = p.getInt("$serverId.port", DEFAULT_PORT),
        )
    }

    fun save(context: Context, serverId: String, config: WolConfig?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (config == null) {
                remove("$serverId.mac"); remove("$serverId.broadcast"); remove("$serverId.port")
            } else {
                putString("$serverId.mac", config.mac)
                putString("$serverId.broadcast", config.broadcast)
                putInt("$serverId.port", config.port)
            }
        }.apply()
    }

    fun packet(mac: String): ByteArray {
        val bytes = mac.split(':').map { it.toInt(16).toByte() }
        require(bytes.size == 6)
        return ByteArray(6) { 0xFF.toByte() } + (1..16).flatMap { bytes }.toByteArray()
    }

    /** Sends the magic packet three times, a moment apart, as some network cards miss the first. */
    suspend fun send(config: WolConfig) = withContext(Dispatchers.IO) {
        val data = packet(config.mac)
        val address = InetAddress.getByName(config.broadcast)
        DatagramSocket().use { socket ->
            socket.broadcast = true
            repeat(3) { i ->
                socket.send(DatagramPacket(data, data.size, address, config.port))
                if (i < 2) delay(150)
            }
        }
    }
}
