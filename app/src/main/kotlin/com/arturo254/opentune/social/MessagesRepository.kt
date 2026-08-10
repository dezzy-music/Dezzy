/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
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
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.TimeUnit

data class ChatMessage(
    val id: String,
    val senderId: String,
    val content: String,
    val createdAtMs: Long,
)

/** Una fila de la bandeja de entrada: el último mensaje con cada persona. */
data class Conversation(
    val friendId: String,
    val name: String,
    val username: String?,
    val avatarUrl: String?,
    val lastMessage: String,
    val lastAtMs: Long,
    val lastFromMe: Boolean,
)

/**
 * Native 1:1 direct messaging over the app's Supabase `messages` table, authenticated with the
 * signed-in user's JWT (RLS guarantees you only see/send your own conversations).
 */
object MessagesRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
    private val jsonMedia = "application/json".toMediaType()

    suspend fun getMessages(friendId: String, sinceMs: Long? = null): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            val headers = SupabaseAuth.authHeaders() ?: return@withContext emptyList()
            val me = SupabaseAuth.currentUserId.value ?: return@withContext emptyList()
            try {
                val filter = "or=(and(sender_id.eq.$me,receiver_id.eq.$friendId)," +
                    "and(sender_id.eq.$friendId,receiver_id.eq.$me))"
                val since = sinceMs?.let {
                    "&created_at=gt." + URLEncoder.encode(Instant.ofEpochMilli(it).toString(), "UTF-8")
                }.orEmpty()
                val url = "${SupabaseConfig.REST}/messages?$filter$since" +
                    "&select=id,sender_id,content,created_at&order=created_at.asc&limit=500"
                val builder = Request.Builder().url(url).get()
                headers.forEach { (k, v) -> builder.addHeader(k, v) }
                client.newCall(builder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    parse(resp.body?.string().orEmpty())
                }
            } catch (e: Exception) {
                reportException(e)
                emptyList()
            }
        }

    /**
     * Bandeja de entrada: el último mensaje con cada persona. Una sola consulta
     * (todos mis mensajes recientes) que se agrupa por interlocutor en cliente, y
     * se enriquece con nombre/avatar desde la lista de amigos. Devuelve vacío ante
     * cualquier fallo para no romper la pantalla.
     */
    suspend fun getConversations(): List<Conversation> = withContext(Dispatchers.IO) {
        val headers = SupabaseAuth.authHeaders() ?: return@withContext emptyList()
        val me = SupabaseAuth.currentUserId.value ?: return@withContext emptyList()
        try {
            val url = "${SupabaseConfig.REST}/messages?" +
                "or=(sender_id.eq.$me,receiver_id.eq.$me)" +
                "&select=id,sender_id,receiver_id,content,created_at" +
                "&order=created_at.desc&limit=400"
            val builder = Request.Builder().url(url).get()
            headers.forEach { (k, v) -> builder.addHeader(k, v) }
            val rows = client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                runCatching { JSONArray(resp.body?.string().orEmpty()) }.getOrNull()
                    ?: return@withContext emptyList()
            }

            // El primer mensaje visto por interlocutor es el más reciente (orden desc).
            val lastByFriend = LinkedHashMap<String, Conversation>()
            for (i in 0 until rows.length()) {
                val o = rows.optJSONObject(i) ?: continue
                val sender = o.optString("sender_id")
                val receiver = o.optString("receiver_id")
                val other = if (sender == me) receiver else sender
                if (other.isBlank() || lastByFriend.containsKey(other)) continue
                val createdAt = runCatching {
                    Instant.parse(o.optString("created_at")).toEpochMilli()
                }.getOrDefault(System.currentTimeMillis())
                lastByFriend[other] = Conversation(
                    friendId = other,
                    name = "Usuario",
                    username = null,
                    avatarUrl = null,
                    lastMessage = o.optString("content"),
                    lastAtMs = createdAt,
                    lastFromMe = sender == me,
                )
            }
            if (lastByFriend.isEmpty()) return@withContext emptyList()

            // Enriquecer con nombre/avatar de la lista de amigos (una sola llamada).
            val friends = runCatching { SocialRepository.getFriends() }.getOrDefault(emptyList())
                .associateBy { it.id }
            lastByFriend.values
                .map { conv ->
                    val f = friends[conv.friendId]
                    if (f != null) conv.copy(name = f.name, username = f.username, avatarUrl = f.avatarUrl)
                    else conv
                }
                .sortedByDescending { it.lastAtMs }
        } catch (e: Exception) {
            reportException(e)
            emptyList()
        }
    }

    suspend fun sendMessage(friendId: String, content: String): SocialResult =
        withContext(Dispatchers.IO) {
            val text = content.trim()
            if (text.isEmpty()) return@withContext SocialResult.Error("Mensaje vacío")
            val headers = SupabaseAuth.authHeaders() ?: return@withContext SocialResult.Error("Inicia sesión")
            val me = SupabaseAuth.currentUserId.value ?: return@withContext SocialResult.Error("Inicia sesión")
            try {
                val body = JSONObject()
                    .put("sender_id", me)
                    .put("receiver_id", friendId)
                    .put("content", text)
                    .toString().toRequestBody(jsonMedia)
                val builder = Request.Builder()
                    .url("${SupabaseConfig.REST}/messages")
                    .addHeader("Prefer", "return=minimal")
                    .post(body)
                headers.forEach { (k, v) -> builder.addHeader(k, v) }
                client.newCall(builder.build()).execute().use { resp ->
                    if (resp.isSuccessful) SocialResult.Success
                    else SocialResult.Error("No se pudo enviar (${resp.code})")
                }
            } catch (e: Exception) {
                reportException(e)
                SocialResult.Error(e.message ?: "Error")
            }
        }

    private fun parse(body: String): List<ChatMessage> {
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val out = ArrayList<ChatMessage>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val createdAt = runCatching {
                Instant.parse(o.optString("created_at")).toEpochMilli()
            }.getOrDefault(System.currentTimeMillis())
            out += ChatMessage(
                id = o.optString("id"),
                senderId = o.optString("sender_id"),
                content = o.optString("content"),
                createdAtMs = createdAt,
            )
        }
        return out
    }
}
