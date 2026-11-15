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
    }

    suspend fun signOut() {
        P2PMessaging.stop()
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
        )
    }.getOrNull()

    /** Carga perfiles: primero de la copia local, luego de los relés los que falten (o todos si [force]). */
    suspend fun loadProfiles(pubkeys: Collection<String>, force: Boolean = false) = withContext(Dispatchers.IO) {
        val wanted = pubkeys.filter { it.length == 64 }.distinct()
        if (wanted.isEmpty()) return@withContext
        val missingFromMemory = wanted.filter { !profileCache.containsKey(it) }
        if (missingFromMemory.isNotEmpty()) {
            dao.profiles(missingFromMemory).forEach {
                remember(P2PUser(it.pubkey, it.name, it.about, it.picture, it.isPrivate))
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
                    P2PProfileEntity(profile.pubkey, profile.name, profile.about, profile.picture, profile.isPrivate, latest.createdAt),
                )
            }
        }
    }

    /** Publica mi perfil. [picture] puede ser una URL o un `data:` con una miniatura. */
    suspend fun updateMyProfile(name: String, about: String, picture: String?, isPrivate: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val k = keys.value ?: return@withContext false
            val content = buildJsonObject {
                put("name", name.trim())
                put("display_name", name.trim())
                put("about", about.trim())
                picture?.let { put("picture", it) }
                put(PRIVATE_MARK, isPrivate)
            }.toString()
            val event = NostrEvent.sign(k, Kinds.METADATA, listOf(listOf("t", APP_TAG)), content)
            val profile = P2PUser(k.publicKey, name.trim(), about.trim(), picture, isPrivate)
            remember(profile)
            dao.upsertProfile(P2PProfileEntity(profile.pubkey, profile.name, profile.about, profile.picture, isPrivate, event.createdAt))
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

    /** Sube una foto a un servidor Blossom gratuito (autorizado con mi firma). */
    suspend fun uploadPhoto(bytes: ByteArray): String? = withContext(Dispatchers.IO) {
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
                    .put(bytes.toRequestBody("image/jpeg".toMediaType()))
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
        val likes = HashMap<String, Int>()
        val comments = HashMap<String, Int>()
        val liked = HashSet<String>()
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
                    Kinds.REACTION -> if (event.content != "-") {
                        likes[target] = (likes[target] ?: 0) + 1
                        if (event.pubkey == myKey) {
                            liked += target
                            myReactions[target] = event.id
                        }
                    }
                    Kinds.TEXT_NOTE -> comments[target] = (comments[target] ?: 0) + 1
                }
            }
        }
        val privateIds = posts.filter { it.isPrivate }.map { it.id }
        if (privateIds.isNotEmpty()) {
            dao.privateReactions(privateIds).forEach { r ->
                if (r.type == "like") {
                    likes[r.postId] = (likes[r.postId] ?: 0) + 1
                    if (r.author == myKey) liked += r.postId
                } else {
                    comments[r.postId] = (comments[r.postId] ?: 0) + 1
                }
            }
        }
        posts.map { p ->
            p.copy(likes = likes[p.id] ?: 0, comments = comments[p.id] ?: 0, likedByMe = p.id in liked)
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

    /** Me gusta / quitar me gusta. Devuelve el nuevo estado. */
    suspend fun toggleLike(post: P2PPost): Boolean = withContext(Dispatchers.IO) {
        val k = keys.value ?: return@withContext post.likedByMe
        if (post.isPrivate) {
            if (post.likedByMe) return@withContext true // los privados no se pueden retirar de los otros teléfonos
            val rumor = NostrEvent.rumor(
                k.publicKey,
                Kinds.REACTION,
                listOf(listOf("e", post.id), listOf("p", post.author), listOf(APP_TAG, "private_reaction")),
                "+",
            )
            P2PMessaging.storePrivateReaction(rumor)
            P2PMessaging.sendWrapped(rumor, listOf(post.author, k.publicKey).distinct())
            return@withContext true
        }
        if (post.likedByMe) {
            val reactionId = myReactions[post.id] ?: return@withContext false
            val deletion = NostrEvent.sign(k, Kinds.DELETION, listOf(listOf("e", reactionId), listOf("k", "7")), "")
            RelayPool.publish(deletion)
            myReactions.remove(post.id)
            false
        } else {
            val reaction = NostrEvent.sign(
                k,
                Kinds.REACTION,
                listOf(listOf("e", post.id), listOf("p", post.author), listOf("k", "1")),
                "+",
            )
            myReactions[post.id] = reaction.id
            RelayPool.publish(reaction)
            true
        }
    }

    suspend fun comments(post: P2PPost): List<P2PComment> = withContext(Dispatchers.IO) {
        val list = if (post.isPrivate) {
            dao.privateReactions(listOf(post.id)).filter { it.type == "comment" }
                .map { P2PComment(it.id, it.author, it.createdAt, it.content) }
        } else {
            RelayPool.query(listOf(NostrFilter(kinds = listOf(Kinds.TEXT_NOTE), tags = mapOf("#e" to listOf(post.id)), limit = 300)))
                .map { P2PComment(it.id, it.pubkey, it.createdAt, it.content) }
        }
        loadProfiles(list.map { it.author })
        list.sortedBy { it.createdAt }
    }

    suspend fun addComment(post: P2PPost, text: String): P2PComment? = withContext(Dispatchers.IO) {
        val k = keys.value ?: return@withContext null
        val clean = text.trim().takeIf { it.isNotEmpty() } ?: return@withContext null
        val tags = listOf(listOf("e", post.id, "", "root"), listOf("p", post.author), listOf("t", APP_TAG))
        if (post.isPrivate) {
            val rumor = NostrEvent.rumor(k.publicKey, Kinds.TEXT_NOTE, tags + listOf(listOf(APP_TAG, "private_comment")), clean)
            P2PMessaging.storePrivateReaction(rumor)
            P2PMessaging.sendWrapped(rumor, listOf(post.author, k.publicKey).distinct())
            P2PComment(rumor.id, k.publicKey, rumor.createdAt, clean)
        } else {
            val event = NostrEvent.sign(k, Kinds.TEXT_NOTE, tags, clean)
            if (RelayPool.publish(event) > 0) P2PComment(event.id, k.publicKey, event.createdAt, clean) else null
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
