/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.arturo254.opentune.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.arturo254.opentune.db.MusicDatabase
import com.arturo254.opentune.models.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.time.LocalDateTime
import java.util.UUID

/**
 * Importa archivos de música locales (MP3/M4A/MP4…, p. ej. canciones generadas
 * con IA) a la biblioteca: copia el archivo al almacenamiento interno, extrae
 * metadata y carátula, y registra la canción en Room como local y "me gusta".
 *
 * El id lleva el prefijo LOCAL_ y el archivo vive en filesDir/local_songs con
 * el id como nombre de archivo, así la resolución de reproducción no necesita
 * columnas nuevas en la base de datos (cero migraciones, cero discrepancias).
 */
object LocalMusicImporter {
    const val ID_PREFIX = "LOCAL_"

    private val SUPPORTED_EXTENSIONS = listOf("mp3", "m4a", "mp4", "ogg", "opus", "wav", "flac")

    fun isLocalId(mediaId: String?): Boolean = mediaId?.startsWith(ID_PREFIX) == true

    fun directory(context: Context): File =
        File(context.filesDir, "local_songs").apply { if (!exists()) mkdirs() }

    /** Archivo de audio de una canción local importada, o null si no existe. */
    fun audioFileFor(context: Context, mediaId: String): File? =
        SUPPORTED_EXTENSIONS.asSequence()
            .map { File(directory(context), "$mediaId.$it") }
            .firstOrNull { it.exists() }

    fun artworkFileFor(context: Context, mediaId: String): File =
        File(directory(context), "$mediaId.jpg")

    /**
     * Reemplaza la carátula de una canción local importada con la imagen [source]
     * elegida por el usuario. El archivo va versionado con timestamp para que
     * Coil no sirva la carátula vieja desde su caché, y se actualiza el
     * thumbnailUrl en la base de datos. Devuelve la nueva URL.
     */
    suspend fun updateArtwork(
        context: Context,
        database: MusicDatabase,
        mediaId: String,
        source: Uri,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(isLocalId(mediaId)) { "Solo se puede editar la carátula de canciones importadas" }

            val newFile = File(directory(context), "${mediaId}_art_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(source)?.use { input ->
                newFile.outputStream().use { output -> input.copyTo(output) }
            } ?: error("No se pudo leer la imagen seleccionada")

            // Limpieza de carátulas anteriores de esta canción
            directory(context).listFiles()
                ?.filter {
                    it != newFile &&
                        (it.name == "$mediaId.jpg" || it.name.startsWith("${mediaId}_art_"))
                }
                ?.forEach { runCatching { it.delete() } }

            val url = Uri.fromFile(newFile).toString()
            database.song(mediaId).first()?.let { song ->
                database.update(song.song.copy(thumbnailUrl = url))
            } ?: error("La canción no existe en la base de datos")
            url
        }.onFailure { Timber.e(it, "No se pudo actualizar la carátula de $mediaId") }
    }

    /** true si el archivo importado trae pista de video (MP4 con video). */
    fun hasVideoTrack(file: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
        } catch (e: Exception) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Importa [uri] a la biblioteca como canción local con "me gusta".
     * Devuelve la metadata insertada, o el error. Si algo falla a mitad de
     * camino, se limpian los archivos copiados para no dejar huérfanos.
     */
    suspend fun import(
        context: Context,
        database: MusicDatabase,
        uri: Uri,
    ): Result<MediaMetadata> = withContext(Dispatchers.IO) {
        var audioFile: File? = null
        var artworkFile: File? = null
        try {
            val displayName = queryDisplayName(context, uri)
            val extension = displayName?.substringAfterLast('.', "")?.lowercase()
                ?.takeIf { it in SUPPORTED_EXTENSIONS }
                ?: extensionFromMime(context, uri)
                ?: return@withContext Result.failure(
                    Exception("Formato no soportado (usa MP3, M4A o MP4): ${displayName ?: uri}"),
                )

            val mediaId = ID_PREFIX + UUID.randomUUID().toString().replace("-", "")
            val target = File(directory(context), "$mediaId.$extension")

            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext Result.failure(Exception("No se pudo leer el archivo"))
            audioFile = target

            val retriever = MediaMetadataRetriever()
            var title: String? = null
            var artist: String? = null
            var album: String? = null
            var durationSec = -1
            var artworkBytes: ByteArray? = null
            try {
                retriever.setDataSource(target.absolutePath)
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                durationSec = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.let { (it / 1000).toInt() } ?: -1
                artworkBytes = retriever.embeddedPicture
            } catch (e: Exception) {
                Timber.w(e, "No se pudo extraer metadata de $displayName")
            } finally {
                runCatching { retriever.release() }
            }

            val thumbnailUrl = artworkBytes?.let { bytes ->
                runCatching {
                    artworkFileFor(context, mediaId).also { it.writeBytes(bytes) }
                }.getOrNull()?.also { artworkFile = it }?.let { Uri.fromFile(it).toString() }
            }

            val fallbackTitle = displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            val metadata = MediaMetadata(
                id = mediaId,
                title = title?.takeIf { it.isNotBlank() } ?: fallbackTitle ?: "Canción importada",
                artists = listOf(
                    MediaMetadata.Artist(
                        id = null,
                        name = artist?.takeIf { it.isNotBlank() } ?: "Música IA",
                    ),
                ),
                duration = durationSec,
                thumbnailUrl = thumbnailUrl,
                album = null,
                liked = true,
                likedDate = LocalDateTime.now(),
                inLibrary = LocalDateTime.now(),
            )

            // Inserción atómica (@Transaction): canción + artista + mapeos.
            database.insert(metadata) { song ->
                song.copy(
                    isLocal = true,
                    albumName = album?.takeIf { it.isNotBlank() },
                    dateDownload = LocalDateTime.now(),
                )
            }
            Result.success(metadata)
        } catch (e: Exception) {
            runCatching { audioFile?.delete() }
            runCatching { artworkFile?.delete() }
            Timber.e(e, "Local import failed")
            Result.failure(e)
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()

    private fun extensionFromMime(context: Context, uri: Uri): String? =
        when (context.contentResolver.getType(uri)?.lowercase()) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/mp4", "audio/x-m4a", "audio/m4a" -> "m4a"
            "video/mp4" -> "mp4"
            "audio/ogg", "application/ogg" -> "ogg"
            "audio/opus" -> "opus"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/flac", "audio/x-flac" -> "flac"
            else -> null
        }
}
