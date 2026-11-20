/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.connect

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import com.dezzy.music.security.SessionCrypto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Dezzy Connect («dezzy-connect-v1»): encuentra los Dezzy de tu grupo en la red de casa y habla
 * con ellos directamente, sin servidores. La otra mitad vive en Dezzy Desktop
 * (`src/main/connect.ts`); los dos lados deben cambiar a la vez.
 *
 * Grupo: este móvil genera una clave de 32 bytes y la manda al vincular un ordenador (campo
 * `connect.key` de dezzy-link-v1). Todo lo que tenga esa clave es tuyo. De ella salen
 *   disc = HMAC-SHA256(clave, "dezzy-connect-v1|disc")   para los avisos en la red
 *   auth = HMAC-SHA256(clave, "dezzy-connect-v1|auth")   para abrir conexiones
 *
 * Avisos (UDP 47830, difusión): "DZC1" + iv(12) + AES-256-GCM(disc, JSON) + etiqueta(16), con
 * JSON {v:1, t:"here"|"look", id, name, kind:"pc"|"phone", port}. «look» pide a los demás que
 * contesten con «here» directamente a quien pregunta.
 *
 * Conexión (TCP al `port` del aviso). Tramas: longitud (4 bytes, big endian) + cuerpo.
 *   1. cliente → {v:1, t:"hi", id, name, kind, n}                     (JSON en claro; n = 16 bytes hex)
 *   2. servidor → {v:1, t:"hi", id, name, kind, n, mac: HMAC(auth, "srv|nC|nS")}
 *   3. cliente → {t:"auth", mac: HMAC(auth, "cli|nC|nS")}
 *   Clave de sesión = HMAC(auth, "sess|nC|nS"). Desde ahí cada cuerpo es AES-256-GCM (texto +
 *   etiqueta) con iv = [dirección (0 cliente, 1 servidor), 0, 0, 0, contador de 8 bytes big endian].
 *   Mensajes: {t:"ping", a} → {t:"pong", a, b}; {t:"bye"}; el resto (take, state, cmd, stop, drift,
 *   offset, ctrl) los entiende [DezzyConnectSession]. `pb.at` llega ya pasado al reloj de este móvil.
 *
 * Varias conexiones a la vez (hasta [MAX_LINKS]): forman una estrella alrededor del dispositivo
 * que reparte la música; los demás sólo hablan con él.
 *
 * Blindaje (igual en los dos lados):
 *   - Si los dos abren conexión a la vez, se queda la que abrió el de id menor (si sigue viva).
 *   - Si una conexión se corta sin «bye», se reintenta sola durante [RECONNECT_FOR_MS]; los
 *     primeros [GRACE_MS] el otro sigue contando como conectado para que la sesión no se rompa.
 *   - Si se caen los avisos o el servidor, o cambia la red, se vuelven a abrir solos.
 *   - Además de la difusión, se pregunta directamente a la última IP conocida de cada dispositivo.
 */
object DezzyConnect {
    private const val PORT = 47830
    private val MAGIC = "DZC1".toByteArray()
    private const val PREFS = "dezzy_connect"
    private const val ANNOUNCE_MS = 15_000L
    private const val SEARCH_MS = 12_000L
    private const val SEARCH_EVERY_MS = 3_000L
    private const val AVAILABLE_MS = 40_000L
    private const val HANDSHAKE_MS = 6_000
    private const val OPEN_TRIES = 3
    private const val REFRESH_WAIT_MS = 2_000L
    private const val PING_MS = 4_000L
    /** Sin recibir nada en este tiempo se da por perdida (holgado para el móvil en segundo plano). */
    private const val IDLE_MS = 45_000
    /** Una conexión que habló hace menos de esto se considera viva al resolver un choque. */
    private const val FRESH_MS = 8_000L
    /** Comprobación de sockets, red y reconexiones. */
    private const val HEAL_MS = 1_000L
    private const val NET_CHECK_MS = 5_000L
    /** Tras un corte, el otro sigue como «conectado» este tiempo mientras se reconecta. */
    private const val GRACE_MS = 10_000L
    /** Y se sigue intentando reconectar durante este tiempo. */
    private const val RECONNECT_FOR_MS = 10 * 60_000L
    private const val MAX_FRAME = 512 * 1024
    private const val MAX_DEVICES = 20
    /** Conexiones a la vez: este dispositivo y hasta tres más (4 en total). */
    const val MAX_LINKS = 3
    private val ID_RE = Regex("^[0-9a-f]{16}$")
    private val NONCE_RE = Regex("^[0-9a-f]{32}$")
    private val FORWARDED = setOf("take", "state", "cmd", "stop", "drift", "offset", "ctrl")

