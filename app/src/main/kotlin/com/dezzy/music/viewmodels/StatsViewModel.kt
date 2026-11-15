/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.dezzy.music.viewmodels

import java.time.temporal.ChronoUnit
import java.time.YearMonth
import com.dezzy.music.ui.screens.StatsListening
import com.dezzy.music.ui.screens.StatsBucket
import com.dezzy.music.db.entities.EventPlayTime
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dezzy.music.innertube.YouTube
import com.dezzy.music.constants.statToPeriod
import com.dezzy.music.db.MusicDatabase
import com.dezzy.music.ui.screens.OptionStats
import com.dezzy.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel
@Inject
constructor(
    val database: MusicDatabase,
) : ViewModel() {
    val selectedOption = MutableStateFlow(OptionStats.CONTINUOUS)
    val indexChips = MutableStateFlow(0)

    val mostPlayedSongsStats =
        combine(
            selectedOption,
            indexChips,
        ) { first, second -> Pair(first, second) }
            .flatMapLatest { (selection, t) ->
                database
                    .mostPlayedSongsStats(
                        fromTimeStamp = statToPeriod(selection, t),
                        limit = -1,
                        toTimeStamp =
                        if (selection == OptionStats.CONTINUOUS || t == 0) {
                            LocalDateTime
                                .now()
                                .toInstant(
                                    ZoneOffset.UTC,
                                ).toEpochMilli()
                        } else {
                            statToPeriod(selection, t - 1)
                        },
                    )
                    // El catch va sobre el flow interno para que un fallo con un
                    // periodo no rompa el flatMapLatest: al cambiar de chip se
                    // vuelve a consultar. Con datos irrecuperables la pantalla
                    // queda vacía en vez de tumbar la app.
                    .catch { e ->
                        reportException(e)
                        emit(emptyList())
                    }
            }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val mostPlayedSongs =
        combine(
            selectedOption,
            indexChips,
        ) { first, second -> Pair(first, second) }
            .flatMapLatest { (selection, t) ->
                database
                    .mostPlayedSongs(
                        fromTimeStamp = statToPeriod(selection, t),
                        limit = -1,
                        toTimeStamp =
                        if (selection == OptionStats.CONTINUOUS || t == 0) {
                            LocalDateTime
                                .now()
                                .toInstant(
                                    ZoneOffset.UTC,
                                ).toEpochMilli()
                        } else {
                            statToPeriod(selection, t - 1)
                        },
                    )
                    .catch { e ->
                        reportException(e)
                        emit(emptyList())
                    }
            }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val mostPlayedArtists =
        combine(
            selectedOption,
            indexChips,
        ) { first, second -> Pair(first, second) }
            .flatMapLatest { (selection, t) ->
                database
                    .mostPlayedArtists(
                        statToPeriod(selection, t),
                        limit = -1,
                        toTimeStamp =
                        if (selection == OptionStats.CONTINUOUS || t == 0) {
                            LocalDateTime
                                .now()
                                .toInstant(
                                    ZoneOffset.UTC,
                                ).toEpochMilli()
                        } else {
                            statToPeriod(selection, t - 1)
                        },
                    )
                    // Sin filtrar por isYouTubeArtist: si el artista más
                    // escuchado de un mes era local (id que no empieza por UC),
                    // el destacado y la lista de ese mes desaparecían. El
                    // refresco de red del init sí se limita a YouTube.
                    .catch { e ->
                        reportException(e)
                        emit(emptyList())
                    }
            }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val mostPlayedAlbums =
        combine(
            selectedOption,
            indexChips,
        ) { first, second -> Pair(first, second) }
            .flatMapLatest { (selection, t) ->
                database.mostPlayedAlbums(
                    statToPeriod(selection, t),
                    limit = -1,
                    toTimeStamp =
                    if (selection == OptionStats.CONTINUOUS || t == 0) {
                        LocalDateTime
                            .now()
                            .toInstant(
                                ZoneOffset.UTC,
                            ).toEpochMilli()
                    } else {
                        statToPeriod(selection, t - 1)
                    },
                )
                    .catch { e ->
                        reportException(e)
                        emit(emptyList())
                    }
            }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Tiempo escuchado del periodo y su reparto en barras (tarjeta héroe de Estadísticas). */
    val listening =
        combine(
            selectedOption,
            indexChips,
        ) { first, second -> Pair(first, second) }
            .flatMapLatest { (selection, t) ->
                val from = statToPeriod(selection, t)
                val to =
                    if (selection == OptionStats.CONTINUOUS || t == 0) {
                        LocalDateTime.now().toInstant(ZoneOffset.UTC).toEpochMilli()
                    } else {
                        statToPeriod(selection, t - 1)
                    }
                database
                    .eventPlayTimes(from, to)
                    .map { events -> bucketListening(events, from, to) }
                    .catch { e ->
                        reportException(e)
                        emit(StatsListening())
                    }
            }.stateIn(viewModelScope, SharingStarted.Lazily, StatsListening())

    val firstEvent =
        database
            .firstEvent()
            // Si el evento más antiguo quedó huérfano (canción borrada), Room
            // lanza al mapear EventWithSong.song. La BD lo sanea al abrir
            // (repairForeignKeyOrphans), pero si algo se cuela la pantalla debe
            // seguir abriendo.
            .catch { e ->
                reportException(e)
                emit(null)
            }
            .stateIn(viewModelScope, SharingStarted.Lazily, null)

    init {
        viewModelScope.launch {
            mostPlayedArtists.collect { artists ->
                artists
                    .map { it.artist }
                    .filter { it.isYouTubeArtist }
                    .filter {
                        it.thumbnailUrl == null || Duration.between(
                            it.lastUpdateTime,
                            LocalDateTime.now()
                        ) > Duration.ofDays(10)
                    }.forEach { artist ->
                        YouTube.artist(artist.id).onSuccess { artistPage ->
                            database.query {
                                update(artist, artistPage)
                            }
                        }
                    }
            }
        }
        viewModelScope.launch {
            mostPlayedAlbums.collect { albums ->
                albums
                    .filter {
                        it.album.songCount == 0
                    }.forEach { album ->
                        YouTube
                            .album(album.id)
                            .onSuccess { albumPage ->
                                database.query {
                                    update(album.album, albumPage, album.artists)
                                }
                            }.onFailure {
                                reportException(it)
                                if (it.message?.contains("NOT_FOUND") == true) {
                                    database.query {
                                        delete(album.album)
                                    }
                                }
                            }
                    }
            }
        }
    }
}

