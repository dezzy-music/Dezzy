/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.connect

import android.content.Context
import android.media.AudioManager
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.dezzy.music.extensions.metadata
import com.dezzy.music.extensions.toMediaItem
import com.dezzy.music.models.MediaMetadata
import com.dezzy.music.playback.PlayerConnection
import com.dezzy.music.playback.queues.ListQueue
import com.dezzy.music.playback.queues.YouTubeQueue
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Dezzy Connect en el reproductor. Es la misma lógica que `store/connect.ts` en Dezzy Desktop.
 *
 * Varios dispositivos forman una estrella alrededor del que reparte la música (el «centro»: el
 * que los conectó). Los demás sólo hablan con él.
 *
 * Papeles:
 * - [Role.LEADER] (centro): suena aquí y los demás le siguen.
 * - [Role.REMOTE] (centro): suena en otro (su anfitrión) y aquí hacemos de mando. Este móvil
 *   sigue la canción en silencio, como con Chromecast, para que el reproductor, la notificación
 *   y la pantalla de bloqueo muestren lo de allí y sus botones lo controlen. Si hay más
 *   dispositivos, les reenviamos lo que diga el anfitrión.
 * - [Role.HOST]: suena aquí y el centro nos controla. Mandamos nuestro estado y obedecemos.
 * - [Role.FOLLOWER]: suena aquí siguiendo al centro (salto si se va más de 250 ms, un pelo más
 *   rápido o lento si es menos).
 *
 * Control compartido: con «Todos los conectados» lo que toques en cualquiera (pausa, saltar,
 * mover la barra) viaja como orden al centro, que es el único que decide. Así nunca chocan: gana
 * la última orden y todos acaban igual. Con «Sólo este móvil» los demás sólo escuchan.
 *
 * Todo corre en el hilo principal, igual que el reproductor.
 */
object DezzyConnectSession {
    enum class Role { REMOTE, HOST, LEADER, FOLLOWER }

    enum class Mode(val wire: String) { THERE("there"), BOTH("both") }

    data class Track(
        val id: String,
        val title: String,
        val artists: String,
        val album: String?,
        val thumb: String?,
        /** Segundos. */
        val dur: Double?,
    )

    /** Lo que suena. `at` es el reloj local (ms) en que valía `pos` (segundos). */
    data class Playback(
        val queue: List<Track>,
        val index: Int,
        val pos: Double,
        val playing: Boolean,
        val at: Double,
        val vol: Double,
    ) {
        val track: Track? get() = queue.getOrNull(index)
    }

    /** Un dispositivo que suena con nosotros cuando somos el centro. */
    data class Member(
        val id: String,
        val name: String,
        val kind: String,
        /** Es donde suena de verdad y aquí hacemos de mando ([Role.REMOTE]). */
        val host: Boolean,
        /** Diferencia medida con nosotros, en ms. */
        val drift: Int?,
        val volume: Float?,
        /** Ajuste manual de sincronía en ms. */
        val offset: Int,
    )

    /** Quién nos maneja cuando somos [Role.HOST] o [Role.FOLLOWER]. */
    data class Hub(val id: String, val name: String, val controlAll: Boolean)

    private const val PREFS = "dezzy_connect_session"
    private const val QUEUE_WINDOW = 200
    /** Tras una orden nuestra, cuánto se espera a que el centro la aplique antes de corregir. */
    private const val HOLD_MS = 2_500L
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

    private val _role = MutableStateFlow<Role?>(null)
    val role: StateFlow<Role?> = _role.asStateFlow()

    /** Este móvil sigue la música en silencio (hace de mando). MusicService lo usa para callarse. */
    private val _silentHere = MutableStateFlow(false)
    val silentHere: StateFlow<Boolean> = _silentHere.asStateFlow()

    /** Los que suenan con nosotros (sólo cuando somos el centro). */
    private val _members = MutableStateFlow<List<Member>>(emptyList())
    val members: StateFlow<List<Member>> = _members.asStateFlow()

    /** Quién nos maneja (sólo si somos anfitrión o seguidor). */
    private val _hub = MutableStateFlow<Hub?>(null)
    val hub: StateFlow<Hub?> = _hub.asStateFlow()

    /** Ajuste «Quién puede controlar»: true = todos los conectados; false = sólo este móvil. */
    private val _controlAll = MutableStateFlow(true)
    val controlAll: StateFlow<Boolean> = _controlAll.asStateFlow()

    /** Diferencia medida con el centro, en ms (cuando le seguimos). */
    private val _drift = MutableStateFlow<Int?>(null)
    val drift: StateFlow<Int?> = _drift.asStateFlow()

    /** Ajuste de sincronía que nos pidió el centro, en ms (positivo: vamos por delante). */
    private val _offset = MutableStateFlow(0)
    val offset: StateFlow<Int> = _offset.asStateFlow()

    /** Dispositivo al que se está conectando. */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** Lo que suena en el anfitrión cuando hacemos de mando. */
    private val _remote = MutableStateFlow<Playback?>(null)
    val remote: StateFlow<Playback?> = _remote.asStateFlow()

    private val scope = MainScope()
    private lateinit var appContext: Context
    private var started = false
    private var connection: PlayerConnection? = null

    private class MemberInfo(
        var host: Boolean,
        var drift: Int? = null,
        var volume: Float? = null,
        var offset: Int = 0,
        /** Ya le mandamos «take» (sabe que es de los nuestros). */
        var taken: Boolean = false,
    )

