package com.dezzy.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dezzy.music.R
import com.dezzy.music.db.entities.Song
import androidx.navigation.NavController
import com.dezzy.music.playback.PlayerConnection
import com.dezzy.music.playback.queues.YouTubeQueue
import com.dezzy.music.playback.queues.ListQueue
import com.dezzy.music.models.toMediaMetadata
import com.dezzy.music.extensions.toMediaItem

enum class DezzyAction(val label: String, val iconResId: Int) {
    SORPRENDEME("Sorpréndeme", R.drawable.ic_magic_sparkle),
    TENDENCIAS("Tendencias", R.drawable.ic_chart_trending),
    MI_RADIO("Mi Radio", R.drawable.ic_radio_waves),
    RELAX("Relax", R.drawable.ic_mood_relax),
    ENERGIA("Energía", R.drawable.ic_mood_energy),
    FOCUS("Focus", R.drawable.ic_mood_focus),
    FIESTA("Fiesta", R.drawable.ic_mood_party),
}

@Composable
fun DezzyActionSlider(
    navController: NavController,
    playerConnection: PlayerConnection,
    quickPicks: List<Song>?,
    modifier: Modifier = Modifier
) {
    // rememberUpdatedState ensures the lambdas always see the latest value
    val currentQuickPicks = rememberUpdatedState(quickPicks)
    val currentPlayerConnection = rememberUpdatedState(playerConnection)
    val currentNavController = rememberUpdatedState(navController)

    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        items(DezzyAction.entries.size) { index ->
            val action = DezzyAction.entries[index]
            ActionPill(
                label = action.label,
                iconResId = action.iconResId,
                onClick = {
                    handleAction(
                        action = action,
                        navController = currentNavController.value,
                        playerConnection = currentPlayerConnection.value,
                        quickPicks = currentQuickPicks.value
                    )
                }
            )
        }
    }
}

private fun handleAction(
    action: DezzyAction,
    navController: NavController,
    playerConnection: PlayerConnection,
    quickPicks: List<Song>?
) {
    when (action) {
        DezzyAction.SORPRENDEME -> {
            val picks = quickPicks?.shuffled() ?: emptyList()
            if (picks.isNotEmpty()) {
                playerConnection.playQueue(
                    ListQueue(
                        title = "Sorpréndeme",
                        items = picks.map { it.toMediaItem() },
                        startIndex = 0
                    )
                )
            }
        }

        DezzyAction.TENDENCIAS -> {
            navController.navigate("charts_screen")
        }

        DezzyAction.MI_RADIO -> {
            val firstPick = quickPicks?.firstOrNull()
            if (firstPick != null) {
                playerConnection.playQueue(
                    YouTubeQueue.radio(firstPick.toMediaMetadata())
                )
            }
        }

        DezzyAction.RELAX -> {
            navController.navigate("search/Relax music")
        }

        DezzyAction.ENERGIA -> {
            navController.navigate("search/Workout energy music")
        }

        DezzyAction.FOCUS -> {
            navController.navigate("search/Focus concentration music")
        }

        DezzyAction.FIESTA -> {
            navController.navigate("search/Party dance music")
        }
    }
}

@Composable
private fun ActionPill(
    label: String,
    iconResId: Int,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(id = iconResId),
                contentDescription = label,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
