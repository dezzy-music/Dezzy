/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.social

import androidx.datastore.preferences.core.edit
import com.dezzy.music.utils.SupabaseConfig
import com.dezzy.music.utils.dataStore
import com.dezzy.music.utils.reportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Lee un campo de texto tratando el nulo de JSON como ausente.
 *
 * `optString` devuelve la CADENA "null" cuando el valor es `null` en el JSON, así
 * que `.ifBlank { null }` no lo filtraba y la interfaz acababa mostrando "@null"
 * o "null" en el perfil.
 */
internal fun JSONObject.textOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

data class SocialUser(
    val id: String,
    val name: String,
    val username: String?,
    val avatarUrl: String?,
    val level: Int,
    val rank: String?,
    val relationship: String, // none | request_sent | request_received | friends
    val phoneHash: String? = null, // set only for contact matches
)

data class FriendRequest(
    val requestId: String,
    val senderId: String,
    val name: String,
    val avatarUrl: String?,
)

data class Friend(
    val id: String,
    val name: String,
    val username: String?,
    val avatarUrl: String?,
    val level: Int,
    val rank: String?,
)

/** The signed-in user's editable profile (WhatsApp-style). */
data class MyProfile(
    val name: String? = null,
    val username: String? = null,
    val bio: String? = null,
    val avatarUrl: String? = null,
    val hasPhone: Boolean = false,
    val links: String? = null,
)

/** Result wrapper that surfaces a server message (e.g. "Username already taken") to the UI. */
sealed interface SocialResult {
    data object Success : SocialResult
    data class Error(val message: String) : SocialResult
}

/**
 * Native social-network data layer. Talks directly to the app's Supabase backend using the
 * signed-in user's JWT (via [SupabaseAuth]) so the RLS-protected RPCs work securely.
 */
