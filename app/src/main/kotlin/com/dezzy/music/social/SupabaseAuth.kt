/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.social

import com.dezzy.music.App
import com.dezzy.music.constants.OmegaMathUserIdKey
import com.dezzy.music.constants.SupabaseAccessTokenKey
import com.dezzy.music.constants.SupabaseRefreshTokenKey
import com.dezzy.music.constants.SupabaseTokenExpiryKey
import com.dezzy.music.utils.SupabaseConfig
import com.dezzy.music.utils.dataStore
import com.dezzy.music.utils.reportException
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Real Supabase authentication for the app's social network. Persists the access + refresh tokens,
 * transparently refreshes the access token before it expires, and hands out authenticated headers
 * (Bearer JWT) so the RLS-protected social RPCs/tables work for the signed-in user.
 */
object SupabaseAuth {
    private val client = OkHttpClient()

    @Volatile private var accessToken: String? = null
    @Volatile private var refreshToken: String? = null
    @Volatile private var expiryEpochSec: Long = 0L
    @Volatile private var userId: String? = null

    /** Emits the signed-in user id (null when signed out) so UI can react to auth state. */
    val currentUserId = MutableStateFlow<String?>(null)

    val isLoggedIn: Boolean get() = !refreshToken.isNullOrBlank()

    suspend fun load() {
        try {
            val prefs = App.instance.dataStore.data.first()
            accessToken = prefs[SupabaseAccessTokenKey]
            refreshToken = prefs[SupabaseRefreshTokenKey]
            expiryEpochSec = prefs[SupabaseTokenExpiryKey] ?: 0L
            userId = prefs[OmegaMathUserIdKey]?.takeIf { it.isNotBlank() }
            currentUserId.value = if (isLoggedIn) userId else null
        } catch (e: Exception) {
            reportException(e)
        }
    }

    suspend fun saveSession(access: String, refresh: String?, expiresInSec: Long, uid: String?) {
        accessToken = access
        if (!refresh.isNullOrBlank()) refreshToken = refresh
        expiryEpochSec = nowSec() + expiresInSec.coerceAtLeast(0)
        if (!uid.isNullOrBlank()) userId = uid
        persist()
        currentUserId.value = userId
    }

    /** Returns a valid (refreshed if needed) access token, or null when signed out / refresh fails. */
    suspend fun validAccessToken(): String? {
        if (accessToken != null && nowSec() + 60 < expiryEpochSec) return accessToken
        return refresh()
    }

    /** Authenticated REST/RPC headers, or null when there is no valid session. */
    suspend fun authHeaders(): Map<String, String>? {
        val token = validAccessToken() ?: return null
        return mapOf(
            "apikey" to SupabaseConfig.ANON_KEY,
            "Authorization" to "Bearer $token",
            "Content-Type" to "application/json",
        )
    }

    suspend fun logout() {
        accessToken = null
        refreshToken = null
        expiryEpochSec = 0L
        userId = null
        try {
            App.instance.dataStore.edit {
                it.remove(SupabaseAccessTokenKey)
                it.remove(SupabaseRefreshTokenKey)
                it.remove(SupabaseTokenExpiryKey)
            }
        } catch (e: Exception) {
            reportException(e)
        }
        currentUserId.value = null
    }

    /**
     * Ensures a usable Supabase session exists, creating a persistent ANONYMOUS one if needed.
     * This is what lets the messaging ID be generated automatically from the device — the user
     * never sees a separate login. Requires "Anonymous sign-ins" enabled in the Supabase project.
     */
    suspend fun ensureSession(): Boolean {
        if (validAccessToken() != null) return true
        return signInAnonymously()
    }

    /** True when the current session is a real (Google) account, not the per-device anonymous one. */
    val isAnonymous: Boolean
        get() = decodeJwtClaimBool(accessToken, "is_anonymous") ?: false

