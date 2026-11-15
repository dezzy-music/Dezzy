/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.p2p

import android.content.Context
import android.content.SharedPreferences
import com.dezzy.music.p2p.crypto.Bech32
import com.dezzy.music.p2p.crypto.Bip39
import com.dezzy.music.p2p.crypto.hexToBytes
import com.dezzy.music.p2p.crypto.toHex
import com.dezzy.music.p2p.nostr.NostrKeys
import com.dezzy.music.security.SessionCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Identidad de la red social: un par de claves que vive sólo en este teléfono.
 *
 * Las 12 palabras (o la clave) se guardan cifradas con una clave del Android Keystore, que
 * nunca sale del chip seguro del teléfono. No hay correo ni contraseña ni servidor.
 */
object P2PIdentity {

    private const val PREFS = "dezzy_p2p_identity"
    private const val KEY_WORDS = "words"
    private const val KEY_PRIVATE = "private_key"
    private const val KEY_BACKED_UP = "backed_up"

    private val _keys = MutableStateFlow<NostrKeys?>(null)

    /** Claves activas, o null si todavía no hay cuenta en este teléfono. */
    val keys: StateFlow<NostrKeys?> = _keys.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    val publicKey: String? get() = _keys.value?.publicKey

    private fun prefs(context: Context): SharedPreferences =
        SessionCrypto.getSecurePreferences(context.applicationContext, PREFS)

    /** Carga la identidad guardada (en segundo plano: derivar las claves cuesta unos ms). */
    suspend fun load(context: Context) = withContext(Dispatchers.Default) {
        if (_loaded.value) return@withContext
        runCatching {
            val p = prefs(context)
            val privateHex = p.getString(KEY_PRIVATE, null)
            val words = p.getString(KEY_WORDS, null)
            _keys.value = when {
                privateHex != null && privateHex.length == 64 -> NostrKeys(privateHex.hexToBytes())
                words != null -> NostrKeys(Bip39.nostrPrivateKey(Bip39.normalize(words)))
                else -> null
            }
        }.onFailure { Timber.e(it, "No se pudo cargar la identidad P2P") }
        _loaded.value = true
    }

    /** Crea una cuenta nueva y devuelve sus 12 palabras de respaldo. */
    suspend fun create(context: Context): List<String> = withContext(Dispatchers.Default) {
        val words = Bip39.generate(context)
        save(context, words, Bip39.nostrPrivateKey(words), backedUp = false)
        words
    }

    /** Recupera la cuenta con 12 palabras o con una clave `nsec1…`. */
    suspend fun restore(context: Context, input: String): Boolean = withContext(Dispatchers.Default) {
        Bech32.parsePrivateKey(input.trim())?.let { key ->
            save(context, null, key, backedUp = true)
            return@withContext true
        }
        val words = Bip39.normalize(input)
        if (!Bip39.isValid(context, words)) return@withContext false
        save(context, words, Bip39.nostrPrivateKey(words), backedUp = true)
        true
    }

    private fun save(context: Context, words: List<String>?, privateKey: ByteArray, backedUp: Boolean) {
        prefs(context).edit()
            .putString(KEY_PRIVATE, privateKey.toHex())
            .apply { if (words != null) putString(KEY_WORDS, words.joinToString(" ")) else remove(KEY_WORDS) }
            .putBoolean(KEY_BACKED_UP, backedUp)
            .commit()
        _keys.value = NostrKeys(privateKey)
        _loaded.value = true
    }

    /** Las 12 palabras para mostrarlas otra vez (null si la cuenta se importó con nsec). */
    fun backupWords(context: Context): List<String>? =
        prefs(context).getString(KEY_WORDS, null)?.let { Bip39.normalize(it) }

    fun isBackedUp(context: Context): Boolean = prefs(context).getBoolean(KEY_BACKED_UP, false)

    fun markBackedUp(context: Context) {
        prefs(context).edit().putBoolean(KEY_BACKED_UP, true).apply()
    }

    /** Borra la cuenta de este teléfono. Sin las 12 palabras no se puede recuperar. */
    fun signOut(context: Context) {
        prefs(context).edit().clear().commit()
        _keys.value = null
    }
}
