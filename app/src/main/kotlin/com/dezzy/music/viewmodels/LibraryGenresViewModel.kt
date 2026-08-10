/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dezzy.music.db.MusicDatabase
import com.dezzy.music.utils.GenreClassifier
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

/**
 * Organiza la biblioteca (en biblioteca + me gusta + playlists) por género.
 * Ver [GenreClassifier] para la estrategia de clasificación en dos fases.
 */
@HiltViewModel
class LibraryGenresViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    database: MusicDatabase,
) : ViewModel() {

    val songs = database.libraryAndPlaylistSongs()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _genreMap = MutableStateFlow(GenreClassifier.loadCache(context))
    val genreMap: StateFlow<Map<String, String>> = _genreMap.asStateFlow()

    private val _classifying = MutableStateFlow(false)
    val classifying: StateFlow<Boolean> = _classifying.asStateFlow()

    /** (clasificadas, total) de la fase InnerTube en curso. */
    private val _progress = MutableStateFlow(0 to 0)
    val progress: StateFlow<Pair<Int, Int>> = _progress.asStateFlow()

    fun classifyLibrary() {
        if (_classifying.value) return
        _classifying.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val all = songs.value
                val current = _genreMap.value.toMutableMap()

                // Fase 1: heurística local sobre título/álbum/artista (sin red)
                all.filter { it.id !in current }.forEach { song ->
                    GenreClassifier.classifyText(
                        song.song.title,
                        song.song.albumName,
                        song.artists.joinToString(" ") { it.name },
                    )?.let { current[song.id] = it }
                }
                _genreMap.value = current.toMap()
                GenreClassifier.saveCache(context, current)

                // Fase 2: keywords de InnerTube (concurrencia acotada para no
                // disparar la detección de bots). Las locales importadas no
                // existen en YouTube, así que se saltan.
                val unresolved = all.filter {
                    it.id !in current && !it.id.startsWith("LOCAL_")
                }
                _progress.value = 0 to unresolved.size
                var done = 0
                val semaphore = Semaphore(3)
                coroutineScope {
                    unresolved.map { song ->
                        async {
                            semaphore.withPermit {
                                val genre = GenreClassifier.classifyWithInnerTube(song.id)
                                // Toda lectura/copia del mapa compartido va dentro del
                                // lock: copiarlo fuera mientras otro worker lo muta
                                // lanzaba ConcurrentModificationException.
                                val doneNow: Int
                                val snapshot: Map<String, String>?
                                synchronized(current) {
                                    current[song.id] = genre ?: GenreClassifier.UNKNOWN
                                    done++
                                    doneNow = done
                                    snapshot = if (done % 20 == 0) LinkedHashMap(current) else null
                                }
                                _progress.value = doneNow to unresolved.size
                                if (snapshot != null) {
                                    _genreMap.value = snapshot
                                    GenreClassifier.saveCache(context, snapshot)
                                }
                            }
                        }
                    }.awaitAll()
                }

                // Lo que siga sin género (p. ej. locales) queda en "Otros"
                all.filter { it.id !in current }.forEach {
                    current[it.id] = GenreClassifier.UNKNOWN
                }
                _genreMap.value = current.toMap()
                GenreClassifier.saveCache(context, current)
            } finally {
                _classifying.value = false
            }
        }
    }

    /** Borra el caché y vuelve a clasificar toda la biblioteca. */
    fun reclassify() {
        if (_classifying.value) return
        GenreClassifier.clearCache(context)
        _genreMap.value = emptyMap()
        classifyLibrary()
    }
}
