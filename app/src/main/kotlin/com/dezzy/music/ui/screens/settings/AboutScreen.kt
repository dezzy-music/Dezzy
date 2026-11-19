/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 *
 * «Acerca de» brutalista — boceto B «Tablero de bloques»: rejilla de 6 columnas,
 * bordes de 3dp, sombras duras desplazadas y sólo iconos vectoriales de trazo recto.
 */

package com.dezzy.music.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.dezzy.music.BuildConfig
import com.dezzy.music.LocalPlayerAwareWindowInsets
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.ui.utils.backToMain
import com.dezzy.music.utils.Updater
import com.dezzy.music.viewmodels.AboutViewModel
import com.dezzy.music.viewmodels.Contributor

// ── Shimmer brush (lo usan otras pantallas de carga) ────────────────────────

@Composable
fun shimmerEffect(): Brush {
    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.2f),
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.2f),
    )
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = -400f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerX",
    )
    return Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(x = translateAnim, y = 0f),
        end = Offset(x = translateAnim + 500f, y = 0f),
    )
}

// ── Paleta y tipos del tablero ──────────────────────────────────────────────

private val Ink = Color(0xFF0D0D0D)
private val Acid = Color(0xFFE4FF3A)
private val Sky = Color(0xFF9FD3FF)
private val Blush = Color(0xFFE9C9CD)
private val Paper = Color(0xFFF3EFE6)

private val BlockGap = 10.dp
private val EdgeWidth = 3.dp
private val ShadowOffset = 4.dp

private val DisplayFont = FontFamily(Font(R.font.sfprodisplaybold, FontWeight.Black))
private val MonoFont = FontFamily.Monospace

private fun mono(size: TextUnit, weight: FontWeight = FontWeight.Bold) = TextStyle(
    fontFamily = MonoFont,
    fontWeight = weight,
    fontSize = size,
    letterSpacing = 0.08.em,
)

/** Contribuidores reconocidos en «Acerca de» (además del autor). */
private val DezzyContributors = listOf(
    Contributor(
        avatarUrl = "https://github.com/dani36652.png",
        name = "dani36652",
        role = "Contribuidor",
        profileUrl = "https://github.com/dani36652",
    ),
)

private const val UrlGitHub = "https://github.com/dezzy-music"
private const val AvatarLead = "https://github.com/dezzy-music.png"
private const val UrlInstagram = "https://www.instagram.com/dezzy_9n9_/?utm_source=ig_web_button_share_sheet"
private const val UrlFacebook = "https://www.facebook.com/share/18nog3EfkG/"
private const val UrlWebsite = "https://dezzy.space"
private const val UrlLicense = "https://github.com/dezzy-music/Dezzy/blob/master/LICENSE"

// ── Pantalla ────────────────────────────────────────────────────────────────

