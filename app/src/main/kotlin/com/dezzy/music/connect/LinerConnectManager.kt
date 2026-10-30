package com.dezzy.music.connect

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONObject
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class ConnectState {
    DISCONNECTED,
    DISCOVERING,
    CONNECTING,
    CONNECTED
}

data class DesktopTrack(
    val id: String,
    val title: String,
    val artist: String,
    val coverUrl: String?,
    val durationMs: Long
)

data class DesktopPlaybackState(
    val track: DesktopTrack?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val volume: Float
)

@Singleton
class LinerConnectManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    init {
        instance = this
    }

    companion object {
        @Volatile
        private var instance: LinerConnectManager? = null

        fun getInstance(context: Context? = null): LinerConnectManager {
            return instance ?: synchronized(this) {
                instance ?: LinerConnectManager(
                    context?.applicationContext ?: throw IllegalStateException("Context required to initialize LinerConnectManager")
                ).also { instance = it }
            }
        }
    }

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _connectionState = MutableStateFlow(ConnectState.DISCONNECTED)
    val connectionState: StateFlow<ConnectState> = _connectionState.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _desktopState = MutableStateFlow<DesktopPlaybackState?>(null)
    val desktopState: StateFlow<DesktopPlaybackState?> = _desktopState.asStateFlow()

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var activeWebSocket: WebSocket? = null
    private var discoveryJob: Job? = null

    fun startDiscoveryAndConnect() {
        if (_connectionState.value == ConnectState.CONNECTED || _connectionState.value == ConnectState.DISCOVERING) {
            return
        }

        _connectionState.value = ConnectState.DISCOVERING
        discoveryJob?.cancel()
        discoveryJob = coroutineScope.launch {
            try {
                val socket = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 4000
                }

                val probeMsg = "DISCOVER_LINER".toByteArray()
                // Broadcast to standard LAN broadcast addresses
                val broadcastAddrs = listOf(
                    InetAddress.getByName("255.255.255.255"),
                    InetAddress.getByName("192.168.1.255"),
                    InetAddress.getByName("192.168.0.255")
                )

                for (addr in broadcastAddrs) {
                    try {
                        val packet = DatagramPacket(probeMsg, probeMsg.size, addr, 4885)
                        socket.send(packet)
                    } catch (_: Exception) {}
                }

                val buf = ByteArray(1024)
                val respPacket = DatagramPacket(buf, buf.size)
                socket.receive(respPacket)

                val responseStr = String(respPacket.data, 0, respPacket.length)
                val json = JSONObject(responseStr)
                if (json.optString("service") == "dezzy-connect") {
                    val port = json.optInt("port", 4884)
                    val ip = respPacket.address.hostAddress
                    val name = json.optString("name", "Liner Desktop")
                    _connectedDeviceName.value = name
                    connectToHost(ip, port)
                }
                socket.close()
            } catch (e: Exception) {
                Timber.d(e, "[LinerConnect] Auto-discovery timeout, attempting local loopback fallback")
                // If local testing on emulator / same machine
                connectToHost("10.0.2.2", 4884)
            }
        }
    }

    fun connectToHost(ip: String, port: Int = 4884) {
        _connectionState.value = ConnectState.CONNECTING
        val request = Request.Builder()
            .url("ws://$ip:$port")
            .build()

        activeWebSocket?.cancel()
        activeWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Timber.i("[LinerConnect] WebSocket connected to $ip:$port")
                _connectionState.value = ConnectState.CONNECTED

                // Send client greeting
                val hello = JSONObject().apply {
                    put("type", "CLIENT_HELLO")
                    put("payload", JSONObject().apply {
                        put("deviceName", android.os.Build.MODEL)
                        put("deviceType", "android")
                    })
                }
                webSocket.send(hello.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    when (msg.optString("type")) {
                        "STATE_SYNC" -> {
                            val payload = msg.optJSONObject("payload") ?: return
                            val trackObj = payload.optJSONObject("track")
                            val desktopTrack = if (trackObj != null) {
                                DesktopTrack(
                                    id = trackObj.optString("id", ""),
                                    title = trackObj.optString("title", "Desconocido"),
                                    artist = trackObj.optString("artist", "Desconocido"),
                                    coverUrl = trackObj.optString("coverUrl").takeIf { it.isNotBlank() },
                                    durationMs = trackObj.optLong("durationMs", 0L)
                                )
                            } else null

                            _desktopState.value = DesktopPlaybackState(
                                track = desktopTrack,
                                isPlaying = payload.optBoolean("isPlaying", false),
                                positionMs = payload.optLong("positionMs", 0L),
                                durationMs = payload.optLong("durationMs", 0L),
                                volume = payload.optDouble("volume", 1.0).toFloat()
                            )
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "[LinerConnect] Error parsing incoming desktop state")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = ConnectState.DISCONNECTED
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Timber.w(t, "[LinerConnect] WebSocket connection failed")
                _connectionState.value = ConnectState.DISCONNECTED
            }
        })
    }

    fun sendCommand(action: String, extra: Map<String, Any> = emptyMap()) {
        val ws = activeWebSocket ?: return
        val json = JSONObject().apply {
            put("type", "COMMAND")
            val payload = JSONObject().apply {
                put("action", action)
                extra.forEach { (k, v) -> put(k, v) }
            }
            put("payload", payload)
        }
        ws.send(json.toString())
    }

    fun play() = sendCommand("PLAY")
    fun pause() = sendCommand("PAUSE")
    fun togglePlayPause() = sendCommand("TOGGLE")
    fun skipNext() = sendCommand("NEXT")
    fun skipPrevious() = sendCommand("PREVIOUS")
    fun seekTo(positionMs: Long) = sendCommand("SEEK", mapOf("positionMs" to positionMs))
    fun setVolume(volume: Float) = sendCommand("SET_VOLUME", mapOf("volume" to volume))

    fun handoffQueueToDesktop(title: String, artist: String, coverUrl: String?) {
        val ws = activeWebSocket ?: return
        val json = JSONObject().apply {
            put("type", "HANDOFF_TO_DESKTOP")
            put("payload", JSONObject().apply {
                put("track", JSONObject().apply {
                    put("id", "dezzy_handoff_${System.currentTimeMillis()}")
                    put("title", title)
                    put("artist", artist)
                    put("coverUrl", coverUrl ?: "")
                })
            })
        }
        ws.send(json.toString())
    }

    fun disconnect() {
        discoveryJob?.cancel()
        activeWebSocket?.close(1000, "User disconnected")
        activeWebSocket = null
        _connectionState.value = ConnectState.DISCONNECTED
        _connectedDeviceName.value = null
        _desktopState.value = null
    }
}