    /**
     * Anchors the social identity to a real Google account. Takes the tokens captured from the
     * Supabase Google OAuth redirect, validates the user, persists the FULL session (incl. refresh
     * token so it survives restarts / device changes), and seeds the public profile so friends see
     * a real name/photo. This is what connects the user to the ecosystem across devices.
     */
    suspend fun signInWithTokens(
        access: String,
        refresh: String?,
        expiresIn: Long,
        seedName: String? = null,
        seedAvatar: String? = null,
    ): Boolean {
        return try {
            val request = Request.Builder()
                .url("${SupabaseConfig.URL}/auth/v1/user")
                .addHeader("apikey", SupabaseConfig.ANON_KEY)
                .addHeader("Authorization", "Bearer $access")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val uid = JSONObject(resp.body?.string().orEmpty())
                    .optString("id").takeIf { it.isNotBlank() } ?: return false
                saveSession(access, refresh, expiresIn, uid)
                ensureProfile(seedName, seedAvatar)
                true
            }
        } catch (e: Exception) {
            reportException(e)
            false
        }
    }

    private fun decodeJwtClaimBool(jwt: String?, claim: String): Boolean? {
        val payload = jwt?.split(".")?.getOrNull(1) ?: return null
        return try {
            val json = String(
                android.util.Base64.decode(payload, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)
            )
            if (JSONObject(json).has(claim)) JSONObject(json).optBoolean(claim) else null
        } catch (_: Exception) {
            null
        }
    }

    // ── Cuenta propia de Dezzy (correo + contraseña) ────────────────────────
    //
    // No depende de Google: la cuenta se crea y se usa dentro de la app. Google
    // sigue disponible como método alternativo, pero ya no es el camino
    // principal ni un requisito para entrar a la red social.

    /** Resultado de registrarse o iniciar sesión, con el motivo real del fallo. */
    sealed interface AuthOutcome {
        /** Sesión iniciada y lista para usar. */
        data object Success : AuthOutcome
        /** Cuenta creada, pero el proyecto exige confirmar el correo antes de entrar. */
        data object ConfirmEmail : AuthOutcome
        data class Error(val message: String) : AuthOutcome
    }

    /** Crea una cuenta con correo y contraseña. */
    suspend fun signUpWithEmail(
        email: String,
        password: String,
        displayName: String?,
    ): AuthOutcome = authRequest(
        url = "${SupabaseConfig.URL}/auth/v1/signup",
        payload = JSONObject()
            .put("email", email.trim())
            .put("password", password)
            .apply {
                if (!displayName.isNullOrBlank()) {
                    put("data", JSONObject().put("name", displayName.trim()))
                }
            },
        seedName = displayName,
    )

    /** Inicia sesión con una cuenta ya existente. */
    suspend fun signInWithEmail(email: String, password: String): AuthOutcome = authRequest(
        url = "${SupabaseConfig.URL}/auth/v1/token?grant_type=password",
        payload = JSONObject()
            .put("email", email.trim())
            .put("password", password),
        seedName = null,
    )

    /** Envía el correo de recuperación de contraseña. */
    suspend fun sendPasswordReset(email: String): AuthOutcome = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("email", email.trim()).toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${SupabaseConfig.URL}/auth/v1/recover")
                .addHeader("apikey", SupabaseConfig.ANON_KEY)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) AuthOutcome.Success
                else AuthOutcome.Error(parseAuthError(resp.body?.string(), resp.code))
            }
        } catch (e: Exception) {
            AuthOutcome.Error(e.message ?: "Error de red")
        }
    }

    // withContext(IO) obligatorio: la pantalla de acceso llama desde el scope de
    // Compose (hilo principal) y OkHttp bloqueante ahí lanza
    // NetworkOnMainThreadException, que se veía como un genérico "Error de red".
    private suspend fun authRequest(
        url: String,
        payload: JSONObject,
        seedName: String?,
    ): AuthOutcome = withContext(Dispatchers.IO) {
        try {
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                AuthOutcome.Error(parseAuthError(text, resp.code))
            } else {
                val json = JSONObject(text)
                val access = json.optString("access_token").takeIf { it.isNotBlank() }
                if (access == null) {
                    // Registro correcto pero sin sesión: el proyecto tiene activada
                    // la confirmación por correo.
                    AuthOutcome.ConfirmEmail
                } else {
                    val refreshTok = json.optString("refresh_token").takeIf { it.isNotBlank() }
                    val expiresIn = json.optLong("expires_in", 3600L)
                    val uid = json.optJSONObject("user")?.optString("id")
                        ?: json.optString("id").takeIf { it.isNotBlank() }
                    saveSession(access, refreshTok, expiresIn, uid)
                    ensureProfile(seedName)
                    AuthOutcome.Success
                }
            }
        }
        } catch (e: Exception) {
            reportException(e)
            AuthOutcome.Error(e.message ?: "Error de red")
        }
    }

    /** Traduce el error de GoTrue a algo que el usuario pueda entender. */
    private fun parseAuthError(body: String?, code: Int): String {
        val json = runCatching { JSONObject(body.orEmpty()) }.getOrNull()
        val raw = json?.optString("error_description")?.takeIf { it.isNotBlank() }
            ?: json?.optString("msg")?.takeIf { it.isNotBlank() }
            ?: json?.optString("message")?.takeIf { it.isNotBlank() }
            ?: json?.optString("error")?.takeIf { it.isNotBlank() }
        return when {
            raw == null -> "Error $code"
            raw.contains("Invalid login", true) ||
                raw.contains("invalid_grant", true) -> "Correo o contraseña incorrectos"
            raw.contains("already registered", true) ||
                raw.contains("already been registered", true) -> "Ese correo ya tiene una cuenta"
            raw.contains("Password should be", true) -> "La contraseña es demasiado corta"
            raw.contains("Unable to validate email", true) ||
                raw.contains("invalid format", true) -> "El correo no es válido"
            raw.contains("Email not confirmed", true) -> "Confirma tu correo antes de entrar"
            raw.contains("signups not allowed", true) ||
                raw.contains("Signups not allowed", true) -> "El registro está desactivado en el servidor"
            raw.contains("Database error saving new user", true) ||
                raw.contains("Database error creating anonymous user", true) ->
                "El servidor no pudo crear la cuenta (error interno de la base de datos). Inténtalo más tarde."
            else -> raw
        }
    }

    private suspend fun signInAnonymously(): Boolean {
        return try {
            val body = JSONObject().put("data", JSONObject()).toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${SupabaseConfig.URL}/auth/v1/signup")
                .addHeader("apikey", SupabaseConfig.ANON_KEY)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return false // e.g. anonymous sign-ins disabled
                val json = JSONObject(resp.body?.string().orEmpty())
                val access = json.optString("access_token").takeIf { it.isNotBlank() } ?: return false
                val refreshTok = json.optString("refresh_token").takeIf { it.isNotBlank() }
                val expiresIn = json.optLong("expires_in", 3600L)
                val uid = json.optJSONObject("user")?.optString("id")
                saveSession(access, refreshTok, expiresIn, uid)
                // Anonymous users have no auto-created profile row; create one now so that
                // set_username / set_phone_hash (which UPDATE the row) actually persist.
                ensureProfile(null)
                true
            }
        } catch (e: Exception) {
            reportException(e)
            false
        }
    }

    /** Upserts the signed-in user's public profile (name/avatar) so others see them by name. */
    suspend fun ensureProfile(name: String?, avatarUrl: String? = null) {
        val headers = authHeaders() ?: return
        val id = currentUserId.value ?: return
        try {
            val record = JSONObject().put("id", id)
            if (!name.isNullOrBlank()) record.put("name", name)
            if (!avatarUrl.isNullOrBlank()) record.put("avatar_url", avatarUrl)
            val request = Request.Builder()
                .url("${SupabaseConfig.REST}/profiles")
                .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(record.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // Si el upsert del perfil falla (RLS, columnas nuevas del otro
                    // producto…), todo lo que dependa de la fila (set_username,
                    // set_phone_hash) fallará en silencio. Dejar rastro.
                    reportException(
                        Exception("profiles upsert ${resp.code}: ${resp.body?.string()?.take(200)}")
                    )
                }
            }
        } catch (e: Exception) {
            reportException(e)
        }
    }

    private suspend fun refresh(): String? {
        val rt = refreshToken ?: return null
        return try {
            val body = JSONObject().put("refresh_token", rt).toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${SupabaseConfig.URL}/auth/v1/token?grant_type=refresh_token")
                .addHeader("apikey", SupabaseConfig.ANON_KEY)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // Refresh token invalid/expired → force re-login.
                    if (resp.code == 400 || resp.code == 401) logout()
                    return null
                }
                val json = JSONObject(resp.body?.string().orEmpty())
                val newAccess = json.optString("access_token").takeIf { it.isNotBlank() } ?: return null
                val newRefresh = json.optString("refresh_token").takeIf { it.isNotBlank() }
                val expiresIn = json.optLong("expires_in", 3600L)
                val uid = json.optJSONObject("user")?.optString("id")
                saveSession(newAccess, newRefresh, expiresIn, uid)
                newAccess
            }
        } catch (e: Exception) {
            reportException(e)
            null
        }
    }

    private suspend fun persist() {
        try {
            App.instance.dataStore.edit {
                accessToken?.let { v -> it[SupabaseAccessTokenKey] = v }
                refreshToken?.let { v -> it[SupabaseRefreshTokenKey] = v }
                it[SupabaseTokenExpiryKey] = expiryEpochSec
                userId?.let { v -> it[OmegaMathUserIdKey] = v }
            }
        } catch (e: Exception) {
            reportException(e)
        }
    }

    private fun nowSec() = System.currentTimeMillis() / 1000L
}
