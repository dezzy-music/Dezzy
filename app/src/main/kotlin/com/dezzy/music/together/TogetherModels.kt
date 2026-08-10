/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.together

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
@Immutable
data class TogetherTrack(
    val id: String,
    val title: String,
    val artists: List<String> = emptyList(),
    val durationSec: Int = -1,
    val thumbnailUrl: String? = null,
)

@Serializable
@Immutable
data class TogetherParticipant(
    val id: String,
    val name: String,
    val isHost: Boolean = false,
    val isPending: Boolean = false,
    val isConnected: Boolean = true,
)

@Serializable
@Immutable
data class TogetherRoomSettings(
    val allowGuestsToAddTracks: Boolean = true,
    val allowGuestsToControlPlayback: Boolean = false,
    val requireHostApprovalToJoin: Boolean = false,
    val isPublic: Boolean = false,
    val djModeEnabled: Boolean = false,
)

@Serializable
@Immutable
data class TogetherRoomState(
    val sessionId: String,
    val hostId: String,
    val participants: List<TogetherParticipant> = emptyList(),
    val settings: TogetherRoomSettings = TogetherRoomSettings(),
    val queue: List<TogetherTrack> = emptyList(),
    val queueHash: String = "",
    val currentIndex: Int = 0,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val repeatMode: Int = 0,
    val shuffleEnabled: Boolean = false,
    val sentAtElapsedRealtimeMs: Long = 0L,
    val theme: TogetherRoomTheme = TogetherRoomTheme.MIXED,
    val currentVote: TogetherVote? = null,
)

@Immutable
sealed class TogetherRole {
    data object Host : TogetherRole()
    data object Guest : TogetherRole()
}

@Immutable
sealed class TogetherSessionState {
    data object Idle : TogetherSessionState()
    data class Hosting(
        val sessionId: String,
        val joinLink: String,
        val localAddressHint: String?,
        val port: Int,
        val settings: TogetherRoomSettings,
        val roomState: TogetherRoomState?,
    ) : TogetherSessionState()

    data class HostingOnline(
        val sessionId: String,
        val code: String,
        val settings: TogetherRoomSettings,
        val roomState: TogetherRoomState?,
    ) : TogetherSessionState()

    data class Joining(
        val joinLink: String,
    ) : TogetherSessionState()

    data class JoiningOnline(
        val code: String,
    ) : TogetherSessionState()

    data class Joined(
        val role: TogetherRole,
        val sessionId: String,
        val selfParticipantId: String,
        val roomState: TogetherRoomState,
    ) : TogetherSessionState()

    data class Error(
        val message: String,
        val recoverable: Boolean = true,
    ) : TogetherSessionState()
}

@Serializable
enum class TogetherRoomTheme {
    LOFI,
    REGGAETON,
    POP,
    ELECTRONIC,
    HIP_HOP,
    RNB,
    LATIN,
    KPOP,
    ROCK,
    JAZZ,
    CLASSICAL,
    MIXED,
}

@Serializable
@Immutable
data class TogetherVote(
    val voteId: String,
    val proposedByParticipantId: String,
    val proposedByName: String,
    val track: TogetherTrack,
    val votes: Map<String, Boolean> = emptyMap(), // participantId -> true=up, false=down
    val expiresAtMs: Long,
    val resolved: Boolean = false,
    val accepted: Boolean = false,
)

@Serializable
@Immutable
data class TogetherPublicRoom(
    val sessionId: String,
    val code: String,
    val hostName: String,
    val theme: TogetherRoomTheme,
    val participantCount: Int,
    val currentTrackTitle: String? = null,
    val currentTrackArtist: String? = null,
    val currentTrackThumbnail: String? = null,
    val isPublic: Boolean = true,
)
