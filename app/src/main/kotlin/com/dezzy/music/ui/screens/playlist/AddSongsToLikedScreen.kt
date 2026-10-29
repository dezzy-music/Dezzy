/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.screens.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.dezzy.music.LocalDatabase
import com.dezzy.music.R
import com.dezzy.music.innertube.YouTube
import com.dezzy.music.innertube.models.SongItem
import com.dezzy.music.innertube.models.WatchEndpoint
import com.dezzy.music.models.toMediaMetadata
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSongsToLikedScreen(
    playlistId: String? = null,
    onDismiss: () -> Unit,
    onSearchClick: () -> Unit
) {
    val database = LocalDatabase.current
    var songs by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var previewSongId by remember { mutableStateOf<String?>(null) }
    var previewProgress by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val playerConnection = com.dezzy.music.LocalPlayerConnection.current

    LaunchedEffect(previewSongId) {
        if (previewSongId != null) {
            val player = playerConnection?.player
            if (player != null) {
                while(!player.isPlaying) {
                    kotlinx.coroutines.delay(50)
                }
                val startPos = player.currentPosition
                val targetPos = startPos + 30000L
                while(player.isPlaying && player.currentPosition < targetPos) {
                    kotlinx.coroutines.delay(50)
                    val elapsed = player.currentPosition - startPos
                    previewProgress = elapsed.toFloat() / 30000f
                }
                player.pause()
            }
            previewSongId = null
            previewProgress = 0f
        }
    }

    LaunchedEffect(playlistId) {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            isLoading = true
            
            // 1. Determine Context Songs
            val localSongs = if (playlistId == "LIKED") {
                database.songsByPlayTimeAsc().first().reversed().filter { it.song.liked }
            } else if (playlistId != null) {
                database.playlistSongs(playlistId).first().map { it.song }.reversed()
            } else {
                database.songsByPlayTimeAsc().first().reversed()
            }
            
            val ytResults = mutableListOf<SongItem>()
            
            // Take up to 3 most recently added/played songs to fetch related tracks from YouTube
            val seedSongs = localSongs.take(3)
            
            for (seed in seedSongs) {
                val nextResult = YouTube.next(WatchEndpoint(videoId = seed.song.id)).getOrNull()
                val relatedEndpoint = nextResult?.relatedEndpoint
                if (relatedEndpoint != null) {
                    val relatedPage = YouTube.related(relatedEndpoint).getOrNull()
                    if (relatedPage != null && relatedPage.songs.isNotEmpty()) {
                        ytResults.addAll(relatedPage.songs)
                    }
                }
            }
            
            // If we still have empty results, maybe fallback to a generic search or hit search
            if (ytResults.isEmpty()) {
                val hits = YouTube.search("hits music", YouTube.SearchFilter.FILTER_SONG).getOrNull()?.items?.filterIsInstance<SongItem>() ?: emptyList()
                ytResults.addAll(hits)
            }
            
            // 4. Filter out already downloaded or already in playlist
            val finalResults = ytResults
                .distinctBy { it.id } // Remove duplicates from multiple seeds
                .filter { item ->
                    val alreadyDownloaded = database.song(item.id).first() != null
                    !alreadyDownloaded
                }
            
            songs = finalResults
            isLoading = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF121212)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Text("Añadir a esta playlist", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(painterResource(R.drawable.arrow_back), contentDescription = "Back", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF121212),
                        titleContentColor = Color.White
                    )
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF282828))
                            .clickable { 
                                onDismiss()
                                onSearchClick() 
                            },
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = "Busca canciones que te puedan gustar",
                            color = Color.Gray,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Analizando recomendaciones de YouTube...", color = Color.Gray, fontSize = 14.sp)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(bottom = 80.dp)
                    ) {
                        items(songs) { songItem ->
                            val isPreviewing = previewSongId == songItem.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { 
                                        if (isPreviewing) {
                                            playerConnection?.player?.pause()
                                            previewSongId = null
                                        } else {
                                            previewSongId = songItem.id
                                            previewProgress = 0f
                                            playerConnection?.let { conn ->
                                                val durationSecs = songItem.duration ?: 0
                                                val startPos = if (durationSecs > 0) (durationSecs * 0.3 * 1000).toLong() else 0L
                                                
                                                conn.playQueue(com.dezzy.music.playback.queues.YouTubeQueue(com.dezzy.music.innertube.models.WatchEndpoint(videoId = songItem.id), songItem.toMediaMetadata()))
                                                conn.player.seekTo(startPos)
                                                conn.player.play()
                                            }
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.size(48.dp)) {
                                    AsyncImage(
                                        model = songItem.thumbnail,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.4f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(if (isPreviewing) R.drawable.pause else R.drawable.play),
                                            contentDescription = "Play",
                                            tint = Color.White,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        if (isPreviewing) {
                                            CircularProgressIndicator(
                                                progress = { previewProgress },
                                                modifier = Modifier.fillMaxSize(),
                                                color = MaterialTheme.colorScheme.primary,
                                                trackColor = Color.White.copy(alpha = 0.2f)
                                            )
                                        }
                                    }
                                }
                                
                                Spacer(modifier = Modifier.width(12.dp))
                                
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = songItem.title,
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        maxLines = 1
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (songItem.explicit) {
                                            Box(
                                                modifier = Modifier
                                                    .background(Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                            ) {
                                                Text("E", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                            }
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }
                                        Text(
                                            text = songItem.artists.joinToString { it.name }.takeIf { it.isNotBlank() } ?: "Unknown Artist",
                                            color = Color.Gray,
                                            fontSize = 14.sp,
                                            maxLines = 1
                                        )
                                    }
                                }
                                
                                Spacer(modifier = Modifier.width(12.dp))
                                
                                IconButton(onClick = {
                                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        database.insert(
                                            com.dezzy.music.db.entities.SongEntity(
                                                id = songItem.id,
                                                title = songItem.title,
                                                duration = songItem.duration ?: 0,
                                                thumbnailUrl = songItem.thumbnail,
                                                liked = if (playlistId == "LIKED") true else false,
                                                likedDate = if (playlistId == "LIKED") LocalDateTime.now() else null
                                            )
                                        )
                                        songItem.artists.forEach { artist ->
                                            database.insert(
                                                com.dezzy.music.db.entities.ArtistEntity(
                                                    id = artist.id ?: artist.name,
                                                    name = artist.name
                                                )
                                            )
                                            database.insert(
                                                com.dezzy.music.db.entities.SongArtistMap(
                                                    songId = songItem.id,
                                                    artistId = artist.id ?: artist.name,
                                                    position = 0
                                                )
                                            )
                                        }
                                        if (playlistId != null && playlistId != "LIKED") {
                                            val currentPosition = database.playlistSongs(playlistId).first().size
                                            database.insert(
                                                com.dezzy.music.db.entities.PlaylistSongMap(
                                                    playlistId = playlistId,
                                                    songId = songItem.id,
                                                    position = currentPosition
                                                )
                                            )
                                        }
                                        
                                        val currentSongs = songs.toMutableList()
                                        currentSongs.removeIf { it.id == songItem.id }
                                        songs = currentSongs
                                    }
                                }) {
                                    Icon(
                                        painter = painterResource(R.drawable.add),
                                        contentDescription = "Add",
                                        tint = Color.Gray,
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