    data class Device(
        val id: String,
        val name: String,
        /** "pc" o "phone". */
        val kind: String,
        val available: Boolean,
        val connected: Boolean,
    )

    data class Peer(val id: String, val name: String, val kind: String, val incoming: Boolean, val rtt: Int? = null)

    /** Mensaje de sesión con quién lo manda. */
    data class Incoming(val from: String, val name: String, val msg: JsonObject)

    /** `ip`: última dirección en la que respondió, para preguntarle directamente. */
    private data class Known(val id: String, val name: String, val kind: String, val ip: String? = null)
    private data class Seen(val ip: InetAddress, val port: Int, val lastSeen: Long)

    /** Dispositivo con el que se cortó la conexión sin despedirse: se intenta recuperar. */
    private class Wanted(val name: String, val kind: String, val since: Long, var next: Long) {
        var tries = 0
        var busy = false
        var graceOver = false
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    /** Un solo hilo para el estado; las lecturas de red bloquean en Dispatchers.IO. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val logic = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + logic)

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()
    /** Todos los conectados ahora mismo, en el orden en que se conectaron. */
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()
    /** El primero de [peers] (lo que había antes de poder conectar varios). */
    private val _peer = MutableStateFlow<Peer?>(null)
    val peer: StateFlow<Peer?> = _peer.asStateFlow()
    private val _rtt = MutableStateFlow<Int?>(null)
    val rtt: StateFlow<Int?> = _rtt.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _messages = MutableSharedFlow<Incoming>(extraBufferCapacity = 64)
    val messages: SharedFlow<Incoming> = _messages.asSharedFlow()

    private lateinit var appContext: Context
    private var started = false
    private var selfId = ""
    private var disc: ByteArray? = null
    private var auth: ByteArray? = null
    private var known = listOf<Known>()
    private val seen = HashMap<String, Seen>()
    private var udp: DatagramSocket? = null
    private var server: ServerSocket? = null
    private var tcpPort = 0
    /** Conexiones ya autenticadas, por identificador del otro. */
    private val links = LinkedHashMap<String, Link>()
    private var pending = 0
    private var searchJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val wanted = LinkedHashMap<String, Wanted>()
    private var netSig = ""
    private var lastNetCheck = 0L

    /* ── Clave de grupo ── */

    /**
     * Clave de grupo (hex) que este móvil comparte con los ordenadores que vincula. Se crea una
     * vez y se guarda cifrada con la clave del dispositivo.
     */
    @Synchronized
    fun groupKey(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString("key", null)?.let { runCatching { SessionCrypto.decrypt(it) }.getOrNull() }
        if (stored != null && Regex("^[0-9a-f]{64}$").matches(stored)) return stored
        val fresh = ByteArray(32).also(random::nextBytes).toHexString()
        prefs.edit().putString("key", SessionCrypto.encrypt(fresh)).apply()
        return fresh
    }

