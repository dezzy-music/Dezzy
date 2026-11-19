/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.p2p

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.dezzy.music.constants.AccountChannelHandleKey
import com.dezzy.music.constants.AccountNameKey
import com.dezzy.music.constants.InnerTubeCookieKey
import com.dezzy.music.constants.SpotifySpDcKey
import com.dezzy.music.innertube.utils.parseCookieString
import com.dezzy.music.p2p.crypto.CryptoUtils
import com.dezzy.music.p2p.crypto.Nip44
import com.dezzy.music.p2p.crypto.hexToBytes
import com.dezzy.music.p2p.crypto.toHex
import com.dezzy.music.p2p.nostr.NostrEvent
import com.dezzy.music.p2p.nostr.NostrFilter
import com.dezzy.music.p2p.nostr.NostrKeys
import com.dezzy.music.p2p.nostr.RelayPool
import com.dezzy.music.security.SessionCrypto
import com.dezzy.music.utils.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Pasa las sesiones de este móvil (YouTube Music y Spotify) a Dezzy en el ordenador, que nunca
 * inicia sesión por su cuenta. Protocolo «dezzy-link-v1»; la otra mitad vive en Dezzy Desktop
 * (`src/main/link.ts`) y las dos deben cambiar a la vez.
 *
 * 1. El ordenador muestra un código de 8 caracteres (base32 de Crockford) y un QR con
 *    `dezzy://link?c=CODIGO`. PBKDF2-HMAC-SHA512 (sal «dezzy-link-v1», 100 000 iteraciones,
 *    64 bytes) da `lookup` = hex de los bytes 0..15 y `auth` = bytes 32..63.
 * 2. Cada lado usa una clave Nostr efímera al azar: quien grabe el tráfico no puede descifrar
 *    nada, aunque más tarde adivine el código.
 * 3. Eventos de tipo 21337 (efímero: los relés no lo guardan):
 *    - hola (móvil): ["t", lookup], contenido vacío.
 *    - oferta (ordenador → móvil): ["p", pkMóvil], NIP-44 de {v:1, type:"offer",
 *      mac: HMAC-SHA256(auth, "offer|pkOrdenador|pkMóvil")}.
 *    - sesiones (móvil → ordenador): ["p", pkOrdenador], NIP-44 de {v:1, type:"sessions",
 *      mac: HMAC-SHA256(auth, "sessions|pkMóvil|pkOrdenador"), device, youtube?, spotify?, social?,
 *      connect?: {key}} (clave de grupo de Dezzy Connect, 32 bytes en hex; ver connect/DezzyConnect.kt).
 *    - acuse (ordenador → móvil): ["p", pkMóvil], NIP-44 de {v:1, type:"ack", ok, message?}.
 *    Las mac (en hex) prueban que el otro lado conoce el código.
 */
object DesktopLink {
    private const val KIND = 21337
    private const val SALT = "dezzy-link-v1"
    private const val ITERATIONS = 100_000
    private const val TIMEOUT_MS = 40_000L
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    private val json = Json { ignoreUnknownKeys = true }

    /** Lo que se le pasa al ordenador. Sólo vive en memoria mientras se envía. */
    class Sessions(
        val device: String,
        val youtubeCookie: String?,
        val youtubeName: String?,
        val youtubeHandle: String?,
        val spotifySpDc: String?,
        /** Clave de la identidad social P2P (hex), para que el ordenador publique como tú. */
        val socialKey: String? = null,
        /** Clave de grupo de Dezzy Connect: el ordenador queda en tu grupo de dispositivos. */
        val connectKey: String? = null,
    ) {
        val isEmpty: Boolean get() = youtubeCookie == null && spotifySpDc == null && socialKey == null
    }

    enum class Step { SEARCHING, SENDING }

    sealed interface Outcome {
        data object Linked : Outcome
        data class Failed(val message: String) : Outcome
    }

    /**
     * Acepta el código tal cual lo escriba la persona (minúsculas, guion, O por 0, I o L por 1)
     * o el contenido entero del QR. Devuelve null si no es un código válido.
     */
    fun parseCode(input: String): String? {
        val raw = Regex("""[?&]c=([^&\s]+)""").find(input)?.groupValues?.get(1) ?: input
        val code = raw.uppercase()
            .replace(Regex("""[\s-]"""), "")
            .replace('O', '0')
            .replace('I', '1')
            .replace('L', '1')
        return code.takeIf { it.length == 8 && it.all { c -> c in ALPHABET } }
    }

    /** Lee las sesiones guardadas (descifradas sólo para este envío). */
    suspend fun currentSessions(context: Context): Sessions = withContext(Dispatchers.IO) {
        val prefs = context.dataStore.data.first()
        val cookie = SessionCrypto.decryptResilient(prefs[InnerTubeCookieKey].orEmpty()).orEmpty()
        val spDc = SessionCrypto.decryptResilient(prefs[SpotifySpDcKey].orEmpty()).orEmpty()
        runCatching { P2PIdentity.load(context) }
        Sessions(
            device = deviceName(context),
            youtubeCookie = cookie.takeIf { "SAPISID" in parseCookieString(it) },
            youtubeName = prefs[AccountNameKey]?.takeIf { it.isNotBlank() },
            youtubeHandle = prefs[AccountChannelHandleKey]?.takeIf { it.isNotBlank() },
            spotifySpDc = spDc.takeIf { it.isNotBlank() },
            socialKey = P2PIdentity.keys.value?.privateKey?.toHex(),
            connectKey = runCatching { com.dezzy.music.connect.DezzyConnect.groupKey(context) }.getOrNull(),
        )
    }

