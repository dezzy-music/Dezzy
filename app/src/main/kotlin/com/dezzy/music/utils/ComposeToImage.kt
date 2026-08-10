/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.utils

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.app.Activity
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import android.view.View
import android.view.PixelCopy
import androidx.core.view.drawToBitmap
import com.dezzy.music.R
import com.dezzy.music.ui.component.LyricsBackgroundType
import com.dezzy.music.ui.component.LyricsCardConfig
import com.dezzy.music.ui.component.LyricsGlassStyle
import com.dezzy.music.ui.component.LyricsLayoutStyle
import com.dezzy.music.ui.theme.getTypeface
import com.dezzy.music.ui.utils.highRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.math.max

object ComposeToImage {

    // ─────────────────────────────────────────────────────────────────────────
    // Utilidades internas
    // ─────────────────────────────────────────────────────────────────────────

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity      -> this
        is ContextWrapper -> baseContext.findActivity()
        else             -> null
    }

    /**
     * Tipografías (normal, bold) de la fuente elegida en Ajustes — todos los
     * renderers las usan para que lo compartido coincida con la app.
     */
    private fun resolveTypefaces(context: Context, config: LyricsCardConfig): Pair<Typeface, Typeface> {
        val base = getTypeface(context, config.fontName)
        return base to Typeface.create(base, Typeface.BOLD)
    }

    private fun ensureSoftwareBitmap(bitmap: Bitmap): Bitmap {
        val config = bitmap.config
        if (config != Bitmap.Config.HARDWARE && config != null) return bitmap
        return runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: bitmap
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun pixelCopyViewBitmap(view: View): Bitmap? {
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return null
        val activity = view.context.findActivity() ?: return null
        val window   = activity.window ?: return null
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val rect   = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val result = suspendCancellableCoroutine { cont ->
            PixelCopy.request(window, rect, bitmap, { cont.resume(it) }, Handler(Looper.getMainLooper()))
        }
        return if (result == PixelCopy.SUCCESS) bitmap else null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // API pública de captura de View
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun captureViewBitmap(
        view: View,
        targetWidth: Int? = null,
        targetHeight: Int? = null,
        backgroundColor: Int? = null,
    ): Bitmap {
        val fallbackBitmap = runCatching { view.drawToBitmap() }.getOrElse {
            val w = view.width.coerceAtLeast(1)
            val h = view.height.coerceAtLeast(1)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                backgroundColor?.let { Canvas(bmp).drawColor(it) }
            }
        }
        val original = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pixelCopyViewBitmap(view) ?: fallbackBitmap
        } else fallbackBitmap

        val needsScale = (targetWidth  != null && targetWidth  > 0 && targetWidth  != original.width) ||
                (targetHeight != null && targetHeight > 0 && targetHeight != original.height)
        val base = if (needsScale) {
            val safeOriginal = ensureSoftwareBitmap(original)
            val tw = targetWidth  ?: original.width
            val th = targetHeight ?: (original.height * tw / original.width)
            ensureSoftwareBitmap(Bitmap.createScaledBitmap(safeOriginal, tw, th, true))
        } else ensureSoftwareBitmap(original)

        if (backgroundColor != null) {
            val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
            Canvas(out).apply { drawColor(backgroundColor); drawBitmap(base, 0f, 0f, null) }
            return out
        }
        return base
    }

    fun cropBitmap(source: Bitmap, left: Int, top: Int, width: Int, height: Int): Bitmap {
        val safeSource = ensureSoftwareBitmap(source)
        val safeLeft   = left.coerceIn(0, safeSource.width.coerceAtLeast(1) - 1)
        val safeTop    = top.coerceIn(0, safeSource.height.coerceAtLeast(1) - 1)
        val safeWidth  = width.coerceIn(1, safeSource.width - safeLeft)
        val safeHeight = height.coerceIn(1, safeSource.height - safeTop)
        return ensureSoftwareBitmap(Bitmap.createBitmap(safeSource, safeLeft, safeTop, safeWidth, safeHeight))
    }

    fun fitBitmap(source: Bitmap, targetWidth: Int, targetHeight: Int, backgroundColor: Int): Bitmap {
        val safeSource = ensureSoftwareBitmap(source)
        val out    = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(backgroundColor)
        val scale   = minOf(targetWidth.toFloat() / safeSource.width.coerceAtLeast(1),
            targetHeight.toFloat() / safeSource.height.coerceAtLeast(1))
        val scaledW = (safeSource.width  * scale).toInt().coerceAtLeast(1)
        val scaledH = (safeSource.height * scale).toInt().coerceAtLeast(1)
        val scaled  = if (scaledW != safeSource.width || scaledH != safeSource.height) {
            ensureSoftwareBitmap(Bitmap.createScaledBitmap(safeSource, scaledW, scaledH, true))
        } else safeSource
        canvas.drawBitmap(scaled, ((targetWidth - scaled.width) / 2f), ((targetHeight - scaled.height) / 2f), null)
        return out
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Carga de la carátula (compartida por todos los renderers)
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun loadCoverArt(context: Context, url: String?): Bitmap? {
        if (url == null) return null
        return runCatching {
            // Las tarjetas se exportan a 1080 px y la imagen puede ocupar casi todo
            // el ancho: pedirla a 512 obligaba a escalar hacia arriba y salía
            // borrosa. `highRes()` además reescribe la URL de YouTube para que el
            // servidor entregue la versión grande en vez de la miniatura.
            val request = ImageRequest.Builder(context)
                .data(url.highRes())
                .size(720)
                .allowHardware(false)
                .build()
            context.imageLoader.execute(request).image?.toBitmap()
        }.getOrNull()?.let(::trimUniformBorders)
    }

    /**
     * Recorta las bandas sólidas de los bordes (el pillarbox blanco o el letterbox
     * negro con el que YouTube rellena las miniaturas cuando la imagen original no
     * es cuadrada).
     *
     * Sin esto, las tarjetas de compartir heredaban esas bandas: la foto del artista
     * salía como una franja estrecha en medio de un círculo blanco.
     *
     * Solo recorta si las bandas son claramente uniformes y no se come más de la
     * mitad de la imagen, para no estropear portadas que legítimamente tengan un
     * borde liso.
     */
    private fun trimUniformBorders(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w < 8 || h < 8) return src

        // Tolerancia por canal: las bandas suelen ser planas, pero el JPEG
        // introduce algo de ruido.
        val tol = 12

        fun sample(x: Int, y: Int) = src.getPixel(x, y)
        fun similar(a: Int, b: Int): Boolean =
            kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) <= tol &&
                kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) <= tol &&
                kotlin.math.abs((a and 0xFF) - (b and 0xFF)) <= tol

        /** ¿Es toda la columna/fila del mismo color que la referencia? */
        fun columnUniform(x: Int, ref: Int): Boolean {
            var y = 0
            while (y < h) {
                if (!similar(sample(x, y), ref)) return false
                y += maxOf(1, h / 24)   // muestreo: 24 puntos bastan y es ~24x más rápido
            }
            return true
        }
        fun rowUniform(y: Int, ref: Int): Boolean {
            var x = 0
            while (x < w) {
                if (!similar(sample(x, y), ref)) return false
                x += maxOf(1, w / 24)
            }
            return true
        }

        var left = 0
        val leftRef = sample(0, h / 2)
        while (left < w / 2 && columnUniform(left, leftRef)) left++

        var right = w - 1
        val rightRef = sample(w - 1, h / 2)
        while (right > w / 2 && columnUniform(right, rightRef)) right--

        var top = 0
        val topRef = sample(w / 2, 0)
        while (top < h / 2 && rowUniform(top, topRef)) top++

        var bottom = h - 1
        val bottomRef = sample(w / 2, h - 1)
        while (bottom > h / 2 && rowUniform(bottom, bottomRef)) bottom--

        val newW = right - left + 1
        val newH = bottom - top + 1
        // Nada que recortar, o el recorte es tan agresivo que probablemente
        // estemos ante una imagen casi plana: se deja como está.
        if (newW <= 0 || newH <= 0) return src
        if (newW >= w && newH >= h) return src
        if (newW < w / 3 || newH < h / 3) return src

        return runCatching { Bitmap.createBitmap(src, left, top, newW, newH) }.getOrDefault(src)
    }

    /**
     * Dibuja el bitmap llenando [dst] con recorte central (center-crop), en vez de
     * estirarlo. Preserva la proporción: una imagen vertical ya no sale deformada
     * ni deja huecos dentro del círculo/recuadro.
     */
    private fun Canvas.drawBitmapCropped(bitmap: Bitmap, dst: RectF) {
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        if (bw <= 0f || bh <= 0f) return

        val dstRatio = dst.width() / dst.height()
        val srcRatio = bw / bh

        val src = if (srcRatio > dstRatio) {
            // Más ancha que el destino: recortamos a los lados.
            val newW = bh * dstRatio
            val dx = (bw - newW) / 2f
            android.graphics.Rect(dx.toInt(), 0, (dx + newW).toInt(), bh.toInt())
        } else {
            // Más alta: recortamos arriba y abajo.
            val newH = bw / dstRatio
            val dy = (bh - newH) / 2f
            android.graphics.Rect(0, dy.toInt(), bw.toInt(), (dy + newH).toInt())
        }
        drawBitmap(bitmap, src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper: fondo de álbum (desenfocado) + scrim
    // ─────────────────────────────────────────────────────────────────────────

    private fun drawAlbumBackground(
        canvas: Canvas,
        cardSize: Int,
        coverArt: Bitmap?,
        dimAlpha: Float,
        cornerRadius: Float,
    ) {
        val rect = RectF(0f, 0f, cardSize.toFloat(), cardSize.toFloat())
        val path = Path().apply { addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW) }

        if (coverArt != null) {
            // Usa la imagen original sin escalar primero
            val scaled = ensureSoftwareBitmap(
                Bitmap.createScaledBitmap(
                    coverArt,
                    cardSize,
                    cardSize,
                    false  // Cambia a false para mejor calidad
                )
            )
            // Reduce la intensidad del blur o usa un blur más suave
            val blurred = if (coverArt.width > cardSize * 2) {
                // Si la imagen original es grande, no la reduzcas tanto
                scaleBlurQuality(scaled, 12) // Reduce la intensidad del blur
            } else {
                scaleBlurQuality(scaled, 18)
            }
            canvas.withClip(path) { drawBitmap(blurred, 0f, 0f, null) }
        } else {
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, Paint().apply {
                color = 0xFF0F0F0F.toInt(); isAntiAlias = true
            })
        }

        // Scrim
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, Paint().apply {
            color = android.graphics.Color.argb((dimAlpha * 255).toInt(), 0, 0, 0)
            isAntiAlias = true
        })
    }

    private fun scaleBlurQuality(source: Bitmap, strength: Int): Bitmap {
        val safe = ensureSoftwareBitmap(source)

        // Si la imagen ya es pequeña, no la reduzcas
        if (safe.width <= 400 || safe.height <= 400) {
            return safe
        }

        // Reducción más suave para mantener calidad
        val targetSize = max(safe.width / 4, 200) // Máximo reducción a 1/4
        val smallW = targetSize
        val smallH = (safe.height * targetSize / safe.width).coerceAtLeast(100)

        val small = Bitmap.createScaledBitmap(safe, smallW, smallH, true)
        val result = Bitmap.createScaledBitmap(small, safe.width, safe.height, true)

        small.recycle() // Limpia memoria
        return result
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper: panel frosted sobre el canvas
    // ─────────────────────────────────────────────────────────────────────────

    private fun drawFrostedPanel(
        canvas: Canvas,
        cardSize: Int,
        coverArt: Bitmap?,
        style: LyricsGlassStyle,
        panelRect: RectF,
        cornerRadius: Float,
    ) {
        val path = Path().apply { addRoundRect(panelRect, cornerRadius, cornerRadius, Path.Direction.CW) }

        if (coverArt != null) {
            val left   = panelRect.left.toInt().coerceIn(0, cardSize - 1)
            val top    = panelRect.top.toInt().coerceIn(0, cardSize - 1)
            val width  = panelRect.width().toInt().coerceIn(1, cardSize - left)
            val height = panelRect.height().toInt().coerceIn(1, cardSize - top)
            val scaled = ensureSoftwareBitmap(Bitmap.createScaledBitmap(coverArt, cardSize, cardSize, true))
            val crop   = Bitmap.createBitmap(scaled, left, top, width, height)
            val frosted = scaleBlur(crop, 12)
            canvas.withClip(path) { drawBitmap(frosted, panelRect.left, panelRect.top, null) }
        }

        fun composeColor(c: androidx.compose.ui.graphics.Color, a: Float): Int =
            android.graphics.Color.argb((a * 255).toInt(),
                (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())

        canvas.drawRoundRect(panelRect, cornerRadius, cornerRadius, Paint().apply {
            color = composeColor(style.surfaceTint, style.surfaceAlpha); isAntiAlias = true
        })
        canvas.drawRoundRect(panelRect, cornerRadius, cornerRadius, Paint().apply {
            color = composeColor(style.overlayColor, style.overlayAlpha); isAntiAlias = true
        })
        canvas.drawRoundRect(panelRect, cornerRadius, cornerRadius, Paint().apply {
            this.style = Paint.Style.STROKE; strokeWidth = 1.5f
            color = android.graphics.Color.argb(25, 255, 255, 255); isAntiAlias = true
        })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper: texto de letras con auto-fit vertical
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildFittedLyricsLayout(
        lyrics: String,
        paint: TextPaint,
        maxWidth: Int,
        availableHeight: Float,
        alignment: Layout.Alignment = Layout.Alignment.ALIGN_CENTER,
        initialTextSize: Float,
        minTextSize: Float = 22f,
    ): StaticLayout {
        var textSize = initialTextSize
        var layout: StaticLayout
        do {
            paint.textSize = textSize
            layout = StaticLayout.Builder.obtain(lyrics, 0, lyrics.length, paint, maxWidth)
                .setAlignment(alignment)
                .setIncludePad(false)
                .setLineSpacing(8f, 1.35f)
                .setMaxLines(12)
                .build()
            if (layout.height > availableHeight) textSize -= 2f else break
        } while (textSize > minTextSize)
        return layout
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper: metadata row (carátula + título + artista)
    // ─────────────────────────────────────────────────────────────────────────

    private fun drawMetadataRow(
        canvas: Canvas,
        coverArt: Bitmap?,
        songTitle: String,
        artistName: String,
        cardSize: Int,
        contentLeft: Float,
        contentTop: Float,
        contentRight: Float,
        showCover: Boolean,
        showTitle: Boolean,
        showArtist: Boolean,
        mainTextColor: Int,
        secondaryTextColor: Int,
        baseTypeface: Typeface = Typeface.DEFAULT,
        boldTypeface: Typeface = Typeface.DEFAULT_BOLD,
    ) {
        val coverSize       = (cardSize * 0.16f)
        val imageCornerRadius = cardSize * 0.035f
        var textStartX      = contentLeft

        if (showCover && coverArt != null) {
            val rect = RectF(contentLeft, contentTop, contentLeft + coverSize, contentTop + coverSize)
            val path = Path().apply { addRoundRect(rect, imageCornerRadius, imageCornerRadius, Path.Direction.CW) }
            canvas.withClip(path) { drawBitmapCropped(coverArt, rect) }
            canvas.drawRoundRect(rect, imageCornerRadius, imageCornerRadius, Paint().apply {
                style = Paint.Style.STROKE; strokeWidth = 1f
                color = android.graphics.Color.argb(38, 255, 255, 255); isAntiAlias = true
            })
            textStartX = contentLeft + coverSize + cardSize * 0.04f
        }

        val titlePaint = TextPaint().apply {
            color    = mainTextColor
            textSize = cardSize * 0.038f
            typeface = boldTypeface
            isAntiAlias = true; letterSpacing = -0.02f
        }
        val artistPaint = TextPaint().apply {
            color    = secondaryTextColor
            textSize = cardSize * 0.028f
            typeface = baseTypeface
            isAntiAlias = true
        }

        val textMaxWidth = (contentRight - textStartX).toInt().coerceAtLeast(1)
        if (showTitle) {
            val titleLayout = StaticLayout.Builder.obtain(songTitle, 0, songTitle.length, titlePaint, textMaxWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(1).build()
            val artistLayout = StaticLayout.Builder.obtain(artistName, 0, artistName.length, artistPaint, textMaxWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(1).build()

            val imageCenter     = contentTop + (if (showCover && coverArt != null) coverSize / 2f else titleLayout.height / 2f)
            val textBlockHeight = titleLayout.height + (if (showArtist) artistLayout.height + 6f else 0f)
            val textBlockY      = imageCenter - textBlockHeight / 2f

            canvas.withTranslation(textStartX, textBlockY) {
                titleLayout.draw(this)
                if (showArtist) {
                    translate(0f, titleLayout.height.toFloat() + 6f)
                    artistLayout.draw(this)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper: branding Dezzy (igual al original)
    // ─────────────────────────────────────────────────────────────────────────

    private fun drawBranding(
        context: Context,
        canvas: Canvas,
        cardSize: Int,
        x: Float,
        y: Float,
        circleColor: Int,
        logoTint: Int,
        textColor: Int,
        typeface: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL),
    ) {
        val baseSize  = cardSize.toFloat()
        val logoSize  = (baseSize * 0.045f).toInt()
        val rawLogo   = context.getDrawable(R.drawable.star)?.toBitmap(logoSize, logoSize)
        val logo = rawLogo?.let { source ->
            val colored = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            Canvas(colored).drawBitmap(source, 0f, 0f, Paint().apply {
                colorFilter = PorterDuffColorFilter(logoTint, PorterDuff.Mode.SRC_IN); isAntiAlias = true
            })
            colored
        }
        val appNamePaint = TextPaint().apply {
            color    = textColor; textSize = baseSize * 0.028f
            this.typeface = typeface
            isAntiAlias = true; letterSpacing = 0.02f
        }
        val circleRadius = logoSize * 0.55f
        val circleX = x + circleRadius
        val circleY = y - circleRadius
        
        canvas.drawCircle(circleX, circleY, circleRadius, Paint().apply {
            color = circleColor; isAntiAlias = true; style = Paint.Style.FILL
        })
        logo?.let { canvas.drawBitmap(it, circleX - logoSize / 2f, circleY - logoSize / 2f, null) }
        
        canvas.drawText(
            "Dezzy",
            circleX + circleRadius + 10f,
            circleY + appNamePaint.textSize * 0.3f,
            appNamePaint,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ▸ API PRINCIPAL NUEVA — despacha al renderer correcto según config
    // ─────────────────────────────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.M)
    suspend fun createLyricsImageWithConfig(
        context: Context,
        coverArtUrl: String?,
        songTitle: String,
        artistName: String,
        lyrics: String,
        config: LyricsCardConfig,
        outputSize: Int = 1080,
    ): Bitmap = withContext(Dispatchers.Default) {
        val coverArt = loadCoverArt(context, coverArtUrl)
        when (config.layoutStyle) {
            LyricsLayoutStyle.GlassCard      -> renderGlassCard(context, coverArt, songTitle, artistName, lyrics, config, outputSize)
            LyricsLayoutStyle.Minimal        -> renderMinimal(context, coverArt, songTitle, artistName, lyrics, config, outputSize)
            LyricsLayoutStyle.CoverFocused   -> renderCoverFocused(context, coverArt, songTitle, artistName, lyrics, config, outputSize)
            LyricsLayoutStyle.Centered       -> renderCentered(context, coverArt, songTitle, artistName, lyrics, config, outputSize)
            LyricsLayoutStyle.BlurWash       -> renderBlurWash(context, coverArt, songTitle, artistName, lyrics, config, outputSize)
            LyricsLayoutStyle.StreamingModern -> renderStreamingModern(context, coverArt, songTitle, artistName, lyrics, config, outputSize)
            LyricsLayoutStyle.DezzyShare     -> renderDezzyShare(context, coverArt, songTitle, artistName, lyrics, config, outputSize)
            LyricsLayoutStyle.PaperPolaroid  -> renderPaperPolaroid(context, coverArt, songTitle, artistName, config, outputSize)
            LyricsLayoutStyle.EntityCard     -> renderEntityCard(context, coverArt, songTitle, artistName, config, outputSize)
        }
    }

    /** Pista mostrada en la tarjeta de playlist con listado. */
    data class ShareTrack(val title: String, val artist: String, val thumbnailUrl: String?)

    /**
     * Tarjeta de PLAYLIST con listado (el tab "Playlist" del share, estilo Spotify):
     * cabecera con la portada pequeña y el nombre, y debajo las primeras pistas con su
     * miniatura. El branding va abajo a la derecha, como en la referencia.
     */
    suspend fun createPlaylistShareImage(
        context: Context,
        coverArtUrl: String?,
        playlistName: String,
        descriptor: String,
        tracks: List<ShareTrack>,
        config: LyricsCardConfig,
        outputSize: Int = 1080,
    ): Bitmap = withContext(Dispatchers.Default) {
        val cover = loadCoverArt(context, coverArtUrl)
        val visible = tracks.take(4)
        val thumbs = visible.map { loadCoverArt(context, it.thumbnailUrl) }

        val width = outputSize
        val height = outputSize * 4 / 3
        val (baseTf, boldTf) = resolveTypefaces(context, config)
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val w = width.toFloat()
        val h = height.toFloat()

        val bgColor = config.customBgColor.toArgb()
        val mainColor = config.customTextColor.toArgb()
        val secondaryClr = config.customSecondaryTextColor.toArgb()

        canvas.drawRect(RectF(0f, 0f, w, h), Paint().apply {
            isAntiAlias = true
            if (config.customBgGradient) {
                shader = android.graphics.LinearGradient(
                    0f, 0f, 0f, h,
                    bgColor, android.graphics.Color.BLACK,
                    android.graphics.Shader.TileMode.CLAMP,
                )
            } else {
                color = bgColor
            }
        })

        val pad = w * 0.075f
        // ── Cabecera: portada + nombre de la playlist ──
        val headerArt = w * 0.30f
        if (cover != null) {
            val rect = RectF(pad, pad, pad + headerArt, pad + headerArt)
            val path = Path().apply { addRoundRect(rect, w * 0.012f, w * 0.012f, Path.Direction.CW) }
            canvas.withClip(path) { drawBitmapCropped(cover, rect) }
        }
        val headTextLeft = pad + headerArt + w * 0.045f
        val headTextWidth = (w - pad - headTextLeft).toInt().coerceAtLeast(1)
        val namePaint = TextPaint().apply {
            color = mainColor; textSize = w * 0.058f
            typeface = boldTf; isAntiAlias = true
        }
        val descPaint = TextPaint().apply {
            color = secondaryClr; textSize = w * 0.038f
            typeface = baseTf; isAntiAlias = true
        }
        fun clipped(text: String, paint: TextPaint, maxWidth: Int, lines: Int) =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, maxWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(lines)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .setEllipsizedWidth(maxWidth)
                .build()
        val nameLayout = clipped(playlistName, namePaint, headTextWidth, 2)
        val descLayout = clipped(descriptor, descPaint, headTextWidth, 1)
        val headBlockH = nameLayout.height + w * 0.015f + descLayout.height
        val headTextTop = pad + (headerArt - headBlockH) / 2f
        canvas.withTranslation(headTextLeft, headTextTop) { nameLayout.draw(this) }
        canvas.withTranslation(headTextLeft, headTextTop + nameLayout.height + w * 0.015f) {
            descLayout.draw(this)
        }

        // ── Listado de pistas ──
        val rowArt = w * 0.145f
        val rowGap = w * 0.035f
        var rowTop = pad + headerArt + w * 0.075f
        val titlePaint = TextPaint().apply {
            color = mainColor; textSize = w * 0.045f
            typeface = boldTf; isAntiAlias = true
        }
        val artistPaint = TextPaint().apply {
            color = secondaryClr; textSize = w * 0.036f
            typeface = baseTf; isAntiAlias = true
        }
        visible.forEachIndexed { index, track ->
            thumbs.getOrNull(index)?.let { thumb ->
                val rect = RectF(pad, rowTop, pad + rowArt, rowTop + rowArt)
                val path = Path().apply { addRoundRect(rect, w * 0.008f, w * 0.008f, Path.Direction.CW) }
                canvas.withClip(path) { drawBitmapCropped(thumb, rect) }
            }
            val textLeft = pad + rowArt + w * 0.035f
            val textWidth = (w - pad - textLeft).toInt().coerceAtLeast(1)
            val tLayout = clipped(track.title, titlePaint, textWidth, 1)
            val aLayout = clipped(track.artist, artistPaint, textWidth, 1)
            val blockH = tLayout.height + w * 0.008f + aLayout.height
            val textTop = rowTop + (rowArt - blockH) / 2f
            canvas.withTranslation(textLeft, textTop) { tLayout.draw(this) }
            canvas.withTranslation(textLeft, textTop + tLayout.height + w * 0.008f) { aLayout.draw(this) }
            rowTop += rowArt + rowGap
        }

        // ── Branding abajo a la derecha ──
        val brandH = w * 0.055f
        val brandPaint = TextPaint().apply {
            color = mainColor; textSize = w * 0.042f
            typeface = boldTf; isAntiAlias = true
        }
        val brandText = "Dezzy"
        val brandWidth = brandPaint.measureText(brandText)
        val rowWidth = brandH + w * 0.018f + brandWidth
        var bx = w - pad - rowWidth
        val brandCenterY = h - pad - brandH / 2f
        context.getDrawable(R.drawable.star)
            ?.toBitmap(brandH.toInt(), brandH.toInt())
            ?.let { star ->
                canvas.drawBitmap(star, bx, brandCenterY - brandH / 2f, Paint().apply {
                    colorFilter = PorterDuffColorFilter(mainColor, PorterDuff.Mode.SRC_IN)
                    isAntiAlias = true
                })
            }
        bx += brandH + w * 0.018f
        canvas.drawText(
            brandText, bx,
            brandCenterY - (brandPaint.descent() + brandPaint.ascent()) / 2f,
            brandPaint,
        )

        bitmap
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RENDERER — EntityCard (artista / playlist), estilo Spotify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Espejo 1:1 de la preview de `ShareBottomSheet` en modo entidad: lienzo 3:4 con
     * el color de la paleta, y dentro una tarjeta oscura con la imagen (círculo para
     * artista, cuadrada para playlist), el nombre, el descriptor ("Artista en Dezzy")
     * y el branding abajo a la izquierda.
     */
    private fun renderEntityCard(
        context: Context,
        coverArt: Bitmap?,
        name: String,
        descriptor: String,
        config: LyricsCardConfig,
        cardSize: Int,
    ): Bitmap {
        val width = cardSize
        val height = cardSize * 4 / 3
        val (baseTf, boldTf) = resolveTypefaces(context, config)
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val w = width.toFloat()
        val h = height.toFloat()

        val bgColor = config.customBgColor.toArgb()
        val mainColor = config.customTextColor.toArgb()
        val secondaryClr = config.customSecondaryTextColor.toArgb()

        // Fondo exterior (sólido o degradado color→negro)
        canvas.drawRect(RectF(0f, 0f, w, h), Paint().apply {
            isAntiAlias = true
            if (config.customBgGradient) {
                shader = android.graphics.LinearGradient(
                    0f, 0f, 0f, h,
                    bgColor, android.graphics.Color.BLACK,
                    android.graphics.Shader.TileMode.CLAMP,
                )
            } else {
                color = bgColor
            }
        })

        // Tarjeta interior oscura, anclada arriba (deja aire abajo, como Spotify)
        val outerPad = w * 0.085f
        val innerCorner = w * 0.02f
        val innerLeft = outerPad
        val innerTop = outerPad
        val innerRight = w - outerPad
        val innerPad = w * 0.055f
        val imageSize = (innerRight - innerLeft) - innerPad * 2

        val namePaint = TextPaint().apply {
            color = mainColor; textSize = w * 0.062f
            typeface = boldTf; isAntiAlias = true
        }
        val descPaint = TextPaint().apply {
            color = secondaryClr; textSize = w * 0.042f
            typeface = baseTf; isAntiAlias = true
        }
        val textWidth = imageSize.toInt().coerceAtLeast(1)
        fun line(text: String, paint: TextPaint) =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(1)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .setEllipsizedWidth(textWidth)
                .build()
        val nameLayout = line(name, namePaint)
        val descLayout = line(descriptor, descPaint)

        val brandH = w * 0.055f
        val innerBottom = innerTop + innerPad + imageSize + w * 0.05f +
            nameLayout.height + w * 0.012f + descLayout.height + w * 0.05f + brandH + innerPad

        canvas.drawRoundRect(
            RectF(innerLeft, innerTop, innerRight, innerBottom),
            innerCorner, innerCorner,
            Paint().apply { isAntiAlias = true; color = android.graphics.Color.parseColor("#0B0B0B") },
        )

        // Imagen: círculo (artista) o cuadrada con esquinas suaves (playlist)
        if (coverArt != null) {
            val imgLeft = innerLeft + innerPad
            val imgTop = innerTop + innerPad
            val imgRect = RectF(imgLeft, imgTop, imgLeft + imageSize, imgTop + imageSize)
            val path = Path().apply {
                if (config.entityCircularImage) {
                    addCircle(imgRect.centerX(), imgRect.centerY(), imageSize / 2f, Path.Direction.CW)
                } else {
                    addRoundRect(imgRect, w * 0.012f, w * 0.012f, Path.Direction.CW)
                }
            }
            canvas.withClip(path) { drawBitmapCropped(coverArt, imgRect) }
        }

        // Nombre + descriptor, alineados a la izquierda bajo la imagen
        val textLeft = innerLeft + innerPad
        val nameTop = innerTop + innerPad + imageSize + w * 0.05f
        canvas.withTranslation(textLeft, nameTop) { nameLayout.draw(this) }
        val descTop = nameTop + nameLayout.height + w * 0.012f
        canvas.withTranslation(textLeft, descTop) { descLayout.draw(this) }

        // Branding "★ Dezzy" abajo a la izquierda de la tarjeta interior
        val brandPaint = TextPaint().apply {
            color = mainColor; textSize = w * 0.042f
            typeface = boldTf; isAntiAlias = true
        }
        val brandCenterY = descTop + descLayout.height + w * 0.05f + brandH / 2f
        var bx = textLeft
        context.getDrawable(R.drawable.star)
            ?.toBitmap(brandH.toInt(), brandH.toInt())
            ?.let { star ->
                canvas.drawBitmap(star, bx, brandCenterY - brandH / 2f, Paint().apply {
                    colorFilter = PorterDuffColorFilter(mainColor, PorterDuff.Mode.SRC_IN)
                    isAntiAlias = true
                })
            }
        bx += brandH + w * 0.018f
        canvas.drawText(
            "Dezzy", bx,
            brandCenterY - (brandPaint.descent() + brandPaint.ascent()) / 2f,
            brandPaint,
        )

        return bitmap
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RENDERER — Polaroid sobre papel de cuaderno (estilo foto pegada)
    // ─────────────────────────────────────────────────────────────────────────

    private fun renderPaperPolaroid(
        context: Context,
        coverArt: Bitmap?,
        songTitle: String,
        artistName: String,
        config: LyricsCardConfig,
        cardSize: Int,
    ): Bitmap {
        val (baseTf, boldTf) = resolveTypefaces(context, config)
        val bmp = createBitmap(cardSize, cardSize)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // ── Fondo: papel de cuaderno de color claro con líneas ──
        canvas.drawColor(android.graphics.Color.parseColor("#F4F1E8"))
        // Textura sutil (viñeta clara)
        paint.shader = android.graphics.RadialGradient(
            cardSize * 0.5f, cardSize * 0.4f, cardSize * 0.75f,
            intArrayOf(android.graphics.Color.parseColor("#FBFAF4"), android.graphics.Color.parseColor("#EDE8DA")),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, cardSize.toFloat(), cardSize.toFloat(), paint)
        paint.shader = null

        // Líneas horizontales de cuaderno
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#22597EA8")
            strokeWidth = cardSize * 0.0025f
        }
        val lineGap = cardSize * 0.055f
        var ly = cardSize * 0.14f
        while (ly < cardSize) {
            canvas.drawLine(0f, ly, cardSize.toFloat(), ly, linePaint)
            ly += lineGap
        }
        // Margen rojo vertical
        canvas.drawLine(
            cardSize * 0.12f, 0f, cardSize * 0.12f, cardSize.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#33D06A63")
                strokeWidth = cardSize * 0.004f
            },
        )

        // ── Garabatos dispersos (estrellitas y rayas a mano) ──
        val doodle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#5566708A")
            style = Paint.Style.STROKE
            strokeWidth = cardSize * 0.006f
            strokeCap = Paint.Cap.ROUND
        }
        drawSparkle(canvas, doodle, cardSize * 0.12f, cardSize * 0.12f, cardSize * 0.03f)
        drawSparkle(canvas, doodle, cardSize * 0.90f, cardSize * 0.14f, cardSize * 0.024f)
        drawHeart(canvas, doodle, cardSize * 0.90f, cardSize * 0.62f, cardSize * 0.03f)
        drawSquiggle(canvas, doodle, cardSize * 0.10f, cardSize * 0.64f, cardSize * 0.09f)

        // ── Papeles rotos/notas con garabatos, detrás de la polaroid (como Spotify) ──
        drawPaperScrap(canvas, cardSize * 0.19f, cardSize * 0.26f, cardSize * 0.22f, cardSize * 0.17f, -14f, 0)
        drawPaperScrap(canvas, cardSize * 0.84f, cardSize * 0.34f, cardSize * 0.17f, cardSize * 0.24f, 11f, 1)
        drawPaperScrap(canvas, cardSize * 0.22f, cardSize * 0.83f, cardSize * 0.24f, cardSize * 0.16f, 7f, 2)

        // ── Polaroid centrada, ligeramente rotada ──
        canvas.save()
        canvas.rotate(-4.5f, cardSize * 0.5f, cardSize * 0.5f)

        val polW = cardSize * 0.58f
        val polH = cardSize * 0.72f
        val polLeft = (cardSize - polW) / 2f
        val polTop = (cardSize - polH) / 2f - cardSize * 0.02f
        val polRect = RectF(polLeft, polTop, polLeft + polW, polTop + polH)

        // Sombra de la polaroid
        paint.color = android.graphics.Color.parseColor("#33000000")
        paint.setShadowLayer(cardSize * 0.03f, 0f, cardSize * 0.012f, android.graphics.Color.parseColor("#55000000"))
        canvas.drawRect(polRect, Paint(paint).apply { color = android.graphics.Color.WHITE })
        paint.clearShadowLayer()

        // Marco blanco polaroid
        paint.color = android.graphics.Color.WHITE
        canvas.drawRect(polRect, paint)

        // Foto (portada) dentro del marco
        val pad = polW * 0.06f
        val photo = RectF(polLeft + pad, polTop + pad, polLeft + polW - pad, polTop + polW - pad)
        if (coverArt != null) {
            canvas.save()
            canvas.clipRect(photo)
            val scale = maxOf(photo.width() / coverArt.width, photo.height() / coverArt.height)
            val dw = coverArt.width * scale
            val dh = coverArt.height * scale
            val l = photo.centerX() - dw / 2f
            val t = photo.centerY() - dh / 2f
            canvas.drawBitmap(coverArt, null, RectF(l, t, l + dw, t + dh), Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
        } else {
            paint.color = android.graphics.Color.parseColor("#DDDDDD")
            canvas.drawRect(photo, paint)
        }

        // Título + artista en la banda inferior de la polaroid
        val titlePaint = TextPaint().apply {
            color = android.graphics.Color.parseColor("#1A1A1A"); isAntiAlias = true
            typeface = boldTf; textSize = polW * 0.10f
        }
        val artistPaint = TextPaint().apply {
            color = android.graphics.Color.parseColor("#777777"); isAntiAlias = true
            typeface = baseTf; textSize = polW * 0.072f
        }
        val bandTop = photo.bottom + polW * 0.06f
        fun ellip(text: String, p: TextPaint) =
            android.text.TextUtils.ellipsize(text, p, polW - pad * 2, android.text.TextUtils.TruncateAt.END).toString()
        canvas.drawText(ellip(songTitle, titlePaint), photo.left, bandTop + titlePaint.textSize, titlePaint)
        canvas.drawText(ellip(artistName, artistPaint), photo.left, bandTop + titlePaint.textSize + artistPaint.textSize * 1.5f, artistPaint)

        // Marca "★ Dezzy" pequeña abajo a la derecha de la polaroid
        val brandPaint = TextPaint().apply {
            color = android.graphics.Color.parseColor("#999999"); isAntiAlias = true
            typeface = boldTf; textSize = polW * 0.06f; textAlign = Paint.Align.RIGHT
        }
        canvas.drawText("★ Dezzy", polRect.right - pad, polRect.bottom - pad * 0.6f, brandPaint)

        canvas.restore()
        return bmp
    }

    /** Dibuja una estrellita de 4 puntas a mano alzada (garabato). */
    private fun drawSparkle(canvas: Canvas, paint: Paint, cx: Float, cy: Float, r: Float) {
        canvas.drawLine(cx, cy - r, cx, cy + r, paint)
        canvas.drawLine(cx - r, cy, cx + r, cy, paint)
        canvas.drawLine(cx - r * 0.55f, cy - r * 0.55f, cx + r * 0.55f, cy + r * 0.55f, paint)
        canvas.drawLine(cx - r * 0.55f, cy + r * 0.55f, cx + r * 0.55f, cy - r * 0.55f, paint)
    }

    /** Corazón a mano alzada. */
    private fun drawHeart(canvas: Canvas, paint: Paint, cx: Float, cy: Float, r: Float) {
        val p = Path().apply {
            moveTo(cx, cy + r * 0.7f)
            cubicTo(cx - r * 1.4f, cy - r * 0.4f, cx - r * 0.5f, cy - r * 1.1f, cx, cy - r * 0.3f)
            cubicTo(cx + r * 0.5f, cy - r * 1.1f, cx + r * 1.4f, cy - r * 0.4f, cx, cy + r * 0.7f)
            close()
        }
        canvas.drawPath(p, paint)
    }

    /** Garabato ondulado (subrayado a mano). */
    private fun drawSquiggle(canvas: Canvas, paint: Paint, cx: Float, cy: Float, w: Float) {
        val p = Path().apply {
            moveTo(cx, cy)
            quadTo(cx + w * 0.25f, cy - w * 0.35f, cx + w * 0.5f, cy)
            quadTo(cx + w * 0.75f, cy + w * 0.35f, cx + w, cy)
        }
        canvas.drawPath(p, paint)
    }

    /** Papel roto/nota con un garabato encima y una sombra suave (estilo scrapbook). */
    private fun drawPaperScrap(
        canvas: Canvas,
        cx: Float, cy: Float, w: Float, h: Float, rotation: Float, doodle: Int,
    ) {
        canvas.save()
        canvas.rotate(rotation, cx, cy)
        val rect = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#FBFAF3")
            setShadowLayer(w * 0.06f, 0f, w * 0.03f, android.graphics.Color.parseColor("#33000000"))
        }
        canvas.drawRoundRect(rect, w * 0.04f, w * 0.04f, paperPaint)
        // líneas tenues del papelito
        val faint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#18597EA8"); strokeWidth = w * 0.012f
        }
        var yy = rect.top + h * 0.28f
        while (yy < rect.bottom - h * 0.1f) {
            canvas.drawLine(rect.left + w * 0.12f, yy, rect.right - w * 0.12f, yy, faint)
            yy += h * 0.22f
        }
        val dood = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#775B6B86")
            style = Paint.Style.STROKE; strokeWidth = w * 0.028f; strokeCap = Paint.Cap.ROUND
        }
        when (doodle) {
            0 -> drawSparkle(canvas, dood, cx, rect.top + h * 0.28f, h * 0.14f)
            1 -> drawHeart(canvas, dood.apply { style = Paint.Style.FILL }, cx, cy, h * 0.16f)
            else -> drawSquiggle(canvas, dood, rect.left + w * 0.2f, cy, w * 0.6f)
        }
        canvas.restore()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ▸ API LEGADA — sin cambios, backward compatible
    // ─────────────────────────────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.M)
    suspend fun createLyricsImage(
        context: Context,
        coverArtUrl: String?,
        songTitle: String,
        artistName: String,
        lyrics: String,
        width: Int,
        height: Int,
        backgroundColor: Int? = null,
        textColor: Int? = null,
        secondaryTextColor: Int? = null,
        glassStyle: LyricsGlassStyle? = null,
        fontName: String = "POPPINS",
    ): Bitmap = createLyricsImageWithConfig(
        context    = context,
        coverArtUrl = coverArtUrl,
        songTitle  = songTitle,
        artistName = artistName,
        lyrics     = lyrics,
        config     = LyricsCardConfig(
            glassStyle = glassStyle ?: LyricsGlassStyle.FrostedDark,
            fontName   = fontName,
        ),
        outputSize = minOf(width, height),
    )

    // ─────────────────────────────────────────────────────────────────────────
    // RENDERER 1 — Glass Card  (fiel al diseño Compose original)
    // ─────────────────────────────────────────────────────────────────────────

    private fun renderGlassCard(
        context: Context,
        coverArt: Bitmap?,
        songTitle: String,
        artistName: String,
        lyrics: String,
        config: LyricsCardConfig,
        cardSize: Int,
    ): Bitmap {
        val style         = config.glassStyle
        val (baseTf, boldTf) = resolveTypefaces(context, config)
        val bitmap        = createBitmap(cardSize, cardSize)
        val canvas        = Canvas(bitmap)
        val outerCorner   = cardSize * 0.06f
        val margin        = cardSize * 0.04f
        val panelRect     = RectF(margin, margin, cardSize - margin, cardSize - margin)
        val panelCorner   = cardSize * 0.05f
        val contentPad    = (config.cardPadding.value / 340f) * cardSize

        // Fondo
        drawAlbumBackground(canvas, cardSize, coverArt, style.backgroundDimAlpha, outerCorner)
        // Panel frosted
        drawFrostedPanel(canvas, cardSize, coverArt, style, panelRect, panelCorner)

        val mainColor = style.textColor.toArgb()
        val secColor  = style.secondaryTextColor.toArgb()
        val contentLeft  = panelRect.left + contentPad
        val contentRight = panelRect.right - contentPad
        val contentTop   = panelRect.top + contentPad

        // Metadata
        if (config.showTitle || config.showCoverArt || config.showArtist) {
            drawMetadataRow(canvas, coverArt, songTitle, artistName, cardSize,
                contentLeft, contentTop, contentRight,
                config.showCoverArt, config.showTitle, config.showArtist,
                mainColor, secColor, baseTf, boldTf)
        }

        // Letras
        val lyricsPaint    = TextPaint().apply { color = mainColor; typeface = boldTf; isAntiAlias = true; letterSpacing = -0.01f }
        val lyricsMaxWidth = (panelRect.width() * 0.85f).toInt()
        val logoH          = (panelRect.height() * 0.09f)
        val lyricsTop      = panelRect.top + panelRect.height() * 0.24f
        val lyricsBottom   = panelRect.bottom - (logoH + contentPad)

        val lyricsLayout = buildFittedLyricsLayout(
            lyrics           = lyrics,
            paint            = lyricsPaint,
            maxWidth         = lyricsMaxWidth,
            availableHeight  = lyricsBottom - lyricsTop,
            alignment        = config.textAlign.toLayoutAlignment(),
            initialTextSize  = (cardSize * 0.055f) * config.textSizeMultiplier,
        )
        val lyricsY = lyricsTop + ((lyricsBottom - lyricsTop) - lyricsLayout.height) / 2f
        canvas.withTranslation(panelRect.left + (panelRect.width() - lyricsMaxWidth) / 2f, lyricsY) {
            lyricsLayout.draw(this)
        }

        // Branding
        if (config.showBranding) {
            drawBranding(context, canvas, cardSize,
                x          = contentLeft,
                y          = panelRect.bottom - contentPad * 0.4f,
                circleColor = secColor,
                logoTint    = if (style.isDark) 0xDD000000.toInt() else 0xE6FFFFFF.toInt(),
                textColor   = secColor,
                typeface    = baseTf)
        }
        return bitmap
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RENDERER 2 — Minimal
    // ─────────────────────────────────────────────────────────────────────────

    private fun renderMinimal(
        context: Context,
        coverArt: Bitmap?,
        songTitle: String,
        artistName: String,
        lyrics: String,
        config: LyricsCardConfig,
        cardSize: Int,
    ): Bitmap {
        val isDark       = config.glassStyle.isDark
        val bgColor      = if (isDark) 0xFF0F0F0F.toInt() else 0xFFF5F5F5.toInt()
        val mainColor    = if (isDark) 0xFFFFFFFF.toInt() else 0xFF1A1A1A.toInt()
        val secondaryClr = if (isDark) 0x80FFFFFF.toInt() else 0x801A1A1A.toInt()
        val accentCompose = config.glassStyle.surfaceTint
        val accent = if (accentCompose == androidx.compose.ui.graphics.Color.Black ||
            accentCompose == androidx.compose.ui.graphics.Color.White)
            0xFF6366F1.toInt()
        else accentCompose.copy(alpha = 1f).toArgb()

        val (baseTf, boldTf) = resolveTypefaces(context, config)
        val bitmap  = createBitmap(cardSize, cardSize)
        val canvas  = Canvas(bitmap)
        val corner  = cardSize * 0.06f
        val padding = (config.cardPadding.value / 340f) * cardSize
        val size    = cardSize.toFloat()

        // Fondo sólido
        canvas.drawRoundRect(RectF(0f, 0f, size, size), corner, corner, Paint().apply {
            color = bgColor; isAntiAlias = true
        })

        // Línea de acento superior
        val accentPaint = Paint().apply {
            isAntiAlias = true
            shader = LinearGradient(0f, 0f, size * 0.5f, 0f,
                accent, android.graphics.Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(RectF(0f, 0f, size, 4f), accentPaint)

        // Comilla decorativa
        val quotePaint = TextPaint().apply {
            color    = (accent and 0x00FFFFFF) or 0x3F000000 // accent @ 25% alpha
            textSize = cardSize * 0.23f
            typeface = boldTf
            isAntiAlias = true
        }
        canvas.drawText("\u201C", padding, padding + quotePaint.textSize * 0.7f, quotePaint)

        // Letras
        val lyricsPaint = TextPaint().apply {
            color    = mainColor
            typeface = boldTf
            isAntiAlias = true
            letterSpacing = -0.02f
        }
        val lyricsMaxWidth  = (size - padding * 2).toInt()
        val quoteSpaceUsed  = cardSize * 0.18f
        val bottomReserved  = cardSize * 0.25f
        val lyricsAreaTop   = padding + quoteSpaceUsed
        val lyricsAreaH     = size - lyricsAreaTop - bottomReserved

        val lyricsLayout = buildFittedLyricsLayout(
            lyrics          = lyrics,
            paint           = lyricsPaint,
            maxWidth        = lyricsMaxWidth,
            availableHeight = lyricsAreaH,
            alignment       = config.textAlign.toLayoutAlignment(),
            initialTextSize = (cardSize * 0.065f) * config.textSizeMultiplier,
            minTextSize     = 24f,
        )
        val lyricsY = lyricsAreaTop + (lyricsAreaH - lyricsLayout.height) / 2f
        canvas.withTranslation(padding, lyricsY) { lyricsLayout.draw(this) }

        // Separador + metadatos
        val metaTop = size - bottomReserved + padding * 0.5f
        canvas.drawLine(padding, metaTop, size - padding, metaTop, Paint().apply {
            color = (mainColor and 0x00FFFFFF) or 0x1A000000; strokeWidth = 1.5f; isAntiAlias = true
        })
        val metaY = metaTop + padding * 0.8f
        val titlePaint = TextPaint().apply {
            color = mainColor; textSize = cardSize * 0.036f; typeface = boldTf; isAntiAlias = true
        }
        val artistPaint = TextPaint().apply {
            color = secondaryClr; textSize = cardSize * 0.028f; typeface = baseTf; isAntiAlias = true
        }
        if (config.showTitle) canvas.drawText(songTitle, padding, metaY, titlePaint)
        if (config.showArtist) canvas.drawText(artistName, padding, metaY + cardSize * 0.044f, artistPaint)

        if (config.showBranding) {
            drawBranding(context, canvas, cardSize,
                x = padding, y = size - padding * 0.2f,
                circleColor = secondaryClr, logoTint = if (isDark) 0xDD000000.toInt() else 0xE6FFFFFF.toInt(),
                textColor = secondaryClr, typeface = baseTf)
        }
        return bitmap
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RENDERER 3 — Cover Focused
    // ─────────────────────────────────────────────────────────────────────────

    private fun renderCoverFocused(
        context: Context,
        coverArt: Bitmap?,
        songTitle: String,
        artistName: String,
        lyrics: String,
        config: LyricsCardConfig,
        cardSize: Int,
    ): Bitmap {
        val style      = config.glassStyle
        val (baseTf, boldTf) = resolveTypefaces(context, config)
        val mainColor  = style.textColor.toArgb()
        val secColor   = style.secondaryTextColor.toArgb()
        val bitmap     = createBitmap(cardSize, cardSize)
        val canvas     = Canvas(bitmap)
        val corner     = cardSize * 0.06f
        val padding    = (config.cardPadding.value / 340f) * cardSize
        val size       = cardSize.toFloat()

        // Fondo desenfocado
        drawAlbumBackground(canvas, cardSize, coverArt, 0.55f, corner)

        // Carátula grande centrada en mitad superior
        if (config.showCoverArt && coverArt != null) {
            val artSize   = size * 0.4f
            val artLeft   = (size - artSize) / 2f
            val artTop    = padding
            val artRect   = RectF(artLeft, artTop, artLeft + artSize, artTop + artSize)
            val artCorner = cardSize * 0.04f
            val path = Path().apply { addRoundRect(artRect, artCorner, artCorner, Path.Direction.CW) }
            canvas.withClip(path) { drawBitmapCropped(coverArt, artRect) }
            canvas.drawRoundRect(artRect, artCorner, artCorner, Paint().apply {
                this.style = Paint.Style.STROKE; strokeWidth = 2f
                color = android.graphics.Color.argb(51, 255, 255, 255); isAntiAlias = true
            })
        }

        // Título y artista debajo de la carátula
        val artBottom = padding + size * 0.4f + padding * 0.5f
        val titlePaint = TextPaint().apply {
            color = mainColor; textSize = cardSize * 0.042f; typeface = boldTf
            textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true; letterSpacing = -0.02f
        }
        val artistPaint = TextPaint().apply {
            color = secColor; textSize = cardSize * 0.030f; typeface = baseTf
            textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true
        }
        if (config.showTitle) canvas.drawText(songTitle, size / 2f, artBottom, titlePaint)
        if (config.showArtist) canvas.drawText(artistName, size / 2f, artBottom + cardSize * 0.05f, artistPaint)

        // Separador
        val divY = artBottom + cardSize * 0.09f
        canvas.drawLine(padding * 2, divY, size - padding * 2, divY, Paint().apply {
            color = android.graphics.Color.argb(38, 255, 255, 255); strokeWidth = 1.5f; isAntiAlias = true
        })

        // Letras en la parte inferior
        val lyricsPaint = TextPaint().apply {
            color = mainColor; typeface = boldTf; isAntiAlias = true; letterSpacing = -0.01f
        }
        val lyricsMaxWidth  = (size * 0.82f).toInt()
        val logoH           = cardSize * 0.09f
        val lyricsTop       = divY + padding * 0.8f
        val lyricsBottom    = size - (if (config.showBranding) logoH else padding)

        val lyricsLayout = buildFittedLyricsLayout(
            lyrics          = lyrics,
            paint           = lyricsPaint,
            maxWidth        = lyricsMaxWidth,
            availableHeight = lyricsBottom - lyricsTop,
            alignment       = config.textAlign.toLayoutAlignment(),
            initialTextSize = (cardSize * 0.048f) * config.textSizeMultiplier,
            minTextSize     = 20f,
        )
        val lyricsY = lyricsTop + ((lyricsBottom - lyricsTop) - lyricsLayout.height) / 2f
        canvas.withTranslation((size - lyricsMaxWidth) / 2f, lyricsY) { lyricsLayout.draw(this) }

        if (config.showBranding) {
            drawBranding(context, canvas, cardSize,
                x = padding, y = size - padding * 0.2f,
                circleColor = secColor, logoTint = if (style.isDark) 0xDD000000.toInt() else 0xE6FFFFFF.toInt(),
                textColor = secColor, typeface = baseTf)
        }
        return bitmap
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RENDERER 4 — Centered
    // ─────────────────────────────────────────────────────────────────────────

    private fun renderCentered(
        context: Context,
        coverArt: Bitmap?,
        songTitle: String,
        artistName: String,
        lyrics: String,
        config: LyricsCardConfig,
        cardSize: Int,
    ): Bitmap {
        val style     = config.glassStyle
        val (baseTf, boldTf) = resolveTypefaces(context, config)
        val mainColor = style.textColor.toArgb()
        val secColor  = style.secondaryTextColor.toArgb()
        val bitmap    = createBitmap(cardSize, cardSize)
        val canvas    = Canvas(bitmap)
        val corner    = cardSize * 0.06f
        val padding   = (config.cardPadding.value / 340f) * cardSize
        val size      = cardSize.toFloat()

        // Fondo con scrim triple
        drawAlbumBackground(canvas, cardSize, coverArt, 0.60f, corner)
        // Scrim extra en bordes
        val extraScrimPaint = Paint().apply {
            shader = RadialGradient(size / 2f, size / 2f, size * 0.5f,
                intArrayOf(android.graphics.Color.TRANSPARENT, android.graphics.Color.argb(100, 0, 0, 0)),
                floatArrayOf(0.5f, 1f), Shader.TileMode.CLAMP)
            isAntiAlias = true
        }
        canvas.drawRoundRect(RectF(0f, 0f, size, size), corner, corner, extraScrimPaint)

        // Metadatos arriba centrados
        val titlePaint = TextPaint().apply {
            color = mainColor; textSize = cardSize * 0.038f; typeface = boldTf
            textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true; letterSpacing = 0.01f
        }
        val artistPaint = TextPaint().apply {
            color = secColor; textSize = cardSize * 0.028f; typeface = baseTf
            textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true; letterSpacing = 0.04f
        }
        var headerBottom = padding
        if (config.showTitle) {
            canvas.drawText(songTitle, size / 2f, padding + cardSize * 0.05f, titlePaint)
            headerBottom = padding + cardSize * 0.07f
        }
        if (config.showArtist) {
            canvas.drawText(artistName, size / 2f, headerBottom + cardSize * 0.04f, artistPaint)
            headerBottom += cardSize * 0.05f
        }

        // Letra centrada en el espacio restante
        val lyricsPaint = TextPaint().apply {
            color = mainColor; typeface = boldTf; isAntiAlias = true; letterSpacing = -0.02f
        }
        val lyricsMaxWidth = (size * 0.82f).toInt()
        val logoH          = if (config.showBranding) cardSize * 0.09f else 0f
        val lyricsAreaTop  = headerBottom + padding * 0.5f
        val lyricsAreaBottom = size - logoH - padding

        val lyricsLayout = buildFittedLyricsLayout(
            lyrics          = lyrics,
            paint           = lyricsPaint,
            maxWidth        = lyricsMaxWidth,
            availableHeight = lyricsAreaBottom - lyricsAreaTop,
            alignment       = Layout.Alignment.ALIGN_CENTER,
            initialTextSize = (cardSize * 0.068f) * config.textSizeMultiplier,
            minTextSize     = 22f,
        )
        val lyricsY = lyricsAreaTop + (lyricsAreaBottom - lyricsAreaTop - lyricsLayout.height) / 2f
        canvas.withTranslation((size - lyricsMaxWidth) / 2f, lyricsY) { lyricsLayout.draw(this) }

        if (config.showBranding) {
            drawBranding(context, canvas, cardSize,
                x = padding, y = size - padding * 0.2f,
                circleColor = secColor, logoTint = if (style.isDark) 0xDD000000.toInt() else 0xE6FFFFFF.toInt(),
                textColor = secColor, typeface = baseTf)
        }
        return bitmap
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RENDERER 5 — Blur Wash
    // ─────────────────────────────────────────────────────────────────────────

    private fun renderBlurWash(
        context: Context,
        coverArt: Bitmap?,
        songTitle: String,
        artistName: String,
        lyrics: String,
        config: LyricsCardConfig,
        cardSize: Int,
    ): Bitmap {
        val style     = config.glassStyle
        val (baseTf, boldTf) = resolveTypefaces(context, config)
        val mainColor = style.textColor.toArgb()
        val secColor  = style.secondaryTextColor.toArgb()
        val bitmap    = createBitmap(cardSize, cardSize)
        val canvas    = Canvas(bitmap)
        val corner    = cardSize * 0.06f
        val padding   = (config.cardPadding.value / 340f) * cardSize
        val size      = cardSize.toFloat()

        // Fondo con blur máximo (30)
        if (coverArt != null) {
            val scaled  = ensureSoftwareBitmap(Bitmap.createScaledBitmap(coverArt, cardSize, cardSize, true))
            val blurred = scaleBlur(scaled, 30)
            val path    = Path().apply { addRoundRect(RectF(0f, 0f, size, size), corner, corner, Path.Direction.CW) }
            canvas.withClip(path) { drawBitmap(blurred, 0f, 0f, null) }
        } else {
            canvas.drawRoundRect(RectF(0f, 0f, size, size), corner, corner, Paint().apply {
                color = 0xFF121232.toInt(); isAntiAlias = true
            })
        }
        canvas.drawRoundRect(RectF(0f, 0f, size, size), corner, corner, Paint().apply {
            color = android.graphics.Color.argb(96, 0, 0, 0); isAntiAlias = true
        })

        // Header de metadata
        val metaBottom = if (config.showTitle || config.showCoverArt || config.showArtist) {
            drawMetadataRow(canvas, coverArt, songTitle, artistName, cardSize,
                padding, padding, size - padding,
                config.showCoverArt, config.showTitle, config.showArtist,
                mainColor, secColor, baseTf, boldTf)
            padding + cardSize * 0.20f
        } else padding

        // Panel frosted para las letras
        val panelPad    = padding * 0.5f
        val logoH       = if (config.showBranding) cardSize * 0.10f else 0f
        val panelTop    = metaBottom + panelPad
        val panelBottom = size - logoH - panelPad
        val panelRect   = RectF(panelPad, panelTop, size - panelPad, panelBottom)
        val panelCorner = cardSize * 0.04f

        drawFrostedPanel(canvas, cardSize, coverArt, style, panelRect, panelCorner)

        // Letras dentro del panel
        val lyricsPaint = TextPaint().apply {
            color = mainColor; typeface = boldTf; isAntiAlias = true; letterSpacing = -0.01f
        }
        val innerPad       = panelRect.width() * 0.06f
        val lyricsMaxWidth = (panelRect.width() - innerPad * 2).toInt()
        val lyricsLayout   = buildFittedLyricsLayout(
            lyrics          = lyrics,
            paint           = lyricsPaint,
            maxWidth        = lyricsMaxWidth,
            availableHeight = panelRect.height() - innerPad * 2,
            alignment       = config.textAlign.toLayoutAlignment(),
            initialTextSize = (cardSize * 0.052f) * config.textSizeMultiplier,
            minTextSize     = 20f,
        )
        val lyricsX = panelRect.left + innerPad
        val lyricsY = panelRect.top + (panelRect.height() - lyricsLayout.height) / 2f
        canvas.withTranslation(lyricsX, lyricsY) { lyricsLayout.draw(this) }

        if (config.showBranding) {
            drawBranding(context, canvas, cardSize,
                x = padding, y = size - padding * 0.2f,
                circleColor = secColor, logoTint = if (style.isDark) 0xDD000000.toInt() else 0xE6FFFFFF.toInt(),
                textColor = secColor, typeface = baseTf)
        }
        return bitmap
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RENDERER 6 — Streaming Modern
    // ─────────────────────────────────────────────────────────────────────────

    private fun renderStreamingModern(
        context: Context,
        coverArt: Bitmap?,
        songTitle: String,
        artistName: String,
        lyrics: String,
        config: LyricsCardConfig,
        cardSize: Int,
    ): Bitmap {
        val accentCompose = config.glassStyle.surfaceTint
        val accentArgb    = if (accentCompose == androidx.compose.ui.graphics.Color.Black ||
            accentCompose == androidx.compose.ui.graphics.Color.White)
            0xFF6366F1.toInt() else accentCompose.copy(alpha = 1f).toArgb()

        val (baseTf, boldTf) = resolveTypefaces(context, config)
        val mainColor = 0xFFFFFFFF.toInt()
        val secColor  = 0x8CFFFFFF.toInt()
        val bitmap    = createBitmap(cardSize, cardSize)
        val canvas    = Canvas(bitmap)
        val corner    = cardSize * 0.06f
        val padding   = (config.cardPadding.value / 340f) * cardSize
        val size      = cardSize.toFloat()

        // Fondo degradado oscuro
        canvas.drawRoundRect(RectF(0f, 0f, size, size), corner, corner, Paint().apply {
            shader = LinearGradient(0f, 0f, size, size,
                intArrayOf(0xFF0D0D1A.toInt(), 0xFF191928.toInt()),
                null, Shader.TileMode.CLAMP)
            isAntiAlias = true
        })

        // Resplandor de acento en esquina superior derecha
        canvas.drawCircle(size * 0.85f, -size * 0.12f, size * 0.55f, Paint().apply {
            shader = RadialGradient(size * 0.85f, -size * 0.12f, size * 0.55f,
                intArrayOf((accentArgb and 0x00FFFFFF) or 0x38000000, android.graphics.Color.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            isAntiAlias = true
        })

        // Header
        var headerY = padding
        if (config.showCoverArt && coverArt != null) {
            val artSize = size * 0.13f
            val artRect = RectF(padding, padding, padding + artSize, padding + artSize)
            val artCorner = cardSize * 0.025f
            val path = Path().apply { addRoundRect(artRect, artCorner, artCorner, Path.Direction.CW) }
            canvas.withClip(path) { drawBitmapCropped(coverArt, artRect) }
            canvas.drawRoundRect(artRect, artCorner, artCorner, Paint().apply {
                this.style = Paint.Style.STROKE; strokeWidth = 1.5f
                shader = LinearGradient(artRect.left, artRect.top, artRect.right, artRect.bottom,
                    intArrayOf((accentArgb and 0x00FFFFFF) or 0x66000000, android.graphics.Color.TRANSPARENT),
                    null, Shader.TileMode.CLAMP)
                isAntiAlias = true
            })

            val titlePaint = TextPaint().apply {
                color = mainColor; textSize = cardSize * 0.036f
                typeface = boldTf; isAntiAlias = true; letterSpacing = -0.02f
            }
            val artistPaint = TextPaint().apply {
                color = secColor; textSize = cardSize * 0.026f; typeface = baseTf; isAntiAlias = true; letterSpacing = 0.01f
            }
            val textLeft  = padding + artSize + cardSize * 0.035f
            val imgCenter = padding + artSize / 2f
            if (config.showTitle) canvas.drawText(songTitle, textLeft, imgCenter - cardSize * 0.01f, titlePaint)
            if (config.showArtist) canvas.drawText(artistName, textLeft, imgCenter + cardSize * 0.04f, artistPaint)

            headerY = padding + artSize + padding * 0.6f
        } else {
            val titlePaint = TextPaint().apply {
                color = mainColor; textSize = cardSize * 0.036f
                typeface = boldTf; isAntiAlias = true; letterSpacing = -0.02f
            }
            if (config.showTitle) {
                canvas.drawText(songTitle, padding, padding + cardSize * 0.05f, titlePaint)
                headerY = padding + cardSize * 0.07f
            }
            if (config.showArtist) {
                val artistPaint = TextPaint().apply {
                    color = secColor; textSize = cardSize * 0.026f; typeface = baseTf; isAntiAlias = true
                }
                canvas.drawText(artistName, padding, headerY + cardSize * 0.04f, artistPaint)
                headerY += cardSize * 0.05f
            }
        }

        // Separador con acento
        canvas.drawLine(padding, headerY, size * 0.6f, headerY, Paint().apply {
            shader = LinearGradient(padding, headerY, size * 0.6f, headerY,
                intArrayOf(accentArgb, android.graphics.Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP)
            strokeWidth = 1.5f; isAntiAlias = true
        })
        headerY += padding * 0.6f

        // Comilla y letras
        val quotePaint = TextPaint().apply {
            color    = (accentArgb and 0x00FFFFFF) or 0x8C000000.toInt()
            textSize = cardSize * 0.10f
            typeface = boldTf; isAntiAlias = true
        }
        canvas.drawText("\u201C", padding, headerY + quotePaint.textSize * 0.7f, quotePaint)
        val quoteH = quotePaint.textSize * 0.75f

        val lyricsPaint = TextPaint().apply {
            color = mainColor; typeface = boldTf; isAntiAlias = true; letterSpacing = -0.02f
        }
        val logoH          = if (config.showBranding) cardSize * 0.09f else 0f
        val lyricsMaxWidth = (size - padding * 2).toInt()
        val lyricsAreaTop  = headerY + quoteH + padding * 0.3f
        val lyricsAreaBottom = size - logoH - padding

        val lyricsLayout = buildFittedLyricsLayout(
            lyrics          = lyrics,
            paint           = lyricsPaint,
            maxWidth        = lyricsMaxWidth,
            availableHeight = lyricsAreaBottom - lyricsAreaTop,
            alignment       = config.textAlign.toLayoutAlignment(),
            initialTextSize = (cardSize * 0.058f) * config.textSizeMultiplier,
            minTextSize     = 22f,
        )
        val lyricsY = lyricsAreaTop + (lyricsAreaBottom - lyricsAreaTop - lyricsLayout.height) / 2f
        canvas.withTranslation(padding, lyricsY) { lyricsLayout.draw(this) }

        if (config.showBranding) {
            drawBranding(context, canvas, cardSize,
                x = padding, y = size - padding * 0.2f,
                circleColor = secColor, logoTint = 0xDD000000.toInt(), textColor = secColor,
                typeface = baseTf)
        }
        return bitmap
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilidades internas
    // ─────────────────────────────────────────────────────────────────────────

    private fun scaleBlur(source: Bitmap, strength: Int): Bitmap {
        val safe = ensureSoftwareBitmap(source)

        // Reduce menos agresivamente para mejor calidad
        val factor = (1f / (strength / 2f).coerceAtLeast(1F)).coerceIn(0.3f, 1f)
        val smallW = (safe.width * factor).toInt().coerceAtLeast(100)
        val smallH = (safe.height * factor).toInt().coerceAtLeast(100)
        val small = Bitmap.createScaledBitmap(safe, smallW, smallH, true)
        return Bitmap.createScaledBitmap(small, safe.width, safe.height, true)
    }

    /** Convierte TextAlign de Compose al equivalente de Canvas */
    private fun androidx.compose.ui.text.style.TextAlign.toLayoutAlignment() = when (this) {
        androidx.compose.ui.text.style.TextAlign.Start,
        androidx.compose.ui.text.style.TextAlign.Left  -> Layout.Alignment.ALIGN_NORMAL
        androidx.compose.ui.text.style.TextAlign.End,
        androidx.compose.ui.text.style.TextAlign.Right -> Layout.Alignment.ALIGN_OPPOSITE
        else                                            -> Layout.Alignment.ALIGN_CENTER
    }

    /** Convierte Color de Compose a Int ARGB */
    private fun androidx.compose.ui.graphics.Color.toArgb(): Int =
        android.graphics.Color.argb(
            (alpha * 255).toInt(), (red * 255).toInt(),
            (green * 255).toInt(), (blue * 255).toInt(),
        )

    // ─────────────────────────────────────────────────────────────────────────
    // Guardar / persistir bitmap
    // ─────────────────────────────────────────────────────────────────────────

    fun saveBitmapAsFile(context: Context, bitmap: Bitmap, fileName: String): Uri {
        val safeBitmap = ensureSoftwareBitmap(bitmap)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$fileName.png")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Dezzy")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
            ) ?: throw IllegalStateException("Failed to create new MediaStore record")
            context.contentResolver.openOutputStream(uri)?.use { safeBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            uri
        } else {
            val cachePath = File(context.cacheDir, "images").also { it.mkdirs() }
            val imageFile = File(cachePath, "$fileName.png")
            FileOutputStream(imageFile).use { safeBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", imageFile)
        }
    }
    // ─────────────────────────────────────────────────────────────────────────
    // RENDERER 7 — DezzyShare
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Mirrors ShareBottomSheet's preview card 1:1 so what the user shares is exactly what they
     * saw: a 3:4 card in the chosen palette color, big centered cover, centered title/artist,
     * and the "♪ Dezzy" branding row. (The old renderer drew an unrelated square lyrics card,
     * which is why shared images didn't match the preview.)
     */
    private fun renderDezzyShare(
        context: Context,
        coverArt: Bitmap?,
        songTitle: String,
        artistName: String,
        lyrics: String,
        config: LyricsCardConfig,
        cardSize: Int,
    ): Bitmap {
        val width  = cardSize
        val height = cardSize * 4 / 3               // preview: aspectRatio(3f / 4f)
        val (baseTf, boldTf) = resolveTypefaces(context, config)
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val w = width.toFloat()
        val h = height.toFloat()

        val bgColor      = config.customBgColor.toArgb()
        val mainColor    = config.customTextColor.toArgb()
        val secondaryClr = config.customSecondaryTextColor.toArgb()

        // Preview proportions relative to its ~300dp-wide card.
        val corner  = w * 0.053f                    // RoundedCornerShape(16.dp)
        val padding = w * 0.08f                     // padding(24.dp)

        canvas.drawRoundRect(RectF(0f, 0f, w, h), corner, corner, Paint().apply {
            isAntiAlias = true
            if (config.customBgGradient) {
                // Degradado estilo Spotify: el color de la paleta arriba → negro abajo.
                shader = android.graphics.LinearGradient(
                    0f, 0f, 0f, h,
                    bgColor, android.graphics.Color.BLACK,
                    android.graphics.Shader.TileMode.CLAMP,
                )
            } else {
                color = bgColor
            }
        })

        val contentWidth = w - padding * 2

        // ── Variante "Letra" (tab Letra del share): carátula pequeña arriba
        //    con título/artista al lado, y la letra grande centrada ──
        if (lyrics.isNotBlank()) {
            val artSize = w * 0.15f
            val headerTop = padding
            if (coverArt != null) {
                val artRect = RectF(padding, headerTop, padding + artSize, headerTop + artSize)
                val artCorner = w * 0.02f
                val path = Path().apply { addRoundRect(artRect, artCorner, artCorner, Path.Direction.CW) }
                canvas.withClip(path) { drawBitmapCropped(coverArt, artRect) }
            }
            val headTextLeft = padding + artSize + w * 0.035f
            val headTextWidth = (w - padding - headTextLeft).toInt().coerceAtLeast(1)
            val headTitlePaint = TextPaint().apply {
                color = mainColor; textSize = w * 0.05f
                typeface = boldTf; isAntiAlias = true
            }
            val headArtistPaint = TextPaint().apply {
                color = secondaryClr; textSize = w * 0.04f
                typeface = baseTf; isAntiAlias = true
            }
            fun headLine(text: String, paint: TextPaint) =
                StaticLayout.Builder.obtain(text, 0, text.length, paint, headTextWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setMaxLines(1)
                    .setEllipsize(android.text.TextUtils.TruncateAt.END)
                    .setEllipsizedWidth(headTextWidth)
                    .build()
            val headTitle = headLine(songTitle, headTitlePaint)
            val headArtist = headLine(artistName, headArtistPaint)
            val headTextTop = headerTop + (artSize - headTitle.height - headArtist.height - w * 0.01f) / 2f
            canvas.withTranslation(headTextLeft, headTextTop) { headTitle.draw(this) }
            canvas.withTranslation(headTextLeft, headTextTop + headTitle.height + w * 0.01f) { headArtist.draw(this) }

            // Letra centrada entre el header y el branding
            val brandH = w * 0.06f
            val lyricsTop = headerTop + artSize + w * 0.07f
            val lyricsBottom = h - padding - brandH - w * 0.04f
            val lyricsPaint = TextPaint().apply {
                color = mainColor; typeface = boldTf
                isAntiAlias = true; letterSpacing = -0.01f
            }
            val lyricsLayout = buildFittedLyricsLayout(
                lyrics = lyrics,
                paint = lyricsPaint,
                maxWidth = (contentWidth * 0.96f).toInt(),
                availableHeight = lyricsBottom - lyricsTop,
                alignment = Layout.Alignment.ALIGN_NORMAL,
                initialTextSize = w * 0.078f,
            )
            val lyricsY = lyricsTop + ((lyricsBottom - lyricsTop) - lyricsLayout.height).coerceAtLeast(0f) / 2f
            canvas.withTranslation(padding, lyricsY) { lyricsLayout.draw(this) }

            // Branding inferior (estrella + "Dezzy"), centrado
            val brandPaintL = TextPaint().apply {
                color = mainColor; textSize = w * 0.047f
                typeface = boldTf; isAntiAlias = true
            }
            val noteSizeL = w * 0.053f
            val brandTextL = "Dezzy"
            val brandWidthL = brandPaintL.measureText(brandTextL)
            val rowWidthL = noteSizeL + w * 0.02f + brandWidthL
            var xL = (w - rowWidthL) / 2f
            val rowCenterYL = h - padding - brandH / 2f
            context.getDrawable(R.drawable.star)
                ?.toBitmap(noteSizeL.toInt(), noteSizeL.toInt())
                ?.let { star ->
                    val starPaint = Paint().apply {
                        colorFilter = PorterDuffColorFilter(mainColor, PorterDuff.Mode.SRC_IN)
                        isAntiAlias = true
                    }
                    canvas.drawBitmap(star, xL, rowCenterYL - noteSizeL / 2f, starPaint)
                }
            xL += noteSizeL + w * 0.02f
            val baselineL = rowCenterYL - (brandPaintL.descent() + brandPaintL.ascent()) / 2f
            canvas.drawText(brandTextL, xL, baselineL, brandPaintL)

            return bitmap
        }

        // Measure every block first so the whole group can be vertically centered,
        // exactly like the preview's verticalArrangement = Center.
        val titlePaint = TextPaint().apply {
            color = mainColor; textSize = w * 0.073f      // 22.sp
            typeface = boldTf; isAntiAlias = true
        }
        val artistPaint = TextPaint().apply {
            color = secondaryClr; textSize = w * 0.053f   // 16.sp
            typeface = baseTf; isAntiAlias = true
        }
        val brandPaint = TextPaint().apply {
            color = mainColor; textSize = w * 0.047f      // 14.sp
            typeface = boldTf; isAntiAlias = true
        }

        fun singleLine(text: String, paint: TextPaint) =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, contentWidth.toInt())
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(1)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .setEllipsizedWidth(contentWidth.toInt())
                .build()

        val titleLayout  = singleLine(songTitle, titlePaint)
        val artistLayout = singleLine(artistName, artistPaint)

        val coverSize  = contentWidth                 // fillMaxWidth().aspectRatio(1f)
        val gapLarge   = w * 0.08f                    // Spacer(24.dp)
        val gapSmall   = w * 0.013f                   // Spacer(4.dp)
        val noteSize   = w * 0.053f                   // Icon 16.dp
        val brandRowH  = maxOf(noteSize, brandPaint.textSize * 1.2f)

        val totalContent = coverSize + gapLarge + titleLayout.height + gapSmall +
            artistLayout.height + gapLarge + brandRowH
        var y = (h - totalContent) / 2f

        // Cover — full content width, rounded like the preview's RoundedCornerShape(12.dp).
        if (coverArt != null) {
            val artRect = RectF(padding, y, padding + coverSize, y + coverSize)
            val artCorner = w * 0.04f
            val path = Path().apply { addRoundRect(artRect, artCorner, artCorner, Path.Direction.CW) }
            canvas.withClip(path) { drawBitmapCropped(coverArt, artRect) }
        }
        y += coverSize + gapLarge

        canvas.withTranslation(padding, y) { titleLayout.draw(this) }
        y += titleLayout.height + gapSmall

        canvas.withTranslation(padding, y) { artistLayout.draw(this) }
        y += artistLayout.height + gapLarge

        // Branding row (music note + "Dezzy"), centered as a unit like the preview.
        val brandText = "Dezzy"
        val brandTextWidth = brandPaint.measureText(brandText)
        val noteGap = w * 0.02f                       // Spacer(6.dp)
        val rowWidth = noteSize + noteGap + brandTextWidth
        var x = (w - rowWidth) / 2f
        val rowCenterY = y + brandRowH / 2f

        // Star from the app logo (matches the preview's branding row).
        context.getDrawable(R.drawable.star)
            ?.toBitmap(noteSize.toInt(), noteSize.toInt())
            ?.let { star ->
                val starPaint = Paint().apply {
                    colorFilter = PorterDuffColorFilter(mainColor, PorterDuff.Mode.SRC_IN)
                    isAntiAlias = true
                }
                canvas.drawBitmap(star, x, rowCenterY - noteSize / 2f, starPaint)
            }
        x += noteSize + noteGap
        // Baseline vertically centered against the icon.
        val textBaseline = rowCenterY - (brandPaint.descent() + brandPaint.ascent()) / 2f
        canvas.drawText(brandText, x, textBaseline, brandPaint)

        return bitmap
    }
}