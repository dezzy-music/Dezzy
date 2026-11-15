/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.screens.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dezzy.music.R
import com.dezzy.music.constants.SearchSource
import com.dezzy.music.innertube.models.AlbumItem
import com.dezzy.music.innertube.models.ArtistItem
import com.dezzy.music.innertube.models.PlaylistItem
import com.dezzy.music.innertube.models.SongItem
import com.dezzy.music.innertube.models.YTItem

/**
 * Fuente de búsqueda visible como chips (fase 3, boceto A). Sustituye a tener que
 * adivinar el icono que cicla la fuente; ese icono se conserva en la barra.
 * Lossless sólo aparece si la función está activada.
 */
@Composable
fun SearchSourceChips(
    current: SearchSource,
    losslessEnabled: Boolean,
    onSelect: (SearchSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = buildList {
        add(SearchSource.ONLINE to "YouTube Music")
        add(SearchSource.LOCAL to "Biblioteca")
        if (losslessEnabled) add(SearchSource.LOSSLESS to "Lossless")
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        options.forEach { (source, label) ->
            val selected = source == current
            FilterChip(
                selected = selected,
                onClick = { if (!selected) onSelect(source) },
                label = { Text(text = label, maxLines = 1) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(
                            if (selected) {
                                R.drawable.check
                            } else {
                                when (source) {
                                    SearchSource.ONLINE -> R.drawable.language
                                    SearchSource.LOCAL -> R.drawable.library_music
                                    SearchSource.LOSSLESS -> R.drawable.graphic_eq
                                }
                            },
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
            )
        }
    }
}

/** Encabezado de sección en los resultados de búsqueda (sustituye a la barrita de color). */
@Composable
fun SearchResultSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

/**
 * Tarjeta «Mejor resultado»: primer elemento del resumen de YouTube Music, con
 * Reproducir y, si el elemento lo admite, Aleatorio.
 *
 * @param onOpen abre el elemento (lo mismo que tocar su fila).
 * @param onPlay reproduce; null oculta el botón.
 * @param onShuffle aleatorio; null oculta el botón.
 * @param onMenu menú contextual existente del elemento.
 */
@Composable
fun SearchTopResultCard(
    item: YTItem,
    onOpen: () -> Unit,
    onPlay: (() -> Unit)?,
    onShuffle: (() -> Unit)?,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isArtist = item is ArtistItem
    val subtitle = when (item) {
        is ArtistItem -> listOfNotNull(
            "Artista",
            item.monthlyListenerCountText ?: item.subscriberCountText,
        ).joinToString(", ")
        is SongItem -> listOfNotNull("Canción", item.artists.joinToString { it.name }.ifBlank { null })
            .joinToString(", ")
        is AlbumItem -> listOfNotNull(
            "Álbum",
            item.artists?.joinToString { it.name }?.ifBlank { null },
            item.year?.toString(),
        ).joinToString(", ")
        is PlaylistItem -> listOfNotNull("Playlist", item.author?.name, item.songCountText)
            .joinToString(", ")
    }

    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 4.dp),
        ) {
            AsyncImage(
                model = item.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(88.dp)
                    .clip(if (isArtist) CircleShape else RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.size(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (onPlay != null || onShuffle != null) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onPlay != null) {
                            Button(
                                onClick = onPlay,
                                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.play),
                                    contentDescription = null,
                                    modifier = Modifier.size(ButtonDefaults.IconSize),
                                )
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                Text(text = stringResource(R.string.play), maxLines = 1)
                            }
                        }
                        if (onShuffle != null) {
                            FilledTonalIconButton(onClick = onShuffle) {
                                Icon(
                                    painter = painterResource(R.drawable.shuffle),
                                    contentDescription = stringResource(R.string.shuffle),
                                )
                            }
                        }
                    }
                }
            }
            IconButton(onClick = onMenu, modifier = Modifier.align(Alignment.Top)) {
                Icon(
                    painter = painterResource(R.drawable.more_vert),
                    contentDescription = null,
                )
            }
        }
    }
}
