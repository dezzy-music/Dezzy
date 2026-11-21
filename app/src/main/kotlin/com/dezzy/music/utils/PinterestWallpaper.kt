/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/**
 * Fondos desde Pinterest (mismo método que Dezzy Desktop).
 *
 * Acepta pin.it/…, pinterest.<país>/pin/… y enlaces directos a i.pinimg.com / v1.pinimg.com.
 * Cada salto (redirecciones incluidas) se comprueba contra la lista de hosts de Pinterest, y cada
 * descarga tiene tiempo límite, tipo comprobado (cabecera + firma de los primeros bytes) y tope de tamaño.
 * El archivo se guarda en la carpeta privada de la app, así que no hace falta ningún permiso.
 */
object PinterestWallpaper {
    sealed interface Result {
        /** [isLandscape]: más ancho que alto (ya contando la rotación del video o el EXIF de la foto). */
        data class Ok(val file: File, val isVideo: Boolean, val title: String, val isLandscape: Boolean) : Result
        data class Error(val message: String) : Result
    }

    private const val PAGE_TIMEOUT_S = 15L
    private const val MEDIA_TIMEOUT_S = 90L
    private const val PAGE_MAX = 8L * 1024 * 1024
    private const val IMAGE_MAX = 40L * 1024 * 1024
    private const val VIDEO_MAX = 300L * 1024 * 1024
    private const val MAX_HOPS = 5
    private const val DIR = "wallpapers"
    private const val PREFIX = "pin-"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private val IMAGE_TYPES = mapOf(
        "image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp", "image/gif" to "gif", "image/avif" to "avif",
    )
    private val VIDEO_TYPES = mapOf("video/mp4" to "mp4", "video/webm" to "webm")

    private object Err {
        const val INVALID = "Ese enlace no es de un pin de Pinterest. Copia el enlace del pin (pin.it/… o pinterest.com/pin/…)."
        const val GONE = "No se encuentra ese pin: puede ser privado o haberse borrado."
        const val OFFLINE = "Sin conexión. Prueba otra vez cuando vuelva internet."
        const val TIMEOUT = "Pinterest tarda demasiado en responder. Prueba otra vez."
        const val BIG = "La imagen o el video del pin pesa demasiado."
        const val TYPE = "Ese pin no tiene una imagen ni un video que Dezzy pueda usar."
        const val FAIL = "No se pudo bajar el pin. Prueba otra vez en un rato."
    }

    private class PinError(message: String) : Exception(message)

    private val PINTEREST_HOST = Regex("""^(?:[a-z0-9-]+\.)?pinterest\.(?:com|[a-z]{2}|co\.[a-z]{2}|com\.[a-z]{2})$""")
    private val PINIMG_HOST = Regex("""^(?:i|v|v1|v2)\.pinimg\.com$""")

    private fun isPinterestHost(h: String) = h == "pin.it" || PINTEREST_HOST.matches(h)
    private fun isPinimgHost(h: String) = PINIMG_HOST.matches(h)

