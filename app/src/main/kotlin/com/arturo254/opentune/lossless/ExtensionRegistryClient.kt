/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.arturo254.opentune.lossless

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.arturo254.opentune.utils.dataStore
import com.arturo254.opentune.utils.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Descarga y parsea el registry real de extensiones de SpotiFLAC Mobile. Esto es
 * lo que puebla el botón de "Extensiones" con URLs reales sin que el usuario
 * configure nada: al abrir la pantalla se baja este índice y se muestran los
 * proveedores. La lógica de descarga de cada proveedor está reimplementada en
 * Kotlin (no ejecutamos los paquetes .sflx).
 */
class ExtensionRegistryClient(
    private val context: Context,
    private val client: OkHttpClient = defaultClient(),
) {
    /**
     * Devuelve las extensiones del registry. Intenta la red y cae a la caché
     * local si falla; si tampoco hay caché, usa el catálogo empotrado.
     */
    suspend fun load(forceRefresh: Boolean = false): List<LosslessExtension> = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            cached()?.let { return@withContext it }
        }
        runCatching {
            val request = Request.Builder().url(REGISTRY_URL).build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful || body.isNullOrBlank()) {
                    error("registry HTTP ${response.code}")
                }
                val parsed = parse(body)
                context.dataStore.edit { it[LosslessRegistryCacheKey] = body }
                parsed
            }
        }.getOrElse { e ->
            Timber.w(e, "[Lossless] registry fetch failed, usando caché/empotrado")
            cached() ?: BUILT_IN
        }
    }

    private fun cached(): List<LosslessExtension>? =
        runCatching {
            context.dataStore.get(LosslessRegistryCacheKey, "")
                .takeIf { it.isNotBlank() }
                ?.let(::parse)
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()

    private fun parse(raw: String): List<LosslessExtension> {
        val root = JSONObject(raw)
        val arr = root.optJSONArray("extensions") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val tags = o.optJSONArray("tags")?.let { t ->
                    List(t.length()) { t.optString(it) }
                }.orEmpty()
                add(
                    LosslessExtension(
                        id = o.optString("id"),
                        displayName = o.optString("display_name", o.optString("name")),
                        version = o.optString("version"),
                        description = o.optString("description"),
                        category = o.optString("category"),
                        tags = tags,
                        iconUrl = o.optString("icon_url").takeIf { it.isNotBlank() },
                        downloadUrl = o.optString("download_url"),
                        minAppVersion = o.optString("min_app_version").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }

    companion object {
        /** Registry oficial de SpotiFLAC Mobile (fuente indicada por el usuario). */
        const val REGISTRY_URL =
            "https://raw.githubusercontent.com/spotiflacapp/spotiflac-extension/main/registry.json"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        /**
         * Catálogo mínimo empotrado por si el registry no está accesible en el
         * primer arranque. Solo proveedores de descarga que soportamos nativos.
         */
        val BUILT_IN: List<LosslessExtension> = LosslessProvider.entries.map { p ->
            LosslessExtension(
                id = p.id,
                displayName = p.displayName,
                version = "built-in",
                description = "Proveedor de descarga ${p.displayName}.",
                category = "download",
                tags = listOf("download", "lossless"),
                iconUrl = null,
                downloadUrl = "",
                minAppVersion = null,
            )
        }
    }
}
