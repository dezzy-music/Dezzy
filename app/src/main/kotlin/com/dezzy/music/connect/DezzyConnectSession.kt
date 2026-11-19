/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.connect

import android.content.Context
import android.media.AudioManager
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
 * Dezzy Connect en el reproductor: qué hace este móvil con el dispositivo conectado. Es la misma
 * lógica que `store/connect.ts` en Dezzy Desktop.
 *
 * Papeles:
 * - [Role.REMOTE]: suena allí y aquí hacemos de mando. El móvil se calla y la hoja de
 *   dispositivos muestra lo que suena allí con sus controles.
 * - [Role.HOST]: suena aquí y el otro nos controla. Mandamos nuestro estado y obedecemos.
 * - [Role.LEADER] / [Role.FOLLOWER]: suena en los dos. Quien lo pidió marca el paso; el otro le
 *   sigue (salto si se va más de 250 ms, un pelo más rápido o lento si es menos).
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

    private const val PREFS = "dezzy_connect_session"
    private const val QUEUE_WINDOW = 200
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

    private val _role = MutableStateFlow<Role?>(null)
    val role: StateFlow<Role?> = _role.asStateFlow()

    /** Diferencia medida entre los dos, en ms (sólo «En ambos»). */
    private val _drift = MutableStateFlow<Int?>(null)
    val drift: StateFlow<Int?> = _drift.asStateFlow()

    /** Volumen del otro en «En ambos» (0..1). */
    private val _peerVolume = MutableStateFlow<Float?>(null)
    val peerVolume: StateFlow<Float?> = _peerVolume.asStateFlow()

    /** Ajuste manual de sincronía en ms (positivo: el que sigue va por delante). */
    private val _offset = MutableStateFlow(0)
    val offset: StateFlow<Int> = _offset.asStateFlow()

    /** Dispositivo al que se está conectando. */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** Lo que suena allí cuando hacemos de mando. */
    private val _remote = MutableStateFlow<Playback?>(null)
    val remote: StateFlow<Playback?> = _remote.asStateFlow()

    private val scope = MainScope()
    private lateinit var appContext: Context
    private var started = false
    private var connection: PlayerConnection? = null

    /** Canción cargada a petición del otro: se coloca en su sitio cuando esté lista. */
    private var pendingSeek: Pair<Playback, Long>? = null
    /** Posición de cada canción enviada dentro de la cola del reproductor. */
    private var sentMap: List<Int> = emptyList()
    private var lastKey = ""
    private var lastDriftSent = 0L
    private var tick = 0
    /** Corte pedido por nosotros: no se avisa como pérdida de conexión. */
    private var leaving = false
    /** Cambios del reproductor hechos por nosotros (no son del usuario). */
    private var applying = 0
    /** Velocidad que había antes de empezar a corregir el paso. */
    private var baseParameters: PlaybackParameters? = null
    private var lastRate = 1f

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Haciendo de mando, darle a reproducir aquí es darle allí.
            if (playWhenReady && applying == 0 && _role.value == Role.REMOTE) {
                sendCmd(buildJsonObject { put("op", "play") })
                ours { connection?.player?.pause() }
                _remote.value = _remote.value?.let { it.copy(pos = livePos(it), at = now().toDouble(), playing = true) }
            }
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
        DezzyConnect.init(appContext)
        scope.launch { DezzyConnect.messages.collect { onMessage(it) } }
        scope.launch {
            var before: DezzyConnect.Peer? = null
            DezzyConnect.peer.collect { peer ->
                if (peer != null && peer.id != before?.id) _offset.value = prefs().getInt("offset_${peer.id}", 0)
                // Se cerró la conexión.
                if (before != null && peer == null) {
                    val r = _role.value
                    val wasLeaving = leaving
                    leaving = false
                    if (r != null) {
                        endSession(
                            when {
                                wasLeaving -> null
                                r == Role.REMOTE -> "Se perdió la conexión con ${before?.name}. Allí la música sigue sonando."
                                else -> "Se perdió la conexión con ${before?.name}."
                            },
                        )
                    }
                }
                before = peer
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

    /** Sigue al otro: misma canción, mismo punto. */
    private fun follow(pb: Playback) {
        val player = connection?.player ?: return
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
        if (now() - lastDriftSent > 900) {
            lastDriftSent = now()
            DezzyConnect.send(buildJsonObject { put("t", "drift"); put("ms", ms); put("vol", systemVolume()) })
        }
    }

    /** Aplica una orden del otro como si la hubiera dado el usuario aquí. */
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
                // «queue» (reordenar desde el otro) no se aplica aquí en esta versión: manda el estado.
                else -> Unit
            }
        }
        broadcast(force = true)
    }

    /** Como anfitrión o líder, cuenta al otro lo que suena aquí. */
    private fun broadcast(force: Boolean = false) {
        val r = _role.value
        if (r != Role.HOST && r != Role.LEADER) return
        val pb = localPlayback() ?: return
        val key = "${pb.index}|${pb.playing}|${(pb.vol * 100).roundToInt()}|${pb.queue.joinToString(",") { it.id }}"
        if (!force && key == lastKey) return
        lastKey = key
        DezzyConnect.send(buildJsonObject { put("t", "state"); put("pb", pb.toJson()) })
    }

    /* ── Mando a distancia ── */

    private fun sendCmd(cmd: JsonObject) {
        DezzyConnect.send(buildJsonObject { put("t", "cmd"); put("cmd", cmd) })
    }

    /** Pasa a hacer de mando: aquí se calla y se muestra lo de allí. */
    private fun becomeRemote(pb: Playback?) {
        setRateNudge(1f)
        ours { connection?.player?.pause() }
        _remote.value = pb
        _drift.value = null
        _role.value = Role.REMOTE
    }

    /** Deja de hacer de mando. Con `resume`, la música sigue aquí desde donde iba. */
    private fun leaveRemote(resume: Boolean) {
        val pb = _remote.value
        _remote.value = null
        _role.value = null
        _drift.value = null
        _peerVolume.value = null
        val player = connection?.player ?: return
        if (pb == null) return
        if (sameTrack(player.currentMediaItem, pb.track)) {
            ours {
                player.seekTo((livePos(pb) * 1000).toLong())
                if (resume && pb.playing) player.play()
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
        }
    }

    private fun endSession(reason: String?) {
        pendingSeek = null
        setRateNudge(1f)
        if (_role.value == Role.REMOTE) leaveRemote(resume = false)
        _role.value = null
        _drift.value = null
        _peerVolume.value = null
        _notice.value = reason
    }

    private fun onMessage(message: JsonObject) {
        val r = _role.value
        when (message.str("t", 10)) {
            "take" -> {
                val pb = parsePlayback(message["pb"]) ?: return
                if (r == Role.REMOTE) leaveRemote(resume = false)
                if (message.str("mode", 10) == "both") {
                    _role.value = Role.FOLLOWER
                    _drift.value = null
                    follow(pb)
                } else {
                    _role.value = Role.HOST
                    _drift.value = null
                    _peerVolume.value = null
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
            "state" -> {
                val pb = parsePlayback(message["pb"]) ?: return
                when (r) {
                    Role.REMOTE -> {
                        _remote.value = pb
                    }
                    Role.FOLLOWER -> follow(pb)
                    else -> Unit
                }
            }
            "cmd" -> {
                val cmd = message["cmd"] as? JsonObject ?: return
                when (r) {
                    Role.REMOTE -> Unit
                    // El líder sólo nos toca el volumen.
                    Role.FOLLOWER -> if (cmd.str("op", 10) == "vol") cmd.num("v")?.let { setSystemVolume(it) }
                    else -> {
                        if (r == null) _role.value = Role.HOST
                        obey(cmd)
                    }
                }
            }
            "stop" -> {
                leaving = true
                if (r != Role.REMOTE) ours { connection?.player?.pause() }
                endSession(null)
                DezzyConnect.close()
            }
            "drift" -> {
                if (r != Role.LEADER) return
                _drift.value = message.num("ms")?.roundToInt()
                message.num("vol")?.let { _peerVolume.value = it.toFloat().coerceIn(0f, 1f) }
            }
            "offset" -> {
                if (r == Role.FOLLOWER) message.num("ms")?.let { _offset.value = it.roundToInt().coerceIn(-500, 500) }
            }
        }
    }

    /* ── Acciones de la hoja de dispositivos ── */

    fun search() = DezzyConnect.search()

    fun rememberedMode(id: String): Mode? =
        prefs().getString("mode_$id", null)?.let { saved -> Mode.entries.firstOrNull { it.wire == saved } }

    fun connectTo(id: String, mode: Mode, remember: Boolean) {
        if (_busy.value != null) return
        if (remember) prefs().edit().putString("mode_$id", mode.wire).apply()
        _busy.value = id
        _notice.value = null
        scope.launch {
            val problem = DezzyConnect.open(id)
            _busy.value = null
            if (problem != null) {
                _notice.value = problem
                return@launch
            }
            if (_role.value == Role.REMOTE) leaveRemote(resume = mode == Mode.BOTH)
            val pb = localPlayback()
            if (pb == null) {
                // Nada que pasar: queda conectado y se hace de mando de lo que suene allí.
                if (mode == Mode.THERE) {
                    becomeRemote(null)
                } else {
                    _role.value = Role.LEADER
                }
                return@launch
            }
            DezzyConnect.send(buildJsonObject { put("t", "take"); put("mode", mode.wire); put("pb", pb.toJson()) })
            if (mode == Mode.THERE) {
                becomeRemote(pb)
            } else {
                _role.value = Role.LEADER
                _drift.value = null
                broadcast(force = true)
            }
        }
    }

    /** «Este móvil»: la música vuelve aquí. */
    fun bringHere() {
        if (_role.value != Role.REMOTE) return
        leaving = true
        DezzyConnect.send(buildJsonObject { put("t", "stop") })
        leaveRemote(resume = true)
        DezzyConnect.close()
    }

    /** «Sonar también aquí»: de «Sólo allí» a «En ambos». */
    fun alsoHere() {
        val pb = _remote.value
        if (_role.value != Role.REMOTE || pb == null) return
        leaveRemote(resume = true)
        _role.value = Role.LEADER
        _drift.value = null
        val now = pb.copy(pos = livePos(pb), at = now().toDouble())
        DezzyConnect.send(buildJsonObject { put("t", "take"); put("mode", "both"); put("pb", now.toJson()) })
    }

    /** En «En ambos», deja de sonar aquí y el otro sigue solo. */
    fun stopHere() {
        val r = _role.value
        if (r != Role.LEADER && r != Role.FOLLOWER) return
        val pb = localPlayback() ?: return
        DezzyConnect.send(buildJsonObject { put("t", "take"); put("mode", "there"); put("pb", pb.toJson()) })
        becomeRemote(pb)
    }

    /** Quita el otro dispositivo; aquí sigue sonando. */
    fun dropPeer() {
        if (_role.value == Role.REMOTE) {
            bringHere()
            return
        }
        leaving = true
        DezzyConnect.send(buildJsonObject { put("t", "stop") })
        endSession(null)
        DezzyConnect.close()
    }

    /** El otro nos controlaba: se corta y la música sigue aquí. */
    fun release() {
        leaving = true
        endSession(null)
        DezzyConnect.close()
    }

    fun forget(id: String) {
        prefs().edit().remove("mode_$id").remove("offset_$id").apply()
        if (DezzyConnect.peer.value?.id == id) leaving = true
        DezzyConnect.forget(id)
    }

    fun setOffset(ms: Int) {
        val value = ms.coerceIn(-500, 500)
        _offset.value = value
        DezzyConnect.peer.value?.let { prefs().edit().putInt("offset_${it.id}", value).apply() }
        if (_role.value == Role.LEADER) DezzyConnect.send(buildJsonObject { put("t", "offset"); put("ms", value) })
    }

    fun setPeerVolume(v: Float) {
        val value = v.coerceIn(0f, 1f)
        _peerVolume.value = value
        sendCmd(buildJsonObject { put("op", "vol"); put("v", value) })
    }

    /** Controles del mando (cuando suena allí). */
    fun remoteToggle() {
        val pb = _remote.value ?: return
        sendCmd(buildJsonObject { put("op", "toggle") })
        _remote.value = pb.copy(pos = livePos(pb), at = now().toDouble(), playing = !pb.playing)
    }

    fun remoteNext() = sendCmd(buildJsonObject { put("op", "next") })

    fun remotePrev() = sendCmd(buildJsonObject { put("op", "prev") })

    fun remoteSeek(seconds: Double) {
        val pb = _remote.value ?: return
        sendCmd(buildJsonObject { put("op", "seek"); put("pos", seconds) })
        _remote.value = pb.copy(pos = seconds, at = now().toDouble())
    }

    fun remoteVolume(v: Float) {
        val pb = _remote.value ?: return
        sendCmd(buildJsonObject { put("op", "vol"); put("v", v.coerceIn(0f, 1f)) })
        _remote.value = pb.copy(vol = v.coerceIn(0f, 1f).toDouble())
    }

    fun clearNotice() {
        _notice.value = null
    }

    /** Texto para la hoja y el reproductor. */
    fun describe(role: Role?, name: String): String? = when (role) {
        Role.REMOTE -> "Sonando en $name"
        Role.LEADER, Role.FOLLOWER -> "Sonando en este móvil y $name"
        Role.HOST -> "Lo controla $name"
        null -> null
    }

}
