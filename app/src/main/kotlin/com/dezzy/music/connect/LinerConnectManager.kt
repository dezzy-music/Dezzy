package com.dezzy.music.connect

import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
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
    val isConnected: Boolean = false,
    /** false = conocido de antes pero no respondió en la última búsqueda. */
    val isAvailable: Boolean = true,
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
        private const val DISCOVERY_PORT = 4885
        private const val DEFAULT_PORT = 4884
        private const val DISCOVERY_WINDOW_MS = 6_000L
        private const val CONNECT_TIMEOUT_MS = 8_000L
        private const val MAX_KNOWN_DEVICES = 8

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

    /** IP del dispositivo conectado o al que se está conectando. */
    private val _activeDeviceIp = MutableStateFlow<String?>(null)
    val activeDeviceIp: StateFlow<String?> = _activeDeviceIp.asStateFlow()

    private val _discoveredDevices = MutableStateFlow(loadKnownDevices())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    /** Hay una búsqueda en curso (independiente del estado de conexión). */
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /** Último error visible para el usuario (p. ej. «No se pudo conectar con …»). */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _desktopState = MutableStateFlow<DesktopPlaybackState?>(null)
    val desktopState: StateFlow<DesktopPlaybackState?> = _desktopState.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var activeWebSocket: WebSocket? = null
    private var discoveryJob: Job? = null
    private var connectTimeoutJob: Job? = null

    // ── Dispositivos conocidos ────────────────────────────────────────────────

    private fun loadKnownDevices(): List<DiscoveredDevice> = runCatching {
        val arr = JSONArray(prefs.getString("known_devices", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            DiscoveredDevice(
                name = o.getString("name"),
                ip = o.getString("ip"),
                port = o.optInt("port", DEFAULT_PORT),
                isAvailable = false,
            )
        }
    }.getOrDefault(emptyList())

    private fun rememberDevice(device: DiscoveredDevice) {
        val known = loadKnownDevices().filter { it.ip != device.ip && it.name != device.name }
        val updated = (listOf(device) + known).take(MAX_KNOWN_DEVICES)
        val arr = JSONArray()
        updated.forEach { d ->
            arr.put(JSONObject().apply {
                put("name", d.name)
                put("ip", d.ip)
                put("port", d.port)
            })
        }
        prefs.edit().putString("known_devices", arr.toString()).apply()
    }

    fun forgetDevice(device: DiscoveredDevice) {
        val known = loadKnownDevices().filter { it.ip != device.ip }
        val arr = JSONArray()
        known.forEach { d ->
            arr.put(JSONObject().apply {
                put("name", d.name)
                put("ip", d.ip)
                put("port", d.port)
            })
        }
        prefs.edit().putString("known_devices", arr.toString()).apply()
        _discoveredDevices.value = _discoveredDevices.value.filter { it.ip != device.ip || it.isConnected }
    }

    fun clearError() {
        _lastError.value = null
    }

    // ── Búsqueda ──────────────────────────────────────────────────────────────

    /**
     * Direcciones de broadcast reales de las redes del teléfono (calculadas con la IP y el
     * prefijo de cada red), más el broadcast general como respaldo. Antes solo se probaban
     * 192.168.0.255 y 192.168.1.255, y en redes 10.x o 192.168.100.x no aparecía nada.
     */
    private fun broadcastAddresses(): List<InetAddress> {
        val result = linkedSetOf<InetAddress>()
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            val networks = cm.allNetworks
            for (network in networks) {
                val props = cm.getLinkProperties(network) ?: continue
                for (link in props.linkAddresses) {
                    val addr = link.address as? Inet4Address ?: continue
                    if (addr.isLoopbackAddress) continue
                    val prefix = link.prefixLength.coerceIn(0, 32)
                    val ip = addr.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
                    val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
                    val bcast = ip or mask.inv()
                    val bytes = byteArrayOf(
                        (bcast ushr 24).toByte(), (bcast ushr 16).toByte(),
                        (bcast ushr 8).toByte(), bcast.toByte()
                    )
                    result += InetAddress.getByAddress(bytes)
                }
            }
        }.onFailure { Timber.d(it, "[LinerConnect] Could not read link properties") }
        result += InetAddress.getByName("255.255.255.255")
        return result.toList()
    }

    /**
     * Descubrimiento multi-dispositivo estilo Spotify Connect: manda el probe a las
     * direcciones de broadcast de la red y recoge TODAS las respuestas durante ~6 s.
     * Los dispositivos conocidos aparecen desde el principio marcados como no disponibles
     * hasta que respondan. NO conecta automáticamente: el usuario elige.
     */
    fun discoverDevices() {
        if (_isScanning.value) return
        _isScanning.value = true
        discoveryJob?.cancel()
        discoveryJob = coroutineScope.launch {
            val connectedIp = _activeDeviceIp.value.takeIf { _connectionState.value == ConnectState.CONNECTED }
            val found = linkedMapOf<String, DiscoveredDevice>()
            // Conocidos (de esta sesión o guardados) como «no disponibles» hasta que respondan.
            (_discoveredDevices.value + loadKnownDevices()).forEach { d ->
                if (!found.containsKey(d.ip)) {
                    found[d.ip] = d.copy(isAvailable = d.ip == connectedIp, isConnected = d.ip == connectedIp)
                }
            }
            _discoveredDevices.value = found.values.toList()

            // Algunos equipos descartan el broadcast si no hay un MulticastLock activo.
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = runCatching {
                wifi?.createMulticastLock("dezzy-connect")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()

            var socket: DatagramSocket? = null
            try {
                val s = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 1000
                }
                socket = s
                val probeMsg = "DISCOVER_LINER".toByteArray()
                val targets = broadcastAddresses()
                // Dos rondas: el primer paquete a veces se pierde mientras el Wi‑Fi despierta.
                repeat(2) { round ->
                    for (addr in targets) {
                        runCatching { s.send(DatagramPacket(probeMsg, probeMsg.size, addr, DISCOVERY_PORT)) }
                    }
                    if (round == 0) delay(300)
                }
                val deadline = System.currentTimeMillis() + DISCOVERY_WINDOW_MS
                val buf = ByteArray(1024)
                while (isActive && System.currentTimeMillis() < deadline) {
                    try {
                        val respPacket = DatagramPacket(buf, buf.size)
                        s.receive(respPacket)
                        val json = JSONObject(String(respPacket.data, 0, respPacket.length))
                        if (json.optString("service") != "dezzy-connect") continue
                        val port = json.optInt("port", DEFAULT_PORT)
                        val ip = respPacket.address.hostAddress ?: continue
                        val rawName = json.optString("name").trim()
                        val exactName = if (rawName.isNotBlank() && !rawName.equals("Liner Desktop", ignoreCase = true)) {
                            rawName
                        } else {
                            runCatching { respPacket.address.hostName }.getOrNull()
                                ?.takeIf { it.isNotBlank() && it != ip } ?: rawName.takeIf { it.isNotBlank() } ?: lastDeviceName
                        }
                        // Si el mismo equipo cambió de IP, quitar la entrada vieja con su nombre.
                        found.entries.removeAll { it.key != ip && it.value.name == exactName && !it.value.isConnected }
                        found[ip] = DiscoveredDevice(
                            name = exactName,
                            ip = ip,
                            port = port,
                            isConnected = ip == connectedIp,
                            isAvailable = true,
                        )
                        _discoveredDevices.value = found.values.toList()
                    } catch (_: java.net.SocketTimeoutException) {
                        // Sin respuesta en este intervalo: seguir esperando hasta el deadline.
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Respuesta mal formada: ignorarla y seguir escuchando.
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d(e, "[LinerConnect] Discovery finished with error")
            } finally {
                runCatching { socket?.close() }
                runCatching { if (lock?.isHeld == true) lock.release() }
                _isScanning.value = false
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        _isScanning.value = false
    }

    /** Conexión «a ciegas» de versiones anteriores; se conserva por compatibilidad. */
    fun startDiscoveryAndConnect() {
        if (_connectionState.value == ConnectState.CONNECTED || _connectionState.value == ConnectState.CONNECTING) return
        discoverDevices()
    }

    // ── Conexión ──────────────────────────────────────────────────────────────

    /**
     * Conecta y, solo cuando el WebSocket está abierto, pasa la canción a la PC y llama a
     * [onHandedOff] en el hilo principal (ahí se pausa el teléfono). Si la conexión falla
     * o tarda más de 8 s, el teléfono sigue sonando y se publica [lastError].
     */
    fun connectAndHandoff(
        device: DiscoveredDevice,
        title: String?,
        artist: String?,
        coverUrl: String?,
        onHandedOff: () -> Unit,
    ) {
        connectToHost(device.ip, device.port, device.name) { ws ->
            if (title != null) sendHandoff(ws, title, artist.orEmpty(), coverUrl)
            coroutineScope.launch(Dispatchers.Main) { onHandedOff() }
        }
    }

    fun connectToHost(
        ip: String,
        port: Int = DEFAULT_PORT,
        initialName: String? = null,
        onOpened: ((WebSocket) -> Unit)? = null,
    ) {
        _lastError.value = null
        _connectionState.value = ConnectState.CONNECTING
        _activeDeviceIp.value = ip
        val displayName = initialName ?: ip
        _connectedDeviceName.value = initialName

        // Si aún no tenemos nombre exacto, resolverlo por DNS inverso
        if (initialName.isNullOrBlank() || initialName == "Liner Desktop") {
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

        connectTimeoutJob?.cancel()
        activeWebSocket?.cancel()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== activeWebSocket) return
                Timber.i("[LinerConnect] WebSocket connected to $ip:$port")
                connectTimeoutJob?.cancel()
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

                val name = _connectedDeviceName.value ?: displayName
                prefs.edit().putString("last_device_name", name).apply()
                val connected = DiscoveredDevice(name = name, ip = ip, port = port, isConnected = true, isAvailable = true)
                rememberDevice(connected)
                _discoveredDevices.value = listOf(connected) +
                    _discoveredDevices.value.filter { it.ip != ip }.map { it.copy(isConnected = false) }

                onOpened?.invoke(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== activeWebSocket) return
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
                if (webSocket !== activeWebSocket) return
                markDisconnected(error = null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== activeWebSocket) return
                Timber.w(t, "[LinerConnect] WebSocket connection failed")
                val wasConnected = _connectionState.value == ConnectState.CONNECTED
                markDisconnected(
                    error = if (wasConnected) "Se perdió la conexión con $displayName"
                    else "No se pudo conectar con $displayName"
                )
            }
        })
        activeWebSocket = socket

        connectTimeoutJob = coroutineScope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (activeWebSocket === socket && _connectionState.value == ConnectState.CONNECTING) {
                socket.cancel()
                markDisconnected(error = "No se pudo conectar con $displayName")
            }
        }
    }

    /** Cancela un intento de conexión en curso; el teléfono sigue sonando. */
    fun cancelConnect() {
        if (_connectionState.value != ConnectState.CONNECTING) return
        connectTimeoutJob?.cancel()
        activeWebSocket?.cancel()
        markDisconnected(error = null)
    }

    private fun markDisconnected(error: String?) {
        activeWebSocket = null
        connectTimeoutJob?.cancel()
        _connectionState.value = ConnectState.DISCONNECTED
        _connectedDeviceName.value = null
        _activeDeviceIp.value = null
        _desktopState.value = null
        _discoveredDevices.value = _discoveredDevices.value.map { it.copy(isConnected = false) }
        if (error != null) _lastError.value = error
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
        sendHandoff(ws, title, artist, coverUrl)
    }

    private fun sendHandoff(ws: WebSocket, title: String, artist: String, coverUrl: String?) {
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
        connectTimeoutJob?.cancel()
        val ws = activeWebSocket
        activeWebSocket = null
        ws?.close(1000, "User disconnected")
        markDisconnected(error = null)
    }
}
