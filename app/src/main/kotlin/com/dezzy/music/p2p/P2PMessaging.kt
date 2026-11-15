/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.p2p

import android.content.Context
import com.dezzy.music.p2p.data.P2PChatStateEntity
import com.dezzy.music.p2p.data.P2PFollowRequestEntity
import com.dezzy.music.p2p.data.P2PMessageEntity
import com.dezzy.music.p2p.data.P2PPrivateReactionEntity
import com.dezzy.music.p2p.nostr.GiftWrap
import com.dezzy.music.p2p.nostr.Kinds
import com.dezzy.music.p2p.nostr.NostrEvent
import com.dezzy.music.p2p.nostr.NostrFilter
import com.dezzy.music.p2p.nostr.RelayPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Mensajería cifrada de extremo a extremo (NIP-17): cada mensaje viaja en un sobre que sólo
 * puede abrir el destinatario. Se envía también una copia cifrada para mí, así el historial
 * se recupera al entrar con las 12 palabras en otro teléfono.
 *
 * Por el mismo buzón llegan las publicaciones de cuentas privadas, sus me gusta/comentarios
 * y las solicitudes de seguimiento.
 */
object P2PMessaging {

    private const val SYNC_PREFS = "dezzy_p2p_sync"
    private const val KEY_LAST_SYNC = "last_inbox_sync"
    private const val KEY_DM_RELAYS = "dm_relays_published"
    /** Desde cuándo avisar: lo anterior (historial recuperado al restaurar la cuenta) no notifica. */
    private const val KEY_NOTIFY_FROM = "notify_from"
    private const val NOTIFY_MAX_AGE = 7 * 24 * 60 * 60L
    private const val JUNTOS_TAG = "dezzy_juntos"
    private const val CONTROL_TAG = P2PSocial.APP_TAG

    /** Las fechas de los sobres se desordenan hasta 2 días: pedimos con margen. */
    private const val CLOCK_MARGIN = 3 * 24 * 60 * 60L

