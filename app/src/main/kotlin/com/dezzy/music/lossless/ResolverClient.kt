/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.lossless

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Cliente del *resolver propio*: Dezzy no se cuelga del backend gated de
 * SpotiFLAC (api.zarz.moe) ni replica su atestación firmada. En su lugar llama a
 * una URL que el usuario controla/self-hostea, la cual devuelve una URL de
 * descarga directa del archivo lossless.
 *
 * Contrato (compatible con el descriptor de las extensiones de SpotiFLAC):
 *
 *   POST {resolverBase}/dl/{provider}          (provider = dzr | tid2 | qbz | amzn | scd)
 *   body: { "id", "isrc", "quality", "title", "artist" }
 *   200:  { "success": true, "download_url": "https://…", "format": "flac", "size": 123 }
 *         (también se acepta "direct_download_url")
 *   fallo: { "success": false, "message": "…" }
 */
class ResolverClient(
    private val client: OkHttpClient = defaultClient(),
) {
    suspend fun resolve(
        resolverBaseUrl: String,
        track: LosslessTrack,
        quality: LosslessQuality,
    ): ResolvedDownload = withContext(Dispatchers.IO) {
        val base = resolverBaseUrl.trim().trimEnd('/')
        if (base.isEmpty()) {
            return@withContext ResolvedDownload.failure("No hay resolver configurado")
        }
        val url = "$base/dl/${track.provider.resolverPath}"
        // Calidad CANÓNICA para el resolver: cada proveedor tiene sus propios ids
        // (Qobuz "27", Tidal "HI_RES_LOSSLESS"…) que el resolver no mapea y acababa
        // dando MP3. Normalizamos por el contenedor: lossless → "flac", resto →
        // "mp3_320". Así Hi-Res/lossless de cualquier proveedor pide FLAC de verdad
        // (y si algún día se configura Qobuz, su cadena arranca en Hi-Res 24-bit).
        val canonicalQuality = if (quality.container.equals("flac", ignoreCase = true)) "flac" else "mp3_320"
        val payload = JSONObject().apply {
            put("id", track.providerTrackId)
            put("isrc", track.isrc ?: JSONObject.NULL)
            put("quality", canonicalQuality)
            put("title", track.title)
            put("artist", track.artistLabel)
        }
        runCatching {
            val request = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    return@use ResolvedDownload.failure("Resolver sin respuesta (HTTP ${response.code})")
                }
                parse(body, quality, response.code)
            }
        }.getOrElse { e ->
            Timber.w(e, "[Lossless] resolver falló")
            ResolvedDownload.failure(e.message ?: "Error de red con el resolver")
        }
    }

    /**
     * Parseo DEFENSIVO: los resolvers reales devuelven de todo — HTML de
     * Cloudflare/404, arrays JSON, claves distintas ("url", "link"…) o el
     * download_url sin campo "success". Nunca dejamos escapar una JSONException
     * cruda a la UI (el clásico "A JSONObject text must begin with '{'").
     */
    private fun parse(raw: String, quality: LosslessQuality, httpCode: Int): ResolvedDownload {
        val trimmed = raw.trim().removePrefix("﻿").trim()

        // Respuesta que no es JSON (página HTML, texto de error, redirect)
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return ResolvedDownload.failure(
                when {
                    trimmed.startsWith("<") ->
                        "El resolver devolvió una página web (HTTP $httpCode). " +
                            "Revisa la URL del resolver en Ajustes → Descargas lossless."
                    httpCode !in 200..299 -> "Resolver HTTP $httpCode"
                    else -> "Respuesta no válida del resolver: ${trimmed.take(60)}"
                },
            )
        }

        val o = runCatching {
            if (trimmed.startsWith("[")) {
                org.json.JSONArray(trimmed).optJSONObject(0) ?: JSONObject()
            } else {
                JSONObject(trimmed)
            }
        }.getOrElse {
            return ResolvedDownload.failure("Respuesta JSON malformada del resolver (HTTP $httpCode)")
        }

        // URL de descarga: acepta todas las variantes del ecosistema SpotiFLAC
        val downloadUrl = sequenceOf(
            "download_url", "direct_download_url", "url", "link", "file", "stream_url",
        ).map { key -> o.optString(key).takeIf { it.isNotBlank() && it.startsWith("http") } }
            .firstOrNull { it != null }

        // "success" explícito en false → error del proveedor; si hay URL, es éxito
        // aunque el campo no exista (muchos resolvers no lo mandan).
        if (o.has("success") && !o.optBoolean("success", false)) {
            return ResolvedDownload.failure(
                o.optString("message").ifBlank { o.optString("error") }
                    .ifBlank { "El resolver no devolvió el archivo" },
            )
        }
        if (downloadUrl == null) {
            return ResolvedDownload.failure(
                o.optString("message").ifBlank { o.optString("error") }
                    .ifBlank { "El resolver no devolvió una URL de descarga" },
            )
        }

        val container = o.optString("format").takeIf { it.isNotBlank() } ?: quality.container
        val size = o.optLong("size", -1L).takeIf { it > 0 }
        return ResolvedDownload(
            success = true,
            downloadUrl = downloadUrl,
            container = container,
            sizeBytes = size,
        )
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