    /** Los nuestros, en el orden en que se sumaron (sólo como centro). */
    private val memberInfo = LinkedHashMap<String, MemberInfo>()
    /** Anfitrión cuando hacemos de mando. */
    private var hostId: String? = null
    /** Conexiones que cortamos nosotros: no se avisan como pérdida. */
    private val leavingIds = HashSet<String>()

    /** Canción cargada a petición del otro: se coloca en su sitio cuando esté lista. */
    private var pendingSeek: Pair<Playback, Long>? = null
    /** Posición de cada canción enviada dentro de la cola del reproductor. */
    private var sentMap: List<Int> = emptyList()
    private var lastKey = ""
    private var lastDriftSent = 0L
    private var tick = 0
    /** Cambios del reproductor hechos por nosotros (no son del usuario). */
    private var applying = 0
    /** Velocidad que había antes de empezar a corregir el paso. */
    private var baseParameters: PlaybackParameters? = null
    private var lastRate = 1f
    /** Hasta cuándo no se corrige el paso porque acabamos de mandar una orden. */
    private var holdUntil = 0L
    private var lastAnnounce = ""
    private var lastAnnounceAt = 0L

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (applying > 0 || reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) return
            onUserAction(buildJsonObject { put("op", if (playWhenReady) "play" else "pause") })
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (applying > 0 || reason != Player.DISCONTINUITY_REASON_SEEK) return
            if (oldPosition.mediaItemIndex == newPosition.mediaItemIndex) {
                val shift = if (_role.value == Role.FOLLOWER) _offset.value / 1000.0 else 0.0
                val pos = max(0.0, newPosition.positionMs / 1000.0 - shift)
                onUserAction(buildJsonObject { put("op", "seek"); put("pos", pos) })
                return
            }
            // El fundido entre canciones también salta al final: eso no lo hizo el usuario.
            val oldDur = oldPosition.mediaItem?.metadata?.duration?.takeIf { it > 0 }
            if (oldDur != null && oldPosition.positionMs > (oldDur - 20) * 1000L) return
            val id = newPosition.mediaItem?.mediaId ?: return
            onUserAction(buildJsonObject { put("op", "playTrack"); put("id", id) })
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && player.playbackState == Player.STATE_READY) {
                applyPendingSeek()
            }
            if (events.contains(Player.EVENT_POSITION_DISCONTINUITY) && applying == 0) broadcast(force = true)
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_TIMELINE_CHANGED,
                )
            ) {
                broadcast()
            }
        }
    }

    /* ── Arranque ── */

    fun init(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        _controlAll.value = prefs().getBoolean("control_all", true)
        DezzyConnect.init(appContext)
        scope.launch { DezzyConnect.messages.collect { onMessage(it) } }
        scope.launch {
            var before = emptyMap<String, DezzyConnect.Peer>()
            DezzyConnect.peers.collect { list ->
                val current = list.associateBy { it.id }
                for ((id, peer) in before) if (id !in current) onPeerLost(peer)
                before = current
                emitMembers()
            }
        }
        scope.launch {
            while (isActive) {
                delay(1_000)
                tick++
                val r = _role.value
                if (r == Role.LEADER || (r == Role.HOST && tick % 2 == 0)) broadcast(force = true)
            }
        }
    }

    /** Se llama al conectar (o perder) el servicio de música. */
    fun bind(next: PlayerConnection?) {
        if (connection === next) return
        connection?.player?.removeListener(listener)
        connection = next
        next?.player?.addListener(listener)
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun now() = System.currentTimeMillis()

    private inline fun ours(block: () -> Unit) {
        applying++
        try {
            block()
        } finally {
            applying--
        }
    }

    private fun setRole(r: Role?) {
        _role.value = r
        _silentHere.value = r == Role.REMOTE
    }

    private fun isCenter(r: Role? = _role.value) = r == Role.LEADER || r == Role.REMOTE

    private fun emitMembers() {
        val peers = DezzyConnect.peers.value.associateBy { it.id }
        val devices = DezzyConnect.devices.value.associateBy { it.id }
        _members.value = memberInfo.map { (id, info) ->
            Member(
                id = id,
                name = peers[id]?.name ?: devices[id]?.name ?: "Dispositivo",
                kind = peers[id]?.kind ?: devices[id]?.kind ?: "pc",
                host = info.host,
                drift = info.drift,
                volume = info.volume,
                offset = info.offset,
            )
        }
    }

    private fun peerName(id: String): String =
        DezzyConnect.peers.value.firstOrNull { it.id == id }?.name
            ?: DezzyConnect.devices.value.firstOrNull { it.id == id }?.name
            ?: "Otro dispositivo"

    /* ── Conversión ── */

    private fun MediaItem.toWire(): Track? {
        val meta = metadata ?: return null
        if (!VIDEO_ID.matches(meta.id)) return null
        return Track(
            id = meta.id,
            title = meta.title.take(300),
            artists = meta.artists.joinToString(", ") { it.name }.take(300),
            album = meta.album?.title?.take(300),
            thumb = meta.thumbnailUrl?.takeIf { it.startsWith("https://") },
            dur = meta.duration.takeIf { it > 0 }?.toDouble(),
        )
    }

    private fun Track.toMediaItem(): MediaItem =
        MediaMetadata(
            id = id,
            title = title,
            artists = artists.split(", ").filter { it.isNotBlank() }.map { MediaMetadata.Artist(id = null, name = it) },
            duration = dur?.roundToInt() ?: -1,
            thumbnailUrl = thumb,
        ).toMediaItem()

    private fun Track.toJson() = buildJsonObject {
        put("id", id)
        put("title", title)
        put("artists", artists)
        put("album", album)
        put("thumb", thumb)
        put("dur", dur)
    }

    private fun Playback.toJson() = buildJsonObject {
        put("queue", JsonArray(queue.map { it.toJson() }))
        put("index", index)
        put("pos", pos)
        put("playing", playing)
        put("at", at)
        put("vol", vol)
    }

    private fun JsonObject.str(key: String, max: Int): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.take(max)

    private fun JsonObject.num(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

    private fun parseTrack(raw: Any?): Track? {
        val o = raw as? JsonObject ?: return null
        val title = o.str("title", 300)?.takeIf { it.isNotEmpty() } ?: return null
        val id = o.str("id", 11).orEmpty()
        if (id.isNotEmpty() && !VIDEO_ID.matches(id)) return null
        return Track(
            id = id,
            title = title,
            artists = o.str("artists", 300).orEmpty(),
            album = o.str("album", 300),
            thumb = o.str("thumb", 2000)?.takeIf { it.startsWith("https://") },
            dur = o.num("dur"),
        )
    }

    private fun parseQueue(raw: Any?): List<Track>? {
        val array = raw as? JsonArray ?: return null
        if (array.size > 400) return null
        val out = array.map { parseTrack(it) ?: return null }
        // Sin identificador no se puede reproducir aquí.
        return out.takeIf { list -> list.all { it.id.isNotEmpty() } }
    }

    private fun parsePlayback(raw: Any?): Playback? {
        val o = raw as? JsonObject ?: return null
        val queue = parseQueue(o["queue"]) ?: return null
        val index = o.num("index")?.toInt() ?: return null
        if (queue.isEmpty() || index !in queue.indices) return null
        return Playback(
            queue = queue,
            index = index,
            pos = max(0.0, o.num("pos") ?: 0.0),
            playing = (o["playing"] as? JsonPrimitive)?.booleanOrNull == true,
            at = o.num("at") ?: now().toDouble(),
            vol = (o.num("vol") ?: 0.8).coerceIn(0.0, 1.0),
        )
    }

    /** Posición del otro ahora mismo, en segundos. */
    fun livePos(pb: Playback): Double = pb.pos + if (pb.playing) max(0.0, now() - pb.at) / 1000 else 0.0

    private fun sameTrack(item: MediaItem?, track: Track?): Boolean =
        item != null && track != null && track.id.isNotEmpty() && item.mediaId == track.id

    /* ── Volumen del sistema ── */

    private fun audio() = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun systemVolume(): Double {
        val am = audio()
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return am.getStreamVolume(AudioManager.STREAM_MUSIC).toDouble() / maxVol
    }

    private fun setSystemVolume(v: Double) {
        val am = audio()
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, (v.coerceIn(0.0, 1.0) * maxVol).roundToInt(), 0) }
    }

    /* ── Reproductor ── */

    /** Lo que suena aquí, recortado alrededor de la canción actual. */
    private fun localPlayback(): Playback? {
        val player = connection?.player ?: return null
        val count = player.mediaItemCount
        if (count == 0) return null
        val current = player.currentMediaItemIndex
        val from = max(0, current - 20)
        val to = min(count, from + QUEUE_WINDOW)
        val queue = ArrayList<Track>()
        val map = ArrayList<Int>()
        var index = -1
        for (i in from until to) {
            val track = player.getMediaItemAt(i).toWire() ?: continue
            if (i == current) index = queue.size
            queue += track
            map += i
        }
        if (index < 0) return null
        sentMap = map
        return Playback(
            queue = queue,
            index = index,
            pos = player.currentPosition.coerceAtLeast(0L) / 1000.0,
            playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED,
            at = now().toDouble(),
            vol = systemVolume(),
        )
    }

    /** Carga la cola del otro aquí y salta a su posición en cuanto se pueda. */
    private fun playFrom(pb: Playback) {
        val conn = connection ?: return
        pendingSeek = pb to now() + 20_000
        val offset = if (_role.value == Role.FOLLOWER) _offset.value / 1000.0 else 0.0
        ours {
            conn.service.playQueue(
                ListQueue(
                    items = pb.queue.map { it.toMediaItem() },
                    startIndex = pb.index,
                    position = ((livePos(pb) + offset) * 1000).toLong().coerceAtLeast(0L),
                ),
                playWhenReady = pb.playing,
            )
        }
    }

    private fun applyPendingSeek() {
        val (pb, until) = pendingSeek ?: return
        val player = connection?.player ?: return
        if (now() > until) {
            pendingSeek = null
            return
        }
        if (!sameTrack(player.currentMediaItem, pb.track)) return
        pendingSeek = null
        val offset = if (_role.value == Role.FOLLOWER) _offset.value / 1000.0 else 0.0
        ours {
            player.seekTo(((livePos(pb) + offset) * 1000).toLong().coerceAtLeast(0L))
            player.playWhenReady = pb.playing
        }
    }

    /** Ajusta la velocidad un pelo sin tocar la que eligió el usuario. */
    private fun setRateNudge(rate: Float) {
        val player = connection?.player ?: return
        if (abs(rate - lastRate) < 0.004f) return
        if (rate == 1f) {
            baseParameters?.let { base -> ours { player.playbackParameters = base } }
            baseParameters = null
            lastRate = 1f
            return
        }
        val base = baseParameters ?: player.playbackParameters.also { baseParameters = it }
        lastRate = rate
        ours { player.playbackParameters = PlaybackParameters(base.speed * rate, base.pitch) }
    }

    /** Sigue al centro: misma canción, mismo punto. */
    private fun follow(pb: Playback) {
        val player = connection?.player ?: return
        // Acabamos de mandar una orden: se deja hasta que el centro la aplique.
        if (now() < holdUntil) return
        pendingSeek?.let { (_, until) ->
            if (now() < until) {
                pendingSeek = pb to until
                return
            }
        }
        if (!sameTrack(player.currentMediaItem, pb.track)) {
            playFrom(pb)
            return
        }
        val playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED
        if (pb.playing != playing) ours { player.playWhenReady = pb.playing }
        val goal = livePos(pb) + _offset.value / 1000.0
        val drift = player.currentPosition / 1000.0 - goal
        if (!pb.playing || !player.isPlaying) {
            setRateNudge(1f)
            if (abs(drift) > 0.5) ours { player.seekTo((goal * 1000).toLong().coerceAtLeast(0L)) }
            return
        }
        if (abs(drift) > 0.25) {
            setRateNudge(1f)
            // Un poco por delante: el salto tarda en sonar.
            ours { player.seekTo(((goal + 0.04) * 1000).toLong().coerceAtLeast(0L)) }
        } else {
            setRateNudge(if (abs(drift) > 0.02) 1f - (drift * 0.5).coerceIn(-0.05, 0.05).toFloat() else 1f)
        }
        val ms = (drift * 1000).roundToInt()
        _drift.value = ms
        val hubId = _hub.value?.id ?: return
        if (now() - lastDriftSent > 900) {
            lastDriftSent = now()
            DezzyConnect.send(buildJsonObject { put("t", "drift"); put("ms", ms); put("vol", systemVolume()) }, to = hubId)
        }
    }

    /**
     * Haciendo de mando: este móvil sigue lo de allí en silencio, sin afinar al milisegundo, para
     * que el reproductor y la notificación muestren lo que suena allí.
     */
    private fun mirror(pb: Playback) {
        val player = connection?.player ?: return
        if (now() < holdUntil) return
        pendingSeek?.let { (_, until) ->
            if (now() < until) {
                pendingSeek = pb to until
                return
            }
        }
        if (!sameTrack(player.currentMediaItem, pb.track)) {
            playFrom(pb)
            return
        }
        val playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED
        if (pb.playing != playing) ours { player.playWhenReady = pb.playing }
        val goal = livePos(pb)
        if (abs(player.currentPosition / 1000.0 - goal) > 1.5) {
            ours { player.seekTo((goal * 1000).toLong().coerceAtLeast(0L)) }
        }
    }

    /** Aplica una orden como si la hubiera dado el usuario aquí. */
    private fun obey(cmd: JsonObject) {
        val conn = connection ?: return
        val player = conn.player
        ours {
            when (cmd.str("op", 20)) {
                "toggle" -> {
                    player.playWhenReady = !player.playWhenReady
                }
                "play" -> player.play()
                "pause" -> player.pause()
                "next" -> conn.seekToNext()
                "prev" -> conn.seekToPrevious()
                "seek" -> cmd.num("pos")?.let { player.seekTo((it * 1000).toLong().coerceAtLeast(0L)) }
                "vol" -> cmd.num("v")?.let { setSystemVolume(it) }
                "playAt" -> cmd.num("index")?.toInt()?.let { sentMap.getOrNull(it) }?.takeIf { it < player.mediaItemCount }?.let {
                    player.seekToDefaultPosition(it)
                    player.play()
                }
                // Saltar a una canción concreta (lo que tocó otro en su cola). Si ya suena, nada.
                "playTrack" -> cmd.str("id", 11)?.let { id ->
                    if (player.currentMediaItem?.mediaId == id) return@let
                    val current = player.currentMediaItemIndex
                    val found = (0 until player.mediaItemCount)
                        .filter { player.getMediaItemAt(it).mediaId == id }
                        .minByOrNull { abs(it - current) }
                    if (found != null) {
                        player.seekToDefaultPosition(found)
                        player.play()
                    }
                }
                "load" -> {
                    val queue = parseQueue(cmd["queue"])
                    val index = cmd.num("index")?.toInt()
                    if (!queue.isNullOrEmpty() && index != null && index in queue.indices) {
                        conn.playQueue(ListQueue(items = queue.map { it.toMediaItem() }, startIndex = index))
                    }
                }
                "radio" -> parseTrack(cmd["track"])?.takeIf { it.id.isNotEmpty() }?.let { track ->
                    track.toMediaItem().metadata?.let { conn.playQueue(YouTubeQueue.radio(it)) }
                }
                // «sync» sólo pide nuestro estado; «queue» (reordenar desde el otro) no se aplica
                // aquí en esta versión.
                else -> Unit
            }
        }
        broadcast(force = true)
    }

    private fun stateMessage(pb: Playback) = buildJsonObject {
        put("t", "state")
        put("pb", pb.toJson())
        put("ctl", if (_controlAll.value) "all" else "one")
    }

    private fun takeMessage(mode: Mode, pb: Playback, memberId: String) = buildJsonObject {
        put("t", "take")
        put("mode", mode.wire)
        put("pb", pb.toJson())
        put("ctl", if (_controlAll.value) "all" else "one")
        put("offset", memberInfo[memberId]?.offset ?: 0)
    }

    /** Como centro o anfitrión, cuenta a los demás lo que suena aquí. */
    private fun broadcast(force: Boolean = false) {
        val r = _role.value
        if (r != Role.HOST && r != Role.LEADER) return
        val pb = localPlayback() ?: return
        if (r == Role.LEADER) {
            // Los que se sumaron cuando aún no sonaba nada.
            for ((id, info) in memberInfo) {
                if (!info.taken) {
                    info.taken = true
                    DezzyConnect.send(takeMessage(Mode.BOTH, pb, id), to = id)
                }
            }
        }
        val key = "${pb.index}|${pb.playing}|${(pb.vol * 100).roundToInt()}|${pb.queue.joinToString(",") { it.id }}"
        if (!force && key == lastKey) return
        lastKey = key
        if (r == Role.LEADER) {
            DezzyConnect.send(stateMessage(pb))
        } else {
            _hub.value?.let { DezzyConnect.send(stateMessage(pb), to = it.id) }
        }
    }

    /** Haciendo de mando: pasa lo que dice el anfitrión a los demás que suenan. */
    private fun relay(pb: Playback, except: String) {
        if (memberInfo.size <= 1) return
        DezzyConnect.send(stateMessage(pb), except = except)
    }

    /* ── Control compartido ── */

    /** El usuario tocó el reproductor de este móvil (botones, notificación, barra…). */
    private fun onUserAction(cmd: JsonObject) {
        // Mientras se carga lo que mandó otro, los cambios son nuestros aunque no lo parezcan.
        if (pendingSeek != null) return
        when (_role.value) {
            Role.REMOTE -> {
                val host = hostId ?: return
                holdUntil = now() + HOLD_MS
                DezzyConnect.send(buildJsonObject { put("t", "cmd"); put("cmd", cmd) }, to = host)
                optimistic(cmd)
                announce(DezzyConnect.selfId(), DezzyConnect.selfName(), cmd.str("op", 20))
            }
            Role.LEADER -> announce(DezzyConnect.selfId(), DezzyConnect.selfName(), cmd.str("op", 20))
            Role.FOLLOWER -> {
                val hub = _hub.value ?: return
                if (hub.controlAll) {
                    holdUntil = now() + HOLD_MS
                    DezzyConnect.send(buildJsonObject { put("t", "cmd"); put("cmd", cmd) }, to = hub.id)
                } else {
                    // No manda: se vuelve a lo que diga el centro.
                    holdUntil = 0
                    toast("Sólo ${hub.name} puede cambiar la música")
                }
            }
            // Como anfitrión somos quien suena: el estado ya sale solo.
            Role.HOST, null -> Unit
        }
    }

    /** Pone en la hoja lo que acabamos de pedir sin esperar al anfitrión. */
    private fun optimistic(cmd: JsonObject) {
        val pb = _remote.value ?: return
        _remote.value = when (cmd.str("op", 20)) {
            "play" -> pb.copy(pos = livePos(pb), at = now().toDouble(), playing = true)
            "pause" -> pb.copy(pos = livePos(pb), at = now().toDouble(), playing = false)
            "toggle" -> pb.copy(pos = livePos(pb), at = now().toDouble(), playing = !pb.playing)
            "seek" -> cmd.num("pos")?.let { pb.copy(pos = it, at = now().toDouble()) } ?: pb
            "vol" -> cmd.num("v")?.let { pb.copy(vol = it.coerceIn(0.0, 1.0)) } ?: pb
            else -> pb
        }
    }

    private fun opText(op: String?): String? = when (op) {
        "play" -> "reanudó la música"
        "pause" -> "pausó la música"
        "toggle" -> "pausó o reanudó la música"
        "next", "prev", "playAt", "playTrack" -> "cambió de canción"
        "seek" -> "movió la canción"
        "load", "radio" -> "puso otra música"
        else -> null
    }

    /** Como centro, cuenta a todos quién acaba de tocar la música. */
    private fun announce(byId: String, byName: String, op: String?) {
        if (opText(op) == null) return
        val key = "$byId|$op"
        if (key == lastAnnounce && now() - lastAnnounceAt < 1_500) return
        lastAnnounce = key
        lastAnnounceAt = now()
        DezzyConnect.send(
            buildJsonObject { put("t", "ctrl"); put("by", byId); put("name", byName.take(60)); put("op", op) },
            except = byId,
        )
        if (byId != DezzyConnect.selfId()) showControlNotice(byName, op)
    }

    private fun showControlNotice(name: String, op: String?) {
        val text = opText(op) ?: return
        toast("$name $text")
    }

    /** Aviso corto (como el de B), sólo con la app a la vista. */
    private fun toast(text: String) {
        if (!::appContext.isInitialized) return
        val visible = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (visible) Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show()
    }

    /** Cambia el ajuste «Quién puede controlar» y se lo dice a los nuestros. */
    fun setControlAll(all: Boolean) {
        _controlAll.value = all
        if (::appContext.isInitialized) prefs().edit().putBoolean("control_all", all).apply()
        when (_role.value) {
            Role.LEADER -> broadcast(force = true)
            Role.REMOTE -> _remote.value?.let { pb -> hostId?.let { relay(pb, except = it) } }
            else -> Unit
        }
    }

    /* ── Papeles ── */

    /** Pasa a hacer de mando: aquí suena en silencio lo de allí. */
    private fun becomeRemote(pb: Playback?) {
        setRateNudge(1f)
        _remote.value = pb
        _drift.value = null
        setRole(Role.REMOTE)
        // Sin nada que seguir, este móvil se calla del todo.
        if (pb == null) ours { connection?.player?.pause() }
    }

    /** Deja de hacer de mando. Con `resume`, la música sigue aquí desde donde iba. */
    private fun leaveRemote(resume: Boolean) {
        val pb = _remote.value
        _remote.value = null
        setRole(null)
        _drift.value = null
        val player = connection?.player ?: return
        if (pb == null) {
            if (!resume) ours { player.pause() }
            return
        }
        if (sameTrack(player.currentMediaItem, pb.track)) {
            ours {
                player.seekTo((livePos(pb) * 1000).toLong())
                player.playWhenReady = resume && pb.playing
            }
        } else if (resume) {
            pendingSeek = null
            ours {
                connection?.service?.playQueue(
                    ListQueue(
                        items = pb.queue.map { it.toMediaItem() },
                        startIndex = pb.index,
                        position = (livePos(pb) * 1000).toLong(),
                    ),
                    playWhenReady = pb.playing,
                )
            }
        } else {
            ours { player.pause() }
        }
    }

    private fun becomeMember(hubId: String, name: String, role: Role, controlAll: Boolean) {
        val previous = _hub.value
        _hub.value = Hub(hubId, name, controlAll)
        if (previous?.id != hubId) {
            _offset.value = 0
            holdUntil = 0
        }
        setRole(role)
    }

    /** Suelta a todos los nuestros (otro dispositivo pasa a repartir, o se acabó). */
    private fun releaseMembers(except: String? = null) {
        for (id in memberInfo.keys.toList()) {
            if (id == except) continue
            memberInfo.remove(id)
            leavingIds += id
            DezzyConnect.send(buildJsonObject { put("t", "stop") }, to = id)
            DezzyConnect.close(id)
        }
        if (hostId != null && hostId !in memberInfo) hostId = null
        emitMembers()
    }

    /** Dejamos de seguir a nuestro centro (le avisamos) y seguimos por libre. */
    private fun leaveHub() {
        val hub = _hub.value ?: return
        leavingIds += hub.id
        DezzyConnect.send(buildJsonObject { put("t", "stop") }, to = hub.id)
        DezzyConnect.close(hub.id)
        _hub.value = null
        _drift.value = null
        setRateNudge(1f)
        setRole(null)
    }

    private fun endSession(reason: String?) {
        pendingSeek = null
        setRateNudge(1f)
        if (_role.value == Role.REMOTE) leaveRemote(resume = false)
        setRole(null)
        _hub.value = null
        memberInfo.clear()
        hostId = null
        _drift.value = null
        holdUntil = 0
        emitMembers()
        reason?.let { _notice.value = it }
    }

    /**
     * Uno de los nuestros se fue. Si era el anfitrión, otro de los que suenan toma su sitio; si no
     * queda nadie, `resumeHere` decide si la música vuelve a este móvil.
     */
    private fun removeMember(id: String, resumeHere: Boolean, reason: String?) {
        memberInfo.remove(id) ?: return
        val r = _role.value
        if (r == Role.REMOTE && id == hostId) {
            hostId = null
            val next = memberInfo.keys.firstOrNull()
            val pb = _remote.value
            if (next != null && pb != null) {
                val fresh = pb.copy(pos = livePos(pb), at = now().toDouble())
                memberInfo[next]?.host = true
                hostId = next
                DezzyConnect.send(takeMessage(Mode.THERE, fresh, next), to = next)
            } else {
                if (resumeHere) leaveRemote(resume = true) else endSession(null)
                memberInfo.clear()
            }
        }
        if (memberInfo.isEmpty() && isCenter()) {
            if (_role.value == Role.REMOTE) leaveRemote(resume = resumeHere)
            setRole(null)
        }
        emitMembers()
        reason?.let { _notice.value = it }
    }

    private fun onPeerLost(peer: DezzyConnect.Peer) {
        val quiet = leavingIds.remove(peer.id)
        val hub = _hub.value
        if (hub != null && hub.id == peer.id) {
            endSession(if (quiet) null else "Se perdió la conexión con ${peer.name}.")
            return
        }
        if (peer.id !in memberInfo) return
        val wasHost = peer.id == hostId
        removeMember(
            peer.id,
            resumeHere = false,
            reason = when {
                quiet -> null
                wasHost && memberInfo.size <= 1 -> "Se perdió la conexión con ${peer.name}. Allí la música sigue sonando."
                else -> "Se perdió la conexión con ${peer.name}."
            },
        )
    }

    /* ── Mensajes ── */

    private fun onMessage(incoming: DezzyConnect.Incoming) {
        val from = incoming.from
        val message = incoming.msg
        val r = _role.value
        val hub = _hub.value
        when (message.str("t", 10)) {
            "take" -> onTake(from, incoming.name, message)
            "state" -> {
                val pb = parsePlayback(message["pb"]) ?: return
                if (hub != null && from == hub.id) {
                    val all = message.str("ctl", 5) != "one"
                    if (all != hub.controlAll) _hub.value = hub.copy(controlAll = all)
                    if (r == Role.FOLLOWER) follow(pb)
                } else if (r == Role.REMOTE && from == hostId) {
                    _remote.value = pb
                    mirror(pb)
                    relay(pb, except = from)
                }
            }
            "cmd" -> {
                val cmd = message["cmd"] as? JsonObject ?: return
                val op = cmd.str("op", 20)
                when {
                    hub != null && from == hub.id -> when (r) {
                        Role.HOST -> obey(cmd)
                        // El centro sólo nos toca el volumen.
                        Role.FOLLOWER -> if (op == "vol") cmd.num("v")?.let { setSystemVolume(it) }
                        else -> Unit
                    }
                    isCenter(r) && from in memberInfo -> {
                        // El anfitrión ya manda su estado; los demás piden permiso al centro.
                        if (from == hostId || !_controlAll.value) return
                        if (r == Role.LEADER) {
                            obey(cmd)
                        } else {
                            hostId?.let { DezzyConnect.send(buildJsonObject { put("t", "cmd"); put("cmd", cmd) }, to = it) }
                            optimistic(cmd)
                        }
                        announce(from, incoming.name, op)
                    }
                    r == null -> {
                        // Nos maneja sin «take» (p. ej. el ordenador): hacemos de anfitrión.
                        becomeMember(from, incoming.name, Role.HOST, controlAll = true)
                        obey(cmd)
                    }
                }
            }
            "stop" -> {
                if (hub != null && from == hub.id) {
                    leavingIds += from
                    if (r != Role.REMOTE) ours { connection?.player?.pause() }
                    endSession(null)
                    DezzyConnect.close(from)
                } else if (from in memberInfo) {
                    val wasHost = from == hostId
                    leavingIds += from
                    removeMember(
                        from,
                        resumeHere = false,
                        reason = if (wasHost && memberInfo.size <= 1) "${incoming.name} dejó de compartir. Allí la música sigue sonando." else null,
                    )
                    DezzyConnect.close(from)
                }
            }
            "drift" -> memberInfo[from]?.let { info ->
                info.drift = message.num("ms")?.roundToInt()
                message.num("vol")?.let { info.volume = it.toFloat().coerceIn(0f, 1f) }
                emitMembers()
            }
            "offset" -> if (r == Role.FOLLOWER && hub?.id == from) {
                message.num("ms")?.let { _offset.value = it.roundToInt().coerceIn(-500, 500) }
            }
            "ctrl" -> if (hub != null && from == hub.id) {
                if (message.str("by", 16) != DezzyConnect.selfId()) {
                    showControlNotice(message.str("name", 60) ?: hub.name, message.str("op", 20))
                }
            }
        }
    }

    private fun onTake(from: String, name: String, message: JsonObject) {
        val pb = parsePlayback(message["pb"]) ?: return
        val r = _role.value
        // Otro pasa a repartir: dejamos de ser centro.
        if (isCenter(r)) {
            if (r == Role.REMOTE) leaveRemote(resume = false)
            releaseMembers(except = from)
            memberInfo.clear()
            hostId = null
            emitMembers()
        }
        // Nos llevaba otro centro: le avisamos de que nos vamos.
        _hub.value?.takeIf { it.id != from }?.let { old ->
            leavingIds += old.id
            DezzyConnect.send(buildJsonObject { put("t", "stop") }, to = old.id)
            DezzyConnect.close(old.id)
        }
        val all = message.str("ctl", 5) != "one"
        if (message.str("mode", 10) == "both") {
            becomeMember(from, name, Role.FOLLOWER, all)
            message.num("offset")?.let { _offset.value = it.roundToInt().coerceIn(-500, 500) }
            _drift.value = null
            follow(pb)
        } else {
            becomeMember(from, name, Role.HOST, all)
            _drift.value = null
            setRateNudge(1f)
            val player = connection?.player
            // Ya sonaba lo mismo (veníamos de «En ambos»): no se vuelve a cargar.
            if (player != null && sameTrack(player.currentMediaItem, pb.track)) {
                ours { player.playWhenReady = pb.playing }
            } else {
                playFrom(pb)
            }
            broadcast(force = true)
        }
    }

    /* ── Acciones de la hoja de dispositivos ── */

    fun search() = DezzyConnect.search()

    /** Lo que suena ahora en el grupo (aquí o en el anfitrión), al momento. */
    private fun currentPlayback(): Playback? =
        if (_role.value == Role.REMOTE) {
            _remote.value?.let { it.copy(pos = livePos(it), at = now().toDouble()) }
        } else {
            localPlayback()
        }

    private suspend fun ensureLink(id: String): Boolean {
        if (DezzyConnect.peers.value.any { it.id == id }) return true
        if (_busy.value != null) return false
        if (memberInfo.size >= DezzyConnect.MAX_LINKS) {
            _notice.value = "Ya suena en ${DezzyConnect.MAX_LINKS + 1} dispositivos, el máximo."
            return false
        }
        _busy.value = id
        _notice.value = null
        val problem = DezzyConnect.open(id)
        _busy.value = null
        if (problem != null) {
            _notice.value = problem
            return false
        }
        return true
    }

    /** Tocar un dispositivo: la música pasa a sonar sólo allí (como en Spotify). */
    fun moveTo(id: String) {
        if (_role.value == Role.REMOTE && hostId == id && memberInfo.size == 1) return
        scope.launch {
            if (!ensureLink(id)) return@launch
            val pb = currentPlayback()
            if (_hub.value != null) leaveHub()
            releaseMembers(except = id)
            val wasRemote = _role.value == Role.REMOTE
            memberInfo.clear()
            memberInfo[id] = MemberInfo(host = true, taken = true, offset = savedOffset(id))
            hostId = id
            emitMembers()
            if (pb == null) {
                // Nada que pasar: se hace de mando de lo que suene allí.
                becomeRemote(null)
                DezzyConnect.send(buildJsonObject { put("t", "cmd"); put("cmd", buildJsonObject { put("op", "sync") }) }, to = id)
                return@launch
            }
            DezzyConnect.send(takeMessage(Mode.THERE, pb, id), to = id)
            if (wasRemote) _remote.value = pb else becomeRemote(pb)
        }
    }

    /** Casilla de un dispositivo: que suene también allí, o que deje de sonar. */
    fun setPlaying(id: String, on: Boolean) {
        if (!on) {
            if (id !in memberInfo) return
            leavingIds += id
            DezzyConnect.send(buildJsonObject { put("t", "stop") }, to = id)
            removeMember(id, resumeHere = true, reason = null)
            DezzyConnect.close(id)
            return
        }
        if (id in memberInfo) return
        scope.launch {
            if (!ensureLink(id)) return@launch
            if (_hub.value != null) leaveHub()
            val info = MemberInfo(host = false, offset = savedOffset(id))
            memberInfo[id] = info
            emitMembers()
            val pb = currentPlayback()
            if (_role.value != Role.REMOTE) setRole(Role.LEADER)
            if (pb != null) {
                info.taken = true
                DezzyConnect.send(takeMessage(Mode.BOTH, pb, id), to = id)
            }
            broadcast(force = true)
        }
    }

    /** Casilla «Este móvil». */
    fun setHere(on: Boolean) {
        val r = _role.value
        if (on) {
            if (r != Role.REMOTE) return
            val pb = _remote.value
            leaveRemote(resume = true)
            setRole(Role.LEADER)
            hostId = null
            if (pb == null) return
            val fresh = pb.copy(pos = livePos(pb), at = now().toDouble())
            for ((id, info) in memberInfo) {
                info.host = false
                info.taken = true
                DezzyConnect.send(takeMessage(Mode.BOTH, fresh, id), to = id)
            }
            emitMembers()
        } else {
            if (r != Role.LEADER) return
            val first = memberInfo.keys.firstOrNull() ?: return
            val pb = localPlayback() ?: return
            memberInfo[first]?.host = true
            hostId = first
            DezzyConnect.send(takeMessage(Mode.THERE, pb, first), to = first)
            becomeRemote(pb)
            emitMembers()
        }
    }

    /** Compatibilidad: conectar eligiendo el modo de antes. */
    fun connectTo(id: String, mode: Mode) {
        if (mode == Mode.THERE) moveTo(id) else setPlaying(id, true)
    }

    /** «Este móvil»: la música vuelve sólo aquí. */
    fun bringHere() {
        if (_role.value != Role.REMOTE) return
        releaseMembers()
        memberInfo.clear()
        hostId = null
        leaveRemote(resume = true)
        emitMembers()
    }

    /** «Sonar también aquí»: el móvil se suma a los que suenan. */
    fun alsoHere() = setHere(true)

    /** Deja de sonar aquí: como centro, el primero de los nuestros pasa a sonar solo. */
    fun stopHere() {
        when (_role.value) {
            Role.LEADER -> setHere(false)
            Role.FOLLOWER -> {
                ours { connection?.player?.pause() }
                leaveHub()
            }
            else -> Unit
        }
    }

    /** Quita a todos; aquí sigue sonando. */
    fun dropPeer() {
        when {
            _role.value == Role.REMOTE -> bringHere()
            isCenter() -> {
                releaseMembers()
                memberInfo.clear()
                setRole(null)
                emitMembers()
            }
            else -> release()
        }
    }

    /** Nos controlaba otro: se corta y la música sigue aquí. */
    fun release() {
        leaveHub()
        endSession(null)
    }

    fun forget(id: String) {
        prefs().edit().remove("mode_$id").remove("offset_$id").apply()
        if (DezzyConnect.peers.value.any { it.id == id }) leavingIds += id
        if (id in memberInfo) removeMember(id, resumeHere = true, reason = null)
        DezzyConnect.forget(id)
    }

    private fun savedOffset(id: String) = prefs().getInt("offset_$id", 0)

    /** Ajuste de sincronía de uno de los nuestros. */
    fun setOffset(id: String, ms: Int) {
        val value = ms.coerceIn(-500, 500)
        memberInfo[id]?.offset = value
        prefs().edit().putInt("offset_$id", value).apply()
        emitMembers()
        DezzyConnect.send(buildJsonObject { put("t", "offset"); put("ms", value) }, to = id)
    }

    /** Volumen de uno de los nuestros. */
    fun setMemberVolume(id: String, v: Float) {
        val value = v.coerceIn(0f, 1f)
        memberInfo[id]?.volume = value
        emitMembers()
        DezzyConnect.send(buildJsonObject { put("t", "cmd"); put("cmd", buildJsonObject { put("op", "vol"); put("v", value) }) }, to = id)
        if (id == hostId) _remote.value = _remote.value?.copy(vol = value.toDouble())
    }

    /** Controles del mando (cuando suena allí). Pasan por el reproductor de aquí, que lo reenvía. */
    fun remoteToggle() {
        val pb = _remote.value ?: return
        onUserAction(buildJsonObject { put("op", if (pb.playing) "pause" else "play") })
        ours { connection?.player?.playWhenReady = !pb.playing }
    }

    fun remoteNext() = remoteCmd(buildJsonObject { put("op", "next") })

    fun remotePrev() = remoteCmd(buildJsonObject { put("op", "prev") })

    fun remoteSeek(seconds: Double) = remoteCmd(buildJsonObject { put("op", "seek"); put("pos", seconds) })

    fun remoteVolume(v: Float) {
        hostId?.let { setMemberVolume(it, v) }
    }

    private fun remoteCmd(cmd: JsonObject) {
        if (_role.value != Role.REMOTE) return
        onUserAction(cmd)
    }

    fun clearNotice() {
        _notice.value = null
    }

    /** Texto para la hoja y el reproductor. */
    fun describe(): String? {
        val names = _members.value.map { it.name }
        return when (_role.value) {
            Role.REMOTE -> "Sonando en ${names.joinToString(", ")}"
            Role.LEADER -> "Sonando en este móvil y ${names.joinToString(", ")}"
            Role.FOLLOWER -> _hub.value?.let { "Sonando a la vez que ${it.name}" }
            Role.HOST -> _hub.value?.let { "Lo controla ${it.name}" }
            null -> null
        }
    }
}
