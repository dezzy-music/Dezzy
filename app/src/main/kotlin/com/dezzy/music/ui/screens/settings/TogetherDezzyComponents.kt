/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.screens.settings

import com.dezzy.music.together.TogetherRoomMembership
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.dezzy.music.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.dezzy.music.together.TogetherPublicRoom

/*
 * Piezas de la fase 5 (Social y Escuchar juntos, boceto A): tarjetas de acción
 * de la pestaña Juntos, filas compactas de salas públicas, tarjeta grande del
 * código de la sala y filas de participantes con acciones en menú.
 */

/** Tarjeta grande de acción (Crear sala / Unirse con código). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TogetherActionCard(
    iconRes: Int,
    title: String,
    subtitle: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier.heightIn(min = 112.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Encabezado de sección con indicador «en vivo» opcional a la derecha. */
@Composable
internal fun TogetherSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    live: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (trailing != null) {
            if (live) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Etiqueta de grupo dentro de una tarjeta de ajustes («Permisos», «Avanzado»). */
@Composable
internal fun TogetherSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

/** Fila compacta de sala pública: miniatura o icono del tema, datos y «Unirse». */
@Composable
internal fun TogetherPublicRoomRow(
    room: TogetherPublicRoom,
    onJoin: () -> Unit,
    modifier: Modifier = Modifier,
    membership: TogetherRoomMembership = TogetherRoomMembership.NONE,
) {
    val accent = themeColor(room.theme)
    val themeLabel = themeFilters.firstOrNull { it.theme == room.theme }?.label
        ?: room.themeName?.takeIf { it.isNotBlank() }
        ?: room.theme.name
    val people = if (room.participantCount == 1) "1 persona" else "${room.participantCount} personas"
    val nowPlaying = room.currentTrackTitle?.let { title ->
        listOfNotNull(title, room.currentTrackArtist).joinToString(", ")
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onJoin)
            .heightIn(min = 72.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            if (room.currentTrackThumbnail != null) {
                AsyncImage(
                    model = room.currentTrackThumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp),
                )
            } else {
                Icon(
                    painter = painterResource(themeIcon(room.theme)),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (membership == TogetherRoomMembership.HOST) "Tu sala" else "Sala de ${room.hostName}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (membership != TogetherRoomMembership.NONE) {
                Text(
                    text = if (membership == TogetherRoomMembership.HOST) "Eres el anfitrión" else "Ya estás en esta sala",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "$themeLabel, $people",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (nowPlaying != null) {
                Text(
                    text = "Sonando: $nowPlaying",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(onClick = onJoin) {
            Text(
                text = when (membership) {
                    TogetherRoomMembership.HOST -> "Abrir"
                    TogetherRoomMembership.GUEST -> "Volver"
                    TogetherRoomMembership.NONE -> "Unirse"
                },
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * Tarjeta de la sala (boceto «modelo A», como en el escritorio): QR a la izquierda
 * con `dezzy://together?code=…` y el código a la derecha, más Código / Enlace / Compartir.
 * En salas LAN no hay código corto: el QR lleva el enlace completo.
 */
@Composable
internal fun TogetherRoomCodeCard(
    code: String?,
    qrContent: String,
    addressHint: String?,
    onCopyCode: (() -> Unit)?,
    onCopyLink: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TogetherQrCode(content = qrContent, modifier = Modifier.size(132.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = if (code != null) "CÓDIGO DE LA SALA" else "SALA EN TU RED",
                        style = MaterialTheme.typography.labelMedium,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (code != null) {
                        Text(
                            text = code,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 3.sp,
                            ),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    } else if (addressHint != null) {
                        Text(
                            text = addressHint,
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = if (code != null) {
                            "Escanéalo con otro móvil o escribe el código en Escuchar juntos."
                        } else {
                            "Escanéalo con otro móvil conectado a la misma wifi."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (onCopyCode != null) {
                    FilledTonalButton(
                        onClick = onCopyCode,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Icon(painterResource(R.drawable.content_copy), null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Código", fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
                OutlinedButton(
                    onClick = onCopyLink,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Icon(painterResource(R.drawable.link), null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Enlace", fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                OutlinedButton(
                    onClick = onShare,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Icon(painterResource(R.drawable.share), null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Compartir", fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

/** QR negro sobre blanco (siempre, también en tema oscuro, para que lo lea cualquier cámara). */
@Composable
internal fun TogetherQrCode(content: String, modifier: Modifier = Modifier) {
    val matrix = remember(content) {
        runCatching {
            QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                0,
                0,
                mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
            )
        }.getOrNull()
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(10.dp),
    ) {
        if (matrix != null) {
            Canvas(Modifier.fillMaxSize()) {
                val cells = matrix.width
                val cell = size.minDimension / cells
                for (y in 0 until cells) {
                    for (x in 0 until cells) {
                        if (matrix[x, y]) {
                            // +0.5px evita las líneas finas entre módulos por redondeo.
                            drawRect(
                                color = Color.Black,
                                topLeft = Offset(x * cell, y * cell),
                                size = Size(cell + 0.5f, cell + 0.5f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Fila de participante: inicial, nombre y rol; los pendientes muestran
 * Rechazar/Aceptar y el resto Expulsar/Bloquear en el menú ⋮.
 */
@Composable
internal fun TogetherParticipantRow(
    name: String,
    roleLabel: String,
    isHost: Boolean,
    isPending: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onKick: () -> Unit,
    onBan: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    if (isHost) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.secondaryContainer
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.trim().take(1).uppercase().ifBlank { "?" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isHost) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isHost) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Text(
                        text = roleLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            } else {
                Text(
                    text = roleLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            isHost -> Unit
            isPending -> {
                OutlinedIconButton(onClick = onReject) {
                    Icon(painterResource(R.drawable.close), contentDescription = "Rechazar a $name")
                }
                Spacer(Modifier.width(4.dp))
                FilledIconButton(onClick = onApprove) {
                    Icon(painterResource(R.drawable.check), contentDescription = "Aceptar a $name")
                }
            }
            else -> Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(painterResource(R.drawable.more_vert), contentDescription = "Opciones de $name")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.together_kick)) },
                        leadingIcon = { Icon(painterResource(R.drawable.kick), null) },
                        onClick = {
                            menuOpen = false
                            onKick()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.together_ban),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                painterResource(R.drawable.block),
                                null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onBan()
                        },
                    )
                }
            }
        }
    }
}
