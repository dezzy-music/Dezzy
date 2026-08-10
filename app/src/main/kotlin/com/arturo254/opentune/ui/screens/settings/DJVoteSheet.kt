/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.arturo254.opentune.ui.screens.settings

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.arturo254.opentune.together.TogetherVote
import kotlinx.coroutines.delay

/**
 * DJ Vote Sheet — shows when a guest proposes a song for the queue.
 * Appears as an overlay within the Together session. The host and all guests
 * see this and can vote up or down within a 15-second window.
 */
@Composable
fun DJVoteSheet(
    vote: TogetherVote,
    selfParticipantId: String,
    onUpvote: () -> Unit,
    onDownvote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val selfVote = vote.votes[selfParticipantId]
    val hasVoted = selfVote != null

    val upvotes = vote.votes.values.count { it }
    val downvotes = vote.votes.values.count { !it }
    val totalVoters = vote.votes.size.coerceAtLeast(1)

    // Countdown timer (15 seconds from when the vote was created)
    var timeLeftMs by remember { mutableFloatStateOf(15_000f) }
    val remainingFraction by animateFloatAsState(
        targetValue = timeLeftMs / 15_000f,
        animationSpec = tween(300, easing = LinearEasing),
        label = "timerFraction"
    )

    LaunchedEffect(vote.voteId) {
        val deadline = vote.expiresAtMs
        while (System.currentTimeMillis() < deadline) {
            timeLeftMs = (deadline - System.currentTimeMillis()).coerceAtLeast(0L).toFloat()
            delay(100)
        }
        timeLeftMs = 0f
    }

    val timerColor = when {
        remainingFraction > 0.5f -> Color(0xFF4CAF50)
        remainingFraction > 0.25f -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Surface(shape = CircleShape, color = Color(0xFFFF9800).copy(alpha = 0.15f)) {
                    Text("🎧", modifier = Modifier.padding(8.dp), fontSize = 20.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${vote.proposedByName} propone:",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    Text(
                        text = "¡Vota para agregar a la cola!",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
                // Timer
                Text(
                    text = "${(timeLeftMs / 1000).toInt()}s",
                    style = MaterialTheme.typography.labelLarge,
                    color = timerColor,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(12.dp))

            // Track info
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = vote.track.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = vote.track.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = vote.track.artists.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    vote.track.durationSec.takeIf { it > 0 }?.let { sec ->
                        Text(
                            text = "%d:%02d".format(sec / 60, sec % 60),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Timer bar
            LinearProgressIndicator(
                progress = { remainingFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = timerColor,
                trackColor = timerColor.copy(alpha = 0.15f),
            )

            Spacer(Modifier.height(12.dp))

            // Vote buttons
            if (!hasVoted && !vote.resolved) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onUpvote()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4CAF50),
                            contentColor = Color.White,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("👍 Sí ($upvotes)", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDownvote()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("👎 No ($downvotes)")
                    }
                }
            } else {
                // Already voted or resolved
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (vote.resolved) {
                                if (vote.accepted) Color(0xFF4CAF50).copy(alpha = 0.15f)
                                else Color(0xFFF44336).copy(alpha = 0.15f)
                            } else {
                                if (selfVote == true) Color(0xFF4CAF50).copy(alpha = 0.1f)
                                else Color(0xFFF44336).copy(alpha = 0.1f)
                            }
                        )
                        .padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when {
                            vote.resolved && vote.accepted -> "✅ ¡Canción aceptada! Se añade a la cola"
                            vote.resolved && !vote.accepted -> "❌ La sala rechazó esta canción"
                            selfVote == true -> "👍 Votaste a favor ($upvotes vs $downvotes)"
                            else -> "👎 Votaste en contra ($downvotes vs $upvotes)"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }
}
