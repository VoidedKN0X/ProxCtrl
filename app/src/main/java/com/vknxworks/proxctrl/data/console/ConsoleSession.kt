package com.vknxworks.proxctrl.data.console

import com.vknxworks.proxctrl.data.api.PveClient
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.guests.long
import com.vknxworks.proxctrl.data.guests.str
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

/** What a console connects to. */
@Serializable
sealed interface ConsoleTarget {
    val node: String

    /** The node's own shell (Sys.Console on /nodes/{node}). */
    @Serializable
    data class NodeShell(override val node: String) : ConsoleTarget

    /** A container's console (VM.Console on /vms/{vmid}). */
    @Serializable
    data class Container(override val node: String, val vmid: Int) : ConsoleTarget

    /** A VM's screen over VNC, or its serial terminal when [serial] is set (VM.Console). */
    @Serializable
    data class Vm(override val node: String, val vmid: Int, val serial: String? = null) : ConsoleTarget

    /** A screen (noVNC) rather than a text terminal (xterm.js). */
    val isScreen: Boolean get() = this is Vm && serial == null

    private val base: String
        get() = when (this) {
            is NodeShell -> "/nodes/$node"
            is Container -> "/nodes/$node/lxc/$vmid"
            is Vm -> "/nodes/$node/qemu/$vmid"
        }

    /** POST here to start the proxy; returns port, ticket and user (and a VNC password). */
    val proxyPath: String get() = if (isScreen) "$base/vncproxy" else "$base/termproxy"
    val proxyParams: Map<String, String>
        get() = when {
            isScreen -> mapOf("websocket" to "1")
            this is Vm -> mapOf("serial" to serial.orEmpty())
            else -> emptyMap()
        }
    val socketPath: String get() = "$base/vncwebsocket"

    val aclPath: String get() = if (this is NodeShell) "/nodes/$node" else "/vms/${(this as? Container)?.vmid ?: (this as Vm).vmid}"
    val privilege: String get() = if (this is NodeShell) "Sys.Console" else "VM.Console"
}

/**
 * One console connection. Starts Proxmox's proxy (termproxy or vncproxy) and opens its WebSocket
 * from the app, signed with the token or ticket and pinned to the server's certificate, which a
 * WebView couldn't do. Terminals are authenticated by sending "user:ticket\n" first, as Proxmox's
 * own xterm page does; VNC uses the password Proxmox returns.
 */
class ConsoleSession(private val client: PveClient, val target: ConsoleTarget) {

    interface Listener {
        fun onOpen()
        fun onBytes(bytes: ByteArray)
        fun onClosed(code: Int, reason: String)
        fun onFailure(error: Throwable)
    }

    /** Password for the VNC handshake (VM screens only). */
    @Volatile
    var vncPassword: String = ""
        private set

    @Volatile
    private var socket: WebSocket? = null

    /** Counters for the console's diagnostics panel: what actually went over the socket. */
    class Stats {
        val framesIn = AtomicLong()
        val bytesIn = AtomicLong()
        val framesOut = AtomicLong()
        val bytesOut = AtomicLong()
        val dropped = AtomicLong()
        @Volatile var proxyStarted = false
        @Volatile var socketOpen = false
        @Volatile var closeInfo: String? = null
        @Volatile var lastOut: String = ""
    }

    val stats = Stats()

    private val wsClient = client.http.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    /** Starts the proxy and connects. Errors from the API are thrown; socket errors go to [listener]. */
    suspend fun connect(listener: Listener) {
        val data = client.post(target.proxyPath, target.proxyParams) as? JsonObject ?: throw PveError.NotProxmox(null)
        val port = data.long("port")?.toString() ?: data.str("port") ?: throw PveError.NotProxmox(null)
        val ticket = data.str("ticket") ?: throw PveError.NotProxmox(null)
        val user = data.str("user") ?: client.server.authId
        vncPassword = data.str("password").orEmpty()
        stats.proxyStarted = true

        val url = client.url(target.socketPath, mapOf("port" to port, "vncticket" to ticket))
        val request = Request.Builder().url(url).apply {
            client.webSocketHeaders().forEach { (k, v) -> header(k, v) }
            header("Sec-WebSocket-Protocol", "binary")
        }.build()

        socket = wsClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    stats.socketOpen = true
                    if (!target.isScreen) webSocket.send("$user:$ticket\n")
                    listener.onOpen()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = received(bytes.toByteArray())
                override fun onMessage(webSocket: WebSocket, text: String) = received(text.toByteArray())

                private fun received(data: ByteArray) {
                    stats.framesIn.incrementAndGet()
                    stats.bytesIn.addAndGet(data.size.toLong())
                    listener.onBytes(data)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    stats.socketOpen = false
                    stats.closeInfo = "closed $code ${reason.take(80)}"
                    listener.onClosed(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val error = when {
                        response?.code == 401 -> PveError.Unauthorized(response.message)
                        response?.code == 403 -> PveError.Forbidden(response.message)
                        t is IOException -> PveError.fromIo(t)
                        else -> t
                    }
                    stats.socketOpen = false
                    stats.closeInfo = "failed ${response?.code ?: "-"} ${t.javaClass.simpleName}: ${t.message.orEmpty().take(80)}"
                    listener.onFailure(error)
                }
            },
        )
    }

    fun sendText(text: String) {
        count(socket?.send(text), text.toByteArray().size)
        // Only the message type and length, never the typed text itself.
        stats.lastOut = Regex("^\\d(:\\d+)?").find(text)?.value ?: "?"
    }

    fun sendBinary(bytes: ByteArray) {
        count(socket?.send(bytes.toByteString()), bytes.size)
    }

    private fun count(sent: Boolean?, size: Int) {
        if (sent == true) {
            stats.framesOut.incrementAndGet()
            stats.bytesOut.addAndGet(size.toLong())
        } else {
            stats.dropped.incrementAndGet()
        }
    }

    fun close() {
        socket?.close(1000, null)
        socket = null
    }
}
