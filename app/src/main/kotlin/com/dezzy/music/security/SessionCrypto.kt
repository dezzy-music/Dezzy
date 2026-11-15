/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import timber.log.Timber
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-backed session crypto layer powered by the Android KeyStore.
 * Provides authenticated AES-256-GCM encryption for in-memory session tokens
 * and cookies before persisting to disk or SharedPreferences.
 */
object SessionCrypto {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "dezzy_session_keystore_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val PREFIX_V1 = "enc:v1:"

    private val keyStore: KeyStore? by lazy {
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        } catch (e: Exception) {
            Timber.w(e, "SessionCrypto: AndroidKeyStore provider not available on this runtime (e.g. JVM test)")
            null
        }
    }

    private var testFallbackKey: SecretKey? = null

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val ks = keyStore
        if (ks != null) {
            if (!ks.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }
            return (ks.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        } else {
            if (testFallbackKey == null) {
                val kg = KeyGenerator.getInstance("AES")
                kg.init(256)
                testFallbackKey = kg.generateKey()
            }
            return testFallbackKey!!
        }
    }

    /**
     * Encrypts plaintext using AES-256-GCM via the app's hardware-backed Android KeyStore.
     * Returns a string prefixed with "enc:v1:" containing Base64(IV + Ciphertext).
     */
    fun encrypt(plaintext: String): String {
        if (plaintext.isBlank()) return plaintext
        return try {
            val secretKey = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
            PREFIX_V1 + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Timber.e(e, "SessionCrypto: encryption failed")
            plaintext
        }
    }

    /**
     * Decrypts ciphertext. If the string was not encrypted with SessionCrypto,
     * returns it as-is for transparent backwards compatibility and migration.
     */
    fun decrypt(ciphertext: String): String {
        if (!ciphertext.startsWith(PREFIX_V1)) return ciphertext
        return try {
            val rawB64 = ciphertext.removePrefix(PREFIX_V1)
            val combined = Base64.decode(rawB64, Base64.NO_WRAP)
            if (combined.size < GCM_IV_LENGTH_BYTES) return ""
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH_BYTES)
            val encryptedBytes = combined.copyOfRange(GCM_IV_LENGTH_BYTES, combined.size)
            val secretKey = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Timber.e(e, "SessionCrypto: decryption failed")
            ""
        }
    }

    /**
     * Returns whether the given value is currently encrypted.
     */
    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX_V1)

    /**
     * Returns an encrypted SharedPreferences wrapper backed by the app's Android KeyStore.
     */
    fun getSecurePreferences(context: Context, name: String = "secure_session_prefs"): SharedPreferences {
        val basePrefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        return SecureSharedPreferencesWrapper(basePrefs)
    }

    /**
     * Decorator over Android SharedPreferences that encrypts all string values
     * with Android KeyStore before saving to disk.
     */
    private class SecureSharedPreferencesWrapper(
        private val delegate: SharedPreferences
    ) : SharedPreferences {

        override fun getAll(): MutableMap<String, *> {
            val raw = delegate.all
            val result = mutableMapOf<String, Any?>()
            for ((k, v) in raw) {
                if (v is String && v.startsWith(PREFIX_V1)) {
                    result[k] = decrypt(v)
                } else {
                    result[k] = v
                }
            }
            return result
        }

        override fun getString(key: String?, defValue: String?): String? {
            val raw = delegate.getString(key, null) ?: return defValue
            val decrypted = decrypt(raw)
            return decrypted.ifEmpty { defValue }
        }

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            return delegate.getStringSet(key, defValues)
        }

        override fun getInt(key: String?, defValue: Int): Int = delegate.getInt(key, defValue)
        override fun getLong(key: String?, defValue: Long): Long = delegate.getLong(key, defValue)
        override fun getFloat(key: String?, defValue: Float): Float = delegate.getFloat(key, defValue)
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = delegate.getBoolean(key, defValue)
        override fun contains(key: String?): Boolean = delegate.contains(key)

        override fun edit(): SharedPreferences.Editor = SecureEditor(delegate.edit())

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            delegate.registerOnSharedPreferenceChangeListener(listener)
        }

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            delegate.unregisterOnSharedPreferenceChangeListener(listener)
        }

        private class SecureEditor(
            private val editor: SharedPreferences.Editor
        ) : SharedPreferences.Editor {

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                val encrypted = if (value != null) encrypt(value) else null
                editor.putString(key, encrypted)
                return this
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                editor.putStringSet(key, values)
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                editor.putInt(key, value)
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                editor.putLong(key, value)
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                editor.putFloat(key, value)
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                editor.putBoolean(key, value)
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                editor.remove(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                editor.clear()
                return this
            }

            override fun commit(): Boolean = editor.commit()
            override fun apply() = editor.apply()
        }
    }
}