    private fun deviceName(context: Context): String =
        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: Build.MODEL.orEmpty().ifBlank { "Android" }

    private fun mac(auth: ByteArray, label: String, a: String, b: String): String =
        CryptoUtils.hmacSha256(auth, "$label|$a|$b".toByteArray(Charsets.UTF_8)).toHex()

    private fun sameMac(received: String?, expected: String): Boolean {
        if (received == null || received.length != expected.length) return false
        val bytes = runCatching { received.hexToBytes() }.getOrNull() ?: return false
        return CryptoUtils.constantTimeEquals(bytes, expected.hexToBytes())
    }

    private fun seal(keys: NostrKeys, peer: String, payload: JsonObject): NostrEvent {
        val key = Nip44.conversationKey(keys.privateKey, peer.hexToBytes())
        return NostrEvent.sign(keys, KIND, listOf(listOf("p", peer)), Nip44.encrypt(payload.toString(), key))
    }

    private fun open(keys: NostrKeys, event: NostrEvent): JsonObject? {
        if (event.content.isEmpty() || event.content.length > 96 * 1024) return null
        return runCatching {
            val key = Nip44.conversationKey(keys.privateKey, event.pubkey.hexToBytes())
            json.parseToJsonElement(Nip44.decrypt(event.content, key)).jsonObject
        }.getOrNull()
    }

    private fun JsonObject.str(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    /** Envía las sesiones al ordenador que muestra [code]. */
    suspend fun send(code: String, sessions: Sessions, onStep: (Step) -> Unit = {}): Outcome =
        withContext(Dispatchers.Default) {
            onStep(Step.SEARCHING)
            RelayPool.warmUp()
            val seed = CryptoUtils.pbkdf2Sha512(code.toByteArray(Charsets.UTF_8), SALT.toByteArray(Charsets.UTF_8), ITERATIONS, 64)
            val lookup = seed.copyOfRange(0, 16).toHex()
            val auth = seed.copyOfRange(32, 64)
            seed.fill(0)
            val keys = NostrKeys.generate()
            try {
                withTimeoutOrNull(TIMEOUT_MS) { exchange(keys, auth, lookup, sessions, onStep) }
                    ?: Outcome.Failed("El ordenador no responde. Revisa el código y que Dezzy siga abierto en él.")
            } finally {
                auth.fill(0)
                keys.privateKey.fill(0)
            }
        }

    private suspend fun exchange(
        keys: NostrKeys,
        auth: ByteArray,
        lookup: String,
        sessions: Sessions,
        onStep: (Step) -> Unit,
    ): Outcome = coroutineScope {
        val incoming = Channel<NostrEvent>(Channel.UNLIMITED)
        val filter = NostrFilter(
            kinds = listOf(KIND),
            tags = mapOf("#p" to listOf(keys.publicKey)),
            since = NostrEvent.now() - 30,
        )
        val listener = launch { RelayPool.stream(listOf(filter)).collect { incoming.send(it) } }
        // Da tiempo a que los relés registren la suscripción antes de saludar.
        delay(700)
        launch { RelayPool.publish(NostrEvent.sign(keys, KIND, listOf(listOf("t", lookup)), "")) }

        var desktop: String? = null
        var outcome: Outcome? = null
        for (event in incoming) {
            val data = open(keys, event) ?: continue
            when (data.str("type")) {
                "offer" -> {
                    if (desktop != null || !sameMac(data.str("mac"), mac(auth, "offer", event.pubkey, keys.publicKey))) continue
                    desktop = event.pubkey
                    onStep(Step.SENDING)
                    val payload = buildJsonObject {
                        put("v", 1)
                        put("type", "sessions")
                        put("mac", mac(auth, "sessions", keys.publicKey, event.pubkey))
                        put("device", sessions.device)
                        sessions.youtubeCookie?.let { cookie ->
                            putJsonObject("youtube") {
                                put("cookie", cookie)
                                sessions.youtubeName?.let { put("name", it) }
                                sessions.youtubeHandle?.let { put("handle", it) }
                            }
                        }
                        sessions.spotifySpDc?.let { spDc -> putJsonObject("spotify") { put("spDc", spDc) } }
                        sessions.socialKey?.let { sk -> putJsonObject("social") { put("sk", sk) } }
                        sessions.connectKey?.let { key -> putJsonObject("connect") { put("key", key) } }
                    }
                    launch { RelayPool.publish(seal(keys, event.pubkey, payload)) }
                }
                "ack" -> {
                    if (event.pubkey != desktop) continue
                    outcome = if (data["ok"]?.jsonPrimitive?.booleanOrNull == true) {
                        Outcome.Linked
                    } else {
                        Outcome.Failed(data.str("message") ?: "El ordenador no pudo guardar las sesiones.")
                    }
                    break
                }
            }
        }
        listener.cancel()
        incoming.close()
        coroutineContext.cancelChildren()
        outcome ?: Outcome.Failed("Se cortó la conexión con el ordenador.")
    }
}
