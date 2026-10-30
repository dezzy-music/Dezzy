/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.listentogether

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Message types for Listen Together protocol
 */
object MessageTypes {
    // Client -> Server
    const val CREATE_ROOM = "create_room"
    const val JOIN_ROOM = "join_room"
    const val LEAVE_ROOM = "leave_room"
    const val APPROVE_JOIN = "approve_join"
    const val REJECT_JOIN = "reject_join"
    const val PLAYBACK_ACTION = "playback_action"
    const val BUFFER_READY = "buffer_ready"
    const val KICK_USER = "kick_user"
    const val TRANSFER_HOST = "transfer_host"
    const val PING = "ping"
    const val CHAT = "chat"
    const val TYPING = "typing"
    const val EDIT_MESSAGE = "edit_message"
    const val CHAT_ACK = "chat_ack"
    const val REQUEST_SYNC = "request_sync"
    const val RECONNECT = "reconnect"
    const val SUGGEST_TRACK = "suggest_track"
    const val APPROVE_SUGGESTION = "approve_suggestion"
    const val REJECT_SUGGESTION = "reject_suggestion"

    // Server -> Client
    const val ROOM_CREATED = "room_created"
    const val JOIN_REQUEST = "join_request"
    const val JOIN_APPROVED = "join_approved"
    const val JOIN_REJECTED = "join_rejected"
    const val USER_JOINED = "user_joined"
    const val USER_LEFT = "user_left"
    const val SYNC_PLAYBACK = "sync_playback"
    const val BUFFER_WAIT = "buffer_wait"
    const val BUFFER_COMPLETE = "buffer_complete"
    const val ERROR = "error"
    const val PONG = "pong"
    const val HOST_CHANGED = "host_changed"
    const val KICKED = "kicked"
    const val SYNC_STATE = "sync_state"
    const val RECONNECTED = "reconnected"
    const val USER_RECONNECTED = "user_reconnected"
    const val USER_DISCONNECTED = "user_disconnected"
    const val SUGGESTION_RECEIVED = "suggestion_received"
    const val SUGGESTION_APPROVED = "suggestion_approved"
    const val SUGGESTION_REJECTED = "suggestion_rejected"
    const val CHAT_MESSAGE = "chat"
    const val TYPING_STATUS = "typing"
    const val MESSAGE_EDITED = "edit_message"
}

/**
 * Playback action types
 */
object PlaybackActions {
    const val PLAY = "play"
    const val PAUSE = "pause"
    const val SEEK = "seek"
    const val SKIP_NEXT = "skip_next"
    const val SKIP_PREV = "skip_prev"
    const val CHANGE_TRACK = "change_track"
    const val QUEUE_ADD = "queue_add"
    const val QUEUE_REMOVE = "queue_remove"
    const val QUEUE_CLEAR = "queue_clear"
    const val SYNC_QUEUE = "sync_queue"
    const val SET_VOLUME = "set_volume"
    const val SET_REPEAT_MODE = "set_repeat_mode"
    const val SET_SHUFFLE = "set_shuffle"
}

/**
 * Base message structure
 */
@Serializable
data class Message(
    val type: String = "",
    val payload: ByteArray? = null,
    val compressed: Boolean = false
)

/**
 * Track information
 */
@Serializable
data class TrackInfo(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String? = null,
    val duration: Long = 0L, // milliseconds
    val thumbnail: String? = null,
    @SerialName("suggested_by") val suggestedBy: String? = null
)

/**
 * User information
 */
@Serializable
data class UserInfo(
    @SerialName("user_id") val userId: String = "",
    val username: String = "",
    @SerialName("is_host") val isHost: Boolean = false,
    @SerialName("is_connected") val isConnected: Boolean = true
)

