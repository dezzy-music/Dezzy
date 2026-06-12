/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.arturo254.opentune.listentogether

import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.serializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

enum class LogLevel {
    INFO,
    WARNING,
    ERROR,
    DEBUG
}

/**
 * Message format for encoding/decoding
 */
enum class MessageFormat {
    JSON,
    PROTOBUF
}

/**
 * Codec for encoding and decoding messages in JSON
 */
class MessageCodec(
    var format: MessageFormat = MessageFormat.JSON,
    var compressionEnabled: Boolean = false,
    var logger: ((LogLevel, String, String?) -> Unit)? = null
) {
    companion object {
        private const val TAG = "MessageCodec"
        private const val COMPRESSION_THRESHOLD = 100 // Only compress if > 100 bytes
        
        fun detectMessageFormat(data: ByteArray): MessageFormat {
            if (data.isEmpty()) return MessageFormat.JSON
            // A simple heuristic: if it starts with a printable character, probably JSON
            return if (data[0] == '{'.code.toByte() || data[0] == '['.code.toByte()) {
                MessageFormat.JSON
            } else {
                MessageFormat.PROTOBUF
            }
        }
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }
    
    /**
     * Encode a message in JSON format
     */
    fun encode(msgType: String, payload: Any?): ByteArray {
        return encodeJson(msgType, payload)
    }
    
    /**
     * Decode a message in JSON format
     */
    fun decode(data: ByteArray): Pair<String, ByteArray> {
        return decodeJson(data)
    }
    
    /**
     * Encode message
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun encodeJson(msgType: String, payload: Any?): ByteArray {
        val payloadBytes = when (format) {
            MessageFormat.PROTOBUF -> payload?.let { ProtoBuf.encodeToByteArray(serializer(it), it) }
            MessageFormat.JSON -> payload?.let { json.encodeToString(serializer(it), it).toByteArray() }
        }

        val msg = Message(
            type = msgType,
            payload = payloadBytes
        )
        
        var data = when (format) {
            MessageFormat.PROTOBUF -> ProtoBuf.encodeToByteArray(Message.serializer(), msg)
            MessageFormat.JSON -> json.encodeToString(Message.serializer(), msg).toByteArray()
        }
        
        if (compressionEnabled && data.size > COMPRESSION_THRESHOLD) {
            val compressed = compressData(data)
            if (compressed.size < data.size) {
                data = compressed
            }
        }
        
        return data
    }
    
    /**
     * Decode message
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun decodeJson(data: ByteArray): Pair<String, ByteArray> {
        val actualData = if (compressionEnabled && data.size > 2 && 
                             data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) {
            decompressData(data) ?: data
        } else {
            data
        }
        
        val detectedFormat = detectMessageFormat(actualData)
        
        val msg = when (detectedFormat) {
            MessageFormat.PROTOBUF -> ProtoBuf.decodeFromByteArray(serializer<Message>(), actualData)
            MessageFormat.JSON -> json.decodeFromString<Message>(actualData.decodeToString())
        }
        
        val payloadBytes = msg.payload ?: byteArrayOf()
        
        return Pair(msg.type, payloadBytes)
    }
    
    /**
     * Compress data using GZIP
     */
    private fun compressData(data: ByteArray): ByteArray {
        val outputStream = ByteArrayOutputStream()
        GZIPOutputStream(outputStream).use { gzip ->
            gzip.write(data)
        }
        return outputStream.toByteArray()
    }
    
    /**
     * Decompress GZIP data
     */
    private fun decompressData(data: ByteArray): ByteArray? {
        return try {
            val inputStream = ByteArrayInputStream(data)
            GZIPInputStream(inputStream).use { gzip ->
                gzip.readBytes()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to decompress data")
            null
        }
    }
    
    /**
     * Decode payload to Kotlin objects
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun decodePayload(msgType: String, payloadBytes: ByteArray, format: MessageFormat): Any? {
        if (payloadBytes.isEmpty()) return null
        
        val isJson = format == MessageFormat.JSON
        val payloadString = if (isJson) payloadBytes.decodeToString() else ""
        
        return try {
            when (msgType) {
                MessageTypes.ROOM_CREATED -> if (isJson) json.decodeFromString<RoomCreatedPayload>(payloadString) else ProtoBuf.decodeFromByteArray(serializer<RoomCreatedPayload>(), payloadBytes)
                MessageTypes.JOIN_REQUEST -> if (isJson) json.decodeFromString<JoinRequestPayload>(payloadString) else ProtoBuf.decodeFromByteArray(serializer<JoinRequestPayload>(), payloadBytes)
                MessageTypes.JOIN_APPROVED -> if (isJson) json.decodeFromString<JoinApprovedPayload>(payloadString) else ProtoBuf.decodeFromByteArray(serializer<JoinApprovedPayload>(), payloadBytes)
                MessageTypes.JOIN_REJECTED -> if (isJson) json.decodeFromString<JoinRejectedPayload>(payloadString) else ProtoBuf.decodeFromByteArray(serializer<JoinRejectedPayload>(), payloadBytes)
                MessageTypes.USER_JOINED -> if (isJson) json.decodeFromString<UserJoinedPayload>(payloadString) else ProtoBuf.decodeFromByteArray(serializer<UserJoinedPayload>(), payloadBytes)
                MessageTypes.USER_LEFT -> if (isJson) json.decodeFromString<UserLeftPayload>(payloadString) else ProtoBuf.decodeFromByteArray(serializer<UserLeftPayload>(), payloadBytes)
                MessageTypes.SYNC_PLAYBACK -> if (isJson) json.decodeFromString<PlaybackActionPayload>(payloadString) else ProtoBuf.decodeFromByteArray(serializer<PlaybackActionPayload>(), payloadBytes)
                MessageTypes.BUFFER_WAIT -> if (isJson) json.decodeFromString<BufferWaitPayload>(payloadString) else ProtoBuf.decodeFromByteArray(serializer<BufferWaitPayload>(), payloadBytes)
                MessageTypes.BUFFER_COMPLETE -> if (isJson) json.decodeFromString<BufferCompletePayload>(payloadString) else ProtoBuf.decodeFromByteArray(serializer<BufferCompletePayload>(), payloadBytes)
                MessageTypes.ERROR -> if (isJson) json.decodeFromString<ErrorPayload>(payloadString) else ProtoBuf.decodeFromByteArray(serializer<ErrorPayload>(), payloadBytes)
                MessageTypes.HOST_CHANGED -> if (isJson) json.decodeFromString<HostChangedPayload>(payloadString) else ProtoBuf.decodeFromByteArray(serializer<HostChangedPayload>(), payloadBytes)
                MessageTypes.KICKED -> if (isJson) json.decodeFromString<KickedPayload>(payloadString) else ProtoBuf.decodeFromByteArray(serializer<KickedPayload>(), payloadBytes)
                MessageTypes.SYNC_STATE -> if (isJson) json.decodeFromString<SyncStatePayload>(payloadString) else ProtoBuf.decodeFromByteArray(serializer<SyncStatePayload>(), payloadBytes)
                MessageTypes.RECONNECTED -> if (isJson) json.decodeFromString<ReconnectedPayload>(payloadString) else ProtoBuf.decodeFromByteArray(serializer<ReconnectedPayload>(), payloadBytes)
                MessageTypes.USER_RECONNECTED -> if (isJson) json.decodeFromString<UserReconnectedPayload>(payloadString) else ProtoBuf.decodeFromByteArray(serializer<UserReconnectedPayload>(), payloadBytes)
                MessageTypes.USER_DISCONNECTED -> if (isJson) json.decodeFromString<UserDisconnectedPayload>(payloadString) else ProtoBuf.decodeFromByteArray(serializer<UserDisconnectedPayload>(), payloadBytes)
                MessageTypes.SUGGESTION_RECEIVED -> if (isJson) json.decodeFromString<SuggestionReceivedPayload>(payloadString) else ProtoBuf.decodeFromByteArray(serializer<SuggestionReceivedPayload>(), payloadBytes)
                MessageTypes.SUGGESTION_APPROVED -> if (isJson) json.decodeFromString<SuggestionApprovedPayload>(payloadString) else ProtoBuf.decodeFromByteArray(serializer<SuggestionApprovedPayload>(), payloadBytes)
                MessageTypes.SUGGESTION_REJECTED -> if (isJson) json.decodeFromString<SuggestionRejectedPayload>(payloadString) else ProtoBuf.decodeFromByteArray(serializer<SuggestionRejectedPayload>(), payloadBytes)
                MessageTypes.CHAT -> if (isJson) json.decodeFromString<ChatMessagePayload>(payloadString) else ProtoBuf.decodeFromByteArray(serializer<ChatMessagePayload>(), payloadBytes)
                MessageTypes.TYPING_STATUS -> if (isJson) json.decodeFromString<TypingPayload>(payloadString) else ProtoBuf.decodeFromByteArray(serializer<TypingPayload>(), payloadBytes)
                MessageTypes.MESSAGE_EDITED -> if (isJson) json.decodeFromString<ChatMessagePayload>(payloadString) else ProtoBuf.decodeFromByteArray(serializer<ChatMessagePayload>(), payloadBytes)
                MessageTypes.CHAT_ACK -> if (isJson) json.decodeFromString<ChatAckPayload>(payloadString) else ProtoBuf.decodeFromByteArray(serializer<ChatAckPayload>(), payloadBytes)
                else -> null
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to decode payload for type $msgType")
            null
        }
    }
    
    @Suppress("UNCHECKED_CAST")
    private fun <T> serializer(value: T): kotlinx.serialization.KSerializer<T> {
        return when (value) {
            is CreateRoomPayload -> CreateRoomPayload.serializer()
            is JoinRoomPayload -> JoinRoomPayload.serializer()
            is ApproveJoinPayload -> ApproveJoinPayload.serializer()
            is RejectJoinPayload -> RejectJoinPayload.serializer()
            is PlaybackActionPayload -> PlaybackActionPayload.serializer()
            is BufferReadyPayload -> BufferReadyPayload.serializer()
            is KickUserPayload -> KickUserPayload.serializer()
            is SuggestTrackPayload -> SuggestTrackPayload.serializer()
            is ApproveSuggestionPayload -> ApproveSuggestionPayload.serializer()
            is RejectSuggestionPayload -> RejectSuggestionPayload.serializer()
            is ReconnectPayload -> ReconnectPayload.serializer()
            is TransferHostPayload -> TransferHostPayload.serializer()
            is ChatPayload -> ChatPayload.serializer()
            is ChatMessagePayload -> ChatMessagePayload.serializer()
            is TypingPayload -> TypingPayload.serializer()
            is EditMessagePayload -> EditMessagePayload.serializer()
            is ChatAckPayload -> ChatAckPayload.serializer()
            else -> throw IllegalArgumentException("Unknown type: ${value!!::class.simpleName}")
        } as kotlinx.serialization.KSerializer<T>
    }
}