    /** Sin redirecciones automáticas: cada salto se valida a mano. */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(PAGE_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** ¿Lo pegado parece de Pinterest? Sirve para mostrar el logo junto al campo. */
    fun isPinterestUrl(raw: String): Boolean {
        val u = parse(raw) ?: return false
        return isPinterestHost(u.host) || isPinimgHost(u.host)
    }

    /** ¿Este fondo lo bajó Dezzy de Pinterest? (para borrar el archivo viejo al cambiarlo o quitarlo). */
    fun isOwnFile(context: Context, uri: String): Boolean {
        if (!uri.startsWith("file://")) return false
        val path = Uri.parse(uri).path ?: return false
        val f = File(path)
        return f.name.startsWith(PREFIX) && f.parentFile?.canonicalPath == File(context.filesDir, DIR).canonicalPath
    }

    fun deleteOwnFile(context: Context, uri: String) {
        if (isOwnFile(context, uri)) Uri.parse(uri).path?.let { File(it).delete() }
    }

    private fun parse(raw: String): HttpUrl? {
        var s = raw.trim()
        if (!Regex("^[a-zA-Z]+://").containsMatchIn(s)) s = "https://$s" // «pin.it/abc» sin https:// también vale
        val u = s.toHttpUrlOrNull() ?: return null
        if (u.username.isNotEmpty() || u.password.isNotEmpty()) return null
        if (u.port != 80 && u.port != 443) return null
        return u.newBuilder().scheme("https").port(443).build()
    }

    private fun pinId(u: HttpUrl): String? =
        Regex("""/pin/(?:[^/]*--)?(\d{5,22})(?:/|$)""").find(u.encodedPath)?.groupValues?.get(1)

    private fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Petición con redirecciones a mano: cada salto tiene que seguir en hosts permitidos. */
    private fun get(
        context: Context,
        start: HttpUrl,
        allow: (String) -> Boolean,
        timeoutS: Long,
        accept: String,
    ): Pair<Response, HttpUrl> {
        var url = start
        repeat(MAX_HOPS + 1) {
            if (url.scheme != "https" || !allow(url.host)) throw PinError(Err.INVALID)
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", accept)
                .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                .build()
            val call = client.newCall(req)
            call.timeout().timeout(timeoutS, TimeUnit.SECONDS)
            val res = try {
                call.execute()
            } catch (e: InterruptedIOException) {
                throw PinError(if (isOnline(context)) Err.TIMEOUT else Err.OFFLINE)
            } catch (e: IOException) {
                throw PinError(if (isOnline(context)) Err.FAIL else Err.OFFLINE)
            }
            if (res.code in 300..399) {
                val loc = res.header("Location")
                res.close()
                url = loc?.let { url.resolve(it) } ?: throw PinError(Err.FAIL)
                return@repeat
            }
            return res to url
        }
        throw PinError(Err.FAIL)
    }

    /** Lee el cuerpo como texto sin pasar de `max` bytes. */
    private fun readText(res: Response, max: Long): String = res.use { r ->
        val body = r.body ?: throw PinError(Err.FAIL)
        if (body.contentLength() > max) throw PinError(Err.FAIL)
        val src = body.source()
        // request() lee hasta max+1 bytes o hasta el final: si llega a max+1, la página es demasiado grande.
        if (src.request(max + 1)) throw PinError(Err.FAIL)
        src.buffer.readUtf8()
    }

    /** Firma de los primeros bytes: que el archivo sea de verdad lo que dice la cabecera. */
    private fun sniff(b: ByteArray): String? {
        if (b.size < 12) return null
        fun ascii(from: Int, to: Int) = String(b.copyOfRange(from, to), Charsets.ISO_8859_1)
        val u = { i: Int -> b[i].toInt() and 0xff }
        return when {
            u(0) == 0xff && u(1) == 0xd8 && u(2) == 0xff -> "jpg"
            u(0) == 0x89 && ascii(1, 4) == "PNG" -> "png"
            ascii(0, 4) == "GIF8" -> "gif"
            ascii(0, 4) == "RIFF" && ascii(8, 12) == "WEBP" -> "webp"
            ascii(4, 8) == "ftyp" -> if (Regex("^avi[fs]").containsMatchIn(ascii(8, 12))) "avif" else "mp4"
            u(0) == 0x1a && u(1) == 0x45 && u(2) == 0xdf && u(3) == 0xa3 -> "webm"
            else -> null
        }
    }

    private fun unescapeJson(s: String) = s.replace(Regex("""\\u002F""", RegexOption.IGNORE_CASE), "/").replace("\\/", "/")

    private data class PinMedia(val video: String?, val image: String?, val title: String)

    /** Saca del HTML del pin el mejor medio del propio pin (no de los relacionados que vienen debajo). */
    private fun pickFromPage(html: String, id: String): PinMedia? {
        // Bloque de datos del pin pedido: Pinterest lo manda como respuesta Relay de «v3GetPinQueryv2».
        val at = html.indexOf("\"v3GetPinQueryv2\"")
        val end = if (at < 0) -1 else html.indexOf("</script>", at)
        val raw = if (at < 0) "" else unescapeJson(html.substring(at, if (end < 0) minOf(html.length, at + 200_000) else end))
        // Sólo vale si es el pin pedido; si no, se tira de og:image, que siempre es el del pin.
        val block = if (raw.contains("\"entityId\":\"$id\"")) raw else ""
        val title = (
            Regex("\"seoTitle\":\"([^\"]{1,200})\"").find(block)?.groupValues?.get(1)
                ?: Regex("<meta content=\"([^\"]{1,200})\"[^>]*og:title\"").find(html)?.groupValues?.get(1)
                ?: ""
            )
            .replace(Regex("""\\u([0-9a-fA-F]{4})""")) { it.groupValues[1].toInt(16).toChar().toString() }
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")

        var video: String? = null
        if (block.isNotEmpty()) {
            // Pin de video normal: el MP4 de 720p es H.264 (los «hevcMp4» no se ven en todos los móviles).
            video = Regex(""""v720P":\{[^}]*?"url":"(https://v1\.pinimg\.com/[^"]+\.mp4)"""").find(block)?.groupValues?.get(1)
                ?: Regex(""""(https://v1\.pinimg\.com/videos/mc/[^"]+\.mp4)"""").find(block)?.groupValues?.get(1)
                // Pin de idea (storyPinData): sólo trae HLS; el MP4 se saca luego de su lista maestra.
                ?: Regex(""""(https://v1\.pinimg\.com/videos/iht/hls/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{32}\.m3u8)"""")
                    .find(block)?.groupValues?.get(1)
        }
        val og = ogImage(html)
        val sig = Regex("\"imageSignature\":\"([0-9a-f]{32})\"").find(block)?.groupValues?.get(1)
            ?: Regex("""i\.pinimg\.com/\w+/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{2}/([0-9a-f]{32})""").find(og ?: "")?.groupValues?.get(1)
        var image = sig?.let {
            Regex("""https://i\.pinimg\.com/originals/[0-9a-f/]{9}$it\.(jpg|jpeg|png|webp|gif)""").find(unescapeJson(html))?.value
        }
        if (image == null) image = og
        return if (video != null || image != null) PinMedia(video, image, title) else null
    }

    private fun ogImage(html: String): String? =
        (
            Regex("""<meta[^>]+content="(https://i\.pinimg\.com/[^"]+)"[^>]*property="og:image"""").find(html)
                ?: Regex("""<meta[^>]+property="og:image"[^>]*content="(https://i\.pinimg\.com/[^"]+)"""").find(html)
            )?.groupValues?.get(1)

    /** Para un enlace directo a i.pinimg.com: prueba antes el original a toda resolución. */
    private fun originalsFor(u: HttpUrl): List<HttpUrl> {
        val m = Regex("""^/[^/]+/([0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{32})\.(\w+)$""").find(u.encodedPath)
        if (m == null || u.encodedPath.startsWith("/originals/")) return listOf(u)
        val exts = listOf(m.groupValues[2], "jpg", "png", "webp", "gif").distinct()
        return exts.mapNotNull { "https://i.pinimg.com/originals/${m.groupValues[1]}.$it".toHttpUrlOrNull() } + u
    }

    /** Busca el medio del pin. No descarga nada todavía. */
    private fun resolvePin(context: Context, raw: String): PinMedia {
        val start = parse(raw)
        if (start == null || !(isPinterestHost(start.host) || isPinimgHost(start.host))) throw PinError(Err.INVALID)
        if (isPinimgHost(start.host)) {
            return if (start.host == "i.pinimg.com") PinMedia(null, start.toString(), "") else PinMedia(start.toString(), null, "")
        }
        var url = start
        if (start.host == "pin.it") {
            // El acortador lleva a api.pinterest.com y de ahí al pin. Se sigue con la misma lista de hosts.
            val (res, landed) = get(context, start, ::isPinterestHost, PAGE_TIMEOUT_S, "text/html")
            val code = res.code
            res.close()
            if (code == 404) throw PinError(Err.GONE)
            url = landed
        }
        val id = pinId(url) ?: throw PinError(if (start.host == "pin.it") Err.GONE else Err.INVALID)
        val page = "https://www.pinterest.com/pin/$id/".toHttpUrlOrNull()!!
        val (res, _) = get(context, page, ::isPinterestHost, PAGE_TIMEOUT_S, "text/html")
        if (res.code == 404 || res.code == 410) {
            res.close(); throw PinError(Err.GONE)
        }
        if (!res.isSuccessful) {
            res.close(); throw PinError(Err.FAIL)
        }
        return pickFromPage(readText(res, PAGE_MAX), id) ?: throw PinError(Err.GONE)
    }

    /**
     * HLS de un pin de idea → MP4 H.264 equivalentes, del más ancho al más estrecho (hasta 1920 px).
     * Pinterest publica cada ancho de la lista maestra también como `iht/expMp4/<hash>_<ancho>w.mp4`.
     */
    private fun mp4sFromHls(context: Context, m3u8: HttpUrl): List<HttpUrl> {
        val m = Regex("""^/videos/iht/hls/([0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{32})\.m3u8$""").find(m3u8.encodedPath)
            ?: return emptyList()
        var widths = emptyList<Int>()
        try {
            val (res, _) = get(context, m3u8, ::isPinimgHost, PAGE_TIMEOUT_S, "application/vnd.apple.mpegurl,*/*")
            if (res.isSuccessful) {
                widths = Regex("""_(\d{3,4})w\.m3u8""").findAll(readText(res, 256 * 1024)).map { it.groupValues[1].toInt() }.toList()
            } else res.close()
        } catch (e: PinError) {
            if (e.message == Err.OFFLINE) throw e
        }
        return (widths.filter { it <= 1920 } + 720).distinct().sortedDescending()
            .mapNotNull { "https://v1.pinimg.com/videos/iht/expMp4/${m.groupValues[1]}_${it}w.mp4".toHttpUrlOrNull() }
    }

    /** Baja directo a un archivo temporal (los videos pueden pesar mucho), con tope y firma comprobada. */
    private fun download(context: Context, u: HttpUrl, wantVideo: Boolean): Pair<File, Boolean> {
        val (res, _) = get(context, u, ::isPinimgHost, MEDIA_TIMEOUT_S, if (wantVideo) "video/*" else "image/avif,image/webp,image/*")
        res.use { r ->
            if (r.code == 403 || r.code == 404) throw PinError(Err.GONE)
            if (!r.isSuccessful) throw PinError(Err.FAIL)
            val type = (r.header("Content-Type") ?: "").substringBefore(';').trim().lowercase()
            val isVideo = type in VIDEO_TYPES
            if (type !in IMAGE_TYPES && !isVideo) throw PinError(Err.TYPE)
            val max = if (isVideo) VIDEO_MAX else IMAGE_MAX
            val body = r.body ?: throw PinError(Err.FAIL)
            if (body.contentLength() > max) throw PinError(Err.BIG)

            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val tmp = File(dir, "tmp-${System.nanoTime()}")
            try {
                var total = 0L
                val head = ByteArray(16)
                var headLen = 0
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = try {
                                input.read(buf)
                            } catch (e: InterruptedIOException) {
                                throw PinError(Err.TIMEOUT)
                            } catch (e: IOException) {
                                throw PinError(if (isOnline(context)) Err.FAIL else Err.OFFLINE)
                            }
                            if (n < 0) break
                            if (headLen < head.size) {
                                val c = minOf(n, head.size - headLen)
                                System.arraycopy(buf, 0, head, headLen, c)
                                headLen += c
                            }
                            total += n
                            if (total > max) throw PinError(Err.BIG)
                            out.write(buf, 0, n)
                        }
                    }
                }
                // La firma manda: un .jpg que en realidad es WebP se guarda como .webp; algo que no es imagen/video, fuera.
                val real = sniff(head.copyOf(headLen))
                val allowed = if (isVideo) VIDEO_TYPES.values else IMAGE_TYPES.values
                if (real == null || real !in allowed) throw PinError(Err.TYPE)
                val dest = File(dir, "$PREFIX${System.currentTimeMillis()}.$real")
                if (!tmp.renameTo(dest)) throw PinError(Err.FAIL)
                return dest to isVideo
            } finally {
                tmp.delete()
            }
        }
    }

    /**
     * ¿El archivo es horizontal? Cuenta la rotación del video y la orientación EXIF de la foto.
     * Si no se puede leer el tamaño, se da por vertical (lo normal en Pinterest). Cuadrado = vertical.
     */
    private fun isLandscape(file: File, isVideo: Boolean): Boolean = try {
        if (isVideo) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(file.absolutePath)
                val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rot % 180 != 0) h > w else w > h
            } finally {
                r.release()
            }
        } else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, o)
            val turned = try {
                when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
                    ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE -> true
                    else -> false
                }
            } catch (e: Exception) {
                false
            }
            if (turned) o.outHeight > o.outWidth else o.outWidth > o.outHeight
        }
    } catch (e: Exception) {
        false
    }

    /** Enlace de Pinterest → archivo listo para usar como fondo. Si el video no se puede bajar, queda la imagen. */
    suspend fun fetch(context: Context, raw: String): Result = withContext(Dispatchers.IO) {
        try {
            val media = resolvePin(context, raw)
            val groups = buildList<Pair<Boolean, () -> List<HttpUrl>>> {
                media.video?.toHttpUrlOrNull()?.let { v ->
                    add(true to { if (v.encodedPath.endsWith(".m3u8")) mp4sFromHls(context, v) else listOf(v) })
                }
                media.image?.toHttpUrlOrNull()?.let { i -> add(false to { originalsFor(i) }) }
            }
            var last: Exception? = null
            for ((wantVideo, urls) in groups) {
                for (u in urls()) {
                    try {
                        val (file, isVideo) = download(context, u, wantVideo)
                        return@withContext Result.Ok(file, isVideo, media.title.trim(), isLandscape(file, isVideo))
                    } catch (e: PinError) {
                        last = e
                        if (e.message == Err.OFFLINE) throw e
                        // «No existe esa resolución» → se prueba la siguiente; otro fallo → se pasa al siguiente grupo.
                        if (e.message != Err.GONE) break
                    }
                }
            }
            throw last ?: PinError(Err.FAIL)
        } catch (e: PinError) {
            Result.Error(e.message ?: Err.FAIL)
        } catch (e: Exception) {
            Result.Error(Err.FAIL)
        }
    }
}
