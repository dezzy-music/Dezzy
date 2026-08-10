/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



 package com.dezzy.music.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.dezzy.music.utils.reportException
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.delay
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future
import kotlin.math.roundToInt
import com.dezzy.music.ui.utils.highRes

class CoilBitmapLoader(
    private val context: Context,
    private val scope: CoroutineScope,
    /**
     * Salvaguarda: se invoca (en IO) cuando una URL solo produjo el placeholder
     * gris de YouTube o no se pudo cargar en absoluto, para que el dueño
     * refresque la carátula real de esa canción (refetch de metadata + update
     * en la BD). Así una carátula rota se corrige sola en vez de quedarse gris.
     */
    private val onArtworkUnusable: ((String) -> Unit)? = null,
) : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        scope.future(Dispatchers.IO) {
            if (data.isEmpty()) {
                throw IllegalArgumentException("Empty image data")
            }
            // Si falla la decodificación, dejamos que el future falle: media3 NO cacheará
            // un bitmap vacío como éxito y reintentará en la siguiente actualización.
            BitmapFactory.decodeByteArray(data, 0, data.size)
                ?: throw IllegalStateException("Could not decode image data")
        }

    private fun scaleBitmap(bitmap: Bitmap, maxIconSizePx: Int): Bitmap? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        if (bitmap.width <= maxIconSizePx && bitmap.height <= maxIconSizePx) {
            return bitmap
        }
        val scale = minOf(
            maxIconSizePx.toFloat() / bitmap.width.toFloat(),
            maxIconSizePx.toFloat() / bitmap.height.toFloat()
        )
        val targetWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    /** Loads one candidate URL through Coil; null when it fails or yields an unusable bitmap. */
    private suspend fun tryLoad(data: Any, maxIconSizePx: Int): Bitmap? {
        return try {
            val request = ImageRequest.Builder(context)
                .data(data)
                .allowHardware(false)
                .build()
            val result = context.imageLoader.execute(request)
            if (result !is SuccessResult) return null
            val bitmap = result.image.toBitmap()
            if (isGrayPlaceholder(bitmap)) {
                // If it's a placeholder, return it unscaled so the caller can identify it
                return bitmap
            }
            scaleBitmap(bitmap, maxIconSizePx)
        } catch (e: Exception) {
            reportException(e)
            null
        }
    }

    /** YouTube devuelve un placeholder gris de 120x90 con HTTP 200 cuando la variante no existe. */
    private fun isGrayPlaceholder(bitmap: Bitmap): Boolean =
        bitmap.width <= 121 && bitmap.height <= 91

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        scope.future(Dispatchers.IO) {
            val maxIconSizePx = 512 // Reducido para evitar el límite de Binder en notificaciones
            val original = uri.toString()

            // Cadena de candidatos, del mejor al peor. Para i.ytimg.com, highRes() fuerza
            // maxresdefault.jpg, que en muchos videos NO existe (YouTube responde un
            // placeholder gris 120x90 con HTTP 200) — por eso hqdefault.jpg va de respaldo:
            // esa variante existe para todos los videos. Esta cadena es la causa #1 de la
            // "carátula gris" en la notificación al saltar a una canción nueva.
            val candidates = buildList {
                add(original)
                val high = original.highRes()
                if (high != original) add(high)
                if (original.contains("i.ytimg.com")) {
                    val hq = Regex("/[a-z0-9]+\\.jpg").replace(original, "/hqdefault.jpg")
                    if (hq != original) add(hq)
                }
            }

            // Hasta 3 rondas sobre toda la cadena: un corte de red puntual justo al cambiar
            // de canción (mientras se resuelve la URL del stream) no debe dejar la
            // notificación sin carátula.
            var placeholderResult: Bitmap? = null
            val rounds = 3
            for (round in 1..rounds) {
                for (candidate in candidates) {
                    val scaled = tryLoad(candidate, maxIconSizePx) ?: continue
                    if (isGrayPlaceholder(scaled)) {
                        // Guardarlo como último recurso, pero seguir buscando una carátula real.
                        if (placeholderResult == null) placeholderResult = scaled
                        continue
                    }
                    return@future scaled.copy(Bitmap.Config.RGB_565, false) ?: scaled
                }
                if (round < rounds) delay(300L * round)
            }

            // CRITICAL FIX: NUNCA devolver un bitmap gris de placeholder a Media3/Sistema.
            // Devolver un bitmap gris hace que Media3 y el NotificationManager del sistema
            // fijen esa imagen gris para la sesión y contaminen todas las canciones posteriores.
            // Al lanzar una excepción, la notificación no se contamina y cada canción posterior
            // cargará su carátula de forma independiente y limpia.
            runCatching { onArtworkUnusable?.invoke(original) }
            throw IllegalStateException("Could not load clean artwork for uri: $uri")
        }
}
