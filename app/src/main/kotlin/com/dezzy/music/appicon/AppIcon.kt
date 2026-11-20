/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.appicon

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dezzy.music.R
import com.dezzy.music.utils.PreferenceStore
import com.dezzy.music.utils.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Icono de la app: dos formas (d·play, la predeterminada, y la estrella de siempre) y tres
 * colores libres (fondo, figura y acento). Dentro de la app se dibuja con los colores exactos;
 * el launcher de Android sólo admite iconos del APK, así que usa la paleta más parecida
 * (2 formas × 8 paletas como activity-alias, ver AndroidManifest).
 */
enum class AppIconShape(val id: String, val label: String) {
    D_PLAY("d", "d · play"),
    STAR("star", "Estrella");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: D_PLAY
    }
}

@Immutable
data class AppIconPalette(val name: String, val bg: Int, val fg: Int, val ac: Int)

@Immutable
data class AppIconStyle(val shape: AppIconShape, val bg: Int, val fg: Int, val ac: Int) {
    /** Paleta del APK idéntica a estos colores, o -1 si son colores propios. */
    val paletteIndex: Int
        get() = AppIcon.PALETTES.indexOfFirst { it.bg == bg && it.fg == fg && it.ac == ac }

    /** Paleta del APK más parecida (el fondo pesa más: es lo que más se ve). */
    val nearestPalette: Int
        get() = AppIcon.PALETTES.indices.minBy { i ->
            val p = AppIcon.PALETTES[i]
            3 * dist(p.bg, bg) + 2 * dist(p.fg, fg) + dist(p.ac, ac)
        }

    companion object {
        val Default = AppIconStyle(AppIconShape.D_PLAY, AppIcon.PALETTES[0].bg, AppIcon.PALETTES[0].fg, AppIcon.PALETTES[0].ac)

        private fun dist(a: Int, b: Int): Double {
            val la = DoubleArray(3).also { ColorUtils.colorToLAB(a, it) }
            val lb = DoubleArray(3).also { ColorUtils.colorToLAB(b, it) }
            return ColorUtils.distanceEuclidean(la, lb)
        }
    }
}

object AppIcon {
    val PALETTES = listOf(
        AppIconPalette("Original", 0xFF7C3AED.toInt(), 0xFF0B0B10.toInt(), 0xFF34D399.toInt()),
        AppIconPalette("Medianoche", 0xFF0B0B10.toInt(), 0xFFF7F5FF.toInt(), 0xFF7C3AED.toInt()),
        AppIconPalette("Dezzy", 0xFFED5564.toInt(), 0xFFFFFFFF.toInt(), 0xFF2A0E14.toInt()),
        AppIconPalette("Lima", 0xFFC6F432.toInt(), 0xFF0B0B10.toInt(), 0xFF7C3AED.toInt()),
        AppIconPalette("Océano", 0xFF0EA5E9.toInt(), 0xFF0B1220.toInt(), 0xFFFDE047.toInt()),
        AppIconPalette("Coral", 0xFFFF7A59.toInt(), 0xFF1B1B1F.toInt(), 0xFFFFF1E6.toInt()),
        AppIconPalette("Bosque", 0xFF14532D.toInt(), 0xFFECFDF5.toInt(), 0xFFFACC15.toInt()),
        AppIconPalette("Papel", 0xFFF7F5FF.toInt(), 0xFF0B0B10.toInt(), 0xFF7C3AED.toInt()),
    )

    val ShapeKey = stringPreferencesKey("appIconShape")
    val BgKey = intPreferencesKey("appIconBg")
    val FgKey = intPreferencesKey("appIconFg")
    val AcKey = intPreferencesKey("appIconAc")

    /** Alias del launcher activo («d_0», «star_3»…), guardado al pulsar «Aplicar al launcher». */
    val LauncherKey = stringPreferencesKey("appIconLauncher")

    fun styleOf(prefs: Preferences?): AppIconStyle {
        val d = AppIconStyle.Default
        return AppIconStyle(
            shape = AppIconShape.of(prefs?.get(ShapeKey)),
            bg = prefs?.get(BgKey) ?: d.bg,
            fg = prefs?.get(FgKey) ?: d.fg,
            ac = prefs?.get(AcKey) ?: d.ac,
        )
    }

