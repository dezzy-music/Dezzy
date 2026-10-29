/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.listentogether

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
        var actualPayload = payload
        
        // Apply Go backend's hack: wrap CHAT messages as JSON string inside ErrorPayload
        if (msgType == MessageTypes.CHAT && format == MessageFormat.PROTOBUF && payload != null) {
            val jsonString = json.encodeToString(serializer(payload), payload)
            actualPayload = ErrorPayload(code = "", message = jsonString)
        }
        
        var payloadBytes = when (format) {
            MessageFormat.PROTOBUF -> actualPayload?.let { ProtoBuf.encodeToByteArray(serializer(it), it) }
            MessageFormat.JSON -> actualPayload?.let { json.encodeToString(serializer(it), it).toByteArray() }
        }

        var isCompressed = false
        if (compressionEnabled && payloadBytes != null && payloadBytes.size > COMPRESSION_THRESHOLD) {
            val compressed = compressData(payloadBytes)
            if (compressed.size < payloadBytes.size) {
                payloadBytes = compressed
                isCompressed = true
            }
        }

        val msg = Message(
            type = msgType,
            payload = payloadBytes,
            compressed = isCompressed
        )
        
        return when (format) {
            MessageFormat.PROTOBUF -> ProtoBuf.encodeToByteArray(Message.serializer(), msg)
            MessageFormat.JSON -> json.encodeToString(Message.serializer(), msg).toByteArray()
        }
    }
    
    /**
     * Decode message
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun decodeJson(data: ByteArray): Pair<String, ByteArray> {
        val detectedFormat = detectMessageFormat(data)
        
        val msg = when (detectedFormat) {
            MessageFormat.PROTOBUF -> ProtoBuf.decodeFromByteArray(serializer<Message>(), data)
            MessageFormat.JSON -> json.decodeFromString<Message>(data.decodeToString())
        }
        
        var payloadBytes = msg.payload ?: byteArrayOf()
        
        if (msg.compressed && payloadBytes.isNotEmpty()) {
            payloadBytes = decompressData(payloadBytes) ?: payloadBytes
        }
        
        // Reverse the Go backend's hack: if it's a CHAT message, it comes wrapped in ErrorPayload
        if (msg.type == MessageTypes.CHAT && format == MessageFormat.PROTOBUF && payloadBytes.isNotEmpty()) {
            try {
                val errorPayload = ProtoBuf.decodeFromByteArray(serializer<ErrorPayload>(), payloadBytes)
                if (errorPayload.code == "__json__") {
                    payloadBytes = errorPayload.message.toByteArray()
                    // Now it's a JSON string bytes of ChatMessagePayload, so we must force decodePayload to read it as JSON
                    // We'll handle this in decodePayload itself by passing the JSON string directly.
                    // Wait, decodePayload checks `format`. If `format` is PROTOBUF, it will try to Protobuf-decode it!
                    // Let's just return the JSON string bytes here, and we'll fix decodePayload.
                }
            } catch (e: Exception) {
                // Ignore and fall through, maybe it's not wrapped
            }
        }
        
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
        
        val isJson = format == MessageFormat.JSON || (payloadBytes.isNotEmpty() && payloadBytes[0] == '{'.code.toByte())
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
            is ErrorPayload -> ErrorPayload.serializer()
            else -> throw IllegalArgumentException("Unknown type: ${value!!::class.simpleName}")
        } as kotlinx.serialization.KSerializer<T>
    }
}
