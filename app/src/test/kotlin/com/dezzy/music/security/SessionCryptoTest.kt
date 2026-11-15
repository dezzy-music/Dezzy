package com.dezzy.music.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCryptoTest {

    @Test
    fun `encrypt produces enc_v1 prefix and decrypt recovers original plaintext`() {
        val originalCookie = "VISITOR_INFO1_LIVE=abc123xyz; SAPISID=sec_token_999; SID=session_id"
        val encrypted = SessionCrypto.encrypt(originalCookie)

        assertTrue(encrypted.startsWith("enc:v1:"))
        assertTrue(SessionCrypto.isEncrypted(encrypted))
        assertNotEquals(originalCookie, encrypted)

        val decrypted = SessionCrypto.decrypt(encrypted)
        assertEquals(originalCookie, decrypted)
    }

    @Test
    fun `decrypt passes through legacy unencrypted strings transparently`() {
        val legacyCookie = "SAPISID=legacy_plaintext_cookie; SID=123"
        assertFalse(SessionCrypto.isEncrypted(legacyCookie))

        val result = SessionCrypto.decrypt(legacyCookie)
        assertEquals(legacyCookie, result)
    }

    @Test
    fun `blank or empty strings remain intact`() {
        assertEquals("", SessionCrypto.encrypt(""))
        assertEquals("", SessionCrypto.decrypt(""))
        assertEquals("   ", SessionCrypto.encrypt("   "))
    }

    @Test
    fun `encrypt produces different ciphertexts for same input due to fresh IVs`() {
        val secret = "sp_dc=AQB_spotify_secure_cookie_value"
        val enc1 = SessionCrypto.encrypt(secret)
        val enc2 = SessionCrypto.encrypt(secret)

        assertNotEquals(enc1, enc2)
        assertEquals(secret, SessionCrypto.decrypt(enc1))
        assertEquals(secret, SessionCrypto.decrypt(enc2))
    }
}