    /** Estilo actual sin bloquear (usa la caché de PreferenceStore; si no hay, el predeterminado). */
    fun current(context: Context): AppIconStyle {
        PreferenceStore.start(context.applicationContext)
        val shape = PreferenceStore.get(ShapeKey)
        val d = AppIconStyle.Default
        return AppIconStyle(
            AppIconShape.of(shape),
            PreferenceStore.get(BgKey) ?: d.bg,
            PreferenceStore.get(FgKey) ?: d.fg,
            PreferenceStore.get(AcKey) ?: d.ac,
        )
    }

    suspend fun save(context: Context, style: AppIconStyle) {
        context.dataStore.edit {
            it[ShapeKey] = style.shape.id
            it[BgKey] = style.bg
            it[FgKey] = style.fg
            it[AcKey] = style.ac
        }
    }

    /** Icono pequeño de notificación (monocromo) con la forma elegida. */
    fun smallIconRes(context: Context): Int {
        // Va con el icono aplicado al launcher (no con el borrador de Ajustes). Al arrancar el
        // servicio la caché aún puede estar vacía: lectura corta de DataStore.
        val launcher = PreferenceStore.get(LauncherKey) ?: runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(400) { context.dataStore.data.first()[LauncherKey] }
        }
        return smallIconFor(launcher)
    }

    /** Icono pequeño (notificaciones) para un alias del launcher como "star_3". */
    fun smallIconFor(launcher: String?): Int =
        if (launcher?.substringBefore('_') == AppIconShape.STAR.id) R.drawable.star else R.drawable.small_icon_d

    // ── Launcher ─────────────────────────────────────────────────────────────────────────

    private fun aliasName(shape: AppIconShape, palette: Int) = "com.dezzy.music.AppIcon_${shape.id}_$palette"

    /** Alias del launcher que corresponde a [style]: misma forma y paleta más parecida. */
    fun launcherAliasFor(style: AppIconStyle) = "${style.shape.id}_${style.nearestPalette}"

    /** Alias activo ahora mismo en el sistema (por si se cambió fuera de Dezzy o nunca se aplicó). */
    fun activeLauncherAlias(context: Context): String {
        val pm = context.packageManager
        for (shape in AppIconShape.entries) for (i in PALETTES.indices) {
            val cn = ComponentName(context.packageName, aliasName(shape, i))
            val state = runCatching { pm.getComponentEnabledSetting(cn) }.getOrNull() ?: continue
            val enabled = when (state) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> shape == AppIconShape.D_PLAY && i == 0
                else -> false
            }
            if (enabled) return "${shape.id}_$i"
        }
        return "d_0"
    }

    /**
     * Cambia el icono del launcher. Primero activa el nuevo alias y luego apaga los demás,
     * para que nunca quede la app sin icono. Algunos launchers cierran la app al hacerlo.
     */
    suspend fun applyToLauncher(context: Context, style: AppIconStyle) {
        val target = launcherAliasFor(style)
        val pm = context.packageManager
        val all = AppIconShape.entries.flatMap { s -> PALETTES.indices.map { s to it } }
        val (ts, ti) = all.first { "${it.first.id}_${it.second}" == target }
        context.dataStore.edit { it[LauncherKey] = target }
        pm.setComponentEnabledSetting(
            ComponentName(context.packageName, aliasName(ts, ti)),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        for ((s, i) in all) {
            if (s == ts && i == ti) continue
            pm.setComponentEnabledSetting(
                ComponentName(context.packageName, aliasName(s, i)),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    // ── Dibujo ───────────────────────────────────────────────────────────────────────────

    private val RING = PathParser.createPathFromPathData("M110,300a110,110 0 1,0 220,0a110,110 0 1,0 -220,0")
    private val STEM = PathParser.createPathFromPathData("M328,130a30,30 0 0,1 30,30v220a30,30 0 0,1 -60,0v-220a30,30 0 0,1 30,-30z")
    private val PLAY = PathParser.createPathFromPathData("M188,250L292,300L188,350Z")
    private const val STAR_OUT_D = "M233,840L298,559L80,370L368,345L480,80L592,345L880,370L662,559L727,840L480,691L233,840Z"
    private const val STAR_IN_D = "M354,673L480,597L606,674L573,530L684,434L538,421L480,285L422,420L276,433L387,530L354,673Z"
    private val STAR_RING = PathParser.createPathFromPathData("$STAR_OUT_D $STAR_IN_D").apply { fillType = Path.FillType.EVEN_ODD }
    private val STAR_IN = PathParser.createPathFromPathData(STAR_IN_D)

    /** Caja de la figura dentro del lienzo de 512 (para dibujar sólo la marca, sin fondo). */
    private fun glyphBounds(shape: AppIconShape) = when (shape) {
        AppIconShape.D_PLAY -> RectF(82f, 130f, 358f, 438f)
        AppIconShape.STAR -> RectF(106f, 106f, 406f, 391f)
    }

    /**
     * Dibuja el icono en [canvas] dentro de un cuadrado de [size] px.
     * @param background true: icono completo con fondo; false: sólo la figura, ajustada al cuadrado.
     * @param corner radio de las esquinas del fondo como fracción del tamaño (0.5 = círculo).
     * @param mono si no es null, toda la figura se pinta de este color (para tintar).
     */
    fun draw(
        canvas: Canvas,
        size: Float,
        style: AppIconStyle,
        background: Boolean = true,
        corner: Float = 0.22f,
        mono: Int? = null,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.save()
        if (background) {
            paint.color = style.bg
            val r = size * corner
            canvas.drawRoundRect(0f, 0f, size, size, r, r, paint)
            canvas.scale(size / 512f, size / 512f)
        } else {
            val b = glyphBounds(style.shape)
            val s = size / maxOf(b.width(), b.height())
            canvas.translate((size - b.width() * s) / 2f, (size - b.height() * s) / 2f)
            canvas.scale(s, s)
            canvas.translate(-b.left, -b.top)
        }
        val fg = mono ?: style.fg
        val ac = mono ?: style.ac
        when (style.shape) {
            AppIconShape.D_PLAY -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 56f
                paint.color = fg
                canvas.drawPath(RING, paint)
                paint.style = Paint.Style.FILL
                canvas.drawPath(STEM, paint)
                paint.color = ac
                canvas.drawPath(PLAY, paint)
            }
            AppIconShape.STAR -> {
                canvas.translate(76f, 76f)
                canvas.scale(0.375f, 0.375f)
                paint.color = fg
                canvas.drawPath(STAR_RING, paint)
                if (mono == null) {
                    paint.color = ac
                    canvas.drawPath(STAR_IN, paint)
                }
            }
        }
        canvas.restore()
    }

    /**
     * Drawable monocromo blanco de la marca (sin fondo) con la forma actual: sustituto directo
     * de `getDrawable(R.drawable.star)` donde luego se tinta con un ColorFilter.
     */
    fun markDrawable(context: Context): Drawable = MarkDrawable(current(context))

    private class MarkDrawable(private val style: AppIconStyle) : Drawable() {
        private var filter: ColorFilter? = null
        override fun draw(canvas: Canvas) {
            val b = bounds
            val size = minOf(b.width(), b.height()).toFloat()
            canvas.save()
            canvas.translate(b.left + (b.width() - size) / 2f, b.top + (b.height() - size) / 2f)
            if (filter != null) {
                canvas.saveLayer(0f, 0f, size, size, Paint().apply { colorFilter = filter })
                draw(canvas, size, style, background = false, mono = android.graphics.Color.WHITE)
                canvas.restore()
            } else {
                draw(canvas, size, style, background = false, mono = android.graphics.Color.WHITE)
            }
            canvas.restore()
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) { filter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth() = 96
        override fun getIntrinsicHeight() = 96
    }
}

/** Estilo del icono, reactivo: cualquier cambio en Ajustes repinta todos los logos al momento. */
@Composable
fun rememberAppIconStyle(): AppIconStyle {
    val context = LocalContext.current
    val flow = remember(context) {
        context.dataStore.data.map { AppIcon.styleOf(it) }.distinctUntilChanged()
    }
    val style by flow.collectAsState(initial = AppIcon.current(context))
    return style
}
