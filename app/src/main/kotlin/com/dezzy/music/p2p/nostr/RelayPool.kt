/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.p2p.nostr

import android.util.LruCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * Conexiones WebSocket a relés Nostr públicos.
 *
 * Los relés son servidores de terceros, gratuitos e intercambiables: sólo guardan y reenvían
 * eventos firmados. Dezzy no tiene servidor propio: si un relé cae, se usan los demás.
 */
object RelayPool {

    /** Relés públicos gratuitos y estables que se usan por defecto. */
    val DEFAULT_RELAYS = listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.primal.net",
        "wss://nostr.mom",
        "wss://offchain.pub",
        "wss://relay.nostr.band",
    )

    /** Relé con búsqueda de texto (NIP-50) para encontrar gente por nombre. */
    const val SEARCH_RELAY = "wss://relay.nostr.band"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(25, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    private val relays = ConcurrentHashMap<String, Relay>()
    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    private val okWaiters = ConcurrentHashMap<String, Channel<Boolean>>()
    private val verified = LruCache<String, Boolean>(8000)

    private val _connected = MutableStateFlow(0)

    /** Cuántos relés hay conectados ahora mismo (para el chip «N relés conectados»). */
    val connectedCount: StateFlow<Int> = _connected.asStateFlow()

    private class Subscription(
        val id: String,
        val filters: List<NostrFilter>,
        val relayUrls: Set<String>,
        val onEvent: (NostrEvent, String) -> Unit,
        val onEose: (String) -> Unit,
    ) {
        val seen: MutableSet<String> = ConcurrentHashMap.newKeySet()

        fun requestMessage(): String = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(id))
            filters.forEach { add(it.toJson()) }
        }.toString()
    }

    private class Relay(val url: String) {
        @Volatile var socket: WebSocket? = null
        @Volatile var open = false
        @Volatile var attempts = 0
        @Volatile var reconnectScheduled = false
        val queue = ConcurrentLinkedQueue<String>()

        fun connect() {
            if (socket != null) return
            socket = runCatching {
                client.newWebSocket(Request.Builder().url(url).build(), Listener(this))
            }.onFailure { Timber.w(it, "Relé inválido: $url") }.getOrNull()
        }

        fun send(message: String) {
            val s = socket
            if (open && s != null && s.send(message)) return
            queue.add(message)
            connect()
        }

        fun sendIfOpen(message: String) {
            if (open) socket?.send(message)
        }
    }

    private class Listener(val relay: Relay) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            relay.open = true
            relay.attempts = 0
            updateConnected()
            subscriptions.values.filter { relay.url in it.relayUrls }.forEach { webSocket.send(it.requestMessage()) }
            while (true) {
                val next = relay.queue.poll() ?: break
                webSocket.send(next)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(relay, text)

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onDown(relay)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Timber.d("Relé ${relay.url} sin conexión: ${t.message}")
            onDown(relay)
        }
    }

    private fun onDown(relay: Relay) {
        relay.open = false
        relay.socket = null
        updateConnected()
        // Para las consultas en curso, un relé caído cuenta como «terminado».
        subscriptions.values.filter { relay.url in it.relayUrls }.forEach { it.onEose(relay.url) }
        val stillNeeded = relay.queue.isNotEmpty() || subscriptions.values.any { relay.url in it.relayUrls }
        if (!stillNeeded || relay.reconnectScheduled) return
        relay.reconnectScheduled = true
        val wait = (1000L shl relay.attempts.coerceAtMost(6)).coerceAtMost(60_000L)
        relay.attempts++
        scope.launch {
            delay(wait)
            relay.reconnectScheduled = false
            relay.connect()
        }
    }

    private fun updateConnected() {
        _connected.value = relays.values.count { it.open }
    }

    private fun relay(url: String): Relay = relays.getOrPut(url) { Relay(url) }.also { it.connect() }

    /** Abre las conexiones por adelantado (por ejemplo al entrar en Social). */
    fun warmUp(urls: Collection<String> = DEFAULT_RELAYS) {
        urls.forEach { relay(it) }
    }

    private fun handleMessage(relay: Relay, text: String) {
        val message = runCatching { NostrEvent.json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return
        when (message.getOrNull(0)?.jsonPrimitive?.content) {
            "EVENT" -> {
                val sub = subscriptions[message.getOrNull(1)?.jsonPrimitive?.content ?: return] ?: return
                val event = runCatching { NostrEvent.fromJson(message[2].jsonObject) }.getOrNull() ?: return
                if (!sub.seen.add(event.id)) return
                if (!isAuthentic(event)) return
                sub.onEvent(event, relay.url)
            }
            "EOSE", "CLOSED" -> {
                val sub = subscriptions[message.getOrNull(1)?.jsonPrimitive?.content ?: return] ?: return
                sub.onEose(relay.url)
            }
            "OK" -> {
                val id = message.getOrNull(1)?.jsonPrimitive?.content ?: return
                val accepted = message.getOrNull(2)?.jsonPrimitive?.booleanOrNull ?: false
                if (!accepted) Timber.d("Relé ${relay.url} rechazó $id: ${message.getOrNull(3)}")
                okWaiters[id]?.trySend(accepted)
            }
            "NOTICE" -> Timber.d("Aviso de ${relay.url}: ${message.getOrNull(1)}")
        }
    }

    /** Comprueba id y firma; recuerda los ya verificados para no repetir cálculos. */
    fun isAuthentic(event: NostrEvent): Boolean {
        verified.get(event.id)?.let { return it }
        val ok = event.verify()
        verified.put(event.id, ok)
        return ok
    }

    private fun open(
        filters: List<NostrFilter>,
        relayUrls: Collection<String>,
        onEvent: (NostrEvent, String) -> Unit,
        onEose: (String) -> Unit,
    ): String {
        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        val sub = Subscription(id, filters, relayUrls.toSet(), onEvent, onEose)
        subscriptions[id] = sub
        sub.relayUrls.forEach { url ->
            val r = relay(url)
            if (r.open) r.sendIfOpen(sub.requestMessage())
        }
        return id
    }

    private fun close(subId: String) {
        val sub = subscriptions.remove(subId) ?: return
        val message = JsonArray(listOf(JsonPrimitive("CLOSE"), JsonPrimitive(subId))).toString()
        sub.relayUrls.forEach { relays[it]?.sendIfOpen(message) }
    }

    /**
     * Consulta puntual: junta lo que respondan los relés hasta que todos terminan (EOSE),
     * hasta 1,5 s después del primero que termina, o hasta [timeoutMs].
     */
    suspend fun query(
        filters: List<NostrFilter>,
        relayUrls: Collection<String> = DEFAULT_RELAYS,
        timeoutMs: Long = 7000,
    ): List<NostrEvent> {
        if (filters.isEmpty() || relayUrls.isEmpty()) return emptyList()
        val results = ConcurrentHashMap<String, NostrEvent>()
        val pending: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { addAll(relayUrls) }
        val done = CompletableDeferred<Unit>()
        val firstEose = CompletableDeferred<Unit>()
        val subId = open(
            filters = filters,
            relayUrls = relayUrls,
            onEvent = { event, _ -> results[event.id] = event },
            onEose = { url ->
                if (pending.remove(url)) {
                    firstEose.complete(Unit)
                    if (pending.isEmpty()) done.complete(Unit)
                }
            },
        )
        val grace = scope.launch {
            firstEose.await()
            delay(1500)
            done.complete(Unit)
        }
        try {
            withTimeoutOrNull(timeoutMs) { done.await() }
        } finally {
            grace.cancel()
            close(subId)
        }
        return results.values.sortedByDescending { it.createdAt }
    }

    /** Suscripción en vivo: emite cada evento nuevo hasta que se deja de recolectar. */
    fun stream(filters: List<NostrFilter>, relayUrls: Collection<String> = DEFAULT_RELAYS): Flow<NostrEvent> = callbackFlow {
        val subId = open(filters, relayUrls, onEvent = { event, _ -> trySend(event) }, onEose = { })
        awaitClose { close(subId) }
    }.flowOn(Dispatchers.IO)

    /**
     * Publica un evento. Devuelve cuántos relés lo aceptaron antes de [timeoutMs]
     * (los que no estaban conectados lo reciben en cuanto conecten).
     */
    suspend fun publish(
        event: NostrEvent,
        relayUrls: Collection<String> = DEFAULT_RELAYS,
        timeoutMs: Long = 6000,
    ): Int {
        val targets = relayUrls.toSet()
        if (targets.isEmpty()) return 0
        val answers = Channel<Boolean>(Channel.UNLIMITED)
        okWaiters[event.id] = answers
        val message = buildJsonArray {
            add(JsonPrimitive("EVENT"))
            add(event.toJsonObject())
        }.toString()
        targets.forEach { relay(it).send(message) }
        var accepted = 0
        var answered = 0
        try {
            withTimeoutOrNull(timeoutMs) {
                while (answered < targets.size) {
                    if (answers.receive()) accepted++
                    answered++
                    if (accepted >= 2) break
                }
            }
        } finally {
            okWaiters.remove(event.id)
        }
        return accepted
    }
}