object SocialRepository {
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
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw RpcException(extractError(text, resp.code))
                }
                text
            }
        } catch (e: RpcException) {
            throw e
        } catch (e: Exception) {
            reportException(e)
            null
        }
    }

    private fun extractError(body: String, code: Int): String =
        runCatching { JSONObject(body).optString("message") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "Error $code"

    private class RpcException(message: String) : Exception(message)

    suspend fun searchUsers(query: String): List<SocialUser> {
        val body = runCatching { rpc("search_users", JSONObject().put("p_query", query)) }.getOrNull()
            ?: return emptyList()
        return parseUsers(body)
    }

    suspend fun findFriendsByContacts(hashes: List<String>): List<SocialUser> {
        if (hashes.isEmpty()) return emptyList()
        val arr = JSONArray().apply { hashes.forEach { put(it) } }
        val body = runCatching { rpc("find_friends_by_contacts", JSONObject().put("p_hashes", arr)) }
            .getOrNull() ?: return emptyList()
        return parseUsers(body)
    }

    suspend fun getFriends(): List<Friend> {
        val body = runCatching { rpc("get_friends", JSONObject()) }.getOrNull() ?: return emptyList()
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Friend>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += Friend(
                id = o.optString("friend_id"),
                name = (o.textOrNull("name") ?: "Usuario"),
                username = o.textOrNull("username"),
                avatarUrl = o.textOrNull("avatar_url"),
                level = o.optInt("level", 1),
                rank = o.textOrNull("rank"),
            )
        }
        return out
    }

    suspend fun getFriendRequests(): List<FriendRequest> {
        val body = runCatching { rpc("get_friend_requests", JSONObject()) }.getOrNull() ?: return emptyList()
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val out = ArrayList<FriendRequest>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += FriendRequest(
                requestId = o.optString("request_id"),
                senderId = o.optString("sender_id"),
                name = (o.textOrNull("name") ?: "Usuario"),
                avatarUrl = o.textOrNull("avatar_url"),
            )
        }
        return out
    }

    suspend fun sendFriendRequest(userId: String): SocialResult =
        call("send_friend_request", JSONObject().put("p_receiver_id", userId))

    suspend fun respondFriendRequest(requestId: String, accept: Boolean): SocialResult =
        call("respond_friend_request", JSONObject().put("p_request_id", requestId).put("p_accept", accept))

    suspend fun setUsername(username: String): SocialResult {
        // set_username hace UPDATE sobre profiles: si la fila aún no existe (el
        // alta del perfil pudo fallar en el registro), el UPDATE toca 0 filas y
        // el username "se guarda" sin guardarse. Garantizar la fila primero.
        SupabaseAuth.ensureProfile(null)
        return call("set_username", JSONObject().put("p_username", username))
    }

    suspend fun setPhoneHash(hash: String): SocialResult =
        call("set_phone_hash", JSONObject().put("p_hash", hash))

    private suspend fun call(rpcName: String, params: JSONObject): SocialResult =
        try {
            if (SupabaseAuth.authHeaders() == null) {
                SocialResult.Error("Inicia sesión")
            } else if (rpc(rpcName, params) == null) {
                // rpc() devuelve null en fallos de red (no lanza): antes eso se
                // reportaba como Success y la UI daba por guardado algo que
                // nunca llegó al servidor.
                SocialResult.Error("Sin conexión con el servidor")
            } else {
                SocialResult.Success
            }
        } catch (e: Exception) {
            SocialResult.Error(e.message ?: "Error")
        }

    private fun parseUsers(body: String): List<SocialUser> {
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val out = ArrayList<SocialUser>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += SocialUser(
                id = o.optString("id"),
                name = (o.textOrNull("name") ?: "Usuario"),
                username = o.textOrNull("username"),
                avatarUrl = o.textOrNull("avatar_url"),
                level = o.optInt("level", 1),
                rank = o.textOrNull("rank"),
                relationship = o.optString("relationship").ifBlank { "none" },
                phoneHash = o.textOrNull("phone_hash"),
            )
        }
        return out
    }

    suspend fun acceptReferral(inviterId: String): SocialResult =
        call("accept_referral", JSONObject().put("p_inviter_id", inviterId))

    // --- Profile (WhatsApp-style) ---------------------------------------------------------------

    suspend fun getMyProfile(): MyProfile? = withContext(Dispatchers.IO) {
        val headers = SupabaseAuth.authHeaders() ?: return@withContext null
        val id = SupabaseAuth.currentUserId.value ?: return@withContext null
        try {
            val builder = Request.Builder()
                .url("${SupabaseConfig.REST}/profiles?id=eq.$id&select=name,username,bio,avatar_url,phone_hash,links")
                .get()
            headers.forEach { (k, v) -> builder.addHeader(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val arr = JSONArray(resp.body?.string().orEmpty())
                val o = arr.optJSONObject(0) ?: return@withContext MyProfile()
                MyProfile(
                    name = o.textOrNull("name"),
                    username = o.textOrNull("username"),
                    bio = o.textOrNull("bio"),
                    avatarUrl = o.textOrNull("avatar_url"),
                    hasPhone = !o.optString("phone_hash").isNullOrBlank(),
                    links = o.textOrNull("links"),
                )
            }
        } catch (e: Exception) {
            reportException(e)
            null
        }
    }

    /** Upserts editable profile fields (any null is left unchanged). */
    suspend fun updateProfile(
        name: String? = null,
        bio: String? = null,
        links: String? = null,
        avatarUrl: String? = null,
    ): SocialResult = withContext(Dispatchers.IO) {
        val headers = SupabaseAuth.authHeaders() ?: return@withContext SocialResult.Error("Inicia sesión")
        val id = SupabaseAuth.currentUserId.value ?: return@withContext SocialResult.Error("Inicia sesión")
        try {
            val record = JSONObject().put("id", id)
            name?.let { record.put("name", it) }
            bio?.let { record.put("bio", it) }
            links?.let { record.put("links", it) }
            avatarUrl?.let { record.put("avatar_url", it) }
            val builder = Request.Builder()
                .url("${SupabaseConfig.REST}/profiles")
                .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(record.toString().toRequestBody(jsonMedia))
            headers.forEach { (k, v) -> builder.addHeader(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                if (resp.isSuccessful) SocialResult.Success
                else SocialResult.Error("No se pudo guardar (${resp.code})")
            }
        } catch (e: Exception) {
            SocialResult.Error(e.message ?: "Error")
        }
    }

    /** Uploads avatar bytes to Supabase Storage and returns its public URL (and saves it). */
    suspend fun uploadAvatar(bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        val headers = SupabaseAuth.authHeaders() ?: return@withContext null
        val id = SupabaseAuth.currentUserId.value ?: return@withContext null
        try {
            val path = "$id/avatar_${System.currentTimeMillis()}.jpg"
            val builder = Request.Builder()
                .url("${SupabaseConfig.URL}/storage/v1/object/avatars/$path")
                .addHeader("Content-Type", "image/jpeg")
                .addHeader("x-upsert", "true")
                .post(bytes.toRequestBody("image/jpeg".toMediaType()))
            // Storage needs the auth/apikey headers, but not the json Content-Type.
            headers.forEach { (k, v) -> if (k != "Content-Type") builder.addHeader(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    reportException(Exception("avatar upload failed: ${resp.code} ${resp.body?.string()}"))
                    return@withContext null
                }
            }
            val publicUrl = "${SupabaseConfig.URL}/storage/v1/object/public/avatars/$path"
            updateProfile(avatarUrl = publicUrl)
            publicUrl
        } catch (e: Exception) {
            reportException(e)
            null
        }
    }

    /** If an invite/referral is pending and the user is signed in, link them to the inviter once. */
    suspend fun consumePendingReferral() {
        if (SupabaseAuth.authHeaders() == null) return
        val prefs = runCatching {
            com.dezzy.music.App.instance.dataStore.data.first()
        }.getOrNull() ?: return
        val inviterId = prefs[com.dezzy.music.constants.PendingReferralKey]?.takeIf { it.isNotBlank() } ?: return
        if (inviterId == SupabaseAuth.currentUserId.value) {
            clearPendingReferral(); return
        }
        if (acceptReferral(inviterId) is SocialResult.Success) {
            clearPendingReferral()
        }
    }

    private suspend fun clearPendingReferral() {
        runCatching {
            com.dezzy.music.App.instance.dataStore.edit {
                it.remove(com.dezzy.music.constants.PendingReferralKey)
            }
        }
    }
}
