package com.example.mobile_connect_android.data

import android.os.Handler
import android.os.Looper
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * WebSocket to one terminal session. The server keeps the shell alive and replays recent output on every connect,
 * so reconnecting after a drop or a trip to the background just works.
 *
 * Events are always delivered on the main thread.
 */
class TermSocket(
    private val api: ApiClient,
    val id: String
) {
    sealed class Event {
        data class Output(val text: String) : Event()
        data class Replay(val text: String) : Event()
        data class Exited(val code: Int?) : Event()
        data class Failed(val message: String) : Event()
        object Disconnected : Event()
    }

    @Serializable
    private data class IncomingMessage(
        val t: String,
        val d: String? = null,
        val code: Int? = null,
        val m: String? = null
    )

    private val main = Handler(Looper.getMainLooper())
    private var webSocket: WebSocket? = null

    /** True from `connect()` until the socket fails or is closed; messages sent before the upgrade completes are queued by OkHttp. */
    var isConnected: Boolean = false
        private set

    /**
     * Whether the person is looking at this terminal. The server forgets it on every connection, so it is sent again
     * each time; while it is on, a run that ends is not news.
     */
    var watching: Boolean = false
        private set

    var onEvent: (Event) -> Unit = {}

    private val json = Json { ignoreUnknownKeys = true }

    fun connect() {
        if (webSocket != null) return
        val request = Request.Builder()
            .url(api.buildUrl("/term/ws", mapOf("id" to id)))
            .header("Origin", api.getOrigin())
            .build()

        isConnected = true
        val socket = api.webSocketClient.newWebSocket(request, Listener())
        webSocket = socket
        if (watching) send(buildJsonObject { put("t", "v"); put("on", true) }.toString())
    }

    fun setWatching(on: Boolean) {
        if (watching == on) return
        watching = on
        send(buildJsonObject { put("t", "v"); put("on", on) }.toString())
    }

    fun sendInput(input: String) {
        if (input.isEmpty()) return
        send(buildJsonObject { put("t", "i"); put("d", input) }.toString())
    }

    fun resize(cols: Int, rows: Int) {
        send(buildJsonObject { put("t", "r"); put("c", cols); put("r", rows) }.toString())
    }

    fun close() {
        isConnected = false
        webSocket?.close(1000, "Going away")
        webSocket = null
    }

    private fun send(text: String) {
        if (isConnected) webSocket?.send(text)
    }

    private inner class Listener : WebSocketListener() {
        /** Callbacks from a socket we closed ourselves or already replaced are ignored. */
        private fun current(socket: WebSocket) = webSocket === socket

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = try {
                json.decodeFromString<IncomingMessage>(text)
            } catch (_: Exception) {
                return
            }
            val event = when (msg.t) {
                "o" -> Event.Output(msg.d ?: "")
                "replay" -> Event.Replay(msg.d ?: "")
                "x" -> Event.Exited(msg.code)
                "e" -> Event.Failed(msg.m ?: "Terminal error")
                else -> return
            }
            main.post { if (current(webSocket)) onEvent(event) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket)

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            dropped(webSocket)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket)

        private fun dropped(socket: WebSocket) {
            main.post {
                if (!current(socket)) return@post
                this@TermSocket.webSocket = null
                isConnected = false
                onEvent(Event.Disconnected)
            }
        }
    }
}
