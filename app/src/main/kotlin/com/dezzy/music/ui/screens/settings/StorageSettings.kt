/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.dezzy.music.ui.screens.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.annotation.ExperimentalCoilApi
import coil3.imageLoader
import com.dezzy.music.LocalPlayerAwareWindowInsets
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.constants.MaxCanvasCacheSizeKey
import com.dezzy.music.constants.MaxImageCacheSizeKey
import com.dezzy.music.constants.MaxSongCacheSizeKey
import com.dezzy.music.constants.MaxParallelDownloadsKey
import com.dezzy.music.constants.ExportDownloadsToMusicKey
import com.dezzy.music.constants.SmartTrimmerKey
import com.dezzy.music.extensions.directorySizeBytes
import com.dezzy.music.extensions.tryOrNull
import com.dezzy.music.ui.component.ActionPromptDialog
import com.dezzy.music.ui.component.IconButton
import com.dezzy.music.ui.component.ListPreference
import com.dezzy.music.ui.component.PreferenceEntry
import com.dezzy.music.ui.component.SwitchPreference
import com.dezzy.music.ui.player.CanvasArtworkPlaybackCache
import com.dezzy.music.ui.utils.backToMain
import com.dezzy.music.ui.utils.formatFileSize
import com.dezzy.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoilApi::class, ExperimentalMaterial3Api::class)
@Composable
fun StorageSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val imageDiskCache = context.imageLoader.diskCache ?: return
    val playerCache = LocalPlayerConnection.current?.service?.playerCache ?: return
    val downloadCache = LocalPlayerConnection.current?.service?.downloadCache ?: return

    val downloadCacheDir = remember { context.filesDir.resolve("download") }
    val playerCacheDir = remember { context.filesDir.resolve("exoplayer") }

    val coroutineScope = rememberCoroutineScope()
    val (smartTrimmer, onSmartTrimmerChange) = rememberPreference(
        key = SmartTrimmerKey,
        defaultValue = false
    )
    val (maxImageCacheSize, onMaxImageCacheSizeChange) = rememberPreference(
        key = MaxImageCacheSizeKey,
        defaultValue = -1
    )
    val (maxSongCacheSize, onMaxSongCacheSizeChange) = rememberPreference(
        key = MaxSongCacheSizeKey,
        defaultValue = -1
    )
    val (maxCanvasCacheSize, onMaxCanvasCacheSizeChange) = rememberPreference(
        key = MaxCanvasCacheSizeKey,
        defaultValue = -1,
    )
    val (maxParallelDownloads, onMaxParallelDownloadsChange) = rememberPreference(
        key = MaxParallelDownloadsKey,
        defaultValue = 1,
    )
    val (exportDownloadsToMusic, onExportDownloadsToMusicChange) = rememberPreference(
        key = ExportDownloadsToMusicKey,
        defaultValue = false,
    )
    var clearCacheDialog by remember { mutableStateOf(false) }
    var clearDownloads by remember { mutableStateOf(false) }
    var clearImageCacheDialog by remember { mutableStateOf(false) }
    var clearCanvasCacheDialog by remember { mutableStateOf(false) }

    var pendingTrimSongCache by remember { mutableStateOf<Int?>(null) }
    var pendingTrimImageCache by remember { mutableStateOf<Int?>(null) }
    var pendingTrimCanvasCache by remember { mutableStateOf<Int?>(null) }

    var imageCacheSize by remember {
        mutableStateOf(imageDiskCache.size)
    }
    var playerCacheSize by remember {
        mutableStateOf(0L)
    }
    var downloadCacheSize by remember {
        mutableStateOf(0L)
    }
    var canvasCacheSize by remember {
        mutableStateOf(CanvasArtworkPlaybackCache.size())
    }
    val imageCacheProgress by animateFloatAsState(
        targetValue = if (imageDiskCache.maxSize > 0) {
            (imageCacheSize.toFloat() / imageDiskCache.maxSize).coerceIn(0f, 1f)
        } else 0f,
        label = "imageCacheProgress",
    )
    val maxSongCacheSizeBytes = if (maxSongCacheSize > 0) maxSongCacheSize * 1024 * 1024L else 0L
    val playerCacheProgress by animateFloatAsState(
        targetValue = if (maxSongCacheSizeBytes > 0) {
            (playerCacheSize.toFloat() / maxSongCacheSizeBytes).coerceIn(0f, 1f)
        } else 0f,
        label = "playerCacheProgress",
    )
    val canvasCacheProgress by animateFloatAsState(
        targetValue = if (maxCanvasCacheSize > 0) {
            (canvasCacheSize.toFloat() / maxCanvasCacheSize).coerceIn(0f, 1f)
        } else 0f,
        label = "canvasCacheProgress",
    )

    val isSmartTrimmerAvailable = maxImageCacheSize != 0 || maxSongCacheSize != 0
    LaunchedEffect(isSmartTrimmerAvailable) {
        if (!isSmartTrimmerAvailable && smartTrimmer) onSmartTrimmerChange(false)
    }

    LaunchedEffect(maxImageCacheSize) {
        if (maxImageCacheSize == 0) {
            coroutineScope.launch(Dispatchers.IO) {
                imageDiskCache.clear()
                com.dezzy.music.utils.ArtworkStorage.clear(context)
            }
        }
    }
    LaunchedEffect(maxSongCacheSize) {
        if (maxSongCacheSize == 0) {
            coroutineScope.launch(Dispatchers.IO) {
                playerCache.keys.forEach { key ->
                    playerCache.removeResource(key)
                }
            }
        }
    }
    LaunchedEffect(maxCanvasCacheSize) {
        CanvasArtworkPlaybackCache.setMaxSize(maxCanvasCacheSize)
        if (maxCanvasCacheSize == 0) {
            CanvasArtworkPlaybackCache.clear()
        }
    }

    LaunchedEffect(imageDiskCache) {
        while (isActive) {
            delay(500)
            imageCacheSize = imageDiskCache.size
        }
    }
    LaunchedEffect(playerCache, playerCacheDir) {
        while (isActive) {
            delay(500)
            playerCacheSize =
                withContext(Dispatchers.IO) {
                    val cacheSpace = tryOrNull { playerCache.cacheSpace } ?: 0L
                    if (cacheSpace == 0L) playerCacheDir.directorySizeBytes() else cacheSpace
                }
        }
    }
    LaunchedEffect(downloadCache, downloadCacheDir) {
        while (isActive) {
            delay(500)
            downloadCacheSize =
                withContext(Dispatchers.IO) {
                    val cacheSpace = tryOrNull { downloadCache.cacheSpace } ?: 0L
                    if (cacheSpace == 0L) downloadCacheDir.directorySizeBytes() else cacheSpace
                }
        }
    }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(500)
            canvasCacheSize = CanvasArtworkPlaybackCache.size()
        }
    }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)
            )
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.smart_trimmer)) },
            description = stringResource(R.string.smart_trimmer_description),
            checked = smartTrimmer && isSmartTrimmerAvailable,
            onCheckedChange = onSmartTrimmerChange,
            isEnabled = isSmartTrimmerAvailable,
        )

        // --- Section: Downloads ---
        CacheCard(
            icon = R.drawable.ic_download,
            title = stringResource(R.string.downloaded_songs),
            description = stringResource(R.string.size_used, formatFileSize(downloadCacheSize)),
            progress = null,
            actions = {
                ListPreference(
                    title = { Text(stringResource(R.string.max_parallel_downloads)) },
                    selectedValue = maxParallelDownloads,
                    values = listOf(1, 2, 3, 6),
                    valueText = { "$it" },
                    onValueSelected = onMaxParallelDownloadsChange,
                )
                SwitchPreference(
                    title = { Text(stringResource(R.string.export_downloads_to_music)) },
                    description = stringResource(R.string.export_downloads_to_music_desc),
                    icon = { Icon(painterResource(R.drawable.ic_download), null) },
                    checked = exportDownloadsToMusic,
                    onCheckedChange = onExportDownloadsToMusicChange,
                )
                PreferenceEntry(
                    title = { Text(stringResource(R.string.clear_all_downloads)) },
                    onClick = { clearDownloads = true },
                )
            }
        )

        if (clearDownloads) {
            ActionPromptDialog(
                title = stringResource(R.string.clear_all_downloads),
                onDismiss = { clearDownloads = false },
                onConfirm = {
                    coroutineScope.launch(Dispatchers.IO) {
                        downloadCache.keys.forEach { key ->
                            downloadCache.removeResource(key)
                        }
                    }
                    clearDownloads = false
                },
                onCancel = { clearDownloads = false },
                content = {
                    Text(text = stringResource(R.string.clear_downloads_dialog))
                }
            )
        }

        // --- Section: Song cache ---
        CacheCard(
            icon = R.drawable.ic_music,
            title = stringResource(R.string.song_cache),
            description = if (maxSongCacheSize == -1) {
                stringResource(R.string.size_used, formatFileSize(playerCacheSize))
            } else {
                "${formatFileSize(playerCacheSize)} / ${formatFileSize(maxSongCacheSize * 1024 * 1024L)}"
            },
            progress = if (maxSongCacheSize > 0) playerCacheProgress else null,
            actions = {
                ListPreference(
                    title = { Text(stringResource(R.string.max_cache_size)) },
                    selectedValue = maxSongCacheSize,
                    values = listOf(0, 128, 256, 512, 1024, 2048, 4096, 8192, -1),
                    valueText = {
                        when (it) {
                            0 -> stringResource(R.string.disable)
                            -1 -> stringResource(R.string.unlimited)
                            else -> formatFileSize(it * 1024 * 1024L)
                        }
                    },
                    onValueSelected = { newLimit ->
                        if (newLimit != -1 && (playerCacheSize / (1024 * 1024L)) > newLimit) {
                            pendingTrimSongCache = newLimit
                        } else {
                            onMaxSongCacheSizeChange(newLimit)
                        }
                    },
                )
                PreferenceEntry(
                    title = { Text(stringResource(R.string.clear_song_cache)) },
                    onClick = { clearCacheDialog = true },
                )
            }
        )

        if (clearCacheDialog) {
            ActionPromptDialog(
                title = stringResource(R.string.clear_song_cache),
                onDismiss = { clearCacheDialog = false },
                onConfirm = {
                    coroutineScope.launch(Dispatchers.IO) {
                        playerCache.keys.forEach { key ->
                            playerCache.removeResource(key)
                        }
                    }
                    clearCacheDialog = false
                },
                onCancel = { clearCacheDialog = false },
                content = {
                    Text(text = stringResource(R.string.clear_song_cache_dialog))
                }
            )
        }

        if (pendingTrimSongCache != null) {
            ActionPromptDialog(
                title = stringResource(R.string.max_cache_size),
                onDismiss = { pendingTrimSongCache = null },
                onConfirm = {
                    onMaxSongCacheSizeChange(pendingTrimSongCache!!)
                    pendingTrimSongCache = null
                },
                onCancel = { pendingTrimSongCache = null },
                content = {
                    Text(text = "Se eliminarán los elementos necesarios para igualar el límite seleccionado.")
                }
            )
        }

        // --- Section: Image cache ---
        CacheCard(
            icon = R.drawable.image,
            title = stringResource(R.string.image_cache),
            description = if (maxImageCacheSize > 0) {
                "${formatFileSize(imageCacheSize)} / ${formatFileSize(imageDiskCache.maxSize)}"
            } else {
                stringResource(R.string.disable)
            },
            progress = if (maxImageCacheSize > 0) imageCacheProgress else null,
            actions = {
                ListPreference(
                    title = { Text(stringResource(R.string.max_cache_size)) },
                    selectedValue = maxImageCacheSize,
                    values = listOf(0, 128, 256, 512, 1024, 2048, 4096, 8192, -1),
                    valueText = {
                        when (it) {
                            0 -> stringResource(R.string.disable)
                            -1 -> stringResource(R.string.unlimited)
                            else -> formatFileSize(it * 1024 * 1024L)
                        }
                    },
                    onValueSelected = { newLimit ->
                        if (newLimit != -1 && (imageCacheSize / (1024 * 1024L)) > newLimit) {
                            pendingTrimImageCache = newLimit
                        } else {
                            onMaxImageCacheSizeChange(newLimit)
                        }
                    },
                )
                PreferenceEntry(
                    title = { Text(stringResource(R.string.clear_image_cache)) },
                    onClick = { clearImageCacheDialog = true },
                )
            }
        )

        if (clearImageCacheDialog) {
            ActionPromptDialog(
                title = stringResource(R.string.clear_image_cache),
                onDismiss = { clearImageCacheDialog = false },
                onConfirm = {
                    coroutineScope.launch(Dispatchers.IO) {
                        imageDiskCache.clear()
                        com.dezzy.music.utils.ArtworkStorage.clear(context)
                    }
                    clearImageCacheDialog = false
                },
                onCancel = { clearImageCacheDialog = false },
                content = {
                    Text(text = stringResource(R.string.clear_image_cache_dialog))
                }
            )
        }

        if (pendingTrimImageCache != null) {
            ActionPromptDialog(
                title = stringResource(R.string.max_cache_size),
                onDismiss = { pendingTrimImageCache = null },
                onConfirm = {
                    onMaxImageCacheSizeChange(pendingTrimImageCache!!)
                    pendingTrimImageCache = null
                },
                onCancel = { pendingTrimImageCache = null },
                content = {
                    Text(text = "Se eliminarán los elementos necesarios para igualar el límite seleccionado.")
                }
            )
        }

        // --- Section: Canvas cache ---
        CacheCard(
            icon = R.drawable.motion_photos_on,
            title = stringResource(R.string.canvas_cache),
            description = if (maxCanvasCacheSize > 0) {
                stringResource(
                    R.string.canvas_cache_usage,
                    stringResource(R.string.canvas_cache_items, canvasCacheSize),
                    stringResource(R.string.canvas_cache_items, maxCanvasCacheSize),
                )
            } else {
                stringResource(R.string.disable)
            },
            progress = if (maxCanvasCacheSize > 0) canvasCacheProgress else null,
            actions = {
                ListPreference(
                    title = { Text(stringResource(R.string.max_cache_size)) },
                    selectedValue = maxCanvasCacheSize,
                    values = listOf(0, 64, 128, 256, 512, 1024, -1),
                    valueText = {
                        when (it) {
                            0 -> stringResource(R.string.disable)
                            -1 -> stringResource(R.string.unlimited)
                            else -> stringResource(R.string.canvas_cache_items, it)
                        }
                    },
                    onValueSelected = { newLimit ->
                        if (newLimit != -1 && canvasCacheSize > newLimit) {
                            pendingTrimCanvasCache = newLimit
                        } else {
                            onMaxCanvasCacheSizeChange(newLimit)
                        }
                    },
                )
                PreferenceEntry(
                    title = { Text(stringResource(R.string.clear_canvas_cache)) },
                    onClick = { clearCanvasCacheDialog = true },
                )
            }
        )

        if (clearCanvasCacheDialog) {
            ActionPromptDialog(
                title = stringResource(R.string.clear_canvas_cache),
                onDismiss = { clearCanvasCacheDialog = false },
                onConfirm = {
                    CanvasArtworkPlaybackCache.clear()
                    clearCanvasCacheDialog = false
                },
                onCancel = { clearCanvasCacheDialog = false },
                content = {
                    Text(text = stringResource(R.string.clear_canvas_cache_dialog))
                }
            )
        }

        if (pendingTrimCanvasCache != null) {
            ActionPromptDialog(
                title = stringResource(R.string.max_cache_size),
                onDismiss = { pendingTrimCanvasCache = null },
                onConfirm = {
                    onMaxCanvasCacheSizeChange(pendingTrimCanvasCache!!)
                    pendingTrimCanvasCache = null
                },
                onCancel = { pendingTrimCanvasCache = null },
                content = {
                    Text(text = "Se eliminarán los elementos necesarios para igualar el límite seleccionado.")
                }
            )
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.storage)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        }
    )
}

@Composable
fun CacheCard(
    icon: Int,
    title: String,
    description: String,
    progress: Float?,
    actions: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = MaterialTheme.shapes.large
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Card(
                    modifier = Modifier.padding(end = 12.dp),
                    shape = MaterialTheme.shapes.small,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                ) {
                    androidx.compose.foundation.layout.Box(modifier = Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(icon),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (progress != null) {
                Spacer(Modifier.padding(top = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        strokeCap = StrokeCap.Round
                    )
                    // percent label
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.padding(4.dp))
            actions()
        }
    }
}
