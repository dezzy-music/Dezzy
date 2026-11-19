/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.p2p

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.dezzy.music.p2p.crypto.Bech32
import com.dezzy.music.p2p.crypto.CryptoUtils
import com.dezzy.music.p2p.crypto.toHex
import com.dezzy.music.p2p.data.P2PDao
import com.dezzy.music.p2p.data.P2PDatabase
import com.dezzy.music.p2p.data.P2PFollowRequestEntity
import com.dezzy.music.p2p.data.P2PPrivatePostEntity
import com.dezzy.music.p2p.data.P2PProfileEntity
import com.dezzy.music.p2p.nostr.Kinds
import com.dezzy.music.p2p.nostr.NostrEvent
import com.dezzy.music.p2p.nostr.NostrFilter
import com.dezzy.music.p2p.nostr.NostrKeys
import com.dezzy.music.p2p.nostr.RelayPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Red social de Dezzy sin servidores propios (protocolo abierto Nostr).
 *
 * - Perfil, seguidos, publicaciones, me gusta y comentarios son eventos firmados en el teléfono.
 * - Las cuentas privadas no publican en abierto: cada publicación viaja cifrada (gift wrap) sólo
 *   a los seguidores que la cuenta aprobó.
 * - Todo lo de Dezzy lleva la etiqueta `#dezzy` para que el feed sea sólo de música.
 */
object P2PSocial {

    const val APP_TAG = "dezzy"
    private const val PRIVATE_MARK = "dezzy_private"
    /** Degradado de portada de Dezzy (sólo el nombre del preset; no sube nada). */
    const val BANNER_PRESET_MARK = "dezzy_banner"
    const val BANNER_PRESET_PREFIX = "preset:"
    const val BANNER_VIDEO_MARK = "dezzy_banner_video"
    private const val BANNER_VIDEO_MAX_BYTES = 30L * 1024 * 1024
    private const val BANNER_VIDEO_MAX_MS = 15_500L

    /** Reacciones de Dezzy (NIP-25). El «+» de otros clientes de Nostr cuenta como ❤️. */
    val REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🔥")
    const val DEFAULT_REACTION = "❤️"

    /** Emoji de una reacción; null si la retira («-») o no es nuestra. */
    private fun reactionEmoji(content: String): String? = when {
        content == "-" -> null
        content in REACTIONS -> content
        else -> DEFAULT_REACTION
    }
    private const val SETTINGS = "dezzy_p2p_settings"

    /** Servidores Blossom gratuitos para fotos de publicaciones (las fotos son públicas). */
    private val BLOSSOM_SERVERS = listOf("https://blossom.primal.net", "https://nostr.download")

    private lateinit var appContext: Context
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val dao: P2PDao get() = P2PDatabase.get(appContext).dao
    private val json = Json { ignoreUnknownKeys = true }

    val keys: StateFlow<NostrKeys?> get() = P2PIdentity.keys
    val me: String? get() = P2PIdentity.publicKey

    private val _following = MutableStateFlow<Set<String>>(emptySet())
    val following: StateFlow<Set<String>> = _following.asStateFlow()
    private var lastContactsEvent: NostrEvent? = null

    private val profileCache = ConcurrentHashMap<String, P2PUser>()
    private val _profiles = MutableStateFlow<Map<String, P2PUser>>(emptyMap())

    /** Perfiles conocidos (la UI los observa para pintar nombres y fotos). */
    val profiles: StateFlow<Map<String, P2PUser>> = _profiles.asStateFlow()