    /** Arranca los avisos y el servidor. Se llama al abrir la app; repetirlo no hace nada. */
    fun init(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        scope.launch {
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            selfId = prefs.getString("id", null)?.takeIf { ID_RE.matches(it) }
                ?: ByteArray(8).also(random::nextBytes).toHexString().also { prefs.edit().putString("id", it).apply() }
            known = runCatching {
                json.parseToJsonElement(prefs.getString("devices", "[]") ?: "[]").let { el ->
                    (el as? JsonArray).orEmpty().mapNotNull { item ->
                        val o = item as? JsonObject ?: return@mapNotNull null
                        val id = o.str("id")?.takeIf { ID_RE.matches(it) } ?: return@mapNotNull null
                        Known(id, o.str("name") ?: "Ordenador", if (o.str("kind") == "phone") "phone" else "pc", o.str("ip"))
                    }
                }
            }.getOrDefault(emptyList())
            val key = groupKey(appContext).hexToBytes()
            disc = hmac(key, "dezzy-connect-v1|disc")
            auth = hmac(key, "dezzy-connect-v1|auth")
            key.fill(0)
            netSig = netSignature()
            start()
            lookKnown()
            emitDevices()
            // Repaso: sockets caídos, cambios de red y reconexiones pendientes.
            scope.launch {
                while (isActive) {
                    delay(HEAL_MS)
                    heal()
                }
            }
            while (isActive) {
                delay(ANNOUNCE_MS)
                announce("here", null)
                lookKnown()
                // Los que dejan de anunciarse pasan a «No disponible ahora».
                emitDevices()
            }
        }
    }

    /* ── Blindaje ── */

    /** Huella de las IP de este móvil: si cambia, la red cambió. */
    private fun netSignature(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.interfaceAddresses.filter { it.address is java.net.Inet4Address }.map { "${it.address.hostAddress}/${it.networkPrefixLength}" } }
            .sorted()
            .joinToString(",")
    }.getOrDefault("")

    /** La red cambió: avisos nuevos, búsqueda y reconexión inmediata. */
    private fun networkChanged() {
        udp?.let { old ->
            udp = null
            runCatching { old.close() }
        }
        start()
        announce("look", null)
        lookKnown()
        val soon = now() + 500 + random.nextInt(1_000)
        for (w in wanted.values) w.next = minOf(w.next, soon)
    }

    /** Pregunta directamente a la última IP de cada dispositivo conocido que no esté conectado. */
    private fun lookKnown() {
        for (k in known) {
            val ip = k.ip ?: continue
            if (links[k.id]?.key != null) continue
            val addr = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: continue
            announce("look", addr to PORT)
        }
    }

    private fun heal() {
        if (disc == null) return
        val t = now()
        if (t - lastNetCheck >= NET_CHECK_MS) {
            lastNetCheck = t
            val sig = netSignature()
            if (sig != netSig) {
                val changed = netSig.isNotEmpty()
                netSig = sig
                if (changed) {
                    networkChanged()
                    return
                }
            }
            if (udp == null || server == null) start()
        }
        val iter = wanted.entries.iterator()
        while (iter.hasNext()) {
            val (id, w) = iter.next()
            if (links[id]?.key != null) {
                iter.remove()
                continue
            }
            if (t - w.since > RECONNECT_FOR_MS) {
                iter.remove()
                emitDevices()
                continue
            }
            if (!w.graceOver && t - w.since >= GRACE_MS) {
                w.graceOver = true
                emitDevices()
            }
            if (w.busy || t < w.next) continue
            w.busy = true
            w.tries++
            scope.launch {
                try {
                    if (!isAvailable(id)) refresh(id)
                    if (wanted[id] === w) openOnce(id)
                } finally {
                    w.busy = false
                    w.next = now() + minOf(30_000L, 1_000L shl minOf(w.tries, 5)) + random.nextInt(1_000)
                }
            }
        }
    }

    /** Apunta un corte inesperado para reconectar solo. */
    private fun want(l: Link) {
        if (l.id.isEmpty()) return
        val t = now()
        wanted[l.id] = Wanted(l.name, l.kind, t, t + 300 + random.nextInt(700))
    }

    /* ── Estado ── */

    private fun now() = System.currentTimeMillis()

    private fun isAvailable(id: String): Boolean = seen[id]?.let { now() - it.lastSeen < AVAILABLE_MS } == true

    private fun live(): List<Link> = links.values.filter { it.key != null && !it.closed }

