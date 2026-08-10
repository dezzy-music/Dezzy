/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.arturo254.opentune.lossless

/**
 * Modelos del subsistema "lossless" (estilo SpotiFLAC): extensiones/proveedores,
 * resultados de búsqueda y descriptores de descarga.
 *
 * Bifurcación de almacenamiento (ver [LosslessDownloadManager]): lo que se baja
 * por un proveedor lossless se guarda como archivo LOCAL en el dispositivo y se
 * reproduce por [com.arturo254.opentune.playback.LocalFileRoutingDataSource] sin
 * tocar la caché ni la red; las canciones de YouTube siguen en caché como hasta
 * ahora.
 */

/**
 * Proveedor de descarga soportado. El [id] coincide con el id de la extensión en
 * el registry de SpotiFLAC; [resolverPath] es la ruta que espera un *resolver
 * propio* (el usuario elige "resolver propio", ver [ResolverClient]).
 */
enum class LosslessProvider(
    val id: String,
    val displayName: String,
    val resolverPath: String,
    val qualities: List<LosslessQuality>,
) {
    DEEZER(
        id = "deezer",
        displayName = "Deezer",
        resolverPath = "dzr",
        qualities = listOf(
            LosslessQuality("flac", "FLAC", "flac"),
            LosslessQuality("mp3_320", "MP3 320", "mp3"),
        ),
    ),
    QOBUZ(
        id = "qobuz-web",
        displayName = "Qobuz",
        resolverPath = "qbz",
        qualities = listOf(
            LosslessQuality("27", "Hi-Res 24-bit", "flac"),
            LosslessQuality("7", "Hi-Res 24/96", "flac"),
            LosslessQuality("6", "FLAC 16/44.1", "flac"),
            LosslessQuality("5", "MP3 320", "mp3"),
        ),
    ),
    TIDAL(
        id = "tidal-web",
        displayName = "Tidal",
        resolverPath = "tid2",
        qualities = listOf(
            LosslessQuality("HI_RES_LOSSLESS", "HiRes FLAC", "flac"),
            LosslessQuality("LOSSLESS", "Lossless", "flac"),
            LosslessQuality("HIGH", "High", "m4a"),
        ),
    ),
    AMAZON(
        id = "amazon",
        displayName = "Amazon Music",
        resolverPath = "amzn",
        qualities = listOf(
            LosslessQuality("flac", "FLAC", "flac"),
        ),
    ),
    SOUNDCLOUD(
        id = "soundcloud",
        displayName = "SoundCloud",
        resolverPath = "scd",
        qualities = listOf(
            LosslessQuality("opus", "Opus", "opus"),
            LosslessQuality("mp3", "MP3", "mp3"),
        ),
    ),
    ;

    companion object {
        fun fromId(id: String?): LosslessProvider? = entries.firstOrNull { it.id == id }

        /** Proveedor por defecto para la búsqueda pública (Deezer expone ISRC gratis). */
        val DEFAULT = DEEZER
    }
}

/** Opción de calidad/formato de un proveedor. [container] es la extensión de archivo esperada. */
data class LosslessQuality(
    val id: String,
    val label: String,
    val container: String,
)

/**
 * Metadatos de una extensión tal cual vienen del registry.json de SpotiFLAC
 * (`spotiflacapp/spotiflac-extension`). Solo se usa para poblar la pantalla de
 * extensiones con datos reales; la lógica de cada proveedor vive nativa en Kotlin.
 */
data class LosslessExtension(
    val id: String,
    val displayName: String,
    val version: String,
    val description: String,
    val category: String,
    val tags: List<String>,
    val iconUrl: String?,
    val downloadUrl: String,
    val minAppVersion: String?,
) {
    /** Proveedor nativo asociado, si esta extensión es de descarga y lo soportamos. */
    val provider: LosslessProvider? get() = LosslessProvider.fromId(id)
}

/**
 * Resultado de búsqueda listo para descargar. [providerTrackId] es el id en el
 * proveedor de origen (p. ej. id de Deezer); [isrc] permite re-emparejar la
 * misma grabación en otros proveedores vía el resolver.
 */
data class LosslessTrack(
    val providerTrackId: String,
    val provider: LosslessProvider,
    val title: String,
    val artistNames: List<String>,
    val albumName: String?,
    val durationSec: Int,
    val isrc: String?,
    val thumbnailUrl: String?,
    val explicit: Boolean = false,
) {
    val artistLabel: String get() = artistNames.joinToString(", ").ifBlank { "Desconocido" }
}

/**
 * Respuesta del resolver a una petición de descarga. Modela el mismo descriptor
 * que usan las extensiones de SpotiFLAC (`success` + `download_url` /
 * `direct_download_url`), para ser compatible con resolvers ya existentes.
 */
data class ResolvedDownload(
    val success: Boolean,
    val downloadUrl: String?,
    val container: String,
    val message: String? = null,
    val sizeBytes: Long? = null,
) {
    companion object {
        fun failure(message: String) =
            ResolvedDownload(success = false, downloadUrl = null, container = "flac", message = message)
    }
}

/** Estado observable de una descarga en curso, por [trackKey]. */
data class LosslessDownloadState(
    val trackKey: String,
    val title: String,
    val provider: LosslessProvider,
    val phase: Phase,
    val progress: Float = 0f,
    val error: String? = null,
    val thumbnailUrl: String? = null,
) {
    enum class Phase {
        QUEUED, RESOLVING, DOWNLOADING, TAGGING, DONE, FAILED;

        val isActive: Boolean get() = this == QUEUED || this == RESOLVING || this == DOWNLOADING || this == TAGGING
        val isFinished: Boolean get() = this == DONE || this == FAILED
    }
}