    private var inboxJob: Job? = null
    private val dmRelaysCache = ConcurrentHashMap<String, List<String>>()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private fun prefs() = P2PSocial.context().getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)

    // ───────────────────────────── Buzón ─────────────────────────────

    /** Escucha mi buzón cifrado en los relés (idempotente). */
    fun start() {
        if (inboxJob?.isActive == true) return
        val keys = P2PSocial.keys.value ?: return
        val lastSync = prefs().getLong(KEY_LAST_SYNC, 0L)
        val since = if (lastSync > 0) lastSync - CLOCK_MARGIN else null
        inboxJob = P2PSocial.scope.launch {
            publishDmRelaysOnce()
            _syncing.value = true
            val filter = NostrFilter(kinds = listOf(Kinds.GIFT_WRAP), tags = mapOf("#p" to listOf(keys.publicKey)), since = since)
            // Primero lo pendiente (consulta), luego en vivo (suscripción).
            runCatching { RelayPool.query(listOf(filter.copy(limit = 1000)), timeoutMs = 10_000).forEach { handle(it) } }
                .onFailure { Timber.w(it, "Buzón P2P") }
            prefs().edit().putLong(KEY_LAST_SYNC, NostrEvent.now()).apply()
            _syncing.value = false
            RelayPool.stream(listOf(filter.copy(since = NostrEvent.now() - CLOCK_MARGIN))).collect { wrap ->
                handle(wrap)
                prefs().edit().putLong(KEY_LAST_SYNC, NostrEvent.now()).apply()
            }
        }
    }

    fun stop() {
        inboxJob?.cancel()
        inboxJob = null
    }

    /**
     * Revisa el buzón una sola vez (lo usa [P2PInboxWorker] con la app cerrada).
     * Los sobres ya guardados se descartan, así que no hay avisos repetidos.
     */
    suspend fun syncNow() = withContext(Dispatchers.IO) {
        val keys = P2PSocial.keys.value ?: return@withContext
        val lastSync = prefs().getLong(KEY_LAST_SYNC, 0L)
        val since = if (lastSync > 0) lastSync - CLOCK_MARGIN else null
        val filter = NostrFilter(kinds = listOf(Kinds.GIFT_WRAP), tags = mapOf("#p" to listOf(keys.publicKey)), since = since)
        runCatching { RelayPool.query(listOf(filter.copy(limit = 500)), timeoutMs = 15_000).forEach { handle(it) } }
            .onSuccess { prefs().edit().putLong(KEY_LAST_SYNC, NostrEvent.now()).apply() }
            .onFailure { Timber.w(it, "Buzón P2P (segundo plano)") }
    }

    /** ¿Merece aviso algo creado en [createdAt]? Nada del historial previo a la primera sincronización. */
    private fun shouldNotify(createdAt: Long): Boolean {
        val prefs = prefs()
        var from = prefs.getLong(KEY_NOTIFY_FROM, 0L)
        if (from == 0L) {
            from = NostrEvent.now()
            prefs.edit().putLong(KEY_NOTIFY_FROM, from).apply()
        }
        return createdAt >= from && createdAt >= NostrEvent.now() - NOTIFY_MAX_AGE
    }

    private suspend fun handle(wrap: NostrEvent) {
        val keys = P2PSocial.keys.value ?: return
        val rumor = withContext(Dispatchers.Default) { GiftWrap.unwrap(wrap, keys) } ?: return
        val me = keys.publicKey
        val control = rumor.tagValues(CONTROL_TAG).firstOrNull()
        val dao = P2PSocial.dao
        when {
            control == "private_post" && rumor.kind == Kinds.TEXT_NOTE -> P2PSocial.storePrivatePost(rumor)
            control == "private_reaction" || control == "private_comment" -> storePrivateReaction(rumor)
            control == "private_delete" -> {
                val target = rumor.tag("e") ?: return
                val stored = dao.privatePost(target) ?: return
                if (stored.author == rumor.pubkey) dao.deletePrivatePost(target)
            }
            control == "follow_request" && rumor.pubkey != me -> {
                val existing = dao.followRequest(rumor.pubkey, incoming = true)
                if (existing?.status == "approved") return
                dao.upsertFollowRequest(P2PFollowRequestEntity(rumor.pubkey, incoming = true, status = "pending", createdAt = rumor.createdAt))
                P2PSocial.loadProfiles(listOf(rumor.pubkey))
                if (existing == null && shouldNotify(rumor.createdAt)) {
                    P2PNotifications.notifyFollowRequest(P2PSocial.context(), rumor.pubkey)
                }
            }
            control == "follow_approved" && rumor.pubkey != me -> {
                dao.upsertFollowRequest(P2PFollowRequestEntity(rumor.pubkey, incoming = false, status = "approved", createdAt = rumor.createdAt))
            }
            control == "follow_rejected" && rumor.pubkey != me -> {
                dao.upsertFollowRequest(P2PFollowRequestEntity(rumor.pubkey, incoming = false, status = "rejected", createdAt = rumor.createdAt))
            }
            control == null && rumor.kind == Kinds.CHAT_MESSAGE -> {
                val fromMe = rumor.pubkey == me
                val peer = if (fromMe) rumor.tagValues("p").firstOrNull { it != me } ?: return else rumor.pubkey
                val song = P2PSong.fromTag(rumor.fullTag(P2PSong.TAG))
                val rowId = dao.insertMessage(
                    P2PMessageEntity(
                        id = rumor.id,
                        peer = peer,
                        fromMe = fromMe,
                        content = rumor.content,
                        createdAt = rumor.createdAt,
                        songJson = song?.toJson(),
                        juntosLink = rumor.tag(JUNTOS_TAG),
                    ),
                )
                if (!fromMe) {
                    P2PSocial.loadProfiles(listOf(peer))
                    // -1 = ya estaba guardado (llegó por otro relé o en otra sincronización).
                    if (rowId != -1L && shouldNotify(rumor.createdAt)) {
                        P2PNotifications.notifyMessage(P2PSocial.context(), peer, rumor.content, song?.title, rumor.createdAt)
                    }
                }
            }
        }
    }

    internal suspend fun storePrivateReaction(rumor: NostrEvent) {
        val postId = rumor.tagValues("e").firstOrNull() ?: return
        P2PSocial.dao.insertPrivateReaction(
            P2PPrivateReactionEntity(
                id = rumor.id,
                postId = postId,
                author = rumor.pubkey,
                createdAt = rumor.createdAt,
                type = if (rumor.kind == Kinds.REACTION) "like" else "comment",
                content = rumor.content,
                replyTo = rumor.tags.firstOrNull { it.size >= 4 && it[0] == "e" && it[3] == "reply" }?.get(1),
            ),
        )
    }

    // ───────────────────────────── Envío ─────────────────────────────

    /** Relés donde cada persona dice recibir sus mensajes (NIP-17, kind 10050). */
    private suspend fun dmRelaysOf(pubkey: String): List<String> {
        dmRelaysCache[pubkey]?.let { return it }
        val list = runCatching {
            RelayPool.query(listOf(NostrFilter(authors = listOf(pubkey), kinds = listOf(Kinds.DM_RELAYS), limit = 1)), timeoutMs = 4000)
                .maxByOrNull { it.createdAt }
                ?.tags?.filter { it.firstOrNull() == "relay" }?.mapNotNull { it.getOrNull(1) }
                ?.filter { it.startsWith("wss://") }
                .orEmpty()
        }.getOrDefault(emptyList())
        val merged = (list.take(4) + RelayPool.DEFAULT_RELAYS).distinct()
        dmRelaysCache[pubkey] = merged
        return merged
    }

    private suspend fun publishDmRelaysOnce() {
        if (prefs().getBoolean(KEY_DM_RELAYS, false)) return
        val keys = P2PSocial.keys.value ?: return
        val event = NostrEvent.sign(keys, Kinds.DM_RELAYS, RelayPool.DEFAULT_RELAYS.map { listOf("relay", it) }, "")
        if (RelayPool.publish(event) > 0) prefs().edit().putBoolean(KEY_DM_RELAYS, true).apply()
    }

    /** Cifra [rumor] por separado para cada destinatario y lo publica en sus relés. */
    internal suspend fun sendWrapped(rumor: NostrEvent, recipients: Collection<String>): Int = withContext(Dispatchers.IO) {
        val keys = P2PSocial.keys.value ?: return@withContext 0
        var delivered = 0
        recipients.distinct().forEach { recipient ->
            val wrap = withContext(Dispatchers.Default) { GiftWrap.wrap(rumor, keys, recipient) }
            if (RelayPool.publish(wrap, dmRelaysOf(recipient)) > 0) delivered++
        }
        delivered
    }

    /** Envía un mensaje (opcionalmente con canción o invitación a «Juntos»). */
    suspend fun sendMessage(peer: String, text: String, song: P2PSong? = null, juntosLink: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            val keys = P2PSocial.keys.value ?: return@withContext false
            val clean = text.trim()
            if (clean.isEmpty() && song == null && juntosLink == null) return@withContext false
            val tags = buildList {
                add(listOf("p", peer))
                song?.let { add(it.toTag()) }
                juntosLink?.let { add(listOf(JUNTOS_TAG, it)) }
            }
            val rumor = NostrEvent.rumor(keys.publicKey, Kinds.CHAT_MESSAGE, tags, clean)
            P2PSocial.dao.insertMessage(
                P2PMessageEntity(rumor.id, peer, true, clean, rumor.createdAt, song?.toJson(), juntosLink),
            )
            markRead(peer)
            // Primero al destinatario; la copia para mí no bloquea el resultado.
            val ok = sendWrapped(rumor, listOf(peer)) > 0
            P2PSocial.scope.launch { sendWrapped(rumor, listOf(keys.publicKey)) }
            ok
        }

    // ───────────────────────────── Seguir cuentas privadas ─────────────────────────────

    private suspend fun sendControl(peer: String, control: String, text: String) {
        val keys = P2PSocial.keys.value ?: return
        val rumor = NostrEvent.rumor(
            keys.publicKey,
            Kinds.CHAT_MESSAGE,
            listOf(listOf("p", peer), listOf(CONTROL_TAG, control)),
            text,
        )
        sendWrapped(rumor, listOf(peer))
    }

    internal suspend fun requestFollow(peer: String) = withContext(Dispatchers.IO) {
        P2PSocial.dao.upsertFollowRequest(P2PFollowRequestEntity(peer, incoming = false, status = "pending", createdAt = NostrEvent.now()))
        sendControl(peer, "follow_request", "Quiere seguirte en Dezzy")
    }

    suspend fun approveFollower(peer: String) = withContext(Dispatchers.IO) {
        P2PSocial.dao.upsertFollowRequest(P2PFollowRequestEntity(peer, incoming = true, status = "approved", createdAt = NostrEvent.now()))
        sendControl(peer, "follow_approved", "Aceptó tu solicitud en Dezzy")
        P2PSocial.shareRecentPrivatePosts(peer)
    }

    suspend fun rejectFollower(peer: String) = withContext(Dispatchers.IO) {
        P2PSocial.dao.upsertFollowRequest(P2PFollowRequestEntity(peer, incoming = true, status = "rejected", createdAt = NostrEvent.now()))
        sendControl(peer, "follow_rejected", "")
    }

    // ───────────────────────────── Lectura para la UI ─────────────────────────────

    fun messages(peer: String): Flow<List<P2PMessage>> = P2PSocial.dao.messages(peer).map { list ->
        list.map { P2PMessage(it.id, it.fromMe, it.content, it.createdAt, P2PSong.fromJson(it.songJson), it.juntosLink) }
    }

    val conversations: Flow<List<P2PConversation>>
        get() = combine(P2PSocial.dao.lastMessages(), P2PSocial.dao.unreadCounts()) { last, unread ->
            val unreadMap = unread.associate { it.peer to it.unread }
            last.map { m ->
                val preview = when {
                    m.content.isNotBlank() -> m.content
                    m.songJson != null -> "Canción: " + (P2PSong.fromJson(m.songJson)?.title ?: "Canción")
                    m.juntosLink != null -> "Invitación a escuchar juntos"
                    else -> ""
                }
                P2PConversation(m.peer, preview, m.createdAt, m.fromMe, unreadMap[m.peer] ?: 0)
            }
        }

    val totalUnread: Flow<Int> get() = P2PSocial.dao.unreadCounts().map { list -> list.sumOf { it.unread } }

    suspend fun markRead(peer: String) = withContext(Dispatchers.IO) {
        P2PNotifications.clearChat(P2PSocial.context(), peer)
        P2PSocial.dao.upsertChatState(P2PChatStateEntity(peer, NostrEvent.now()))
    }

    suspend fun deleteConversation(peer: String) = withContext(Dispatchers.IO) {
        P2PSocial.dao.deleteConversation(peer)
    }
}
