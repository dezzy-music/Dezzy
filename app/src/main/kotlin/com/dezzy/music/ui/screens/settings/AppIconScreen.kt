/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.screens.settings

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.navigation.NavController
import com.dezzy.music.LocalPlayerAwareWindowInsets
import com.dezzy.music.R
import com.dezzy.music.appicon.AppIcon
import com.dezzy.music.appicon.AppIconShape
import com.dezzy.music.appicon.AppIconStyle
import com.dezzy.music.appicon.DezzyAppIcon
import com.dezzy.music.appicon.rememberAppIconStyle
import com.dezzy.music.ui.component.IconButton
import com.dezzy.music.ui.utils.backToMain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ajustes › Apariencia › Icono de la app (boceto B «Galería»): las 16 combinaciones del launcher
 * en una cuadrícula y, debajo, «Colores propios» con barras de tono y de luz por color.
 * Dentro de la app el cambio es inmediato; el launcher sólo cambia con «Aplicar al launcher».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppIconScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val saved = rememberAppIconStyle()

    // Borrador local: las barras se arrastran sin esperar a DataStore; se guarda 120 ms después.
    var draft by remember { mutableStateOf<AppIconStyle?>(null) }
    val style = draft ?: saved
    LaunchedEffect(draft) {
        val d = draft ?: return@LaunchedEffect
        delay(120)
        AppIcon.save(context, d)
    }
    fun update(s: AppIconStyle) { draft = s }

    var customOpen by rememberSaveable { mutableStateOf(false) }
    var confirmApply by remember { mutableStateOf(false) }
    var activeAlias by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        activeAlias = withContext(Dispatchers.IO) { AppIcon.activeLauncherAlias(context) }
    }
    val targetAlias = AppIcon.launcherAliasFor(style)
    val applied = activeAlias == targetAlias

    Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))

        // ── Vista previa ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 12.dp),
        ) {
            DezzyAppIcon(Modifier.size(84.dp).semantics { contentDescription = "Icono actual" }, style, corner = 0.5f)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                val pal = style.paletteIndex
                Text(
                    style.shape.label + " · " + (if (pal >= 0) AppIcon.PALETTES[pal].name else "Personalizada"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Se ve así en toda la app al momento.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (pal < 0) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DezzyAppIcon(
                            Modifier.size(22.dp),
                            AppIconStyle(style.shape, AppIcon.PALETTES[style.nearestPalette].bg, AppIcon.PALETTES[style.nearestPalette].fg, AppIcon.PALETTES[style.nearestPalette].ac),
                            corner = 0.5f,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Launcher: «${AppIcon.PALETTES[style.nearestPalette].name}»",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ── Galería: 2 formas × 8 paletas ──
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                AppIconShape.entries.forEach { shape ->
                    Text(
                        shape.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 6.dp),
                    )
                    AppIcon.PALETTES.indices.chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth()) {
                            row.forEach { i ->
                                val p = AppIcon.PALETTES[i]
                                val cell = AppIconStyle(shape, p.bg, p.fg, p.ac)
                                val selected = style == cell
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            update(cell)
                                        }
                                        .padding(vertical = 8.dp)
                                        .semantics { contentDescription = "${shape.label}, ${p.name}" },
                                ) {
                                    DezzyAppIcon(Modifier.size(52.dp), cell, corner = 0.5f)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        p.name,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Colores propios ──
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { customOpen = !customOpen }
                        .padding(vertical = 4.dp),
                ) {
                    Icon(painterResource(R.drawable.palette), null, Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Colores propios", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Icon(painterResource(if (customOpen) R.drawable.expand_less else R.drawable.expand_more), null)
                }
                AnimatedVisibility(customOpen) {
                    Column {
                        val isD = style.shape == AppIconShape.D_PLAY
                        ColorEditor("Fondo", style.bg) { update(style.copy(bg = it)) }
                        ColorEditor(if (isD) "Letra" else "Estrella", style.fg) { update(style.copy(fg = it)) }
                        ColorEditor(if (isD) "Play" else "Centro", style.ac) { update(style.copy(ac = it)) }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "En el launcher se usará la paleta más parecida. Dentro de Dezzy, tus colores exactos.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { confirmApply = true },
            enabled = !applied,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Icon(painterResource(if (applied) R.drawable.check else R.drawable.home_filled), null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (applied) "Ya está en el launcher" else "Aplicar al launcher")
        }
        Text(
            "El icono de la pantalla de inicio sólo cambia al pulsar este botón.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
        )
    }

    if (confirmApply) {
        val p = AppIcon.PALETTES[style.nearestPalette]
        AlertDialog(
            onDismissRequest = { confirmApply = false },
            icon = {
                DezzyAppIcon(Modifier.size(56.dp), AppIconStyle(style.shape, p.bg, p.fg, p.ac), corner = 0.5f)
            },
            title = { Text("¿Cambiar el icono del launcher?") },
            text = {
                Text(
                    "Se pondrá «${style.shape.label} · ${p.name}». La app puede cerrarse unos segundos " +
                        "mientras el sistema cambia el icono, y en algunos launchers tendrás que volver " +
                        "a poner Dezzy en la pantalla de inicio.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmApply = false
                    scope.launch {
                        draft?.let { AppIcon.save(context, it) }
                        val ok = runCatching {
                            withContext(Dispatchers.IO) { AppIcon.applyToLauncher(context, style) }
                        }.isSuccess
                        activeAlias = withContext(Dispatchers.IO) { AppIcon.activeLauncherAlias(context) }
                        Toast.makeText(
                            context,
                            if (ok) "Icono del launcher cambiado" else "No se pudo cambiar el icono",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }) { Text("Aplicar") }
            },
            dismissButton = { TextButton(onClick = { confirmApply = false }) { Text("Cancelar") } },
        )
    }

    TopAppBar(
        title = { Text("Icono de la app") },
        navigationIcon = {
            IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
        actions = {
            IconButton(onClick = { update(AppIconStyle.Default) }, onLongClick = {}) {
                Icon(painterResource(R.drawable.restore), contentDescription = "Restablecer")
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

/** Un color con barra de tono y barra de luz (de oscuro a claro). */
@Composable
private fun ColorEditor(label: String, color: Int, onChange: (Int) -> Unit) {
    val hsl = remember(color) { FloatArray(3).also { ColorUtils.colorToHSL(color, it) } }
    Column(Modifier.padding(top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(color))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(5.dp)),
            )
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                "#%06X".format(color and 0xFFFFFF),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        // Tono. Si el color es gris, darle saturación para que mover el tono se note.
        val hues = remember { (0..6).map { Color.hsl(it * 60f % 360f, 0.85f, 0.55f) } }
        GradientBar(Brush.horizontalGradient(hues), hsl[0] / 360f, Color(color), "Tono de $label") { f ->
            val s = if (hsl[1] < 0.2f) 0.75f else hsl[1]
            val l = hsl[2].coerceIn(0.12f, 0.9f)
            onChange(ColorUtils.HSLToColor(floatArrayOf(f * 360f, s, l)))
        }
        Spacer(Modifier.height(10.dp))
        val mid = ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1], 0.5f))
        GradientBar(
            Brush.horizontalGradient(listOf(Color.Black, Color(mid), Color.White)),
            hsl[2],
            Color(color),
            "Luz de $label",
        ) { f -> onChange(ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1], f))) }
    }
}

@Composable
private fun GradientBar(brush: Brush, fraction: Float, thumb: Color, description: String, onChange: (Float) -> Unit) {
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .semantics { contentDescription = description },
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        fun set(x: Float) = onChange((x / widthPx).coerceIn(0f, 1f))
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(brush)
                .pointerInput(widthPx) { detectTapGestures { set(it.x) } }
                .pointerInput(widthPx) { detectHorizontalDragGestures { change, _ -> set(change.position.x) } },
        )
        val thumbSize = 24.dp
        Box(
            Modifier
                .offset(x = maxWidth * fraction.coerceIn(0f, 1f) - thumbSize / 2)
                .align(Alignment.CenterStart)
                .size(thumbSize)
                .aspectRatio(1f)
                .clip(CircleShape)
                .background(Color.White)
                .padding(3.dp)
                .clip(CircleShape)
                .background(thumb),
        )
    }
}
