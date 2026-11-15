/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.dezzy.music.LocalPlayerAwareWindowInsets
import com.dezzy.music.R
import com.dezzy.music.ui.component.shimmer.ShimmerHost
import com.dezzy.music.ui.component.shimmer.TextPlaceholder
import com.dezzy.music.ui.screens.musicrecognition.MusicRecognitionRoute
import com.dezzy.music.viewmodels.MoodAndGenresViewModel

/** Cuántos géneros se muestran en la pestaña antes de «Ver todo». */
private const val SearchHomeGenreCount = 8

private data class DiscoverEntry(
    val key: String,
    @DrawableRes val icon: Int,
    val title: String,
    val subtitle: String,
    val route: String,
    val highlighted: Boolean = false,
)

/**
 * Pestaña «Buscar» (fase 3 del rediseño, boceto A). La barra de búsqueda la dibuja
 * `TopSearch` en MainActivity; aquí va lo que hay debajo: los accesos «Descubre»
 * (Éxitos, Novedades, Videos y Reconocer música) y los estados de ánimo y géneros.
 * La cuadrícula completa de géneros sigue en [MoodAndGenresScreen] («Ver todo»).
 */
@Composable
fun SearchHomeScreen(
    navController: NavController,
    viewModel: MoodAndGenresViewModel = hiltViewModel(),
) {
    val moodAndGenres by viewModel.moodAndGenres.collectAsState()
    val gridState = rememberLazyGridState()
    val density = LocalDensity.current
    val windowInsets = LocalPlayerAwareWindowInsets.current
    val topPadding = with(density) { windowInsets.getTop(this).toDp() }
    val bottomPadding = with(density) { windowInsets.getBottom(this).toDp() }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val scrollToTop =
        backStackEntry?.savedStateHandle?.getStateFlow("scrollToTop", false)?.collectAsState()

    LaunchedEffect(scrollToTop?.value) {
        if (scrollToTop?.value == true) {
            gridState.animateScrollToItem(0)
            backStackEntry?.savedStateHandle?.set("scrollToTop", false)
        }
    }

    val discover = listOf(
        DiscoverEntry("charts", R.drawable.trending_up, "Éxitos", "Lo más escuchado", "charts_screen", highlighted = true),
        DiscoverEntry("new", R.drawable.new_release, "Novedades", "Álbumes y sencillos", "new_release"),
        DiscoverEntry("videos", R.drawable.slow_motion_video, "Videos", "Los más vistos", ChartsVideosRoute),
        DiscoverEntry("recognize", R.drawable.mic, "Reconocer", "¿Qué está sonando?", MusicRecognitionRoute),
    )

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        state = gridState,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = topPadding + 8.dp,
            end = 16.dp,
            bottom = bottomPadding + 16.dp,
        ),
    ) {
        item(key = "discover_title", span = { GridItemSpan(maxLineSpan) }) {
            SearchHomeSectionTitle(title = "Descubre")
        }

        items(items = discover, key = { "discover_${it.key}" }) { entry ->
            DiscoverCard(
                entry = entry,
                onClick = { navController.navigate(entry.route) },
            )
        }

        item(key = "genres_title", span = { GridItemSpan(maxLineSpan) }) {
            SearchHomeSectionTitle(
                title = "Estados de ánimo y géneros",
                modifier = Modifier.padding(top = 8.dp),
                actionLabel = stringResource(R.string.view_all),
                onAction = { navController.navigate(Screens.MoodAndGenres.route) },
            )
        }

        val genres = moodAndGenres
        if (genres == null) {
            items(SearchHomeGenreCount) {
                ShimmerHost {
                    TextPlaceholder(
                        height = MoodAndGenresButtonHeight,
                        shape = RoundedCornerShape(16.dp),
                    )
                }
            }
        } else {
            items(
                items = genres.take(SearchHomeGenreCount),
                key = { item -> "genre_${item.title}:${item.endpoint.browseId}:${item.endpoint.params}" },
            ) { item ->
                MoodAndGenresButton(
                    title = item.title,
                    stripeColor = item.stripeColor,
                    onClick = {
                        navController.navigate("youtube_browse/${item.endpoint.browseId}?params=${item.endpoint.params}")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Ruta de Éxitos que abre la pantalla ya desplazada a «Top music videos». */
const val ChartsVideosRoute = "charts_screen/videos"

@Composable
private fun SearchHomeSectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (actionLabel != null) {
            TextButton(onClick = onAction) {
                Text(text = actionLabel)
            }
        }
    }
}

@Composable
private fun DiscoverCard(
    entry: DiscoverEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container =
        if (entry.highlighted) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainer
    val iconTint =
        if (entry.highlighted) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.primary
    val supportingColor =
        if (entry.highlighted) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
        else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = container,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Icon(
                painter = painterResource(entry.icon),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = supportingColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