@Suppress("UNUSED_PARAMETER")
@Composable
fun AboutScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: AboutViewModel = viewModel()
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val edge = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface

    // Última versión publicada (Updater la cachea varias horas: no cuesta red cada vez).
    var latestVersion by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        Updater.getLatestVersionName().onSuccess { latestVersion = it }
    }
    val upToDate = latestVersion?.let { Updater.isSameVersion(it, BuildConfig.VERSION_NAME) }

    val versionLabel = "Dezzy ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    val copyVersion = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Dezzy", versionLabel))
        Toast.makeText(context, "Versión copiada", Toast.LENGTH_SHORT).show()
    }

    CompositionLocalProvider(LocalBoardEdge provides edge) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(surface)
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                    )
                )
                .padding(horizontal = 16.dp)
                .padding(
                    top = 10.dp,
                    bottom = if (playerConnection?.player?.isPlaying == true) 96.dp else 32.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(BlockGap),
        ) {
            // ── Barra superior: volver · título · copiar versión ──────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BlockGap),
            ) {
                BrutalBlock(
                    fill = Acid,
                    content = Ink,
                    padding = PaddingValues(10.dp),
                    onClick = { navController.navigateUp() },
                    onLongClick = { navController.backToMain() },
                ) {
                    BoardIcon(R.drawable.about_back, "Volver", 22.dp)
                }
                Column(Modifier.weight(1f)) {
                    Text("ACERCA DE", style = mono(13.sp), color = edge)
                    Text("05 / AJUSTES", style = mono(10.sp, FontWeight.Normal), color = edge.copy(alpha = 0.6f))
                }
                BrutalBlock(
                    fill = surface,
                    content = edge,
                    padding = PaddingValues(10.dp),
                    onClick = copyVersion,
                ) {
                    BoardIcon(R.drawable.about_copy, "Copiar versión", 22.dp)
                }
            }

            // ── Bloque título ─────────────────────────────────────────────────
            BrutalBlock(
                modifier = Modifier.fillMaxWidth(),
                fill = MaterialTheme.colorScheme.primary,
                content = MaterialTheme.colorScheme.onPrimary,
                padding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 48.dp),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            "DEZZY",
                            fontFamily = DisplayFont,
                            fontWeight = FontWeight.Black,
                            fontSize = 64.sp,
                            lineHeight = 60.sp,
                            letterSpacing = (-2).sp,
                        )
                        Text(
                            "*",
                            fontFamily = DisplayFont,
                            fontWeight = FontWeight.Black,
                            fontSize = 48.sp,
                            lineHeight = 48.sp,
                            color = Acid,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("* OBSESSION WAS ALWAYS THE KEY.", style = mono(11.sp))
                }
                // Pegatina girada en la esquina
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 6.dp, y = 34.dp)
                        .rotate(-6f)
                        .background(Acid)
                        .border(EdgeWidth, Ink)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text("GPL-3.0 ✕ GRATIS", style = mono(10.sp), color = Ink)
                }
            }

            // ── Versión · build ───────────────────────────────────────────────
            BoardRow {
                BrutalBlock(
                    modifier = span(3),
                    fill = Acid,
                    content = Ink,
                    onClick = { navController.navigate("settings/update") },
                    onLongClick = copyVersion,
                ) {
                    Column {
                        BlockLabel("VERSIÓN")
                        BigValue(BuildConfig.VERSION_NAME)
                        Spacer(Modifier.height(8.dp))
                        Chip(
                            text = when (upToDate) {
                                null -> "BUSCAR"
                                true -> "AL DÍA"
                                false -> "NUEVA ${latestVersion.orEmpty()}"
                            },
                            fill = if (upToDate == false) Ink else Acid,
                            content = if (upToDate == false) Acid else Ink,
                            iconRes = R.drawable.about_update,
                        )
                    }
                }
                BrutalBlock(
                    modifier = span(3),
                    fill = Ink,
                    content = Color.White,
                ) {
                    Column {
                        BlockLabel("BUILD")
                        BigValue("#${BuildConfig.VERSION_CODE}")
                        Spacer(Modifier.height(8.dp))
                        Chip(
                            text = if (BuildConfig.DEBUG) "DEBUG" else "STABLE",
                            fill = Ink,
                            content = Color.White,
                            edgeColor = Color.White,
                        )
                    }
                }
            }

            // ── Autor · origen ────────────────────────────────────────────────
            BoardRow {
                BrutalBlock(
                    modifier = span(4),
                    fill = Blush,
                    content = Ink,
                    onClick = { uriHandler.openUri(UrlGitHub) },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Face("JH", fill = Ink, content = Blush, avatarUrl = AvatarLead)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Jhonny Hernández",
                                fontFamily = DisplayFont,
                                fontWeight = FontWeight.Black,
                                fontSize = 17.sp,
                                lineHeight = 19.sp,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("LEAD DEVELOPER", style = mono(10.sp))
                        }
                    }
                }
                BrutalBlock(
                    modifier = span(2),
                    fill = Sky,
                    content = Ink,
                ) {
                    Column {
                        BlockLabel("DESDE")
                        Text(
                            "20/30",
                            fontFamily = DisplayFont,
                            fontWeight = FontWeight.Black,
                            fontSize = 22.sp,
                        )
                        Text("NY", style = mono(11.sp))
                    }
                }
            }

            // ── Contribuidores ────────────────────────────────────────────────
            DezzyContributors.forEachIndexed { index, contributor ->
                BrutalBlock(
                    modifier = Modifier.fillMaxWidth(),
                    fill = if (index % 2 == 0) Acid else Sky,
                    content = Ink,
                    onClick = { uriHandler.openUri(contributor.profileUrl) },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Face(contributor.name.take(1).uppercase(), fill = Ink, content = Acid, avatarUrl = contributor.avatarUrl)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                contributor.name,
                                fontFamily = DisplayFont,
                                fontWeight = FontWeight.Black,
                                fontSize = 18.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("${contributor.role.uppercase()} · NUEVO", style = mono(10.sp))
                        }
                        BoardIcon(R.drawable.about_arrow, null, 24.dp)
                    }
                }
            }

            // ── Redes ─────────────────────────────────────────────────────────
            BoardRow {
                LinkSquare("GH", "GITHUB", surface, edge, span(2)) { uriHandler.openUri(UrlGitHub) }
                LinkSquare("IG", "INSTAGRAM", Ink, Color.White, span(2)) { uriHandler.openUri(UrlInstagram) }
                LinkSquare("FB", "FACEBOOK", Sky, Ink, span(2)) { uriHandler.openUri(UrlFacebook) }
            }

            // ── Web ───────────────────────────────────────────────────────────
            BrutalBlock(
                modifier = Modifier.fillMaxWidth(),
                fill = surface,
                content = edge,
                onClick = { uriHandler.openUri(UrlWebsite) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BoardIcon(R.drawable.about_code, null, 24.dp)
                    Spacer(Modifier.size(12.dp))
                    Text(
                        "dezzy.space",
                        modifier = Modifier.weight(1f),
                        fontFamily = DisplayFont,
                        fontWeight = FontWeight.Black,
                        fontSize = 20.sp,
                    )
                    BoardIcon(R.drawable.about_arrow, null, 22.dp)
                }
            }

            // ── Cita · cruz ───────────────────────────────────────────────────
            BoardRow {
                BrutalBlock(
                    modifier = span(4),
                    fill = Ink,
                    content = Color.White,
                ) {
                    Column {
                        Text(
                            "JESUS CHRIST",
                            fontFamily = DisplayFont,
                            fontWeight = FontWeight.Black,
                            fontSize = 20.sp,
                            lineHeight = 22.sp,
                        )
                        Text("loves you", style = mono(12.sp, FontWeight.Normal), color = Acid)
                    }
                }
                BrutalBlock(
                    modifier = span(2),
                    fill = Acid,
                    content = Ink,
                    contentAlignment = Alignment.Center,
                ) {
                    BoardIcon(R.drawable.about_cross, null, 40.dp)
                }
            }

            // ── Licencia ──────────────────────────────────────────────────────
            BrutalBlock(
                modifier = Modifier.fillMaxWidth(),
                fill = Paper,
                content = Ink,
                onClick = { uriHandler.openUri(UrlLicense) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BoardIcon(R.drawable.about_doc, null, 26.dp)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        BlockLabel("LICENCIA")
                        Text(
                            "GNU GPL v3.0",
                            fontFamily = DisplayFont,
                            fontWeight = FontWeight.Black,
                            fontSize = 18.sp,
                        )
                    }
                    Chip(text = "VER LICENCIA", fill = Ink, content = Paper, edgeColor = Ink)
                }
            }

            Text(
                "HECHO CON OBSESIÓN · DEZZY · NEW YORK · ESTD 2030",
                style = mono(9.sp, FontWeight.Normal),
                color = edge.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

// ── Piezas del tablero ──────────────────────────────────────────────────────

/** Color de bordes y sombras: onSurface, así el tablero se adapta al modo oscuro. */
private val LocalBoardEdge = androidx.compose.runtime.staticCompositionLocalOf { Ink }

/** Fila de la rejilla de 6 columnas: todos los bloques comparten altura. */
@Composable
private fun BoardRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(BlockGap),
        content = content,
    )
}