    /** Mis reacciones públicas por publicación, para poder quitar el me gusta. */
    private val myReactions = ConcurrentHashMap<String, String>()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
    }

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        scope.launch {
            P2PIdentity.load(appContext)
            if (P2PIdentity.keys.value != null) onSignedIn()
        }
    }

    internal fun context(): Context = appContext

    /** Se llama al crear o recuperar la cuenta, y al arrancar si ya existía. */
    fun onSignedIn() {
        RelayPool.warmUp()
        scope.launch { runCatching { refreshFollowing() } }
        scope.launch { me?.let { runCatching { loadProfiles(listOf(it), force = true) } } }
        P2PMessaging.start()
        runCatching { P2PInboxWorker.schedule(appContext) }
    }

    suspend fun signOut() {
        P2PMessaging.stop()
        runCatching { P2PInboxWorker.cancel(appContext) }
        P2PNotifications.clearAll(appContext)
        withContext(Dispatchers.IO) {
            dao.clearMessages()
            dao.clearPrivatePosts()
            dao.clearPrivateReactions()
            dao.clearFollowRequests()
            dao.clearChatState()
        }
        P2PIdentity.signOut(appContext)
        _following.value = emptySet()
        lastContactsEvent = null
        myReactions.clear()
    }

    // ───────────────────────────── Ajustes ─────────────────────────────

    private fun settings() = appContext.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)

    var listeningVisibility: ListeningVisibility
        get() = runCatching {
            ListeningVisibility.valueOf(settings().getString("listening", null) ?: ListeningVisibility.EVERYONE.name)
        }.getOrDefault(ListeningVisibility.EVERYONE)
        set(value) {
            settings().edit().putString("listening", value.name).apply()
        }

    var nearbyVisible: Boolean
        get() = settings().getBoolean("nearby_visible", true)
        set(value) {
            settings().edit().putBoolean("nearby_visible", value).apply()
        }

    // ───────────────────────────── Perfiles ─────────────────────────────

    fun user(pubkey: String): P2PUser = profileCache[pubkey] ?: P2PUser(pubkey)

    val myProfile: P2PUser? get() = me?.let { user(it) }

    private fun remember(user: P2PUser) {
        profileCache[user.pubkey] = user
        _profiles.update { it + (user.pubkey to user) }
    }

    private fun parseProfile(event: NostrEvent): P2PUser? = runCatching {
        val o = json.parseToJsonElement(event.content).jsonObject
        fun str(key: String) = o[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        P2PUser(
            pubkey = event.pubkey,
            name = str("display_name") ?: str("name") ?: "",
            about = str("about") ?: "",
            picture = str("picture"),
            isPrivate = o[PRIVATE_MARK]?.jsonPrimitive?.booleanOrNull ?: false,
            // `banner` es el campo estándar de Nostr (URL); los degradados de Dezzy van aparte.
            banner = str("banner")?.takeIf { it.startsWith("https://") }
                ?: str(BANNER_PRESET_MARK)?.takeIf { it.length <= 24 }?.let { "$BANNER_PRESET_PREFIX$it" },
            bannerVideo = str(BANNER_VIDEO_MARK)?.takeIf { it.startsWith("https://") },
        )
    }.getOrNull()

    /** Carga perfiles: primero de la copia local, luego de los relés los que falten (o todos si [force]). */
    suspend fun loadProfiles(pubkeys: Collection<String>, force: Boolean = false) = withContext(Dispatchers.IO) {
        val wanted = pubkeys.filter { it.length == 64 }.distinct()
        if (wanted.isEmpty()) return@withContext
        val missingFromMemory = wanted.filter { !profileCache.containsKey(it) }
        if (missingFromMemory.isNotEmpty()) {
            dao.profiles(missingFromMemory).forEach {
                remember(P2PUser(it.pubkey, it.name, it.about, it.picture, it.isPrivate, it.banner, it.bannerVideo))
            }
        }
        val toFetch = if (force) wanted else wanted.filter { !profileCache.containsKey(it) }
        if (toFetch.isEmpty()) return@withContext
        toFetch.chunked(150).forEach { chunk ->
            val events = RelayPool.query(listOf(NostrFilter(authors = chunk, kinds = listOf(Kinds.METADATA))))
            events.groupBy { it.pubkey }.forEach { (_, list) ->
                val latest = list.maxBy { it.createdAt }
                val profile = parseProfile(latest) ?: return@forEach
                remember(profile)
                dao.upsertProfile(
                    P2PProfileEntity(
                        profile.pubkey, profile.name, profile.about, profile.picture, profile.isPrivate, latest.createdAt, profile.banner,
                        profile.bannerVideo,
                    ),
                )
            }
        }
    }

    /**
     * Publica mi perfil. [picture] puede ser una URL o un `data:` con una miniatura.
     * [banner] es una URL `https://` o `preset:<id>`; por defecto se conserva la actual.
     */
    suspend fun updateMyProfile(
        name: String,
        about: String,
        picture: String?,
        isPrivate: Boolean,
        banner: String? = myProfile?.banner,
        // Se conserva el video puesto desde el escritorio salvo que se cambie la portada.
        bannerVideo: String? = myProfile?.bannerVideo,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val k = keys.value ?: return@withContext false
            val content = buildJsonObject {
                put("name", name.trim())
                put("display_name", name.trim())
                put("about", about.trim())
                picture?.let { put("picture", it) }
                put(PRIVATE_MARK, isPrivate)
                when {
                    banner == null -> Unit
                    banner.startsWith(BANNER_PRESET_PREFIX) -> put(BANNER_PRESET_MARK, banner.removePrefix(BANNER_PRESET_PREFIX))
                    banner.startsWith("https://") -> put("banner", banner)
                }
                bannerVideo?.takeIf { it.startsWith("https://") }?.let { put(BANNER_VIDEO_MARK, it) }
            }.toString()
            val event = NostrEvent.sign(k, Kinds.METADATA, listOf(listOf("t", APP_TAG)), content)
            val profile = P2PUser(k.publicKey, name.trim(), about.trim(), picture, isPrivate, banner, bannerVideo)
            remember(profile)
            dao.upsertProfile(
                P2PProfileEntity(profile.pubkey, profile.name, profile.about, profile.picture, isPrivate, event.createdAt, banner, bannerVideo),
            )
            RelayPool.publish(event) > 0
        }

    /** Reduce una imagen a miniatura JPEG cuadrada y la devuelve como `data:` (para el avatar). */
    fun avatarDataUri(context: Context, uri: Uri, size: Int = 192): String? = runCatching {
        val bitmap = decodeScaled(context, uri, size * 2) ?: return null
        val side = minOf(bitmap.width, bitmap.height)
        val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, size, size, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(out.toByteArray())
    }.getOrNull()

    /** Reduce una foto para publicarla (lado mayor [maxSide] px, JPEG 82 %). */
    fun compressPhoto(context: Context, uri: Uri, maxSide: Int = 1080): ByteArray? = runCatching {
        val bitmap = decodeScaled(context, uri, maxSide) ?: return null
        val scale = minOf(1f, maxSide.toFloat() / maxOf(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else {
            bitmap
        }
        ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
    }.getOrNull()

    private fun decodeScaled(context: Context, uri: Uri, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    /** Video de portada listo para subir: el archivo y su primer fotograma en JPEG. */
    class BannerVideo(val video: ByteArray, val mime: String, val poster: ByteArray)

    /**
     * Comprueba el video de portada (hasta 15 s y 30 MB) y saca su primer fotograma.
     * Lanza un error con el motivo para mostrarlo tal cual.
     */
    fun prepareBannerVideo(context: android.content.Context, uri: android.net.Uri): BannerVideo {
        val resolver = context.contentResolver
        val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (size > BANNER_VIDEO_MAX_BYTES) error("El video pesa demasiado. Elige uno de menos de 30 MB.")
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val ms = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (ms > BANNER_VIDEO_MAX_MS) error("El video dura más de 15 segundos. Recórtalo en la galería y vuelve a elegirlo.")
            val frame = retriever.getFrameAtTime(0) ?: error("No se pudo leer el video.")
            val scale = minOf(1f, 1280f / maxOf(frame.width, frame.height))
            val poster = if (scale < 1f) {
                android.graphics.Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt(), (frame.height * scale).toInt(), true)
            } else {
                frame
            }
            val jpeg = java.io.ByteArrayOutputStream().use {
                poster.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it)
                it.toByteArray()
            }
            val mime = resolver.getType(uri)?.takeIf { it == "video/mp4" || it == "video/webm" } ?: "video/mp4"
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("No se pudo leer el video.")
            return BannerVideo(bytes, mime, jpeg)
        } finally {
            retriever.release()
        }
    }

    /** Sube una foto a un servidor Blossom gratuito (autorizado con mi firma). */
    suspend fun uploadPhoto(bytes: ByteArray, mime: String = "image/jpeg"): String? = withContext(Dispatchers.IO) {
        val k = keys.value ?: return@withContext null
        val hash = CryptoUtils.sha256(bytes).toHex()
        val auth = NostrEvent.sign(
            k,
            Kinds.BLOSSOM_AUTH,
            listOf(
                listOf("t", "upload"),
                listOf("x", hash),
                listOf("expiration", (NostrEvent.now() + 600).toString()),
            ),
            "Foto de Dezzy",
        )
        val header = "Nostr " + Base64.getEncoder().encodeToString(auth.toJson().toByteArray(Charsets.UTF_8))
        for (server in BLOSSOM_SERVERS) {
            val url = runCatching {
                val request = Request.Builder()
                    .url("$server/upload")
                    .put(bytes.toRequestBody(mime.toMediaType()))
                    .header("Authorization", header)
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body.string()
                    json.parseToJsonElement(body).jsonObject["url"]?.jsonPrimitive?.contentOrNull
                }
            }.onFailure { Timber.d("Blossom $server: ${it.message}") }.getOrNull()
            if (url != null) return@withContext url
        }
        null
    }

    /** Busca gente por nombre (relé con búsqueda) o por su enlace/`npub`. */
    suspend fun searchUsers(query: String): List<P2PUser> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        parsePublicKey(q)?.let { pubkey ->
            loadProfiles(listOf(pubkey))
            return@withContext listOf(user(pubkey))
        }
        val events = RelayPool.query(
            listOf(NostrFilter(kinds = listOf(Kinds.METADATA), search = q, limit = 25)),
            relayUrls = listOf(RelayPool.SEARCH_RELAY),
        )
        events.mapNotNull { event -> parseProfile(event)?.also { remember(it) } }
            .distinctBy { it.pubkey }
            .filter { it.pubkey != me }
    }

    /** Acepta `npub1…`, `nostr:npub1…`, hex o enlaces `dezzy://invite?npub=…`. */
    fun parsePublicKey(input: String): String? {
        val trimmed = input.trim()
        val fromLink = runCatching { Uri.parse(trimmed).getQueryParameter("npub") }.getOrNull()
        return Bech32.parsePublicKey(fromLink ?: trimmed)
    }

    /** Enlace para que otra persona me añada. */
    fun myInviteLink(): String? = me?.let { "https://dezzy.space/?npub=${Bech32.npub(it)}" }

    // ───────────────────────────── Seguir ─────────────────────────────

    suspend fun refreshFollowing() = withContext(Dispatchers.IO) {
        val myKey = me ?: return@withContext
        val latest = RelayPool.query(listOf(NostrFilter(authors = listOf(myKey), kinds = listOf(Kinds.CONTACTS), limit = 1)))
            .maxByOrNull { it.createdAt } ?: return@withContext
        if ((lastContactsEvent?.createdAt ?: 0) > latest.createdAt) return@withContext
        lastContactsEvent = latest
        _following.value = latest.tagValues("p").filter { it.length == 64 }.toSet()
    }

    private suspend fun publishFollowing(newSet: Set<String>): Boolean {
        val k = keys.value ?: return false
        val previous = lastContactsEvent
        val otherTags = previous?.tags?.filter { it.firstOrNull() != "p" }.orEmpty()
        val tags = otherTags + newSet.map { listOf("p", it) }
        val event = NostrEvent.sign(k, Kinds.CONTACTS, tags, previous?.content.orEmpty())
        lastContactsEvent = event
        _following.value = newSet
        return RelayPool.publish(event) > 0
    }

    /**
     * Sigue o deja de seguir. Si la cuenta es privada, además le llega una solicitud cifrada
     * y sus publicaciones sólo aparecen cuando la apruebe.
     */
    suspend fun setFollowing(pubkey: String, follow: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (pubkey == me) return@withContext false
        // Asegura no pisar la lista publicada desde otro dispositivo.
        if (lastContactsEvent == null) runCatching { refreshFollowing() }
        val current = _following.value
        val ok = publishFollowing(if (follow) current + pubkey else current - pubkey)
        if (follow && user(pubkey).isPrivate) P2PMessaging.requestFollow(pubkey)
        ok
    }

    suspend fun followState(pubkey: String): FollowState {
        if (pubkey !in _following.value) return FollowState.NONE
        if (!user(pubkey).isPrivate) return FollowState.FOLLOWING
        val request = withContext(Dispatchers.IO) { dao.followRequest(pubkey, incoming = false) }
        return if (request?.status == "approved") FollowState.FOLLOWING else FollowState.REQUESTED
    }

    /** ¿Puedo ver las publicaciones de esta persona? */
    suspend fun canSee(pubkey: String): Boolean =
        pubkey == me || !user(pubkey).isPrivate || followState(pubkey) == FollowState.FOLLOWING

    /** Recuentos aproximados (lo que devuelvan los relés): seguidores y seguidos. */
    suspend fun followCounts(pubkey: String): Pair<Int, Int> = withContext(Dispatchers.IO) {
        coroutineScope {
            val followers = async {
                RelayPool.query(listOf(NostrFilter(kinds = listOf(Kinds.CONTACTS), tags = mapOf("#p" to listOf(pubkey)), limit = 500)))
                    .map { it.pubkey }.distinct().size
            }
            val followingCount = async {
                RelayPool.query(listOf(NostrFilter(authors = listOf(pubkey), kinds = listOf(Kinds.CONTACTS), limit = 1)))
                    .maxByOrNull { it.createdAt }?.tagValues("p")?.size ?: 0
            }
            followers.await() to followingCount.await()
        }
    }

    val pendingFollowRequests: Flow<List<P2PFollowRequestEntity>> get() = dao.pendingIncomingRequests()

    // ───────────────────────────── Publicaciones ─────────────────────────────

    enum class FeedMode { FOLLOWING, DISCOVER }

    private fun NostrEvent.isReply(): Boolean = tags.any { it.firstOrNull() == "e" }

    private fun NostrEvent.toPost(isPrivate: Boolean = false): P2PPost = P2PPost(
        id = id,
        author = pubkey,
        createdAt = createdAt,
        text = content,
        song = P2PSong.fromTag(fullTag(P2PSong.TAG)),
        imageUrl = tag("image"),
        isPrivate = isPrivate,
    )

    private suspend fun privatePostsFrom(authors: List<String>, until: Long?, limit: Int): List<P2PPost> {
        if (authors.isEmpty()) return emptyList()
        return dao.privatePosts(authors, limit * 2)
            .filter { until == null || it.createdAt <= until }
            .mapNotNull { NostrEvent.fromJson(it.json)?.toPost(isPrivate = true) }
            .take(limit)
    }

    /** Feed: «Siguiendo» (gente que sigo + yo) o «Descubrir» (todo #dezzy). Paginado con [until]. */
    suspend fun feed(mode: FeedMode, until: Long? = null, limit: Int = 30): List<P2PPost> = withContext(Dispatchers.IO) {
        val myKey = me
        val posts = when (mode) {
            FeedMode.FOLLOWING -> {
                val authors = (_following.value + listOfNotNull(myKey)).toList()
                if (authors.isEmpty()) return@withContext emptyList()
                val public = authors.chunked(250).flatMap { chunk ->
                    RelayPool.query(
                        listOf(
                            NostrFilter(
                                authors = chunk,
                                kinds = listOf(Kinds.TEXT_NOTE),
                                tags = mapOf("#t" to listOf(APP_TAG)),
                                until = until,
                                limit = limit,
                            ),
                        ),
                    )
                }.filterNot { it.isReply() }.map { it.toPost() }
                public + privatePostsFrom(authors, until, limit)
            }
            FeedMode.DISCOVER -> RelayPool.query(
                listOf(
                    NostrFilter(kinds = listOf(Kinds.TEXT_NOTE), tags = mapOf("#t" to listOf(APP_TAG)), until = until, limit = limit),
                ),
            ).filterNot { it.isReply() }.map { it.toPost() }
        }
        val sorted = posts.distinctBy { it.id }.sortedByDescending { it.createdAt }.take(limit)
        loadProfiles(sorted.map { it.author })
        withStats(sorted)
    }

    /** Publicaciones de una persona (las privadas sólo si me aprobó). */
    suspend fun userPosts(pubkey: String, until: Long? = null, limit: Int = 30): List<P2PPost> = withContext(Dispatchers.IO) {
        val private = privatePostsFrom(listOf(pubkey), until, limit)
        val public = if (user(pubkey).isPrivate && pubkey != me) {
            emptyList()
        } else {
            RelayPool.query(
                listOf(
                    NostrFilter(
                        authors = listOf(pubkey),
                        kinds = listOf(Kinds.TEXT_NOTE),
                        tags = mapOf("#t" to listOf(APP_TAG)),
                        until = until,
                        limit = limit,
                    ),
                ),
            ).filterNot { it.isReply() }.map { it.toPost() }
        }
        withStats((public + private).distinctBy { it.id }.sortedByDescending { it.createdAt }.take(limit))
    }

    /** Añade me gusta y comentarios contados en los relés (y en la copia local si son privados). */
    suspend fun withStats(posts: List<P2PPost>): List<P2PPost> = withContext(Dispatchers.IO) {
        if (posts.isEmpty()) return@withContext posts
        val myKey = me
        val publicIds = posts.filterNot { it.isPrivate }.map { it.id }
        val comments = HashMap<String, Int>()
        // (publicación, autor) → (fecha, emoji o null). Cuenta sólo la última de cada persona:
        // al cambiar de emoji la anterior se borra, pero no todos los relés lo respetan.
        val latest = HashMap<Pair<String, String>, Triple<Long, String?, String>>()
        fun offer(postId: String, author: String, createdAt: Long, emoji: String?, eventId: String) {
            val key = postId to author
            val current = latest[key]
            if (current == null || createdAt > current.first || (createdAt == current.first && eventId > current.third)) {
                latest[key] = Triple(createdAt, emoji, eventId)
            }
        }
        if (publicIds.isNotEmpty()) {
            val events = publicIds.chunked(100).map { chunk ->
                async {
                    RelayPool.query(
                        listOf(NostrFilter(kinds = listOf(Kinds.REACTION, Kinds.TEXT_NOTE), tags = mapOf("#e" to chunk), limit = 1000)),
                        timeoutMs = 5000,
                    )
                }
            }.awaitAll().flatten()
            for (event in events) {
                val target = event.tagValues("e").lastOrNull { it in publicIds } ?: continue
                when (event.kind) {
                    Kinds.REACTION -> offer(target, event.pubkey, event.createdAt, reactionEmoji(event.content), event.id)
                    Kinds.TEXT_NOTE -> comments[target] = (comments[target] ?: 0) + 1
                }
            }
        }
        val privateIds = posts.filter { it.isPrivate }.map { it.id }
        if (privateIds.isNotEmpty()) {
            dao.privateReactions(privateIds).forEach { r ->
                if (r.type == "like") {
                    offer(r.postId, r.author, r.createdAt, reactionEmoji(r.content), r.id)
                } else {
                    comments[r.postId] = (comments[r.postId] ?: 0) + 1
                }
            }
        }
        val reactions = HashMap<String, HashMap<String, Int>>()
        val mine = HashMap<String, String>()
        for ((key, value) in latest) {
            val (postId, author) = key
            val emoji = value.second ?: continue
            val counts = reactions.getOrPut(postId) { HashMap() }
            counts[emoji] = (counts[emoji] ?: 0) + 1
            if (author == myKey) {
                mine[postId] = emoji
                if (postId in publicIds) myReactions[postId] = value.third
            }
        }
        posts.map { p ->
            val counts = reactions[p.id].orEmpty()
            p.copy(
                likes = counts.values.sum(),
                comments = comments[p.id] ?: 0,
                likedByMe = p.id in mine,
                reactions = counts,
                myReaction = mine[p.id],
            )
        }
    }

    /**
     * Publica. Si mi cuenta es privada, la publicación no sale en abierto: se cifra para cada
     * seguidor aprobado (y para mí, para tenerla en otros dispositivos).
     */
    suspend fun createPost(text: String, song: P2PSong?, photo: ByteArray?): Boolean = withContext(Dispatchers.IO) {
        val k = keys.value ?: return@withContext false
        val imageUrl = photo?.let { uploadPhoto(it) ?: return@withContext false }
        val tags = buildList {
            add(listOf("t", APP_TAG))
            song?.let {
                add(it.toTag())
                add(listOf("r", it.url))
            }
            imageUrl?.let {
                add(listOf("image", it))
                add(listOf("imeta", "url $it", "m image/jpeg"))
            }
        }
        if (myProfile?.isPrivate == true) {
            val rumor = NostrEvent.rumor(k.publicKey, Kinds.TEXT_NOTE, tags + listOf(listOf(APP_TAG, "private_post")), text)
            dao.insertPrivatePost(P2PPrivatePostEntity(rumor.id, k.publicKey, rumor.createdAt, rumor.toJson(includeSig = false)))
            val audience = dao.approvedFollowers() + k.publicKey
            P2PMessaging.sendWrapped(rumor, audience)
            true
        } else {
            RelayPool.publish(NostrEvent.sign(k, Kinds.TEXT_NOTE, tags, text)) > 0
        }
    }

    /** Me gusta / quitar me gusta (❤️). Devuelve si queda con reacción. */
    suspend fun toggleLike(post: P2PPost): Boolean =
        react(post, if (post.myReaction != null) null else DEFAULT_REACTION) != null

    /**
     * Reacciona con [emoji] (uno de [REACTIONS]) o quita la reacción con null (NIP-25).
     * Devuelve la reacción que queda publicada.
     */
    suspend fun react(post: P2PPost, emoji: String?): String? = withContext(Dispatchers.IO) {
        val k = keys.value ?: return@withContext post.myReaction
        val wanted = emoji?.takeIf { it in REACTIONS }
        if (wanted == post.myReaction) return@withContext wanted
        // ❤️ sale como «+», que todos los clientes de Nostr entienden como me gusta.
        val content = when (wanted) {
            null -> "-"
            DEFAULT_REACTION -> "+"
            else -> wanted
        }
        if (post.isPrivate) {
            // Privadas: no hay borrado en los relés; cuenta la última de cada persona («-» la retira).
            val rumor = NostrEvent.rumor(
                k.publicKey,
                Kinds.REACTION,
                listOf(listOf("e", post.id), listOf("p", post.author), listOf(APP_TAG, "private_reaction")),
                content,
            )
            P2PMessaging.storePrivateReaction(rumor)
            P2PMessaging.sendWrapped(rumor, listOf(post.author, k.publicKey).distinct())
            return@withContext wanted
        }
        myReactions.remove(post.id)?.let { old ->
            RelayPool.publish(NostrEvent.sign(k, Kinds.DELETION, listOf(listOf("e", old), listOf("k", "7")), ""))
        }
        if (wanted == null) return@withContext null
        val reaction = NostrEvent.sign(
            k,
            Kinds.REACTION,
            listOf(listOf("e", post.id), listOf("p", post.author), listOf("k", "1")),
            content,
        )
        if (RelayPool.publish(reaction) > 0) {
            myReactions[post.id] = reaction.id
            wanted
        } else {
            null
        }
    }

    suspend fun comments(post: P2PPost): List<P2PComment> = withContext(Dispatchers.IO) {
        val list = if (post.isPrivate) {
            dao.privateReactions(listOf(post.id)).filter { it.type == "comment" }
                .map { P2PComment(it.id, it.author, it.createdAt, it.content, it.replyTo) }
        } else {
            RelayPool.query(listOf(NostrFilter(kinds = listOf(Kinds.TEXT_NOTE), tags = mapOf("#e" to listOf(post.id)), limit = 300)))
                .map { event ->
                    // NIP-10: ["e", raíz, "", "root"] y ["e", padre, "", "reply"].
                    val parent = event.tags.firstOrNull { it.size >= 4 && it[0] == "e" && it[3] == "reply" }?.get(1)
                    P2PComment(event.id, event.pubkey, event.createdAt, event.content, parent?.takeIf { it != post.id })
                }
        }
        loadProfiles(list.map { it.author })
        threadComments(list)
    }

    /**
     * Ordena en hilos de un nivel: cada comentario seguido de sus respuestas. Una respuesta
     * a otra respuesta cuelga del comentario de primer nivel; si el padre no está, va suelta.
     */
    fun threadComments(list: List<P2PComment>): List<P2PComment> {
        val byId = list.associateBy { it.id }
        fun rootOf(c: P2PComment): String? {
            var parent = c.replyTo?.let { byId[it] } ?: return null
            repeat(8) { parent = parent.replyTo?.let { byId[it] } ?: return parent.id }
            return parent.id
        }
        val resolved = list.distinctBy { it.id }.map { it.copy(replyTo = rootOf(it)) }
        val replies = resolved.filter { it.replyTo != null }.groupBy { it.replyTo }
        return resolved.filter { it.replyTo == null }.sortedBy { it.createdAt }.flatMap { top ->
            listOf(top) + replies[top.id].orEmpty().sortedBy { it.createdAt }
        }
    }

    suspend fun addComment(post: P2PPost, text: String, replyTo: P2PComment? = null): P2PComment? = withContext(Dispatchers.IO) {
        val k = keys.value ?: return@withContext null
        val clean = text.trim().takeIf { it.isNotEmpty() } ?: return@withContext null
        val tags = buildList {
            add(listOf("e", post.id, "", "root"))
            replyTo?.let { add(listOf("e", it.id, "", "reply")) }
            add(listOf("p", post.author))
            replyTo?.author?.takeIf { it != post.author }?.let { add(listOf("p", it)) }
            add(listOf("t", APP_TAG))
        }
        // Sólo un nivel: responder a una respuesta cuelga del mismo comentario de arriba.
        val thread = replyTo?.let { it.replyTo ?: it.id }
        if (post.isPrivate) {
            val rumor = NostrEvent.rumor(k.publicKey, Kinds.TEXT_NOTE, tags + listOf(listOf(APP_TAG, "private_comment")), clean)
            P2PMessaging.storePrivateReaction(rumor)
            P2PMessaging.sendWrapped(rumor, listOf(post.author, k.publicKey).distinct())
            P2PComment(rumor.id, k.publicKey, rumor.createdAt, clean, thread)
        } else {
            val event = NostrEvent.sign(k, Kinds.TEXT_NOTE, tags, clean)
            if (RelayPool.publish(event) > 0) P2PComment(event.id, k.publicKey, event.createdAt, clean, thread) else null
        }
    }

    suspend fun deletePost(post: P2PPost): Boolean = withContext(Dispatchers.IO) {
        val k = keys.value ?: return@withContext false
        if (post.author != k.publicKey) return@withContext false
        if (post.isPrivate) {
            dao.deletePrivatePost(post.id)
            val rumor = NostrEvent.rumor(k.publicKey, Kinds.DELETION, listOf(listOf("e", post.id), listOf(APP_TAG, "private_delete")), "")
            P2PMessaging.sendWrapped(rumor, dao.approvedFollowers() + k.publicKey)
            true
        } else {
            RelayPool.publish(NostrEvent.sign(k, Kinds.DELETION, listOf(listOf("e", post.id), listOf("k", "1")), "")) > 0
        }
    }

    /** Reenvía mis últimas publicaciones privadas a un seguidor recién aprobado. */
    internal suspend fun shareRecentPrivatePosts(follower: String) {
        val myKey = me ?: return
        dao.privatePostsOf(myKey, 20).mapNotNull { NostrEvent.fromJson(it.json) }.forEach { rumor ->
            P2PMessaging.sendWrapped(rumor, listOf(follower))
        }
    }

    /** Usado por la mensajería al recibir una publicación privada. */
    internal suspend fun storePrivatePost(rumor: NostrEvent) {
        dao.insertPrivatePost(P2PPrivatePostEntity(rumor.id, rumor.pubkey, rumor.createdAt, rumor.toJson(includeSig = false)))
    }
}
