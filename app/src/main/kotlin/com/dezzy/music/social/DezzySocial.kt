/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.social

import com.dezzy.music.utils.SupabaseConfig
import com.dezzy.music.utils.reportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ─────────────────────────────────────────────────────────────────────────────
//  Modelos
// ─────────────────────────────────────────────────────────────────────────────

/** Un amigo con lo que está escuchando ahora mismo (panel de actividad). */
data class FriendActivity(
    val userId: String,
    val name: String,
    val username: String?,
    val avatarUrl: String?,
    val videoId: String?,
    val title: String?,
    val artist: String?,
    val artworkUrl: String?,
    val isPlaying: Boolean,
    val updatedAt: String?,
)

/** Oyente mostrado en el Music Map (presencia pública o de un amigo, + ubicación). */
data class MapUser(
    val userId: String,
    val name: String,
    val avatarUrl: String?,
    val videoId: String?,
    val title: String?,
    val artist: String?,
    val artworkUrl: String?,
    val lat: Double,
    val lng: Double,
)

/** Publicación del feed musical: siempre referencia una entidad real. */
data class MusicPost(
    val postId: String,
    val userId: String,
    val authorName: String,
    val authorUsername: String?,
    val authorAvatar: String?,
    val entityType: String,
    val entityId: String,
    val entityTitle: String,
    val entitySubtitle: String?,
    val artworkUrl: String?,
    val caption: String?,
    val likesCount: Int,
    val commentsCount: Int,
    val likedByMe: Boolean,
    val createdAt: String?,
)

data class PostComment(
    val commentId: String,
    val userId: String,
    val authorName: String,
    val authorAvatar: String?,
    val content: String,
    val createdAt: String?,
)

/** Comentario anclado a un instante de la canción (estilo SoundCloud). */
data class TrackComment(
    val commentId: String,
    val userId: String,
    val authorName: String,
    val authorAvatar: String?,
    val positionMs: Int,
    val content: String,
)

data class TopEntity(
    val entityId: String,
    val entityName: String,
    val artworkUrl: String?,
    val playCount: Int,
)

/** Perfil musical público. */
data class MusicProfile(
    val userId: String,
    val name: String,
    val username: String?,
    val avatarUrl: String?,
    val bio: String?,
    val followers: Long,
    val following: Long,
    val iFollow: Boolean,
    val isFriend: Boolean,
    val nowPlaying: String?,
    val nowArtist: String?,
    val nowArtwork: String?,
)

/** Estado de reproducción guardado por otro dispositivo. */
data class RemotePlaybackState(
    val videoId: String?,
    val title: String?,
    val artist: String?,
    val artworkUrl: String?,
    val positionMs: Long,
    val deviceName: String?,
    val updatedAt: String?,
)

/** Persona sugerida a seguir (perfil estilo Threads). */
data class SuggestedUser(
    val userId: String,
    val name: String,
    val username: String?,
    val avatarUrl: String?,
    val followers: Long,
)

/** Pista con más comentarios (sección estilo YouTube Music). */
data class CommentedTrack(
    val videoId: String,
    val commentCount: Long,
    val sampleContent: String?,
    val sampleAuthor: String?,
    val sampleAvatar: String?,
)

enum class ActivityVisibility(val wire: String) {
    Public("public"),
    Friends("friends"),
    Private("private"),
}

// ─────────────────────────────────────────────────────────────────────────────
//  Cliente
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Capa de datos única de la red social musical.
 *
 * Habla con las funciones `public.dezzy_*` de Supabase (ver
 * `supabase/migrations/`). Todas son SECURITY DEFINER y derivan la identidad de
 * `auth.uid()`, así que aquí NUNCA se manda el id del usuario actual: el
 * servidor no confía en el cliente.
 *
 * Toda función devuelve un valor vacío/null ante fallo de red en vez de lanzar:
 * la red social es una capa opcional sobre el reproductor y nunca debe tumbar
 * una pantalla si el backend no responde.
 */
