/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.dezzy.music.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dezzy.music.db.MusicDatabase
import com.dezzy.music.db.entities.Album
import com.dezzy.music.db.entities.Artist
import com.dezzy.music.db.entities.LocalItem
import com.dezzy.music.db.entities.Playlist
import com.dezzy.music.db.entities.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.text.Normalizer
import javax.inject.Inject

/**
 * Local (on-device library) search.
 *
 * Matching is done in Kotlin rather than with SQL `LIKE` because SQLite's LIKE
 * is case-insensitive only for ASCII and cannot ignore diacritics — with it,
 * "cancion" never matched "Canción" and "beyonce" never matched "Beyoncé".
 * Songs also match by artist name, and results are ranked (prefix > word
 * prefix > substring > artist match) instead of the previous arbitrary
 * `LIMIT` order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LocalSearchViewModel
@Inject
constructor(
    database: MusicDatabase,
) : ViewModel() {
    val query = MutableStateFlow("")
    val filter = MutableStateFlow(LocalFilter.ALL)

    // Candidate sets are shared and kept warm so each keystroke only re-filters
    // in memory instead of re-querying Room.
    private val songCandidates = database.searchCandidateSongs()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    private val albumCandidates = database.searchCandidateAlbums()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    private val artistCandidates = database.searchCandidateArtists()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    private val playlistCandidates = database.searchCandidatePlaylists()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val result =
        combine(query, filter) { query, filter ->
            query to filter
        }.flatMapLatest { (query, filter) ->
            if (query.isEmpty()) {
                flowOf(LocalSearchResult("", filter, emptyMap()))
            } else {
                val q = normalize(query)
                when (filter) {
                    LocalFilter.ALL ->
                        combine(
                            songCandidates,
                            albumCandidates,
                            artistCandidates,
                            playlistCandidates,
                        ) { songs, albums, artists, playlists ->
                            rankMatches(songs, q, PREVIEW_SIZE) +
                                rankMatches(albums, q, PREVIEW_SIZE) +
                                rankMatches(artists, q, PREVIEW_SIZE) +
                                rankMatches(playlists, q, PREVIEW_SIZE)
                        }

                    LocalFilter.SONG -> songCandidates.map { rankMatches(it, q) }
                    LocalFilter.ALBUM -> albumCandidates.map { rankMatches(it, q) }
                    LocalFilter.ARTIST -> artistCandidates.map { rankMatches(it, q) }
                    LocalFilter.PLAYLIST -> playlistCandidates.map { rankMatches(it, q) }
                }.map { list ->
                    LocalSearchResult(
                        query = query,
                        filter = filter,
                        map =
                        list.groupBy {
                            when (it) {
                                is Song -> LocalFilter.SONG
                                is Album -> LocalFilter.ALBUM
                                is Artist -> LocalFilter.ARTIST
                                is Playlist -> LocalFilter.PLAYLIST
                            }
                        },
                    )
                }
            }
        }.flowOn(Dispatchers.Default)
            .stateIn(
                viewModelScope,
                SharingStarted.Lazily,
                LocalSearchResult("", filter.value, emptyMap())
            )

    companion object {
        const val PREVIEW_SIZE = 3

        /** Lowercases and strips diacritics: "Canción" -> "cancion". */
        private fun normalize(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD)
                .replace(DIACRITICS, "")
                .lowercase()

        private val DIACRITICS = Regex("\\p{Mn}+")

        /**
         * Match rank; lower is better, null means no match.
         * 0 = title/name starts with the query
         * 1 = some word in the title starts with the query
         * 2 = title contains the query
         * 3 = an associated artist matches (songs and albums)
         */
        private fun matchRank(item: LocalItem, q: String): Int? {
            val title = normalize(item.title)
            if (title.startsWith(q)) return 0
            if (title.split(' ').any { it.startsWith(q) }) return 1
            if (title.contains(q)) return 2
            val artistNames = when (item) {
                is Song -> item.artists.map { it.name }
                is Album -> item.artists.map { it.name }
                else -> emptyList()
            }
            if (artistNames.any { normalize(it).contains(q) }) return 3
            return null
        }

        private fun <T : LocalItem> rankMatches(
            candidates: List<T>,
            q: String,
            limit: Int = Int.MAX_VALUE,
        ): List<T> =
            candidates.mapNotNull { item -> matchRank(item, q)?.let { rank -> rank to item } }
                .sortedWith(compareBy({ it.first }, { normalize(it.second.title) }))
                .let { ranked -> if (limit == Int.MAX_VALUE) ranked else ranked.take(limit) }
                .map { it.second }
    }
}

enum class LocalFilter {
    ALL,
    SONG,
    ALBUM,
    ARTIST,
    PLAYLIST,
}

data class LocalSearchResult(
    val query: String,
    val filter: LocalFilter,
    val map: Map<LocalFilter, List<LocalItem>>,
)
