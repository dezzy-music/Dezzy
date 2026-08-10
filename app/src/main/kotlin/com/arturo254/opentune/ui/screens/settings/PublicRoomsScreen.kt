/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.arturo254.opentune.ui.screens.settings

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.arturo254.opentune.LocalPlayerAwareWindowInsets
import com.arturo254.opentune.R
import com.arturo254.opentune.together.TogetherPublicRoom
import com.arturo254.opentune.together.TogetherRoomTheme

data class ThemeFilter(
    val theme: TogetherRoomTheme?,
    val label: String,
    val emoji: String,
)

val themeFilters = listOf(
    ThemeFilter(null, "Todos", "🌍"),
    ThemeFilter(TogetherRoomTheme.LOFI, "Lofi", "☕"),
    ThemeFilter(TogetherRoomTheme.POP, "Pop", "⭐"),
    ThemeFilter(TogetherRoomTheme.HIP_HOP, "Hip-Hop", "🎤"),
    ThemeFilter(TogetherRoomTheme.ELECTRONIC, "Electronic", "🎧"),
    ThemeFilter(TogetherRoomTheme.REGGAETON, "Reggaeton", "🔥"),
    ThemeFilter(TogetherRoomTheme.LATIN, "Latin", "💃"),
    ThemeFilter(TogetherRoomTheme.KPOP, "K-Pop", "🌸"),
    ThemeFilter(TogetherRoomTheme.ROCK, "Rock", "🎸"),
    ThemeFilter(TogetherRoomTheme.RNB, "R&B", "🎶"),
    ThemeFilter(TogetherRoomTheme.JAZZ, "Jazz", "🎷"),
)

fun themeColor(theme: TogetherRoomTheme): Color = when (theme) {
    TogetherRoomTheme.LOFI -> Color(0xFF6C8EBF)
    TogetherRoomTheme.REGGAETON -> Color(0xFFFF6B35)
    TogetherRoomTheme.POP -> Color(0xFFFF69B4)
    TogetherRoomTheme.ELECTRONIC -> Color(0xFF00D4FF)
    TogetherRoomTheme.HIP_HOP -> Color(0xFFFFBE0B)
    TogetherRoomTheme.RNB -> Color(0xFFB388FF)
    TogetherRoomTheme.LATIN -> Color(0xFFFF5252)
    TogetherRoomTheme.KPOP -> Color(0xFFE040FB)
    TogetherRoomTheme.ROCK -> Color(0xFF78909C)
    TogetherRoomTheme.JAZZ -> Color(0xFFFFCC02)
    TogetherRoomTheme.CLASSICAL -> Color(0xFFD4AC0D)
    TogetherRoomTheme.MIXED -> Color(0xFF81C784)
}

fun themeEmoji(theme: TogetherRoomTheme): String = when (theme) {
    TogetherRoomTheme.LOFI -> "☕"
    TogetherRoomTheme.REGGAETON -> "🔥"
    TogetherRoomTheme.POP -> "⭐"
    TogetherRoomTheme.ELECTRONIC -> "🎧"
    TogetherRoomTheme.HIP_HOP -> "🎤"
    TogetherRoomTheme.RNB -> "🎶"
    TogetherRoomTheme.LATIN -> "💃"
    TogetherRoomTheme.KPOP -> "🌸"
    TogetherRoomTheme.ROCK -> "🎸"
    TogetherRoomTheme.JAZZ -> "🎷"
    TogetherRoomTheme.CLASSICAL -> "🎻"
    TogetherRoomTheme.MIXED -> "🌍"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublicRoomsScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val haptic = LocalHapticFeedback.current
    var selectedTheme by remember { mutableStateOf<TogetherRoomTheme?>(null) }

    // Public rooms feed — populated when backend integration is complete.
    // Currently shows hosted public room from the active session if available.
    val publicRooms: List<TogetherPublicRoom> = remember { emptyList() }

    val filteredRooms = if (selectedTheme == null) publicRooms
    else publicRooms.filter { it.theme == selectedTheme }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    )
                )
            )
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "🌐 Salas Públicas",
                    fontWeight = FontWeight.Bold,
                )
            },
            navigationIcon = {
                androidx.compose.material3.IconButton(onClick = { navController.navigateUp() }) {
                    Icon(painter = painterResource(R.drawable.arrow_back), contentDescription = null)
                }
            },
            scrollBehavior = scrollBehavior,
            modifier = Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            ),
        )

        // Theme filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            themeFilters.forEach { filter ->
                FilterChip(
                    selected = selectedTheme == filter.theme,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        selectedTheme = if (selectedTheme == filter.theme) null else filter.theme
                    },
                    label = { Text("${filter.emoji} ${filter.label}") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = filter.theme?.let { themeColor(it) }?.copy(alpha = 0.2f)
                            ?: MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = filter.theme?.let { themeColor(it) }
                            ?: MaterialTheme.colorScheme.primary,
                    ),
                )
            }
        }

        if (filteredRooms.isEmpty()) {
            // Empty state — prompt to create a public room
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(32.dp),
                ) {
                    Text("🎵", fontSize = 56.sp)
                    Text(
                        text = "No hay salas públicas activas",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "¡Sé el primero! Crea una sala pública y comparte tu música con el mundo.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { navController.navigate("settings/music_together") },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.add),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Crear sala pública")
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                ),
            ) {
                items(filteredRooms, key = { it.sessionId }) { room ->
                    PublicRoomCard(
                        room = room,
                        onJoin = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            // The music_together route takes no joinCode arg; navigate to the
                            // registered route (settings/music_together) to avoid a crash.
                            navController.navigate("settings/music_together")
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun PublicRoomCard(
    room: TogetherPublicRoom,
    onJoin: () -> Unit,
) {
    val color = themeColor(room.theme)
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(color.copy(alpha = 0.12f), Color.Transparent)
                    )
                )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Theme badge
                    Surface(
                        shape = CircleShape,
                        color = color.copy(alpha = 0.15f),
                        modifier = Modifier.scale(pulse),
                    ) {
                        Text(
                            text = themeEmoji(room.theme),
                            modifier = Modifier.padding(10.dp),
                            fontSize = 22.sp,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = room.theme.name.lowercase().replaceFirstChar { it.uppercase() } + " Room",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Host: ${room.hostName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    // Listener count
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.group),
                            contentDescription = null,
                            tint = color,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "${room.participantCount}",
                            style = MaterialTheme.typography.labelLarge,
                            color = color,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                // Current track
                room.currentTrackTitle?.let { title ->
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(color.copy(alpha = 0.08f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.graphic_eq),
                            contentDescription = null,
                            tint = color,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            room.currentTrackArtist?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onJoin,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = color,
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("Unirse a la sala", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