object DezzySocial {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    /** Llama a una RPC. Devuelve el cuerpo crudo, o null si no hay sesión o falla. */
    private suspend fun rpc(name: String, params: JSONObject = JSONObject()): String? =
        withContext(Dispatchers.IO) {
            val headers = SupabaseAuth.authHeaders() ?: return@withContext null
            try {
                val builder = Request.Builder()
                    .url("${SupabaseConfig.REST}/rpc/$name")
                    .post(params.toString().toRequestBody(jsonMedia))
                headers.forEach { (k, v) -> builder.addHeader(k, v) }
                client.newCall(builder.build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        reportException(Exception("dezzy rpc $name -> ${resp.code}: $text"))
                        return@use null
                    }
                    text
                }
            } catch (e: Exception) {
                reportException(e)
                null
            }
        }

    private fun rows(body: String?): List<JSONObject> {
        val arr = runCatching { JSONArray(body.orEmpty()) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun JSONObject.str(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }

    // ── Presencia / actividad ───────────────────────────────────────────────

    /**
     * Publica lo que suena ahora. El servicio la llama periódicamente mientras
     * hay reproducción; `visibility` solo se manda cuando el usuario la cambia,
     * para que los pings no pisen su preferencia.
     */
    suspend fun updatePresence(
        videoId: String?,
        title: String?,
        artist: String?,
        album: String? = null,
        artworkUrl: String? = null,
        isPlaying: Boolean = false,
        positionMs: Long = 0,
        durationMs: Long = 0,
        lat: Double? = null,
        lng: Double? = null,
        visibility: ActivityVisibility? = null,
        deviceName: String? = null,
    ) {
        val params = JSONObject()
            .put("p_video_id", videoId ?: JSONObject.NULL)
            .put("p_title", title ?: JSONObject.NULL)
            .put("p_artist", artist ?: JSONObject.NULL)
            .put("p_album", album ?: JSONObject.NULL)
            .put("p_artwork_url", artworkUrl ?: JSONObject.NULL)
            .put("p_is_playing", isPlaying)
            .put("p_position_ms", positionMs)
            .put("p_duration_ms", durationMs)
            .put("p_lat", lat ?: JSONObject.NULL)
            .put("p_lng", lng ?: JSONObject.NULL)
            .put("p_visibility", visibility?.wire ?: JSONObject.NULL)
            .put("p_device_name", deviceName ?: JSONObject.NULL)
        rpc("dezzy_update_presence", params)
    }

    /** Limpia la presencia (al parar la reproducción o cerrar sesión). */
    suspend fun clearPresence() = updatePresence(null, null, null)

    suspend fun friendActivity(maxAgeSeconds: Int = 900): List<FriendActivity> =
        rows(rpc("dezzy_friend_activity", JSONObject().put("p_max_age_seconds", maxAgeSeconds)))
            .map { o ->
                FriendActivity(
                    userId = o.optString("user_id"),
                    name = (o.textOrNull("name") ?: "Usuario"),
                    username = o.str("username"),
                    avatarUrl = o.str("avatar_url"),
                    videoId = o.str("video_id"),
                    title = o.str("title"),
                    artist = o.str("artist"),
                    artworkUrl = o.str("artwork_url"),
                    isPlaying = o.optBoolean("is_playing"),
                    updatedAt = o.str("updated_at"),
                )
            }

    /**
     * Presencias del Music Map: solo quien eligió visibilidad pública y está
     * compartiendo ubicación.
     */
    suspend fun mapPresences(maxAgeSeconds: Int = 900): List<MapUser> =
        rows(rpc("dezzy_map_presences", JSONObject().put("p_max_age_seconds", maxAgeSeconds)))
            .mapNotNull { o ->
                val lat = o.optDouble("lat", Double.NaN)
                val lng = o.optDouble("lng", Double.NaN)
                if (lat.isNaN() || lng.isNaN()) return@mapNotNull null
                MapUser(
                    userId = o.optString("user_id"),
                    name = (o.textOrNull("name") ?: "Usuario"),
                    avatarUrl = o.str("avatar_url"),
                    videoId = o.str("video_id"),
                    title = o.str("title"),
                    artist = o.str("artist"),
                    artworkUrl = o.str("artwork_url"),
                    lat = lat,
                    lng = lng,
                )
            }

    // ── Seguidores ──────────────────────────────────────────────────────────

    suspend fun follow(userId: String, follow: Boolean = true) {
        rpc("dezzy_follow", JSONObject().put("p_user_id", userId).put("p_follow", follow))
    }

    // ── Feed musical ────────────────────────────────────────────────────────

    private fun parsePost(o: JSONObject) = MusicPost(
        postId = o.optString("post_id"),
        userId = o.optString("user_id"),
        authorName = (o.textOrNull("author_name") ?: "Usuario"),
        authorUsername = o.str("author_username"),
        authorAvatar = o.str("author_avatar"),
        entityType = o.optString("entity_type").ifBlank { "song" },
        entityId = o.optString("entity_id"),
        entityTitle = o.optString("entity_title"),
        entitySubtitle = o.str("entity_subtitle"),
        artworkUrl = o.str("artwork_url"),
        caption = o.str("caption"),
        likesCount = o.optInt("likes_count"),
        commentsCount = o.optInt("comments_count"),
        likedByMe = o.optBoolean("liked_by_me"),
        createdAt = o.str("created_at"),
    )

    /** Feed de a quién sigo. `before` pagina por cursor (el createdAt del último). */
    suspend fun getFeed(before: String? = null, limit: Int = 20): List<MusicPost> =
        rows(
            rpc(
                "dezzy_get_feed",
                JSONObject()
                    .put("p_before", before ?: JSONObject.NULL)
                    .put("p_limit", limit),
            )
        ).map(::parsePost)

    /** Descubrimiento: lo más gustado de las últimas 48 h, de cualquiera. */
    suspend fun getDiscover(limit: Int = 20): List<MusicPost> =
        rows(rpc("dezzy_get_discover", JSONObject().put("p_limit", limit))).map(::parsePost)

    /** Publicaciones ("hilos") de un usuario concreto — para su perfil. */
    suspend fun getUserPosts(userId: String, limit: Int = 30): List<MusicPost> =
        rows(
            rpc(
                "dezzy_user_posts",
                JSONObject().put("p_user_id", userId).put("p_limit", limit),
            )
        ).map(::parsePost)

    /** Gente de Dezzy a la que aún no sigo, para "Sugerencias para ti". */
    suspend fun suggestions(limit: Int = 10): List<SuggestedUser> =
        rows(rpc("dezzy_suggestions", JSONObject().put("p_limit", limit))).map { o ->
            SuggestedUser(
                userId = o.optString("user_id"),
                name = (o.textOrNull("name") ?: "Usuario"),
                username = o.str("username"),
                avatarUrl = o.str("avatar_url"),
                followers = o.optLong("followers"),
            )
        }

    /** Pistas con más comentarios con marca de tiempo (estilo YouTube Music). */
    suspend fun topCommented(limit: Int = 10): List<CommentedTrack> =
        rows(rpc("dezzy_top_commented", JSONObject().put("p_limit", limit))).map { o ->
            CommentedTrack(
                videoId = o.optString("video_id"),
                commentCount = o.optLong("comment_count"),
                sampleContent = o.str("sample_content"),
                sampleAuthor = o.str("sample_author"),
                sampleAvatar = o.str("sample_avatar"),
            )
        }

    suspend fun createPost(
        entityType: String,
        entityId: String,
        entityTitle: String,
        entitySubtitle: String? = null,
        artworkUrl: String? = null,
        caption: String? = null,
    ): String? = rpc(
        "dezzy_create_post",
        JSONObject()
            .put("p_entity_type", entityType)
            .put("p_entity_id", entityId)
            .put("p_entity_title", entityTitle)
            .put("p_entity_subtitle", entitySubtitle ?: JSONObject.NULL)
            .put("p_artwork_url", artworkUrl ?: JSONObject.NULL)
            .put("p_caption", caption ?: JSONObject.NULL),
    )?.trim()?.trim('"')?.takeIf { it.isNotBlank() && it != "null" }

    /** Devuelve el nuevo estado del like (true = ahora me gusta). */
    suspend fun toggleLike(postId: String): Boolean? =
        rpc("dezzy_toggle_like", JSONObject().put("p_post_id", postId))
            ?.trim()?.toBooleanStrictOrNull()

    suspend fun addComment(postId: String, content: String): String? =
        rpc("dezzy_add_comment", JSONObject().put("p_post_id", postId).put("p_content", content))
            ?.trim()?.trim('"')

    suspend fun getComments(postId: String): List<PostComment> =
        rows(rpc("dezzy_get_comments", JSONObject().put("p_post_id", postId))).map { o ->
            PostComment(
                commentId = o.optString("comment_id"),
                userId = o.optString("user_id"),
                authorName = (o.textOrNull("author_name") ?: "Usuario"),
                authorAvatar = o.str("author_avatar"),
                content = o.optString("content"),
                createdAt = o.str("created_at"),
            )
        }

    suspend fun deletePost(postId: String) {
        rpc("dezzy_delete_post", JSONObject().put("p_post_id", postId))
    }

    // ── Comentarios con marca de tiempo ─────────────────────────────────────

    suspend fun addTrackComment(videoId: String, positionMs: Int, content: String): String? =
        rpc(
            "dezzy_add_track_comment",
            JSONObject()
                .put("p_video_id", videoId)
                .put("p_position_ms", positionMs)
                .put("p_content", content),
        )?.trim()?.trim('"')

    suspend fun getTrackComments(videoId: String): List<TrackComment> =
        rows(rpc("dezzy_get_track_comments", JSONObject().put("p_video_id", videoId))).map { o ->
            TrackComment(
                commentId = o.optString("comment_id"),
                userId = o.optString("user_id"),
                authorName = (o.textOrNull("author_name") ?: "Usuario"),
                authorAvatar = o.str("author_avatar"),
                positionMs = o.optInt("position_ms"),
                content = o.optString("content"),
            )
        }

    // ── Perfil musical ──────────────────────────────────────────────────────

    /** Registra una escucha para el top del perfil. Silenciosa si no hay sesión. */
    suspend fun trackPlay(
        entityType: String,
        entityId: String,
        entityName: String,
        artworkUrl: String? = null,
    ) {
        rpc(
            "dezzy_track_play",
            JSONObject()
                .put("p_entity_type", entityType)
                .put("p_entity_id", entityId)
                .put("p_entity_name", entityName)
                .put("p_artwork_url", artworkUrl ?: JSONObject.NULL),
        )
    }

    suspend fun getTop(userId: String, entityType: String = "artist", limit: Int = 10): List<TopEntity> =
        rows(
            rpc(
                "dezzy_get_top",
                JSONObject()
                    .put("p_user_id", userId)
                    .put("p_entity_type", entityType)
                    .put("p_limit", limit),
            )
        ).map { o ->
            TopEntity(
                entityId = o.optString("entity_id"),
                entityName = o.optString("entity_name"),
                artworkUrl = o.str("artwork_url"),
                playCount = o.optInt("play_count"),
            )
        }

    suspend fun getProfile(userId: String): MusicProfile? =
        rows(rpc("dezzy_get_profile", JSONObject().put("p_user_id", userId)))
            .firstOrNull()
            ?.let { o ->
                MusicProfile(
                    userId = o.optString("user_id"),
                    name = (o.textOrNull("name") ?: "Usuario"),
                    username = o.str("username"),
                    avatarUrl = o.str("avatar_url"),
                    bio = o.str("bio"),
                    followers = o.optLong("followers"),
                    following = o.optLong("following"),
                    iFollow = o.optBoolean("i_follow"),
                    isFriend = o.optBoolean("is_friend"),
                    nowPlaying = o.str("now_playing"),
                    nowArtist = o.str("now_artist"),
                    nowArtwork = o.str("now_artwork"),
                )
            }

    // ── Sincronización entre dispositivos ───────────────────────────────────

    suspend fun savePlaybackState(
        videoId: String,
        title: String? = null,
        artist: String? = null,
        artworkUrl: String? = null,
        positionMs: Long = 0,
        deviceName: String? = null,
    ) {
        rpc(
            "dezzy_save_playback_state",
            JSONObject()
                .put("p_video_id", videoId)
                .put("p_title", title ?: JSONObject.NULL)
                .put("p_artist", artist ?: JSONObject.NULL)
                .put("p_artwork_url", artworkUrl ?: JSONObject.NULL)
                .put("p_position_ms", positionMs)
                .put("p_device_name", deviceName ?: JSONObject.NULL),
        )
    }

    suspend fun getPlaybackState(): RemotePlaybackState? =
        rows(rpc("dezzy_get_playback_state")).firstOrNull()?.let { o ->
            RemotePlaybackState(
                videoId = o.str("video_id"),
                title = o.str("title"),
                artist = o.str("artist"),
                artworkUrl = o.str("artwork_url"),
                positionMs = o.optLong("position_ms"),
                deviceName = o.str("device_name"),
                updatedAt = o.str("updated_at"),
            )
        }
}