/** Ocupa [columns] de las 6 columnas de la fila y estira el bloque a su altura. */
private fun RowScope.span(columns: Int): Modifier =
    Modifier.weight(columns.toFloat()).fillMaxHeight()

/**
 * Bloque brutalista: relleno plano, borde de 3dp y sombra dura desplazada 4dp. Al pulsarlo
 * el bloque «baja» sobre su sombra.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrutalBlock(
    modifier: Modifier = Modifier,
    fill: Color,
    content: Color,
    padding: PaddingValues = PaddingValues(14.dp),
    contentAlignment: Alignment = Alignment.TopStart,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    body: @Composable BoxScope.() -> Unit,
) {
    val edge = LocalBoardEdge.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shift by animateDpAsState(
        targetValue = if (pressed && onClick != null) ShadowOffset else 0.dp,
        animationSpec = tween(70),
        label = "brutalPress",
    )
    Box(
        modifier = modifier
            .padding(end = ShadowOffset, bottom = ShadowOffset)
            .drawBehind {
                val s = ShadowOffset.toPx()
                drawRect(edge, topLeft = Offset(s, s), size = size)
            }
            .offset(x = shift, y = shift)
            .background(fill)
            .border(EdgeWidth, edge)
            .then(
                if (onClick != null) {
                    Modifier.combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                } else {
                    Modifier
                }
            )
            .padding(padding),
        contentAlignment = contentAlignment,
    ) {
        CompositionLocalProvider(LocalContentColor provides content) {
            body()
        }
    }
}

@Composable
private fun BoardIcon(res: Int, description: String?, size: Dp) {
    Icon(
        painter = painterResource(res),
        contentDescription = description,
        tint = LocalContentColor.current,
        modifier = Modifier.size(size),
    )
}

@Composable
private fun BlockLabel(text: String) {
    Text(text, style = mono(10.sp), modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun BigValue(text: String) {
    Text(
        text,
        fontFamily = DisplayFont,
        fontWeight = FontWeight.Black,
        fontSize = 26.sp,
        lineHeight = 28.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Etiqueta rectangular con borde (sin esquinas redondeadas). */
