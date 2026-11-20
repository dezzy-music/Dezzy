/*
 * Dezzy Project Original (2026)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ai

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.dezzy.music.constants.DjGeminiKeyKey
import com.dezzy.music.constants.DjStyleKey
import com.dezzy.music.constants.DjTtsApiKeyKey
import com.dezzy.music.constants.DjTtsProviderKey
import com.dezzy.music.constants.DjVoiceKey
import com.dezzy.music.constants.DjVoiceNameKey
import com.dezzy.music.security.SessionCrypto
import com.dezzy.music.utils.dataStore
import com.dezzy.music.utils.getAsync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Voz del DJ con la clave de cada usuario (Ajustes › DJ). Dezzy no lleva ninguna clave de pago
 * dentro: sin clave, el DJ habla con la voz gratuita.
 *
 * La clave se guarda cifrada con [SessionCrypto] (clave del dispositivo en AndroidKeyStore) y sólo
 * se envía al proveedor elegido.
 */
object DjVoice {
    const val FREE = "free"
    const val ELEVENLABS = "elevenlabs"
    const val OPENAI = "openai"

    /** Sarah: cálida y multilingüe, la que usaba el DJ hasta ahora. */
    const val ELEVENLABS_DEFAULT = "EXAVITQu4vr4xnSDxMaL"
    const val OPENAI_DEFAULT = "nova"

    data class Voice(val id: String, val name: String, val description: String, val previewUrl: String? = null)

    /** Resultado de comprobar una clave. `remaining`: caracteres que le quedan este mes, si se sabe. */
    data class KeyCheck(
        val ok: Boolean,
        val message: String,
        val voices: List<Voice> = emptyList(),
        val remaining: Int? = null,
    )

    val OPENAI_VOICES = listOf(
        Voice("nova", "Nova", "Cálida y clara"),
        Voice("coral", "Coral", "Alegre"),
        Voice("shimmer", "Shimmer", "Suave"),
        Voice("sage", "Sage", "Serena"),
        Voice("alloy", "Alloy", "Neutra"),
        Voice("ash", "Ash", "Cercana"),
        Voice("echo", "Echo", "Grave"),
        Voice("onyx", "Onyx", "Locutor grave"),
        Voice("fable", "Fable", "Narradora"),
    )

    /** Dónde se saca la clave de cada proveedor. */
    fun keyUrl(provider: String): String = when (provider) {
        OPENAI -> "https://platform.openai.com/api-keys"
        else -> "https://elevenlabs.io/app/settings/api-keys"
    }

    fun providerName(provider: String): String = when (provider) {
        ELEVENLABS -> "ElevenLabs"
        OPENAI -> "OpenAI"
        else -> "Voz gratuita"
    }

    fun defaultVoice(provider: String): String = when (provider) {
        OPENAI -> OPENAI_DEFAULT
        else -> ELEVENLABS_DEFAULT
    }

