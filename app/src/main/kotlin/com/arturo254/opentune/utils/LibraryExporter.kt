/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.arturo254.opentune.utils

import com.arturo254.opentune.db.entities.Song

/**
 * Exportación de la biblioteca a formatos abiertos.
 *
 * El objetivo es que los datos salgan de la app sin quedar atados a ella: el CSV lo
 * abre cualquier hoja de cálculo y lo aceptan los importadores de otros reproductores
 * (y el propio de Dezzy, `BackupRestoreViewModel.importPlaylistFromCsv`, que busca las
 * columnas "title" y "artist"); el M3U es el estándar de listas de reproducción.
 *
 * Para un respaldo completo con historial y ajustes sigue siendo mejor el .backup (ZIP
 * con la base de datos); esto es para migrar y para tener los datos en claro.
 */
object LibraryExporter {

    /** Columnas del CSV. Las dos primeras son las que reconoce el importador propio. */
    private val CSV_HEADER = listOf(
        "Title", "Artist", "Album", "Duration", "VideoId", "Liked", "Playlist",
    )

    /**
     * CSV (RFC 4180) de las canciones dadas. [playlistName] queda en la última columna
     * para poder concatenar varias listas en un mismo archivo.
     */
    fun songsToCsv(songs: List<Song>, playlistName: String = ""): String =
        buildString {
            appendLine(CSV_HEADER.joinToString(",") { escapeCsv(it) })
            songs.forEach { song ->
                appendLine(
                    listOf(
                        song.song.title,
                        song.artists.joinToString("; ") { it.name },
                        song.song.albumName.orEmpty(),
                        song.song.duration.takeIf { it > 0 }?.toString().orEmpty(),
                        song.song.id,
                        if (song.song.liked) "true" else "false",
                        playlistName,
                    ).joinToString(",") { escapeCsv(it) }
                )
            }
        }

    /** Igual que [songsToCsv] pero para varias listas de una vez (una sola cabecera). */
    fun playlistsToCsv(playlists: List<Pair<String, List<Song>>>): String =
        buildString {
            appendLine(CSV_HEADER.joinToString(",") { escapeCsv(it) })
            playlists.forEach { (name, songs) ->
                songs.forEach { song ->
                    appendLine(
                        listOf(
                            song.song.title,
                            song.artists.joinToString("; ") { it.name },
                            song.song.albumName.orEmpty(),
                            song.song.duration.takeIf { it > 0 }?.toString().orEmpty(),
                            song.song.id,
                            if (song.song.liked) "true" else "false",
                            name,
                        ).joinToString(",") { escapeCsv(it) }
                    )
                }
            }
        }

    /**
     * M3U extendido. El formato de `#EXTINF` (duración,artistas - título) es el que
     * espera el lector propio `loadM3UOnline`, así que lo exportado se puede reimportar.
     * La URL de cada pista apunta a YouTube Music para que el archivo sirva fuera de la app.
     */
    fun songsToM3u(songs: List<Song>): String =
        buildString {
            appendLine("#EXTM3U")
            songs.forEach { song ->
                val artists = song.artists.joinToString(";") { it.name }
                val duration = song.song.duration.takeIf { it > 0 } ?: -1
                appendLine("#EXTINF:$duration,$artists - ${song.song.title}")
                appendLine("https://music.youtube.com/watch?v=${song.song.id}")
            }
        }

    /** Entrecomilla solo cuando hace falta y duplica las comillas internas (RFC 4180). */
    private fun escapeCsv(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuotes) "\"$escaped\"" else escaped
    }
}
