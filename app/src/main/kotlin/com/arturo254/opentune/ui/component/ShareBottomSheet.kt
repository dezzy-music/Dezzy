package com.arturo254.opentune.ui.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.arturo254.opentune.R
import com.arturo254.opentune.utils.ComposeToImage
import com.arturo254.opentune.utils.ShareUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareBottomSheet(
    title: String,
    subtitle: String,
    thumbnailUrl: String?,
    shareLink: String,
    lyrics: String? = null,
    hasVideo: Boolean = false,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isProcessing by remember { mutableStateOf(false) }

    val darkBg = Color(0xFF1E1E1E)
    val cardBg = Color(0xFF2B2B2B)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = darkBg,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Preview Card (Spotify style)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp, vertical = 24.dp)
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(cardBg),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(thumbnailUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        color = Color.Gray,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(id = R.drawable.music_note),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Dezzy",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action Buttons
            val shareAction = { action: (Uri) -> Unit ->
                if (!isProcessing) {
                    isProcessing = true
                    coroutineScope.launch {
                        try {
                            val config = LyricsCardConfig(
                                layoutStyle = LyricsLayoutStyle.DezzyShare,
                                showCoverArt = true,
                                showTitle = true,
                                showArtist = true,
                                showBranding = true,
                                customBgColor = cardBg,
                                customTextColor = Color.White,
                                customSecondaryTextColor = Color.Gray
                            )
                            val bitmap = ComposeToImage.createLyricsImageWithConfig(
                                context = context,
                                coverArtUrl = thumbnailUrl,
                                songTitle = title,
                                artistName = subtitle,
                                lyrics = "",
                                config = config,
                                outputSize = 1080
                            )
                            val uri = ShareUtils.saveBitmapToCache(context, bitmap)
                            if (uri != null) {
                                action(uri)
                                onDismiss()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            isProcessing = false
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                ShareIconButton(
                    iconId = R.drawable.link,
                    label = "Copiar Enlace",
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("URL", shareLink))
                        Toast.makeText(context, "Enlace copiado", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                )
                ShareIconButton(
                    iconId = R.drawable.instagram,
                    label = "Historias",
                    onClick = {
                        shareAction { uri -> ShareUtils.shareToInstagramStory(context, uri) }
                    }
                )
                ShareIconButton(
                    iconId = R.drawable.share, // WhatsApp icon
                    label = "WhatsApp",
                    onClick = {
                        shareAction { uri -> ShareUtils.shareToWhatsApp(context, uri, shareLink) }
                    }
                )
                ShareIconButton(
                    iconId = R.drawable.more_horiz,
                    label = "Más",
                    onClick = {
                        shareAction { uri -> ShareUtils.shareGeneric(context, uri, shareLink) }
                    }
                )
            }
        }
    }
}

@Composable
private fun ShareIconButton(
    iconId: Int,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(Color(0xFF333333)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = iconId),
                contentDescription = label,
                colorFilter = ColorFilter.tint(Color.White),
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