    /** La voz guardada, si vale para el proveedor; si no, la predeterminada de ese proveedor. */
    fun voiceFor(provider: String, saved: String?): String {
        val v = saved?.trim().orEmpty()
        return when (provider) {
            OPENAI -> v.takeIf { id -> OPENAI_VOICES.any { it.id == id } } ?: OPENAI_DEFAULT
            ELEVENLABS -> v.takeIf { Regex("^[A-Za-z0-9]{16,40}$").matches(it) && OPENAI_VOICES.none { o -> o.id == it } }
                ?: ELEVENLABS_DEFAULT
            else -> v
        }
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /* ── Guardado ── */

    suspend fun provider(context: Context): String =
        context.dataStore.getAsync(DjTtsProviderKey, FREE)

    suspend fun apiKey(context: Context): String {
        val stored = context.dataStore.getAsync(DjTtsApiKeyKey, "")
        if (stored.isBlank()) return ""
        return SessionCrypto.decryptOrNull(stored).orEmpty()
    }

    /** Guarda proveedor, clave y voz de una vez (lo que deja el asistente). */
    suspend fun save(context: Context, provider: String, key: String, voice: String, voiceName: String) {
        val enc = if (provider == FREE || key.isBlank()) "" else SessionCrypto.encrypt(key.trim())
        context.dataStore.edit {
            it[DjTtsProviderKey] = provider
            it[DjTtsApiKeyKey] = enc
            it[DjVoiceKey] = voice
            it[DjVoiceNameKey] = voiceName
        }
    }

    /** Borra la clave y vuelve a la voz gratuita. */
    suspend fun forget(context: Context) {
        context.dataStore.edit {
            it[DjTtsProviderKey] = FREE
            it[DjTtsApiKeyKey] = ""
            it[DjVoiceNameKey] = ""
        }
    }

    /* ── Clave de Gemini (el guion del DJ) ── */

    const val GEMINI_KEY_URL = "https://aistudio.google.com/app/apikey"

    suspend fun geminiKey(context: Context): String {
        val stored = context.dataStore.getAsync(DjGeminiKeyKey, "")
        if (stored.isBlank()) return ""
        return SessionCrypto.decryptOrNull(stored).orEmpty()
    }

    suspend fun saveGeminiKey(context: Context, key: String) {
        val enc = if (key.isBlank()) "" else SessionCrypto.encrypt(key.trim())
        context.dataStore.edit { it[DjGeminiKeyKey] = enc }
    }

    suspend fun checkGemini(key: String): KeyCheck = withContext(Dispatchers.IO) {
        val k = key.trim()
        if (k.isEmpty()) return@withContext KeyCheck(false, "Pega tu clave")
        runCatching {
            http.newCall(
                Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1")
                    .header("x-goog-api-key", k)
                    .build(),
            ).execute().use { r ->
                when {
                    r.isSuccessful -> KeyCheck(true, "Clave válida")
                    r.code == 400 || r.code == 401 || r.code == 403 -> KeyCheck(false, "Esa clave no es válida")
                    r.code == 429 -> KeyCheck(false, "Has llegado al límite gratuito de hoy")
                    else -> KeyCheck(false, "Google no responde (${r.code})")
                }
            }
        }.getOrElse { KeyCheck(false, "Sin conexión. Inténtalo de nuevo") }
    }

    /* ── Comprobar claves ── */

    suspend fun check(provider: String, key: String): KeyCheck = withContext(Dispatchers.IO) {
        val k = key.trim()
        if (k.isEmpty()) return@withContext KeyCheck(false, "Pega tu clave")
        runCatching {
            when (provider) {
                ELEVENLABS -> checkElevenLabs(k)
                OPENAI -> checkOpenAi(k)
                else -> KeyCheck(true, "No hace falta clave")
            }
        }.getOrElse { KeyCheck(false, "Sin conexión. Inténtalo de nuevo") }
    }

    private fun checkElevenLabs(key: String): KeyCheck {
        val voices = http.newCall(
            Request.Builder().url("https://api.elevenlabs.io/v1/voices").header("xi-api-key", key).build(),
        ).execute().use { r ->
            if (r.code == 401 || r.code == 403) return KeyCheck(false, "Esa clave no es válida")
            if (!r.isSuccessful) return KeyCheck(false, "ElevenLabs no responde (${r.code})")
            val arr = JSONObject(r.body?.string().orEmpty()).optJSONArray("voices")
            buildList {
                for (i in 0 until (arr?.length() ?: 0)) {
                    val o = arr!!.getJSONObject(i)
                    val labels = o.optJSONObject("labels")
                    val desc = listOfNotNull(
                        labels?.optString("description")?.takeIf { it.isNotBlank() },
                        labels?.optString("accent")?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ").ifBlank { labels?.optString("gender").orEmpty() }
                    add(
                        Voice(
                            o.optString("voice_id"),
                            o.optString("name").ifBlank { "Voz" },
                            desc.replaceFirstChar { it.uppercase() },
                            o.optString("preview_url").takeIf { it.startsWith("http") },
                        ),
                    )
                }
            }.filter { it.id.isNotBlank() }
        }
        // Las claves con permisos recortados pueden no leer el plan: no pasa nada.
        val remaining = runCatching {
            http.newCall(
                Request.Builder().url("https://api.elevenlabs.io/v1/user/subscription").header("xi-api-key", key).build(),
            ).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val o = JSONObject(r.body?.string().orEmpty())
                (o.optInt("character_limit") - o.optInt("character_count")).coerceAtLeast(0)
            }
        }.getOrNull()
        val msg = if (remaining != null) "Clave válida · ${"%,d".format(remaining).replace(',', '.')} caracteres restantes" else "Clave válida"
        return KeyCheck(true, msg, voices, remaining)
    }

    private fun checkOpenAi(key: String): KeyCheck =
        http.newCall(
            Request.Builder().url("https://api.openai.com/v1/models").header("Authorization", "Bearer $key").build(),
        ).execute().use { r ->
            when {
                r.code == 401 -> KeyCheck(false, "Esa clave no es válida")
                r.code == 429 -> KeyCheck(false, "Tu cuenta de OpenAI no tiene saldo")
                !r.isSuccessful -> KeyCheck(false, "OpenAI no responde (${r.code})")
                else -> KeyCheck(true, "Clave válida", OPENAI_VOICES)
            }
        }

    /* ── Hablar ── */

    /**
     * Graba `text` en `out` con el proveedor y la voz guardados. Falla (y el DJ usa la voz
     * gratuita) si no hay clave o el proveedor no responde.
     */
    suspend fun synthesize(context: Context, text: String, out: File): Result<File> {
        val provider = provider(context)
        if (provider == FREE) return Result.failure(IllegalStateException("Voz gratuita"))
        val key = apiKey(context)
        if (key.isBlank()) return Result.failure(IllegalStateException("Sin clave"))
        val voice = voiceFor(provider, context.dataStore.getAsync(DjVoiceKey, ""))
        return synthesizeWith(provider, key, voice, text, out, context.dataStore.getAsync(DjStyleKey, "radio"))
    }

    /** Igual que [synthesize] pero con lo que se está eligiendo en el asistente (para «Probar»). */
    suspend fun synthesizeWith(
        provider: String,
        key: String,
        voice: String,
        text: String,
        out: File,
        style: String = "radio",
    ): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = when (provider) {
                    ELEVENLABS -> Request.Builder()
                        .url("https://api.elevenlabs.io/v1/text-to-speech/$voice")
                        .header("xi-api-key", key.trim())
                        .post(
                            JSONObject().put("text", text).put("model_id", "eleven_multilingual_v2").toString()
                                .toRequestBody(JSON),
                        )
                    OPENAI -> Request.Builder()
                        .url("https://api.openai.com/v1/audio/speech")
                        .header("Authorization", "Bearer ${key.trim()}")
                        .post(
                            JSONObject()
                                .put("model", "gpt-4o-mini-tts")
                                .put("input", text)
                                .put("voice", voice)
                                .put("response_format", "mp3")
                                .put("instructions", styleInstructions(style))
                                .toString()
                                .toRequestBody(JSON),
                        )
                    else -> error("Voz gratuita")
                }.build()
                http.newCall(request).execute().use { r ->
                    if (!r.isSuccessful) error("${providerName(provider)} respondió ${r.code}")
                    val body = r.body ?: error("Respuesta vacía")
                    out.outputStream().use { body.byteStream().copyTo(it) }
                }
                out
            }
        }

    private fun styleInstructions(style: String) = when (style) {
        "party" -> "Habla como un DJ de club: mucha energía, ritmo rápido y alegría."
        "chill" -> "Habla tranquilo, cálido y pausado, como radio de madrugada."
        else -> "Habla como un locutor de radio cercano y con ritmo."
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()
}