    private fun emitDevices() {
        val live = live()
        val peers = live.map { Peer(it.id, it.name, it.kind, it.incoming, it.rtt) }.toMutableList()
        // Recién cortados: siguen contando unos segundos mientras se reconecta solo.
        for ((id, w) in wanted) {
            if (!w.graceOver && peers.none { it.id == id }) peers += Peer(id, w.name, w.kind, false, null)
        }
        val ids = peers.mapTo(HashSet()) { it.id }
        _devices.value = known.map { Device(it.id, it.name, it.kind, isAvailable(it.id), it.id in ids) }
        _peers.value = peers
        _peer.value = peers.firstOrNull()
        _rtt.value = peers.firstOrNull()?.rtt
    }

    /** Identificador de este dispositivo en el grupo. */
    fun selfId(): String = selfId

    /** Nombre con el que este dispositivo se presenta a los demás. */
    fun selfName(): String = if (::appContext.isInitialized) deviceName() else "Móvil"

    private fun remember(device: Known) {
        val old = known.firstOrNull { it.id == device.id }
        val merged = if (device.ip == null) device.copy(ip = old?.ip) else device
        if (old == merged) return
        known = (listOf(merged) + known.filter { it.id != device.id }).take(MAX_DEVICES)
        saveKnown()
    }

    private fun saveKnown() {
        val array = JsonArray(
            known.map { k ->
                buildJsonObject {
                    put("id", k.id)
                    put("name", k.name)
                    put("kind", k.kind)
                    k.ip?.let { put("ip", it) }
                }
            },
        )
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("devices", array.toString()).apply()
    }

    private fun deviceName(): String =
        runCatching { Settings.Global.getString(appContext.contentResolver, Settings.Global.DEVICE_NAME) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.take(60)
            ?: Build.MODEL.orEmpty().ifBlank { "Móvil" }.take(60)

    /* ── Avisos en la red ── */

    @SuppressLint("WifiManagerPotentialLeak")
    private fun start() {
        try {
            if (multicastLock == null) {
                // Sin esto muchos móviles tiran los paquetes de difusión que llegan.
                val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicastLock = wifi?.createMulticastLock("dezzy-connect")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            if (server == null) {
                val srv = runCatching { ServerSocket(PORT) }.getOrElse { ServerSocket(0) }
                server = srv
                tcpPort = srv.localPort
                scope.launch(Dispatchers.IO) { acceptLoop(srv) }
            }
            if (udp == null) {
                val sock = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(PORT))
                }
                udp = sock
                scope.launch(Dispatchers.IO) { receiveLoop(sock) }
            }
            _error.value = null
        } catch (e: Exception) {
            _error.value = "No se pudo usar la red local"
        }
        announce("here", null)
    }

