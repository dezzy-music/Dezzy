/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.arturo254.opentune.lossless

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import timber.log.Timber
import java.io.File

/**
 * Copia un archivo lossless ya descargado (y etiquetado) del almacenamiento
 * interno a la carpeta pública Música/Dezzy, para que sea visible en gestores de
 * archivos y otras apps de música. Mantiene la misma convención de carpeta que
 * [com.arturo254.opentune.playback.DownloadExporter].
 *
 * En Android Q+ usa MediaStore (scoped storage); en versiones previas escribe
 * directo a la carpeta pública y lanza un media scan. Es aditivo: el archivo
 * interno sigue existiendo para la reproducción local.
 */
object LosslessPublicExporter {

    /** Subcarpeta dentro de Música/, igual que DownloadExporter. */
    private const val FOLDER = "Dezzy"

    private val ILLEGAL = Regex("[\\\\/:*?\"<>|]")

    fun export(context: Context, sourceFile: File, track: LosslessTrack, artworkFile: File?): Boolean {
        if (!sourceFile.exists()) return false
        val ext = sourceFile.extension.lowercase().ifBlank { "flac" }
        val baseName = "${safe(track.artistLabel)} - ${safe(track.title)}"
        val mime = mimeFor(ext)

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportViaMediaStore(context, sourceFile, artworkFile, baseName, ext, mime)
            } else {
                exportViaFile(context, sourceFile, artworkFile, baseName, ext)
            }
        }.getOrElse {
            Timber.w(it, "[Lossless] exportación a carpeta pública falló")
            false
        }
    }

    private fun exportViaMediaStore(
        context: Context,
        sourceFile: File,
        artworkFile: File?,
        baseName: String,
        ext: String,
        mime: String,
    ): Boolean {
        val relativePath = Environment.DIRECTORY_MUSIC + "/" + FOLDER
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$baseName.$ext")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }
        val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        context.contentResolver.openOutputStream(uri)?.use { out ->
            sourceFile.inputStream().use { it.copyTo(out) }
        } ?: return false

        artworkFile?.takeIf { it.exists() && it.length() > 0 }?.let { art ->
            runCatching {
                val coverValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$baseName.jpg")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                }
                context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, coverValues)?.let { coverUri ->
                    context.contentResolver.openOutputStream(coverUri)?.use { out ->
                        art.inputStream().use { it.copyTo(out) }
                    }
                }
            }
        }
        return true
    }

    private fun exportViaFile(
        context: Context,
        sourceFile: File,
        artworkFile: File?,
        baseName: String,
        ext: String,
    ): Boolean {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER)
            .apply { if (!exists()) mkdirs() }
        val dest = File(dir, "$baseName.$ext")
        sourceFile.copyTo(dest, overwrite = true)

        val scanPaths = mutableListOf(dest.absolutePath)
        artworkFile?.takeIf { it.exists() && it.length() > 0 }?.let { art ->
            runCatching {
                val coverDest = File(dir, "$baseName.jpg")
                art.copyTo(coverDest, overwrite = true)
                scanPaths.add(coverDest.absolutePath)
            }
        }
        MediaScannerConnection.scanFile(context, scanPaths.toTypedArray(), null, null)
        return true
    }

    private fun safe(value: String): String = value.replace(ILLEGAL, "_").trim().ifBlank { "Desconocido" }

    private fun mimeFor(ext: String): String = when (ext) {
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        "m4a", "mp4" -> "audio/mp4"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wav" -> "audio/wav"
        else -> "audio/*"
    }
}
