/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.lossless

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import timber.log.Timber
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Escribe los metadatos (título, artista, álbum, ISRC, año, carátula) DENTRO del
 * archivo de audio descargado usando jaudiotagger, para que quede bien etiquetado
 * al reproducirlo en otras apps o al exportarlo. El etiquetado es *best-effort*:
 * si falla no rompe la descarga (el archivo ya es reproducible y la metadata vive
 * también en la base de datos).
 */
object LosslessTagger {

    /** Contenedores que jaudiotagger sabe etiquetar. Opus no está soportado. */
    private val SUPPORTED = setOf("flac", "mp3", "m4a", "mp4", "ogg", "wav", "aif", "aiff", "wma")

    init {
        // jaudiotagger es muy verboso por java.util.logging; lo silenciamos.
        runCatching { Logger.getLogger("org.jaudiotagger").level = Level.OFF }
    }

    fun tag(file: File, track: LosslessTrack, artworkFile: File?): Boolean {
        val ext = file.extension.lowercase()
        if (ext !in SUPPORTED) return false

        return runCatching {
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tagOrCreateAndSetDefault

            setField(tag, FieldKey.TITLE, track.title)
            if (track.artistNames.isNotEmpty()) {
                setField(tag, FieldKey.ARTIST, track.artistNames.joinToString("; "))
                setField(tag, FieldKey.ALBUM_ARTIST, track.artistNames.first())
            }
            track.albumName?.let { setField(tag, FieldKey.ALBUM, it) }
            track.isrc?.let { setField(tag, FieldKey.ISRC, it) }

            artworkFile?.takeIf { it.exists() && it.length() > 0 }?.let { art ->
                runCatching {
                    val artwork = ArtworkFactory.createArtworkFromFile(art)
                    tag.deleteArtworkField()
                    tag.setField(artwork)
                }.onFailure { Timber.w(it, "[Lossless] no se pudo incrustar carátula") }
            }

            audioFile.commit()
            true
        }.getOrElse {
            Timber.w(it, "[Lossless] etiquetado falló para ${file.name}")
            false
        }
    }

    private fun setField(tag: org.jaudiotagger.tag.Tag, key: FieldKey, value: String) {
        if (value.isBlank()) return
        runCatching {
            tag.deleteField(key)
            tag.setField(key, value)
        }
    }
}
