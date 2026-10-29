package com.dezzy.music.listentogether

import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.Test
import org.junit.Assert.*
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.decodeFromByteArray

class CodecTest {

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun testCodecSerialization() {
        val payload = CreateRoomPayload("testUser")
        val payloadBytes = ProtoBuf.encodeToByteArray(CreateRoomPayload.serializer(), payload)
        
        val msg = Message(
            type = MessageTypes.CREATE_ROOM,
            payload = payloadBytes,
            compressed = false
        )
        
        val envelopeBytes = ProtoBuf.encodeToByteArray(Message.serializer(), msg)
        
        // Print the hex of the encoded envelope
        println("ENVELOPE HEX: " + envelopeBytes.joinToString("") { "%02x".format(it) })
        
        // Let's decode it back
        val decodedMsg = ProtoBuf.decodeFromByteArray(Message.serializer(), envelopeBytes)
        assertEquals(MessageTypes.CREATE_ROOM, decodedMsg.type)
        assertEquals(false, decodedMsg.compressed)
        assertNotNull(decodedMsg.payload)
        
        val decodedPayload = ProtoBuf.decodeFromByteArray(CreateRoomPayload.serializer(), decodedMsg.payload!!)
        assertEquals("testUser", decodedPayload.username)
        
        println("Serialization cycle successful!")
    }
}