private val StatsDayInitials = listOf("L", "M", "X", "J", "V", "S", "D")
private val StatsMonthInitials = listOf("E", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D")

/**
 * Reparte el tiempo escuchado en barras según la duración del periodo:
 * hasta 8 días → por día; hasta 35 → por semana (S1…S5); más → por mes (máx. 12).
 * Los timestamps se guardan como hora local codificada en UTC (ver Converters).
 */
private fun bucketListening(
    events: List<EventPlayTime>,
    from: Long,
    to: Long,
): StatsListening {
    fun List<EventPlayTime>.time() = sumOf { it.playTime.coerceAtLeast(0L) }

    val total = events.time()
    if (events.isEmpty()) return StatsListening(totalMs = total)

    val end = LocalDateTime.ofEpochSecond((to - 1).coerceAtLeast(0L) / 1000, 0, ZoneOffset.UTC).toLocalDate()
    val start = if (from > 0) LocalDateTime.ofEpochSecond(from / 1000, 0, ZoneOffset.UTC).toLocalDate() else null
    val spanDays = start?.let { ChronoUnit.DAYS.between(it, end) + 1 } ?: Long.MAX_VALUE

    val buckets = when {
        spanDays <= 8 -> {
            val byDay = events.groupBy { it.timestamp.toLocalDate() }
            (0L until minOf(spanDays, 7L))
                .map { end.minusDays(it) }
                .reversed()
                .map { day -> StatsBucket(StatsDayInitials[day.dayOfWeek.value - 1], byDay[day].orEmpty().time()) }
        }

        spanDays <= 35 -> {
            val first = start ?: end
            val count = ((spanDays + 6) / 7).toInt().coerceIn(1, 5)
            (0 until count).map { i ->
                val weekStart = first.plusDays(7L * i)
                val weekEnd = weekStart.plusDays(7)
                val inWeek = events.filter {
                    val day = it.timestamp.toLocalDate()
                    !day.isBefore(weekStart) && day.isBefore(weekEnd)
                }
                StatsBucket("S${i + 1}", inWeek.time())
            }
        }

        else -> {
            val endMonth = YearMonth.from(end)
            val startMonth = start?.let { YearMonth.from(it) }
            val months =
                if (startMonth != null && ChronoUnit.MONTHS.between(startMonth, endMonth) < 12) {
                    generateSequence(startMonth) { it.plusMonths(1) }
                        .takeWhile { !it.isAfter(endMonth) }
                        .toList()
                } else {
                    (11L downTo 0L).map { endMonth.minusMonths(it) }
                }
            val byMonth = events.groupBy { YearMonth.from(it.timestamp) }
            months.map { month -> StatsBucket(StatsMonthInitials[month.monthValue - 1], byMonth[month].orEmpty().time()) }
        }
    }
    return StatsListening(totalMs = total, buckets = buckets)
}
