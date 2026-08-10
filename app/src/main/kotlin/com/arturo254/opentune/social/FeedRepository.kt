/*
 * OpenTune Project (2026)
 *
 * Instagram-style feed + stories over the app's Supabase backend (social_posts / social_stories /
 * post_likes), authenticated with the signed-in user's JWT. RLS + SECURITY DEFINER RPCs keep it safe.
 */

package com.arturo254.opentune.social

import com.arturo254.opentune.utils.SupabaseConfig
import com.arturo254.opentune.utils.reportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

data class FeedPost(
    val postId: String,
    val userId: String,
    val authorName: String,
    val authorUsername: String?,
    val authorAvatar: String?,
    val content: String?,
    val imageUrl: String?,
    val likesCount: Int,
    val likedByMe: Boolean,
    val createdAtMs: Long,
)

data class Story(
    val storyId: String,
    val userId: String,
    val authorName: String,
    val authorAvatar: String?,
    val mediaUrl: String,
    val createdAtMs: Long,
)

object FeedRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
    private val jsonMedia = "application/json".toMediaType()

    private suspend fun rpc(name: String, params: JSONObject): String? = withContext(Dispatchers.IO) {
        val headers = SupabaseAuth.authHeaders() ?: return@withContext null
        try {
            val builder = Request.Builder()
                .url("${SupabaseConfig.REST}/rpc/$name")
                .post(params.toString().toRequestBody(jsonMedia))
            headers.forEach { (k, v) -> builder.addHeader(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    reportException(Exception("rpc $name failed ${resp.code}: ${resp.body?.string()}"))
                    return@withContext null
                }
                resp.body?.string()
            }
        } catch (e: Exception) {
            reportException(e)
            null
        }
    }

    private fun parseTime(iso: String?): Long =
        runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(System.currentTimeMillis())

    suspend fun getFeed(beforeMs: Long? = null, limit: Int = 30): List<FeedPost> {
        val params = JSONObject().put("p_limit", limit)
        beforeMs?.let { params.put("p_before", Instant.ofEpochMilli(it).toString()) }
        val body = rpc("get_feed", params) ?: return emptyList()
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    FeedPost(
                        postId = o.optString("post_id"),
                        userId = o.optString("user_id"),
                        authorName = (o.textOrNull("author_name") ?: "Usuario"),
                        authorUsername = o.textOrNull("author_username"),
                        authorAvatar = o.textOrNull("author_avatar"),
                        content = o.textOrNull("content"),
                        imageUrl = o.textOrNull("image_url"),
                        likesCount = o.optInt("likes_count", 0),
                        likedByMe = o.optBoolean("liked_by_me", false),
                        createdAtMs = parseTime(o.optString("created_at")),
                    )
                )
            }
        }
    }

    /** Returns the new post id on success. */
    suspend fun createPost(content: String, imageUrl: String? = null): String? {
        val params = JSONObject().put("p_content", content.trim())
        imageUrl?.let { params.put("p_image_url", it) }
        val body = rpc("create_post", params) ?: return null
        return body.trim().trim('"').takeIf { it.isNotBlank() && it != "null" }
    }

    /** Returns (liked, newCount) or null on failure. */
    suspend fun toggleLike(postId: String): Pair<Boolean, Int>? {
        val body = rpc("toggle_like", JSONObject().put("p_post_id", postId)) ?: return null
        val o = runCatching { JSONArray(body).optJSONObject(0) }.getOrNull() ?: return null
        return o.optBoolean("liked", false) to o.optInt("likes_count", 0)
    }

    suspend fun getStories(): List<Story> {
        val body = rpc("get_stories", JSONObject()) ?: return emptyList()
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    Story(
                        storyId = o.optString("story_id"),
                        userId = o.optString("user_id"),
                        authorName = (o.textOrNull("author_name") ?: "Usuario"),
                        authorAvatar = o.textOrNull("author_avatar"),
                        mediaUrl = o.optString("media_url"),
                        createdAtMs = parseTime(o.optString("created_at")),
                    )
                )
            }
        }
    }

    suspend fun createStory(mediaUrl: String): SocialResult {
        val body = rpc("create_story", JSONObject().put("p_media_url", mediaUrl))
        return if (body != null) SocialResult.Success else SocialResult.Error("No se pudo publicar la historia")
    }

    /**
     * Uploads a post/story image to Supabase Storage (reusing the existing public `avatars` bucket
     * under the user's own folder, so the current RLS policy allows it) and returns its public URL.
     */
    suspend fun uploadImage(bytes: ByteArray, kind: String = "post"): String? = withContext(Dispatchers.IO) {
        val headers = SupabaseAuth.authHeaders() ?: return@withContext null
        val id = SupabaseAuth.currentUserId.value ?: return@withContext null
        try {
            val path = "$id/${kind}_${System.currentTimeMillis()}.jpg"
            val builder = Request.Builder()
                .url("${SupabaseConfig.URL}/storage/v1/object/avatars/$path")
                .addHeader("Content-Type", "image/jpeg")
                .addHeader("x-upsert", "true")
                .post(bytes.toRequestBody("image/jpeg".toMediaType()))
            headers.forEach { (k, v) -> if (k != "Content-Type") builder.addHeader(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    reportException(Exception("image upload failed: ${resp.code} ${resp.body?.string()}"))
                    return@withContext null
                }
            }
            "${SupabaseConfig.URL}/storage/v1/object/public/avatars/$path"
        } catch (e: Exception) {
            reportException(e)
            null
        }
    }
}
