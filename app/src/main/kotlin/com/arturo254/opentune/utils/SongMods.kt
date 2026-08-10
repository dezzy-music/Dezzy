/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * MODS: personalización por canción (título y carátula), NO destructiva. La
 * primera vez que se modifica algo se guarda el valor ORIGINAL en la propia
 * SongEntity, de modo que se puede revertir. Funciona con CUALQUIER canción
 * (de YouTube o local), a diferencia de LocalMusicImporter.updateArtwork.
 */

package com.arturo254.opentune.utils

import android.content.Context
import android.net.Uri
import com.arturo254.opentune.db.MusicDatabase
import com.arturo254.opentune.db.entities.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

object SongMods {

    private fun modsDir(context: Context): File =
        File(context.filesDir, "song_mods").apply { mkdirs() }

    /** Aplica un título personalizado (recordando el original la primera vez). */
    suspend fun setTitle(database: MusicDatabase, song: Song, newTitle: String) =
        withContext(Dispatchers.IO) {
            val trimmed = newTitle.trim()
            if (trimmed.isBlank() || trimmed == song.song.title) return@withContext
            val original = song.song.modOriginalTitle ?: song.song.title
            database.update(
                song.song.copy(
                    title = trimmed,
                    modOriginalTitle = original,
                ),
            )
        }

    /** Copia la imagen elegida a almacenamiento interno y la fija como carátula. */
    suspend fun setCover(context: Context, database: MusicDatabase, song: Song, source: Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File(modsDir(context), "${sanitize(song.id)}_${System.currentTimeMillis()}.jpg")
                context.contentResolver.openInputStream(source)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                } ?: error("No se pudo leer la imagen")

                // Borra carátulas de mod anteriores de esta canción
                modsDir(context).listFiles()
                    ?.filter { it != file && it.name.startsWith("${sanitize(song.id)}_") }
                    ?.forEach { runCatching { it.delete() } }

                val originalThumb = song.song.modOriginalThumbnailUrl ?: song.song.thumbnailUrl ?: ""
                database.update(
                    song.song.copy(
                        thumbnailUrl = Uri.fromFile(file).toString(),
                        modOriginalThumbnailUrl = originalThumb,
                    ),
                )
                Unit
            }
        }

    /** Revierte todo el mod: restaura título y carátula originales y limpia el estado. */
    suspend fun reset(context: Context, database: MusicDatabase, song: Song) =
        withContext(Dispatchers.IO) {
            val fresh = database.song(song.id).first() ?: song
            val restoredTitle = fresh.song.modOriginalTitle ?: fresh.song.title
            val restoredThumb = fresh.song.modOriginalThumbnailUrl?.takeIf { it.isNotBlank() }
                ?: fresh.song.thumbnailUrl
            database.update(
                fresh.song.copy(
                    title = restoredTitle,
                    thumbnailUrl = restoredThumb,
                    modOriginalTitle = null,
                    modOriginalThumbnailUrl = null,
                ),
            )
            // Limpia los archivos de carátula del mod
            modsDir(context).listFiles()
                ?.filter { it.name.startsWith("${sanitize(song.id)}_") }
                ?.forEach { runCatching { it.delete() } }
        }

    private fun sanitize(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
}