/**
 * Room state
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RoomState(
    @ProtoNumber(1) @SerialName("room_code") val roomCode: String = "",
    @ProtoNumber(2) @SerialName("host_id") val hostId: String = "",
    @ProtoNumber(3) val users: List<UserInfo> = emptyList(),
    @ProtoNumber(4) @SerialName("current_track") val currentTrack: TrackInfo? = null,
    @ProtoNumber(5) @SerialName("is_playing") val isPlaying: Boolean = false,
    @ProtoNumber(6) val position: Long = 0L, // milliseconds
    @ProtoNumber(7) @SerialName("last_update") val lastUpdate: Long = 0L, // unix timestamp ms
    @ProtoNumber(8) val volume: Float = 1f,
    @ProtoNumber(9) val queue: List<TrackInfo> = emptyList(),
    @ProtoNumber(10) @SerialName("seq_nbr") val seqNbr: Long = 0L,
    @ProtoNumber(12) @SerialName("repeat_mode") val repeatMode: Int = 0,
    @ProtoNumber(13) @SerialName("shuffle_enabled") val shuffleEnabled: Boolean = false
)

// Request payloads

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CreateRoomPayload(
    @ProtoNumber(1) val username: String = "",
    @ProtoNumber(2) @SerialName("is_public") val isPublic: Boolean = false,
    @ProtoNumber(3) val theme: String = "MIXED"
)

@Serializable
data class JoinRoomPayload(
    @SerialName("room_code") val roomCode: String = "",
    val username: String = ""
)

@Serializable
data class ApproveJoinPayload(
    @SerialName("user_id") val userId: String = ""
)

@Serializable
data class RejectJoinPayload(
    @SerialName("user_id") val userId: String = "",
    val reason: String? = null
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PlaybackActionPayload(
    @ProtoNumber(1) val action: String = "",
    @ProtoNumber(2) @SerialName("track_id") val trackId: String? = null,
    @ProtoNumber(3) val position: Long? = null, // milliseconds
    @ProtoNumber(4) @SerialName("track_info") val trackInfo: TrackInfo? = null,
    @ProtoNumber(5) @SerialName("insert_next") val insertNext: Boolean? = null,
    @ProtoNumber(6) val queue: List<TrackInfo>? = null,
    @ProtoNumber(7) @SerialName("queue_title") val queueTitle: String? = null,
    @ProtoNumber(8) val volume: Float? = null,
    @ProtoNumber(9) @SerialName("server_time") val serverTime: Long? = null,
    @ProtoNumber(10) @SerialName("seq_nbr") val seqNbr: Long? = null,
    @ProtoNumber(11) @SerialName("play_at") val playAt: Long? = null,
    @ProtoNumber(12) @SerialName("repeat_mode") val repeatMode: Int? = null,
    @ProtoNumber(13) @SerialName("shuffle_enabled") val shuffleEnabled: Boolean? = null
)

@Serializable
data class BufferReadyPayload(
    @SerialName("track_id") val trackId: String = ""
)

@Serializable
data class KickUserPayload(
    @SerialName("user_id") val userId: String = "",
    val reason: String? = null
)

@Serializable
data class TransferHostPayload(
    @SerialName("new_host_id") val newHostId: String = ""
)

@Serializable
data class ChatPayload(
    val message: String = "",
    @SerialName("message_id") val messageId: String? = null,
    @SerialName("reply_to_id") val replyToId: String? = null,
    @SerialName("reply_to_text") val replyToText: String? = null,
    val type: String = "text"  // text, sticker, audio
)

@Serializable
data class TypingPayload(
    @SerialName("is_typing") val isTyping: Boolean = false,
    @SerialName("user_id") val userId: String? = null,
    val username: String? = null
)

@Serializable
data class EditMessagePayload(
    @SerialName("message_id") val messageId: String = "",
    @SerialName("new_message") val newMessage: String = ""
)

@Serializable
data class ChatAckPayload(
    @SerialName("message_id") val messageId: String = "",
    val status: String = "" // "delivered", "read"
)

// Suggestions payloads

@Serializable
data class SuggestTrackPayload(
    @SerialName("track_info") val trackInfo: TrackInfo = TrackInfo()
)

@Serializable
data class SuggestionReceivedPayload(
    @SerialName("suggestion_id") val suggestionId: String = "",
    @SerialName("from_user_id") val fromUserId: String = "",
    @SerialName("from_username") val fromUsername: String = "",
    @SerialName("track_info") val trackInfo: TrackInfo = TrackInfo()
)

@Serializable
data class ApproveSuggestionPayload(
    @SerialName("suggestion_id") val suggestionId: String = ""
)

@Serializable
data class RejectSuggestionPayload(
    @SerialName("suggestion_id") val suggestionId: String = "",
    val reason: String? = null
)

@Serializable
data class SuggestionApprovedPayload(
    @SerialName("suggestion_id") val suggestionId: String = "",
    @SerialName("track_info") val trackInfo: TrackInfo = TrackInfo()
)

@Serializable
data class SuggestionRejectedPayload(
    @SerialName("suggestion_id") val suggestionId: String = "",
    val reason: String? = null
)

// Response payloads

@Serializable
data class RoomCreatedPayload(
    @SerialName("room_code") val roomCode: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("session_token") val sessionToken: String = ""
)

@Serializable
data class JoinRequestPayload(
    @SerialName("user_id") val userId: String = "",
    val username: String = ""
)

@Serializable
data class JoinApprovedPayload(
    @SerialName("room_code") val roomCode: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("session_token") val sessionToken: String = "",
    val state: RoomState = RoomState()
)

@Serializable
data class JoinRejectedPayload(
    val reason: String = ""
)

@Serializable
data class UserJoinedPayload(
    @SerialName("user_id") val userId: String = "",
    val username: String = ""
)

@Serializable
data class UserLeftPayload(
    @SerialName("user_id") val userId: String = "",
    val username: String = ""
)

@Serializable
data class BufferWaitPayload(
    @SerialName("track_id") val trackId: String = "",
    @SerialName("waiting_for") val waitingFor: List<String> = emptyList()
)

@Serializable
data class BufferCompletePayload(
    @SerialName("track_id") val trackId: String = ""
)

@Serializable
data class ErrorPayload(
    val code: String = "",
    val message: String = ""
)

@Serializable
data class ChatMessagePayload(
    @SerialName("user_id") val userId: String = "",
    val username: String = "",
    val message: String = "",
    val timestamp: Long = 0L,
    @SerialName("message_id") val messageId: String = "",
    @SerialName("reply_to_id") val replyToId: String? = null,
    @SerialName("reply_to_text") val replyToText: String? = null,
    @SerialName("edited_at") val editedAt: Long? = null,
    val type: String = "text",  // text, sticker, audio
    val status: String = "sent" // pending, sent, delivered, read
)

@Serializable
data class HostChangedPayload(
    @SerialName("new_host_id") val newHostId: String = "",
    @SerialName("new_host_name") val newHostName: String = ""
)

@Serializable
data class KickedPayload(
    val reason: String = ""
)

/**
 * Sync state payload - sent to guest when they request current state
 */
@Serializable
data class SyncStatePayload(
    @SerialName("current_track") val currentTrack: TrackInfo? = null,
    @SerialName("is_playing") val isPlaying: Boolean = false,
    val position: Long = 0L,
    @SerialName("last_update") val lastUpdate: Long = 0L,
    val queue: List<TrackInfo>? = null,
    val volume: Float? = null
)

// Reconnection payloads

@Serializable
data class ReconnectPayload(
    @SerialName("session_token") val sessionToken: String = ""
)

@Serializable
data class ReconnectedPayload(
    @SerialName("room_code") val roomCode: String = "",
    @SerialName("user_id") val userId: String = "",
    val state: RoomState = RoomState(),
    @SerialName("is_host") val isHost: Boolean = false
)

@Serializable
data class UserReconnectedPayload(
    @SerialName("user_id") val userId: String = "",
    val username: String = ""
)

@Serializable
data class UserDisconnectedPayload(
    @SerialName("user_id") val userId: String = "",
    val username: String = ""
)
