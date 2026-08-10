/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.ui.screens.social

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.innertube.YouTube
import com.dezzy.music.innertube.models.WatchEndpoint
import com.dezzy.music.playback.queues.YouTubeQueue
import com.dezzy.music.social.DezzySocial
import com.dezzy.music.social.MusicProfile
import com.dezzy.music.social.TopEntity
import com.dezzy.music.models.toMediaMetadata
import kotlinx.coroutines.launch

/**
 * Perfil musical público: quién es, qué escucha ahora y qué escucha siempre.
 *
 * El "top" sale de `dezzy.listening_stats`, que alimenta el reproductor en cada
 * canción — no hay que pedirle al usuario que rellene nada, el perfil se
 * construye solo con el uso.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicProfileScreen(navController: NavController, userId: String) {
    val scope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current

    var profile by remember(userId) { mutableStateOf<MusicProfile?>(null) }
    var topArtists by remember(userId) { mutableStateOf<List<TopEntity>>(emptyList()) }
    var topSongs by remember(userId) { mutableStateOf<List<TopEntity>>(emptyList()) }
    var loading by remember(userId) { mutableStateOf(true) }

    LaunchedEffect(userId) {
        if (userId.isBlank()) { loading = false; return@LaunchedEffect }
        profile = DezzySocial.getProfile(userId)
        topArtists = DezzySocial.getTop(userId, "artist", 10)
        topSongs = DezzySocial.getTop(userId, "song", 10)
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = profile?.name ?: "Perfil",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        when {
            loading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            profile == null -> SocialEmptyState(
                iconRes = R.drawable.social_profile,
                title = "Perfil no disponible",
                description = "No se pudo cargar este perfil. Comprueba tu conexión.",
                modifier = Modifier.padding(padding),
            )

            else -> {
                val p = profile!!
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(bottom = 32.dp),
                ) {
                    item { ProfileHeader(p) { following ->
                        scope.launch {
                            DezzySocial.follow(p.userId, following)
                            profile = p.copy(
                                iFollow = following,
                                followers = (p.followers + if (following) 1 else -1).coerceAtLeast(0),
                            )
                        }
                    } }

                    // Lo que suena ahora, si su visibilidad me deja verlo.
                    if (!p.nowPlaying.isNullOrBlank()) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ArtworkThumb(url = p.nowArtwork, size = 56.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        NowPlayingBadge()
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            text = p.nowPlaying,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = p.nowArtist.orEmpty(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (topArtists.isNotEmpty()) {
                        item { TopSectionHeader("Artistas que más escucha") }
                        items(topArtists, key = { "a_${it.entityId}" }) { entity ->
                            TopRow(entity) {
                                navController.navigate("artist/${entity.entityId}")
                            }
                        }
                    }

                    if (topSongs.isNotEmpty()) {
                        item { TopSectionHeader("Canciones que más escucha") }
                        items(topSongs, key = { "s_${it.entityId}" }) { entity ->
                            TopRow(entity) {
                                scope.launch {
                                    val song = runCatching {
                                        YouTube.queue(listOf(entity.entityId)).getOrNull()?.firstOrNull()
                                    }.getOrNull()
                                    playerConnection?.playQueue(
                                        YouTubeQueue(
                                            endpoint = WatchEndpoint(videoId = entity.entityId),
                                            preloadItem = song?.toMediaMetadata(),
                                        )
                                    )
                                }
                            }
                        }
                    }

                    if (topArtists.isEmpty() && topSongs.isEmpty()) {
                        item {
                            SocialEmptyState(
                                iconRes = R.drawable.music_note,
                                title = "Sin historial todavía",
                                description = "Cuando escuche música, su top aparecerá aquí.",
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileHeader(profile: MusicProfile, onFollowChange: (Boolean) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ListeningAvatar(
            avatarUrl = profile.avatarUrl,
            isPlaying = !profile.nowPlaying.isNullOrBlank(),
            size = 96.dp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = profile.name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        if (!profile.username.isNullOrBlank()) {
            Text(
                text = "@${profile.username}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!profile.bio.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = profile.bio,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            CountStat(profile.followers, "seguidores")
            CountStat(profile.following, "siguiendo")
        }

        Spacer(Modifier.height(16.dp))
        if (profile.iFollow) {
            OutlinedButton(onClick = { onFollowChange(false) }) { Text("Siguiendo") }
        } else {
            Button(onClick = { onFollowChange(true) }) { Text("Seguir") }
        }
    }
}

@Composable
private fun CountStat(value: Long, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TopSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun TopRow(entity: TopEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkThumb(url = entity.artworkUrl, size = 48.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = entity.entityName,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${entity.playCount}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
