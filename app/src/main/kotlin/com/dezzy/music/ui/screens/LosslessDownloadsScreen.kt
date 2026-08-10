/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.dezzy.music.LocalPlayerAwareWindowInsets
import com.dezzy.music.R
import com.dezzy.music.lossless.LosslessDownloadState
import com.dezzy.music.ui.component.IconButton
import com.dezzy.music.ui.utils.backToMain
import com.dezzy.music.viewmodels.LosslessViewModel

/**
 * Cola global de descargas lossless: muestra el estado de cada descarga (en cola,
 * resolviendo, progreso, guardando, hecho, error) leyendo el StateFlow del
 * [com.dezzy.music.lossless.LosslessDownloadManager]. Permite limpiar las
 * terminadas.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LosslessDownloadsScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: LosslessViewModel = hiltViewModel(),
) {
    val downloads by viewModel.downloads.collectAsState()

    val ordered = downloads.values.sortedWith(
        compareBy(
            { if (it.phase.isActive) 0 else if (it.phase == LosslessDownloadState.Phase.FAILED) 1 else 2 },
            { it.title },
        ),
    )

    Box(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                ),
            )
            .fillMaxSize(),
    ) {
        if (ordered.isEmpty()) {
            Text(
                text = "No hay descargas todavía.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Spacer(
                        Modifier.windowInsetsPadding(
                            LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top),
                        ),
                    )
                }
                items(ordered, key = { it.trackKey }) { state ->
                    LosslessDownloadStateRow(state)
                }
            }
        }
    }

    TopAppBar(
        title = { Text("Cola de descargas") },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
        actions = {
            IconButton(
                onClick = { viewModel.clearFinishedDownloads() },
                onLongClick = {},
            ) {
                Icon(painterResource(R.drawable.delete), contentDescription = "Limpiar terminadas")
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun LosslessDownloadStateRow(state: LosslessDownloadState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = state.thumbnailUrl,
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(
                state.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = state.provider.displayName + " · " + statusLabel(state),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.phase == LosslessDownloadState.Phase.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (state.phase == LosslessDownloadState.Phase.DOWNLOADING) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }
        }
        when (state.phase) {
            LosslessDownloadState.Phase.DONE ->
                Icon(
                    painterResource(R.drawable.check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            LosslessDownloadState.Phase.FAILED ->
                Icon(
                    painterResource(R.drawable.error),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            else ->
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
    }
}

private fun statusLabel(state: LosslessDownloadState): String = when (state.phase) {
    LosslessDownloadState.Phase.QUEUED -> "En cola"
    LosslessDownloadState.Phase.RESOLVING -> "Resolviendo"
    LosslessDownloadState.Phase.DOWNLOADING -> "Descargando ${(state.progress * 100).toInt()}%"
    LosslessDownloadState.Phase.TAGGING -> "Guardando"
    LosslessDownloadState.Phase.DONE -> "Descargado"
    LosslessDownloadState.Phase.FAILED -> state.error ?: "Error"
}
