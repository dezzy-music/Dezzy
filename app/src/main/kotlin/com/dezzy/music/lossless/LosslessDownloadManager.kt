/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.lossless

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.dezzy.music.db.MusicDatabase
import com.dezzy.music.models.MediaMetadata
import com.dezzy.music.utils.LocalMusicImporter
import com.dezzy.music.utils.dataStore
import com.dezzy.music.utils.get
import com.dezzy.music.utils.getAsync
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orquesta el flujo estilo SpotiFLAC: buscar → resolver (resolver propio) →
 * descargar el archivo lossless → registrarlo como canción LOCAL con "me gusta".
 *
 * Bifurcación local / caché: el archivo se escribe en el mismo directorio que la
 * música importada ([LocalMusicImporter.directory]) con el prefijo LOCAL_, así
 * [com.dezzy.music.playback.LocalFileRoutingDataSource] lo reproduce
 * directo del disco y **nunca toca la caché ni la red**. Las canciones de
 * YouTube siguen en caché como siempre. Como el archivo vive en el dispositivo,
 * aparece en los "me gusta" normales igual que cualquier otra canción.
 */
@Singleton
class LosslessDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
) {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val searchClient = DeezerSearchClient()
    private val resolverClient = ResolverClient()
    val registry = ExtensionRegistryClient(context)

    /** Descargas en curso/terminadas, por trackKey. La UI observa este flujo. */
    val downloads = MutableStateFlow<Map<String, LosslessDownloadState>>(emptyMap())

    suspend fun search(query: String): List<LosslessTrack> = searchClient.search(query)

    fun trackKey(track: LosslessTrack): String = "${track.provider.id}:${track.providerTrackId}"

    /**
     * Encola la descarga en WorkManager: sobrevive al cierre de la app, reintenta
     * con backoff y respeta el ajuste solo-Wi-Fi. El trabajo es único por pista,
     * así que tocar dos veces no duplica la descarga. [download] es el cuerpo que
     * ejecuta el worker.
     */
    suspend fun enqueue(track: LosslessTrack, quality: LosslessQuality) {
        val wifiOnly = context.dataStore.getAsync(LosslessWifiOnlyKey, false)
        setState(
            LosslessDownloadState(
                trackKey(track), track.title, track.provider,
                LosslessDownloadState.Phase.QUEUED, thumbnailUrl = track.thumbnailUrl,
            ),
        )

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<LosslessDownloadWorker>()
            .setInputData(LosslessDownloadWorker.dataFor(track, quality))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(LosslessDownloadWorker.WORK_TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            LosslessDownloadWorker.uniqueName(track),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Resuelve SOLO la URL de stream (FLAC del resolver) para REPRODUCIR sin
     * descargar. Devuelve la URL directa o null. Rápido: no baja el archivo.
     */
    suspend fun resolveStreamUrl(track: LosslessTrack, quality: LosslessQuality): String? =
        withContext(Dispatchers.IO) {
            val resolverUrl = context.dataStore.get(LosslessResolverUrlKey, "")
                .ifBlank { DefaultLosslessResolverUrl }
            val resolvable = ensureIsrc(track)
            val resolved = runCatching { resolverClient.resolve(resolverUrl, resolvable, quality) }.getOrNull()
            resolved?.takeIf { it.success }?.downloadUrl
        }

    /**
     * Descarga [track] en la [quality] indicada y lo registra como me gusta local.
     * Devuelve el mediaId LOCAL_ insertado, o el error.
     */
    suspend fun download(track: LosslessTrack, quality: LosslessQuality): Result<String> =
        withContext(Dispatchers.IO) {
            val key = trackKey(track)
            setState(
                LosslessDownloadState(
                    key, track.title, track.provider,
                    LosslessDownloadState.Phase.RESOLVING, thumbnailUrl = track.thumbnailUrl,
                ),
            )

            // Prellenado con el resolver oficial: funciona sin que el usuario configure nada.
            val resolverUrl = context.dataStore.get(LosslessResolverUrlKey, "")
                .ifBlank { DefaultLosslessResolverUrl }

            // Re-emparejar por ISRC si vamos a descargar por un proveedor distinto al de origen.
            val resolvable = ensureIsrc(track)

            val resolved = resolverClient.resolve(resolverUrl, resolvable, quality)
            if (!resolved.success || resolved.downloadUrl == null) {
                return@withContext fail(key, track, resolved.message ?: "El resolver no devolvió el archivo")
            }

            setState(state(key)?.copy(phase = LosslessDownloadState.Phase.DOWNLOADING) ?: return@withContext fail(key, track, "estado perdido"))

            val mediaId = LocalMusicImporter.ID_PREFIX + UUID.randomUUID().toString().replace("-", "")
            val target = File(LocalMusicImporter.directory(context), "$mediaId.${resolved.container}")

            val bytesResult = runCatching {
                streamToFile(resolved.downloadUrl, target, resolved.sizeBytes) { p ->
                    setState(state(key)?.copy(progress = p))
                }
            }
            if (bytesResult.isFailure) {
                runCatching { target.delete() }
                return@withContext fail(key, track, bytesResult.exceptionOrNull()?.message ?: "Descarga interrumpida")
            }

            setState(state(key)?.copy(phase = LosslessDownloadState.Phase.TAGGING, progress = 1f))

            // Carátula al lado del audio (misma convención que el importador local).
            val artworkFile = track.thumbnailUrl?.let { downloadArtworkFile(it, mediaId) }
            val thumbnailUrl = artworkFile?.let { Uri.fromFile(it).toString() }

            // Metadatos incrustados en el propio archivo (jaudiotagger), best-effort.
            LosslessTagger.tag(target, track, artworkFile)

            val metadata = MediaMetadata(
                id = mediaId,
                title = track.title,
                artists = track.artistNames.ifEmpty { listOf("Desconocido") }
                    .map { MediaMetadata.Artist(id = null, name = it) },
                duration = track.durationSec,
                thumbnailUrl = thumbnailUrl,
                album = null,
                explicit = track.explicit,
                liked = true,
                likedDate = LocalDateTime.now(),
                inLibrary = LocalDateTime.now(),
            )

            runCatching {
                database.insert(metadata) { song ->
                    song.copy(
                        isLocal = true,
                        albumName = track.albumName,
                        dateDownload = LocalDateTime.now(),
                        // Etiqueta de formato para la biblioteca (FLAC / MP3).
                        localFormat = resolved.container.uppercase(),
                    )
                }
            }.onFailure {
                runCatching { target.delete() }
                return@withContext fail(key, track, "No se pudo guardar en la biblioteca: ${it.message}")
            }

            // Copia opcional a la carpeta pública Música/Dezzy (aditivo, no bloquea).
            if (context.dataStore.get(LosslessExportToPublicKey, false)) {
                runCatching { LosslessPublicExporter.export(context, target, track, artworkFile) }
                    .onFailure { Timber.w(it, "[Lossless] export a carpeta pública falló") }
            }

            setState(state(key)?.copy(phase = LosslessDownloadState.Phase.DONE, progress = 1f))
            Result.success(mediaId)
        }

    private suspend fun ensureIsrc(track: LosslessTrack): LosslessTrack {
        if (track.isrc != null || track.provider == LosslessProvider.DEEZER) return track
        val isrc = runCatching { searchClient.isrcFor(track.providerTrackId) }.getOrNull()
        return if (isrc != null) track.copy(isrc = isrc) else track
    }

    private fun streamToFile(
        url: String,
        target: File,
        knownSize: Long?,
        onProgress: (Float) -> Unit,
    ) {
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} al descargar")
            val body = response.body ?: error("respuesta vacía")
            val total = knownSize ?: body.contentLength().takeIf { it > 0 } ?: -1L
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
    }

    private fun downloadArtworkFile(url: String, mediaId: String): File? = runCatching {
        val file = LocalMusicImporter.artworkFileFor(context, mediaId)
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.byteStream()?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
        }
        file
    }.getOrElse {
        Timber.w(it, "[Lossless] carátula falló para $mediaId")
        null
    }

    private fun state(key: String): LosslessDownloadState? = downloads.value[key]

    private fun setState(s: LosslessDownloadState?) {
        if (s == null) return
        downloads.update { it + (s.trackKey to s) }
    }

    private fun fail(key: String, track: LosslessTrack, message: String): Result<String> {
        setState(
            LosslessDownloadState(
                trackKey = key,
                title = track.title,
                provider = track.provider,
                phase = LosslessDownloadState.Phase.FAILED,
                error = message,
                thumbnailUrl = track.thumbnailUrl,
            ),
        )
        return Result.failure(IllegalStateException(message))
    }

    /** Quita de la lista las descargas terminadas (completadas o fallidas). */
    fun clearFinished() {
        downloads.update { map -> map.filterValues { !it.phase.isFinished } }
    }

    /** Vacía toda la lista de descargas. */
    fun clearAll() {
        downloads.value = emptyMap()
    }
}
