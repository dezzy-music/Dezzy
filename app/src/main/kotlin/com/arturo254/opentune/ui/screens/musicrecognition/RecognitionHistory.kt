/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.arturo254.opentune.ui.screens.musicrecognition

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.arturo254.opentune.shazamkit.models.RecognitionResult
import com.arturo254.opentune.utils.dataStore
import com.arturo254.opentune.utils.get
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Una canción reconocida en el pasado — lo mínimo para volver a mostrarla y
 * reproducirla sin guardar el resultado entero de Shazam.
 */
@Serializable
data class RecognitionEntry(
    val title: String,
    val artist: String,
    val coverUrl: String? = null,
    val shazamUrl: String? = null,
    val recognizedAt: Long = System.currentTimeMillis(),
)

/**
 * Historial de reconocimientos, persistido en el DataStore de ajustes como una
 * lista JSON. Se guarda aquí (y no en la BD de música) porque son coincidencias
 * de Shazam, no canciones de la biblioteca: no queremos ensuciar la librería con
 * pistas que el usuario quizá nunca reproduzca.
 *
 * Se conservan las últimas [MAX] para que la lista no crezca sin límite.
 */
object RecognitionHistory {

    private const val MAX = 30
    private val KEY = stringPreferencesKey("music_recognition_history")
    private val json = Json { ignoreUnknownKeys = true }

    /** Flujo reactivo del historial, más reciente primero. */
    fun flow(context: Context): Flow<List<RecognitionEntry>> =
        context.dataStore.data.map { prefs -> decode(prefs[KEY]) }

    /** Lectura puntual (fuera de composición). */
    fun current(context: Context): List<RecognitionEntry> =
        decode(context.dataStore.get(KEY))

    /**
     * Añade una coincidencia al principio. Si ya estaba la misma pista (mismo
     * título+artista), se sube arriba en vez de duplicarse.
     */
    suspend fun add(context: Context, result: RecognitionResult) {
        val entry = RecognitionEntry(
            title = result.title,
            artist = result.artist,
            coverUrl = result.coverArtHqUrl ?: result.coverArtUrl,
            shazamUrl = result.shazamUrl,
        )
        context.dataStore.edit { prefs ->
            val existing = decode(prefs[KEY])
            val deduped = existing.filterNot {
                it.title.equals(entry.title, true) && it.artist.equals(entry.artist, true)
            }
            prefs[KEY] = json.encodeToString(listOf(entry) + deduped.take(MAX - 1))
        }
    }

    suspend fun clear(context: Context) {
        context.dataStore.edit { it.remove(KEY) }
    }

    private fun decode(raw: String?): List<RecognitionEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<RecognitionEntry>>(raw) }.getOrDefault(emptyList())
    }
}
