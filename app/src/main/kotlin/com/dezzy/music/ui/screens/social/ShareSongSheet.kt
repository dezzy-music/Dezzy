/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.ui.screens.social

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dezzy.music.R
import com.dezzy.music.social.Friend
import com.dezzy.music.social.MessagesRepository
import com.dezzy.music.social.SocialRepository
import com.dezzy.music.social.SocialResult
import com.dezzy.music.social.SupabaseAuth
import kotlinx.coroutines.launch

/**
 * Hoja de "compartir canción" estilo Spotify: la portada arriba, una fila de
 * amigos a los que enviarla por mensaje directo, y los destinos del sistema
 * (WhatsApp, etc.) mediante el compartir de Android.
 *
 * Enviar a un amigo publica un mensaje 1:1 con el enlace de la canción (reusa el
 * chat que ya existe), así el amigo lo abre desde la conversación.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSongSheet(
    videoId: String,
    title: String,
    artist: String?,
    artworkUrl: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()

    var friends by remember { mutableStateOf<List<Friend>>(emptyList()) }
    var sentTo by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(Unit) {
        if (SupabaseAuth.isLoggedIn) friends = SocialRepository.getFriends()
    }

    val link = "https://music.youtube.com/watch?v=$videoId"
    val shareText = "$title${artist?.let { " — $it" } ?: ""}\n$link"

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            // Portada + título
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtworkThumb(url = artworkUrl, size = 64.dp, corner = 10.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!artist.isNullOrBlank()) {
                        Text(
                            artist,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Amigos a los que enviar por mensaje
            if (friends.isNotEmpty()) {
                Text(
                    "Enviar a un amigo",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(start = 20.dp, bottom = 6.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(friends, key = { it.id }) { f ->
                        val sent = f.id in sentTo
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .width(72.dp)
                                .clickable(enabled = !sent) {
                                    scope.launch {
                                        val r = MessagesRepository.sendMessage(f.id, shareText)
                                        if (r is SocialResult.Success) {
                                            sentTo = sentTo + f.id
                                            Toast.makeText(context, "Enviado a ${f.name}", Toast.LENGTH_SHORT).show()
                                        } else if (r is SocialResult.Error) {
                                            Toast.makeText(context, r.message, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                        ) {
                            Box(contentAlignment = Alignment.BottomEnd) {
                                Avatar(url = f.avatarUrl, size = 58.dp, iconSize = 28.dp)
                                if (sent) {
                                    Box(
                                        Modifier
                                            .size(22.dp)
                                            .padding(2.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            painterResource(R.drawable.social_check),
                                            contentDescription = "Enviado",
                                            tint = NowPlayingGreen,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                f.name.substringBefore(' '),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // Destinos del sistema
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SystemShareButton(
                    iconRes = R.drawable.share,
                    label = "Compartir",
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareText)
                        }
                        context.startActivity(Intent.createChooser(intent, "Compartir"))
                    },
                )
                SystemShareButton(
                    iconRes = R.drawable.link,
                    label = "Copiar enlace",
                    onClick = {
                        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        clip.setPrimaryClip(android.content.ClipData.newPlainText("Dezzy", link))
                        Toast.makeText(context, "Enlace copiado", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }
}

@Composable
private fun SystemShareButton(iconRes: Int, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Box(
            Modifier
                .size(52.dp)
                .padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(26.dp),
            )
        }
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
