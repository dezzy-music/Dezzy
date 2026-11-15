/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.p2p.nostr

import com.dezzy.music.p2p.crypto.CryptoUtils
import com.dezzy.music.p2p.crypto.Nip44
import com.dezzy.music.p2p.crypto.hexToBytes

/**
 * Sobres cifrados de NIP-59 («gift wrap») usados por los mensajes privados de NIP-17.
 *
 * rumor (sin firmar) → seal kind 13 (firmado por el autor, cifrado al destinatario)
 * → gift wrap kind 1059 (firmado por una clave desechable, cifrado al destinatario).
 * El relé sólo ve una clave aleatoria escribiéndole al destinatario: no sabe quién habla
 * con quién, ni cuándo exactamente (las fechas se desordenan hasta 2 días).
 */
object GiftWrap {

    private const val TWO_DAYS = 2 * 24 * 60 * 60L

    private fun randomPastTimestamp(): Long =
        NostrEvent.now() - (CryptoUtils.random.nextInt(TWO_DAYS.toInt()).toLong())

    /** Envuelve [rumor] (escrito por [sender]) para que sólo [recipientPubkey] lo pueda abrir. */
    fun wrap(rumor: NostrEvent, sender: NostrKeys, recipientPubkey: String): NostrEvent {
        val sealKey = Nip44.conversationKey(sender.privateKey, recipientPubkey.hexToBytes())
        val seal = NostrEvent.sign(
            keys = sender,
            kind = Kinds.SEAL,
            tags = emptyList(),
            content = Nip44.encrypt(rumor.toJson(includeSig = false), sealKey),
            createdAt = randomPastTimestamp(),
        )
        val ephemeral = NostrKeys.generate()
        val wrapKey = Nip44.conversationKey(ephemeral.privateKey, recipientPubkey.hexToBytes())
        return NostrEvent.sign(
            keys = ephemeral,
            kind = Kinds.GIFT_WRAP,
            tags = listOf(listOf("p", recipientPubkey)),
            content = Nip44.encrypt(seal.toJson(), wrapKey),
            createdAt = randomPastTimestamp(),
        )
    }

    /**
     * Abre un gift wrap dirigido a [me]. Devuelve el rumor si todo cuadra: firma del sello
     * válida y el autor del rumor es quien firmó el sello (nadie puede hacerse pasar por otro).
     */
    fun unwrap(wrap: NostrEvent, me: NostrKeys): NostrEvent? = runCatching {
        if (wrap.kind != Kinds.GIFT_WRAP) return null
        val wrapKey = Nip44.conversationKey(me.privateKey, wrap.pubkey.hexToBytes())
        val seal = NostrEvent.fromJson(Nip44.decrypt(wrap.content, wrapKey)) ?: return null
        if (seal.kind != Kinds.SEAL || !RelayPool.isAuthentic(seal)) return null
        val sealKey = Nip44.conversationKey(me.privateKey, seal.pubkey.hexToBytes())
        val rumor = NostrEvent.fromJson(Nip44.decrypt(seal.content, sealKey)) ?: return null
        if (rumor.pubkey != seal.pubkey || !rumor.hasValidId()) return null
        rumor
    }.getOrNull()
}
