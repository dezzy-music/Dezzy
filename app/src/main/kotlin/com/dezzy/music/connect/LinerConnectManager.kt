package com.dezzy.music.connect

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.provider.Settings
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

data class DiscoveredDevice(
    val name: String,
    val ip: String,
    val port: Int = 4884,
    val isConnected: Boolean = false
)

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

        /**
         * Obtiene el nombre exacto asignado al celular (ej. "Galaxy de Dezzy", "Samsung Galaxy S24 Ultra",
         * o el nombre Bluetooth/comercial exacto configurado por el usuario en Ajustes).
         */
        fun getExactPhoneDeviceName(context: Context): String {
            // 1. Nombre de dispositivo configurado en Ajustes de Android (Settings.Global.device_name)
            val userConfigured = runCatching {
                Settings.Global.getString(context.contentResolver, "device_name")
            }.getOrNull()?.trim()
            if (!userConfigured.isNullOrBlank()) {
                return userConfigured
            }

            // 2. Nombre Bluetooth configurado por el usuario (Settings.Secure.bluetooth_name)
            val btName = runCatching {
                Settings.Secure.getString(context.contentResolver, "bluetooth_name")
            }.getOrNull()?.trim()
            if (!btName.isNullOrBlank()) {
                return btName
            }

            // 3. Nombre del adaptador Bluetooth local
            val btAdapterName = runCatching {
                val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                manager?.adapter?.name?.trim()
            }.getOrNull()
            if (!btAdapterName.isNullOrBlank()) {
                return btAdapterName
            }

            // 4. Nombre comercial exacto de fabricante + modelo (ej. "Samsung Galaxy S24", "Google Pixel 8")
            val manufacturer = Build.MANUFACTURER.orEmpty().trim().replaceFirstChar { it.uppercase() }
            val model = Build.MODEL.orEmpty().trim()
            return if (model.startsWith(manufacturer, ignoreCase = true)) {
                model
            } else if (manufacturer.isNotBlank() && model.isNotBlank()) {
                "$manufacturer $model"
            } else if (model.isNotBlank()) {
                model
            } else {
                "Teléfono"
            }
        }
    }

    private val prefs = context.getSharedPreferences("liner_connect_prefs", Context.MODE_PRIVATE)

    /**
     * Último nombre de laptop/desktop recordado. Si el usuario tiene su laptop llamada "Dezzy",
     * este valor se recordará o iniciará con "Dezzy".
     */
    val lastDeviceName: String
        get() = prefs.getString("last_device_name", "Dezzy") ?: "Dezzy"

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _connectionState = MutableStateFlow(ConnectState.DISCONNECTED)
    val connectionState: StateFlow<ConnectState> = _connectionState.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

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
                // Broadcast a direcciones de subred comunes y loopback
                val broadcastAddrs = listOf(
                    InetAddress.getByName("255.255.255.255"),
                    InetAddress.getByName("192.168.1.255"),
                    InetAddress.getByName("192.168.0.255"),
                    InetAddress.getByName("127.0.0.1")
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
                    val ip = respPacket.address.hostAddress ?: "127.0.0.1"
                    val rawName = json.optString("name").trim()

                    // Resolver el nombre exacto de la máquina (ej. "Dezzy", "DESKTOP-ABC1234", etc.)
                    val exactName = if (rawName.isNotBlank() && !rawName.equals("Liner Desktop", ignoreCase = true)) {
                        rawName
                    } else {
                        runCatching {
                            respPacket.address.hostName
                        }.getOrNull()?.takeIf { it.isNotBlank() && it != ip } ?: rawName.takeIf { it.isNotBlank() } ?: lastDeviceName
                    }

                    _connectedDeviceName.value = exactName
                    prefs.edit().putString("last_device_name", exactName).apply()

                    val currentList = _discoveredDevices.value.filter { it.ip != ip }
                    _discoveredDevices.value = currentList + DiscoveredDevice(name = exactName, ip = ip, port = port, isConnected = true)

                    connectToHost(ip, port, exactName)
                }
                socket.close()
            } catch (e: Exception) {
                Timber.d(e, "[LinerConnect] Auto-discovery timeout, attempting local loopback fallback")
                val fallbackName = lastDeviceName
                _connectedDeviceName.value = fallbackName
                connectToHost("10.0.2.2", 4884, fallbackName)
            }
        }
    }

    fun connectToHost(ip: String, port: Int = 4884, initialName: String? = null) {
        _connectionState.value = ConnectState.CONNECTING
        if (initialName != null) {
            _connectedDeviceName.value = initialName
        }

        // Si aún no tenemos nombre exacto, resolverlo por DNS inverso
        if (_connectedDeviceName.value.isNullOrBlank() || _connectedDeviceName.value == "Liner Desktop") {
            coroutineScope.launch {
                val host = runCatching {
                    InetAddress.getByName(ip).hostName
                }.getOrNull()?.takeIf { it.isNotBlank() && it != ip }
                if (host != null) {
                    _connectedDeviceName.value = host
                    prefs.edit().putString("last_device_name", host).apply()
                }
            }
        }

        val request = Request.Builder()
            .url("ws://$ip:$port")
            .build()

        activeWebSocket?.cancel()
        activeWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Timber.i("[LinerConnect] WebSocket connected to $ip:$port")
                _connectionState.value = ConnectState.CONNECTED

                // Enviar saludo con el nombre exacto de este celular
                val phoneName = getExactPhoneDeviceName(context)
                val hello = JSONObject().apply {
                    put("type", "CLIENT_HELLO")
                    put("payload", JSONObject().apply {
                        put("deviceName", phoneName)
                        put("deviceType", "android")
                    })
                }
                webSocket.send(hello.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    when (msg.optString("type")) {
                        "DEVICE_INFO", "HELLO_ACK" -> {
                            val payload = msg.optJSONObject("payload")
                            val exactDevName = payload?.optString("deviceName")?.takeIf { it.isNotBlank() }
                                ?: payload?.optString("name")?.takeIf { it.isNotBlank() }
                            if (exactDevName != null) {
                                _connectedDeviceName.value = exactDevName
                                prefs.edit().putString("last_device_name", exactDevName).apply()
                            }
                        }

                        "STATE_SYNC" -> {
                            val payload = msg.optJSONObject("payload") ?: return

                            // Si el payload incluye el nombre del dispositivo de la computadora, actualizarlo
                            val devName = payload.optString("deviceName").takeIf { it.isNotBlank() }
                            if (devName != null) {
                                _connectedDeviceName.value = devName
                                prefs.edit().putString("last_device_name", devName).apply()
                            }

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