@Composable
private fun Chip(
    text: String,
    fill: Color,
    content: Color,
    edgeColor: Color = Ink,
    iconRes: Int? = null,
) {
    Row(
        modifier = Modifier
            .background(fill)
            .border(2.dp, edgeColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconRes != null) {
            Icon(painterResource(iconRes), null, tint = content, modifier = Modifier.size(12.dp))
            Spacer(Modifier.size(5.dp))
        }
        Text(text, style = mono(9.sp), color = content, maxLines = 1)
    }
}

/**
 * «Cara» cuadrada con la foto de GitHub del creador. Las iniciales quedan debajo: se ven
 * mientras carga la foto o si no hay conexión.
 */
@Composable
private fun Face(initials: String, fill: Color, content: Color, avatarUrl: String? = null) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(fill)
            .border(EdgeWidth, Ink),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials,
            fontFamily = DisplayFont,
            fontWeight = FontWeight.Black,
            fontSize = 18.sp,
            color = content,
        )
        if (avatarUrl != null) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = initials,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(EdgeWidth),
            )
        }
    }
}

@Composable
private fun LinkSquare(
    short: String,
    label: String,
    fill: Color,
    content: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    BrutalBlock(
        modifier = modifier.heightIn(min = 92.dp),
        fill = fill,
        content = content,
        padding = PaddingValues(10.dp),
        onClick = onClick,
    ) {
        BoardIcon(R.drawable.about_arrow, null, 18.dp)
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(
                short,
                fontFamily = DisplayFont,
                fontWeight = FontWeight.Black,
                fontSize = 26.sp,
                lineHeight = 26.sp,
            )
            Text(label, style = mono(8.sp), maxLines = 1, overflow = TextOverflow.Clip)
        }
    }
}
