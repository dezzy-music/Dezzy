package com.arturo254.opentune.api

import com.arturo254.opentune.playback.PlayerConnection
import com.arturo254.opentune.playback.queues.YouTubeQueue
import com.arturo254.opentune.innertube.YouTube
import com.arturo254.opentune.innertube.models.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import com.arturo254.opentune.db.MusicDatabase
import kotlinx.coroutines.flow.first
import com.arturo254.opentune.models.toMediaMetadata
import com.arturo254.opentune.extensions.togglePlayPause
import androidx.datastore.preferences.core.edit
import com.arturo254.opentune.utils.dataStore
import android.content.Context
import com.arturo254.opentune.R

/**
 * Handles the execution of actions requested by Dezzy IA.
 */
class DezzyAgent(
    private val playerConnection: PlayerConnection,
    private val database: MusicDatabase,
    private val scope: CoroutineScope
) {
    private val TAG = "DezzyAgent"

    data class AgentResult(
        val responseText: String,
        val recommendations: List<SongItem> = emptyList()
    )

    /**
     * Executes a tool call from the AI.
     * Expects JSON format like: {"action": "play_pause"} or {"action": "search", "query": "..."}
     */
    suspend fun executeAction(context: Context, jsonAction: String): AgentResult {
        return try {
            val json = JSONObject(jsonAction)
            val action = json.optString("action")
            
            when (action) {
                "play_pause" -> {
                    playerConnection.player.togglePlayPause()
                    AgentResult("Reproducción pausada/reanudada.")
                }
                "skip_next" -> {
                    playerConnection.seekToNext()
                    AgentResult("Siguiente canción reproducida.")
                }
                "skip_previous" -> {
                    playerConnection.seekToPrevious()
                    AgentResult("Canción anterior reproducida.")
                }
                "play_song" -> {
                    val query = json.optString("query")
                    if (query.isNotEmpty()) {
                        scope.launch {
                            searchAndPlay(query)
                        }
                        AgentResult("Buscando y reproduciendo $query...")
                    } else {
                        AgentResult("Error: Búsqueda vacía.")
                    }
                }
                "like_song" -> {
                    val query = json.optString("query")
                    if (query.isNotEmpty()) {
                        scope.launch {
                            try {
                                val page = YouTube.searchSummary(query).getOrNull()
                                val topResult = page?.summaries?.firstOrNull()?.items?.firstOrNull { it is SongItem } as? SongItem
                                if (topResult != null) {
                                    val metadata = topResult.toMediaMetadata()
                                    val existingSong = database.song(metadata.id).first()
                                    val songEntity = existingSong?.song ?: metadata.toSongEntity()
                                    if (!songEntity.liked) {
                                        val updatedEntity = songEntity.toggleLike()
                                        database.transaction {
                                            if (existingSong == null) {
                                                insert(songEntity)
                                                update(updatedEntity)
                                            } else {
                                                update(updatedEntity)
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Timber.tag(TAG).e(e, "Error ejecutando action like_song")
                            }
                        }
                        AgentResult("Agregada a favoritos: $query.")
                    } else {
                        AgentResult("Error al agregar a favoritos: nombre vacío.")
                    }
                }
                "recommend" -> {
                    val songsArray = json.optJSONArray("songs")
                    if (songsArray != null && songsArray.length() > 0) {
                        val queries = (0 until songsArray.length()).map { songsArray.getString(it) }
                        
                        val results = queries.map { query ->
                            scope.async {
                                try {
                                    val page = YouTube.searchSummary(query).getOrNull()
                                    page?.summaries?.firstOrNull()?.items?.firstOrNull { it is SongItem } as? SongItem
                                } catch (e: Exception) {
                                    null
                                }
                            }
                        }.awaitAll().filterNotNull()

                        AgentResult("Aquí tienes unas recomendaciones musicales:", results)
                    } else {
                        AgentResult("No se pudieron procesar las recomendaciones.")
                    }
                }
                "create_playlist" -> {
                    val name = json.optString("name")
                    val songsArray = json.optJSONArray("songs")
                    if (name.isNotEmpty() && songsArray != null) {
                        scope.launch {
                            try {
                                val playlist = com.arturo254.opentune.db.entities.PlaylistEntity(name = name)
                                val playlistId = playlist.id
                                database.insert(playlist)
                                
                                val songItems = (0 until songsArray.length()).map { i ->
                                    val query = songsArray.getString(i)
                                    async {
                                        YouTube.searchSummary(query).getOrNull()?.summaries?.firstOrNull()?.items?.firstOrNull { it is SongItem } as? SongItem
                                    }
                                }.awaitAll().filterNotNull()

                                database.transaction {
                                    songItems.forEachIndexed { i, song ->
                                        val metadata = song.toMediaMetadata()
                                        insert(metadata.toSongEntity())
                                        insert(com.arturo254.opentune.db.entities.PlaylistSongMap(playlistId = playlistId, songId = metadata.id, position = i))
                                    }
                                }
                            } catch (e: Exception) {
                                Timber.tag(TAG).e(e, "Error en create_playlist")
                            }
                        }
                        AgentResult("Playlist '$name' creada con éxito.")
                    } else {
                        AgentResult("Error al crear la playlist.")
                    }
                }
                "update_theme" -> {
                    val colorHex = json.optString("color")
                    val style = json.optString("background_style")
                    scope.launch {
                        try {
                            if (colorHex.isNotEmpty()) {
                                playerConnection.service.dataStore.edit {
                                    it[com.arturo254.opentune.constants.CustomThemeColorKey] = colorHex
                                    it[com.arturo254.opentune.constants.DynamicThemeKey] = false
                                }
                            }
                            if (style.isNotEmpty()) {
                                playerConnection.service.dataStore.edit {
                                    it[com.arturo254.opentune.constants.PlayerBackgroundStyleKey] = style
                                }
                            }
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Error en update_theme")
                        }
                    }
                    AgentResult("Apariencia de la aplicación actualizada.")
                }
                else -> AgentResult("Acción desconocida: $action")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error executing AI action")
            AgentResult("Error al procesar la acción: ${e.message ?: "Unknown error"}")
        }
    }

    private suspend fun searchAndPlay(query: String) {
        try {
            YouTube.searchSummary(query).onSuccess { page ->
                val topResult = page.summaries.firstOrNull()?.items?.firstOrNull { it is SongItem } as? SongItem
                if (topResult != null) {
                    val mediaMetadata = topResult.toMediaMetadata()
                    val queue = YouTubeQueue.radio(mediaMetadata)
                    playerConnection.playQueue(queue)
                    Timber.tag(TAG).d("Reproduciendo top result: ${topResult.title}")
                } else {
                    Timber.tag(TAG).w("No se encontró ningún resultado de canción para: $query")
                }
            }.onFailure { e ->
                Timber.tag(TAG).e(e, "Error en búsqueda de YouTube para la IA")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Excepción en searchAndPlay")
        }
    }

    companion object {
        val TOOLS_PROMPT = """
            [SISTEMA DE ACCIONES Y TARJETAS INTERACTIVAS]
            Puedes ejecutar acciones en la aplicación y enviar tarjetas interactivas musicales respondiendo ÚNICAMENTE con un bloque JSON al final absoluto de tu mensaje. 
            
            Acciones disponibles:
            - {"action": "play_pause"}: Pausa o reanuda la música.
            - {"action": "skip_next"}: Pasa a la siguiente canción.
            - {"action": "skip_previous"}: Vuelve a la canción anterior.
            - {"action": "play_song", "query": "nombre de canción"}: Busca y reproduce una canción.
            - {"action": "like_song", "query": "nombre de canción"}: Agrega una canción a tus me gusta (favoritos).
            - {"action": "recommend", "songs": ["cancion 1 artista", "cancion 2 artista"]}: Muestra tarjetas visuales de recomendaciones.
            - {"action": "create_playlist", "name": "Nombre", "songs": ["cancion 1", "cancion 2"]}: Crea una playlist local con esas canciones.
            - {"action": "update_theme", "color": "#HEXCODE", "background_style": "DEFAULT|BLUR|GRADIENT"}: Cambia la apariencia de la app (color de acento y estilo de fondo del reproductor). Úsalo cuando el usuario pida cambiar el ambiente o "mood".
            
            Si el usuario pide información sobre una canción o historia, simplemente responde con texto enriquecido (puedes usar LaTeX para fórmulas si es necesario).
        """.trimIndent()
    }
}