    private fun broadcastTargets(): List<InetAddress> {
        val out = linkedSetOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (a in nif.interfaceAddresses) a.broadcast?.let(out::add)
            }
        }
        return out.toList()
    }

    private fun announce(t: String, to: Pair<InetAddress, Int>?) {
        val sock = udp ?: return
        val key = disc ?: return
        if (tcpPort == 0) return
        val body = buildJsonObject {
            put("v", 1)
            put("t", t)
            put("id", selfId)
            put("name", deviceName())
            put("kind", "phone")
            put("port", tcpPort)
        }.toString().toByteArray()
        val iv = ByteArray(12).also(random::nextBytes)
        val packet = MAGIC + iv + gcmEncrypt(key, iv, body)
        val targets = to?.let { listOf(it) } ?: broadcastTargets().map { it to PORT }
        scope.launch(Dispatchers.IO) {
            for ((ip, port) in targets) runCatching { sock.send(DatagramPacket(packet, packet.size, ip, port)) }
        }
    }

    private suspend fun receiveLoop(sock: DatagramSocket) {
        val buf = ByteArray(2048)
        while (!sock.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            if (runCatching { sock.receive(packet) }.isFailure) break
            val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
            val from = packet.address
            val fromPort = packet.port
            withContext(logic) { onDatagram(data, from, fromPort) }
        }
        withContext(logic) { if (udp === sock) udp = null }
    }

    private fun onDatagram(msg: ByteArray, from: InetAddress, fromPort: Int) {
        val key = disc ?: return
        if (msg.size < 34 || !msg.copyOfRange(0, 4).contentEquals(MAGIC)) return
        val plain = gcmDecrypt(key, msg.copyOfRange(4, 16), msg.copyOfRange(16, msg.size)) ?: return
        val d = runCatching { json.parseToJsonElement(plain.decodeToString()).jsonObject }.getOrNull() ?: return
        val id = d.str("id") ?: return
        if (d.int("v") != 1 || !ID_RE.matches(id) || id == selfId) return
        val port = d.int("port") ?: return
        if (port !in 1..65535) return
        val kind = if (d.str("kind") == "phone") "phone" else "pc"
        val name = d.str("name")?.trim()?.take(60)?.ifBlank { null } ?: if (kind == "phone") "Móvil" else "Ordenador"
        seen[id] = Seen(from, port, now())
        remember(Known(id, name, kind, from.hostAddress))
        if (d.str("t") == "look") announce("here", from to fromPort)
        emitDevices()
    }

    /** Busca durante unos segundos (al abrir la hoja de dispositivos). */
    fun search() {
        if (!started) return
        scope.launch {
            start()
            searchJob?.cancel()
            searchJob = scope.launch {
                _searching.value = true
                val until = now() + SEARCH_MS
                while (now() < until) {
                    announce("look", null)
                    delay(SEARCH_EVERY_MS)
                    emitDevices()
                }
                _searching.value = false
                emitDevices()
            }
        }
    }

    /* ── Conexiones ── */

    private class Link(val socket: Socket, val incoming: Boolean, var id: String) {
        val dir = if (incoming) 1 else 0
        var name = ""
        var kind = "pc"
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        var theirNonce: ByteArray? = null
        var key: ByteArray? = null
        var sendCtr = 0L
        var recvCtr = 0L
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

        @Volatile var closed = false
        /** Última vez que llegó algo (para saber si sigue viva al resolver un choque). */
        var lastRx = System.currentTimeMillis()
        var offset = 0.0
        var rtt: Int? = null
        val samples = ArrayDeque<Pair<Int, Double>>()
        var pingJob: Job? = null
        var ready: CompletableDeferred<String?>? = null
    }

    private suspend fun acceptLoop(srv: ServerSocket) {
        while (!srv.isClosed) {
            val sock = runCatching { srv.accept() }.getOrNull() ?: break
            withContext(logic) {
                if (disc == null || pending >= 4) {
                    runCatching { sock.close() }
                } else {
                    pending++
                    attach(sock, incoming = true, id = "")
                }
            }
        }
        // El servidor se rompió: se suelta y el repaso periódico abre otro.
        withContext(logic) { if (server === srv) server = null }
    }

    private fun attach(sock: Socket, incoming: Boolean, id: String): Link {
        sock.tcpNoDelay = true
        sock.soTimeout = HANDSHAKE_MS
        val l = Link(sock, incoming, id)
        scope.launch(Dispatchers.IO) {
            try {
                while (!l.closed) {
                    val len = l.input.readInt()
                    if (len < 0 || len > MAX_FRAME || (l.key == null && len > 4096)) break
                    val body = ByteArray(len)
                    l.input.readFully(body)
                    val ok = withContext(logic) { onFrame(l, body) }
                    if (!ok) break
                }
            } catch (_: Exception) {
                // Cortada, sin respuesta a tiempo o basura en el canal.
            }
            withContext(logic) {
                if (incoming) pending--
                drop(l, if (l.key == null) "No responde" else "Se cortó la conexión")
            }
        }
        return l
    }

    private fun writeFrame(l: Link, body: ByteArray) {
        if (l.closed) return
        runCatching {
            l.output.writeInt(body.size)
            l.output.write(body)
            l.output.flush()
        }.onFailure { drop(l, "Se cortó la conexión") }
    }

    private fun sendPlain(l: Link, value: JsonObject) = writeFrame(l, value.toString().toByteArray())

    private fun sendSecure(l: Link, value: JsonObject) {
        val key = l.key ?: return
        writeFrame(l, gcmEncrypt(key, ivFor(l.dir, l.sendCtr++), value.toString().toByteArray()))
    }

    private fun hello(l: Link, mac: String? = null) = buildJsonObject {
        put("v", 1)
        put("t", "hi")
        put("id", selfId)
        put("name", deviceName())
        put("kind", "phone")
        put("n", l.nonce.toHexString())
        if (mac != null) put("mac", mac)
    }

    private fun validHello(d: JsonObject): Boolean {
        val id = d.str("id") ?: return false
        val n = d.str("n") ?: return false
        return d.int("v") == 1 && d.str("t") == "hi" && ID_RE.matches(id) && id != selfId && NONCE_RE.matches(n)
    }

    private fun setIdentity(l: Link, d: JsonObject) {
        l.id = d.str("id").orEmpty()
        l.kind = if (d.str("kind") == "phone") "phone" else "pc"
        l.name = d.str("name")?.trim()?.take(60)?.ifBlank { null } ?: if (l.kind == "phone") "Móvil" else "Ordenador"
    }

    private fun handMac(label: String, client: ByteArray, server: ByteArray): String =
        hmac(auth!!, "$label|${client.toHexString()}|${server.toHexString()}").toHexString()

    private fun close(l: Link, bye: Boolean) {
        if (l.closed) return
        if (bye && l.key != null) sendSecure(l, buildJsonObject { put("t", "bye") })
        l.closed = true
        l.pingJob?.cancel()
        runCatching { l.socket.close() }
        l.ready?.complete("Se cortó la conexión")
        l.ready = null
        if (links[l.id] === l) {
            links.remove(l.id)
            emitDevices()
        }
    }

    private fun drop(l: Link, message: String?): Boolean {
        // Estaba conectado y se cortó sin despedirse: se intentará recuperar.
        if (l.key != null && !l.closed && links[l.id] === l) want(l)
        val report = l.ready
        l.ready = null
        close(l, bye = false)
        report?.complete(message ?: "No se pudo conectar")
        return false
    }

    private fun ready(l: Link): Boolean {
        val old = links[l.id]?.takeIf { it !== l }
        if (old != null) {
            // Choque: los dos abrieron a la vez. Gana la que abrió el de id menor, igual en los dos
            // lados, siempre que la vieja siga viva; si no, la nueva sustituye a la vieja.
            val oldOpener = if (old.incoming) old.id else selfId
            val newOpener = if (l.incoming) l.id else selfId
            val oldAlive = old.key != null && !old.closed && now() - old.lastRx < FRESH_MS
            if (oldAlive && oldOpener != newOpener && oldOpener < newOpener) {
                val report = l.ready
                l.ready = null
                close(l, bye = false)
                report?.complete(null)
                return false
            }
            // Primero la nueva, para que la sesión no vea un «se perdió» entre medias.
            links[l.id] = l
            close(old, bye = false)
        } else if (links.size >= MAX_LINKS) {
            return drop(l, "Ya hay ${MAX_LINKS + 1} dispositivos conectados")
        }
        links[l.id] = l
        wanted.remove(l.id)
        // Desde aquí el otro manda un ping cada 4 s: 20 s sin nada es que se fue.
        runCatching { l.socket.soTimeout = IDLE_MS }
        remember(Known(l.id, l.name, l.kind))
        l.pingJob = scope.launch {
            while (!l.closed) {
                sendSecure(l, buildJsonObject { put("t", "ping"); put("a", now()) })
                delay(PING_MS)
            }
        }
        l.ready?.complete(null)
        l.ready = null
        emitDevices()
        return true
    }

    private fun handshake(l: Link, body: ByteArray): Boolean {
        val authKey = auth ?: return drop(l, null)
        val d = runCatching { json.parseToJsonElement(body.decodeToString()).jsonObject }.getOrNull()
            ?: return drop(l, null)
        if (l.incoming) {
            val theirs = l.theirNonce
            if (theirs == null) {
                if (!validHello(d)) return drop(l, null)
                val n = d.str("n")!!.hexToBytes()
                l.theirNonce = n
                setIdentity(l, d)
                sendPlain(l, hello(l, handMac("srv", n, l.nonce)))
                return true
            }
            if (d.str("t") != "auth" || !sameHex(d.str("mac"), handMac("cli", theirs, l.nonce))) return drop(l, null)
            l.key = hmac(authKey, "sess|${theirs.toHexString()}|${l.nonce.toHexString()}")
            return ready(l)
        }
        if (!validHello(d) || d.str("id") != l.id) return drop(l, "Ese dispositivo ya no responde igual; busca otra vez")
        val n = d.str("n")!!.hexToBytes()
        l.theirNonce = n
        if (!sameHex(d.str("mac"), handMac("srv", l.nonce, n))) return drop(l, "Ese dispositivo no es de tu grupo")
        sendPlain(l, buildJsonObject { put("t", "auth"); put("mac", handMac("cli", l.nonce, n)) })
        l.key = hmac(authKey, "sess|${l.nonce.toHexString()}|${n.toHexString()}")
        setIdentity(l, d)
        return ready(l)
    }

    private fun onSecure(l: Link, msg: JsonObject) {
        when (val t = msg.str("t")) {
            "ping" -> {
                // Se devuelve tal cual llegó (número entero de ms en el otro reloj).
                val a = msg["a"]?.takeIf { msg.num("a") != null } ?: return
                sendSecure(l, buildJsonObject { put("t", "pong"); put("a", a); put("b", now()) })
            }
            "pong" -> {
                val a = msg.num("a") ?: return
                val b = msg.num("b") ?: return
                val rtt = (now() - a).toInt()
                if (rtt < 0 || rtt > 10_000) return
                l.samples.addLast(rtt to (b - (a + rtt / 2.0)))
                while (l.samples.size > 8) l.samples.removeFirst()
                // La medida con menos ida y vuelta es la más fiable.
                l.offset = l.samples.minBy { it.first }.second
                val changed = l.rtt == null || kotlin.math.abs(l.rtt!! - rtt) > 5
                l.rtt = rtt
                if (changed) emitDevices()
            }
            "bye" -> {
                // Se despidió a propósito: no hay que reconectar.
                if (links[l.id] === l) wanted.remove(l.id)
                close(l, bye = false)
            }
            else -> {
                if (links[l.id] !== l || t == null || t !in FORWARDED) return
                // `pb.at` viaja en el reloj del otro: se pasa al nuestro.
                val pb = msg["pb"] as? JsonObject
                val at = pb?.num("at")
                val out = if (pb != null && at != null) {
                    JsonObject(msg + ("pb" to JsonObject(pb + ("at" to JsonPrimitive(at - l.offset)))))
                } else msg
                _messages.tryEmit(Incoming(l.id, l.name, out))
            }
        }
    }

    private fun onFrame(l: Link, body: ByteArray): Boolean {
        l.lastRx = now()
        val key = l.key ?: return handshake(l, body)
        val plain = gcmDecrypt(key, ivFor(1 - l.dir, l.recvCtr), body) ?: return drop(l, null)
        l.recvCtr++
        val msg = runCatching { json.parseToJsonElement(plain.decodeToString()).jsonObject }.getOrNull()
            ?: return drop(l, null)
        onSecure(l, msg)
        return !l.closed
    }

    /**
     * Abre conexión con un dispositivo del grupo. Devuelve null si fue bien o el motivo del fallo.
     *
     * Reintenta sola hasta [OPEN_TRIES] veces: lo normal es que el primer intento falle porque la
     * dirección que teníamos del otro ya no vale (cambió de puerto al reabrirse, el Wi-Fi se
     * durmió o aún no había contestado a la búsqueda). Antes de cada reintento se le pregunta
     * «¿dónde estás?» y se espera un momento a su respuesta.
     */
    suspend fun open(id: String): String? {
        var last: String? = null
        for (attempt in 0 until OPEN_TRIES) {
            if (attempt > 0) refresh(id)
            val (problem, retry) = openOnce(id)
            if (problem == null) return null
            last = problem
            if (!retry) break
        }
        return last
    }

    /** Pide al dispositivo que se anuncie y espera (poco) a que conteste con su dirección. */
    private suspend fun refresh(id: String) {
        val before = withContext(logic) {
            announce("look", null)
            // También directo a la última dirección conocida, por si la difusión se pierde.
            seen[id]?.let { announce("look", it.ip to PORT) }
            seen[id]?.lastSeen ?: 0L
        }
        val until = now() + REFRESH_WAIT_MS
        while (now() < until) {
            delay(150)
            if ((withContext(logic) { seen[id]?.lastSeen } ?: 0L) > before) return
        }
    }

    /** Un intento. Devuelve (motivo del fallo o null, si merece la pena reintentar). */
    private suspend fun openOnce(id: String): Pair<String?, Boolean> {
        var problem: String? = null
        var retry = true
        var already = false
        val where: Seen? = withContext(logic) {
            when {
                disc == null -> null.also { problem = "Dezzy Connect no está listo"; retry = false }
                // Ya estamos conectados a ese.
                links[id]?.let { it.key != null && !it.closed } == true -> null.also { already = true }
                links.size >= MAX_LINKS ->
                    null.also { problem = "Ya hay ${MAX_LINKS + 1} dispositivos conectados"; retry = false }
                else -> seen[id]?.takeIf { isAvailable(id) } ?: null.also { problem = "No disponible ahora" }
            }
        }
        if (already) return null to false
        if (where == null) return problem to retry
        val ready = CompletableDeferred<String?>()
        val sock = withContext(Dispatchers.IO) {
            runCatching { Socket().apply { connect(InetSocketAddress(where.ip, where.port), HANDSHAKE_MS) } }.getOrNull()
        } ?: return "No se pudo conectar" to true
        val link = withContext(logic) {
            attach(sock, incoming = false, id = id).also {
                it.ready = ready
                sendPlain(it, hello(it))
            }
        }
        val result = withTimeoutOrNull(HANDSHAKE_MS + 2_000L) { ready.await() }
        if (result == null && !ready.isCompleted) {
            // Sin respuesta: se cierra este intento para que no quede a medias.
            withContext(logic) { drop(link, "No responde") }
            return "No responde" to true
        }
        val message = result ?: return null to false
        val fatal = message.startsWith("Ya hay") || message.contains("no es de tu grupo")
        return message to !fatal
    }

    /**
     * Manda un mensaje de sesión. Sin `to` va a todos los conectados; `except` se salta uno
     * (para reenviar a los demás lo que dijo él).
     */
    fun send(message: JsonObject, to: String? = null, except: String? = null) {
        scope.launch {
            for (l in live()) {
                if (to != null && l.id != to) continue
                if (except != null && l.id == except) continue
                sendSecure(l, message)
            }
        }
    }

    /** Cierra la conexión con `id`, o con todos si es null. */
    fun close(id: String? = null) {
        scope.launch {
            if (id == null) wanted.clear() else wanted.remove(id)
            for (l in links.values.toList()) if (id == null || l.id == id) close(l, bye = true)
            emitDevices()
        }
    }

    fun forget(id: String) {
        scope.launch {
            wanted.remove(id)
            links[id]?.let { close(it, bye = true) }
            seen.remove(id)
            known = known.filter { it.id != id }
            saveKnown()
            emitDevices()
        }
    }

    /* ── Cifrado y utilidades ── */

    private fun hmac(key: ByteArray, text: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(text.toByteArray(Charsets.UTF_8))
        }

    /** Devuelve texto cifrado + etiqueta, igual que en el ordenador. */
    private fun gcmEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            doFinal(data)
        }

    private fun gcmDecrypt(key: ByteArray, iv: ByteArray, body: ByteArray): ByteArray? {
        if (body.size < 16) return null
        return runCatching {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                doFinal(body)
            }
        }.getOrNull()
    }

    private fun ivFor(dir: Int, counter: Long): ByteArray =
        ByteBuffer.allocate(12).put(dir.toByte()).put(0.toByte()).put(0.toByte()).put(0.toByte()).putLong(counter).array()

    private fun sameHex(a: String?, b: String): Boolean {
        if (a == null || a.length != b.length || !a.all { it in '0'..'9' || it in 'a'..'f' }) return false
        return MessageDigest.isEqual(a.hexToBytes(), b.hexToBytes())
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
}
