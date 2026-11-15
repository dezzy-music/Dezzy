/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.dezzy.music.BuildConfig
import com.dezzy.music.LocalPlayerAwareWindowInsets
import com.dezzy.music.R
import com.dezzy.music.constants.AudioNormalizationKey
import com.dezzy.music.constants.AudioQuality
import com.dezzy.music.constants.AudioQualityKey
import com.dezzy.music.constants.AutoTranslateLyricsKey
import com.dezzy.music.constants.ContentLanguageKey
import com.dezzy.music.constants.DarkModeKey
import com.dezzy.music.constants.DiscordTokenKey
import com.dezzy.music.constants.DynamicThemeKey
import com.dezzy.music.constants.LastFMSessionKey
import com.dezzy.music.constants.ListenBrainzTokenKey
import com.dezzy.music.constants.PauseListenHistoryKey
import com.dezzy.music.constants.SpotifySpDcKey
import com.dezzy.music.security.SessionCrypto
import com.dezzy.music.utils.rememberEnumPreference
import com.dezzy.music.utils.rememberPreference
import com.dezzy.music.viewmodels.HomeViewModel
import java.util.Locale

/** Familia tonal del círculo de icono (roles de Material 3, sin colores fijos). */
enum class DezzyIconTone { PRIMARY, SECONDARY, TERTIARY, ERROR, NEUTRAL }

/** Una entrada de la lista principal de Ajustes (fase 7, boceto A). */
@Immutable
data class DezzySettingsEntry(
    val key: String,
    @DrawableRes val icon: Int,
    val title: String,
    val subtitle: String? = null,
    val tone: DezzyIconTone = DezzyIconTone.PRIMARY,
    val showDot: Boolean = false,
    val onClick: () -> Unit,
)

/** Esquinas al estilo Android 16: 24 dp en los extremos del grupo, 6 dp entre filas. */
fun dezzyItemShape(index: Int, count: Int, outer: Dp = 24.dp, inner: Dp = 6.dp): Shape = when {
    count <= 1 -> RoundedCornerShape(outer)
    index == 0 -> RoundedCornerShape(topStart = outer, topEnd = outer, bottomStart = inner, bottomEnd = inner)
    index == count - 1 -> RoundedCornerShape(topStart = inner, topEnd = inner, bottomStart = outer, bottomEnd = outer)
    else -> RoundedCornerShape(inner)
}

@Composable
private fun DezzyIconTone.colors(): Pair<Color, Color> {
    val cs = MaterialTheme.colorScheme
    return when (this) {
        DezzyIconTone.PRIMARY -> cs.primaryContainer to cs.onPrimaryContainer
        DezzyIconTone.SECONDARY -> cs.secondaryContainer to cs.onSecondaryContainer
        DezzyIconTone.TERTIARY -> cs.tertiaryContainer to cs.onTertiaryContainer
        DezzyIconTone.ERROR -> cs.errorContainer to cs.onErrorContainer
        DezzyIconTone.NEUTRAL -> cs.surfaceContainerHighest to cs.onSurfaceVariant
    }
}

/** Círculo de color con el icono de la entrada. */
@Composable
fun DezzySettingsIcon(
    @DrawableRes icon: Int,
    tone: DezzyIconTone,
    modifier: Modifier = Modifier,
) {
    val (container, content) = tone.colors()
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Etiqueta de sección en color primario (navegable por encabezados con TalkBack). */
@Composable
fun DezzySectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(start = 12.dp, top = 20.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

/** Chip de estado (Conectado / Conectar) a la derecha de una fila. */
@Composable
fun DezzyStatusChip(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (active) cs.primaryContainer else cs.surfaceContainerHighest,
        contentColor = if (active) cs.onPrimaryContainer else cs.onSurfaceVariant,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * Fila base de los grupos: icono circular, título, subtítulo con el valor actual
 * y contenido final opcional. Un único nodo para TalkBack.
 */
@Composable
fun DezzySettingsRow(
    title: String,
    shape: Shape,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
    ) {
        DezzyRowContent(title = title, subtitle = subtitle, leading = leading, trailing = trailing)
    }
}

@Composable
private fun DezzyRowContent(
    title: String,
    subtitle: String?,
    leading: (@Composable () -> Unit)?,
    trailing: (@Composable () -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** Fila con interruptor a la derecha; toda la fila conmuta (un solo foco para TalkBack). */
@Composable
fun DezzySettingsSwitchRow(
    @DrawableRes icon: Int,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    tone: DezzyIconTone = DezzyIconTone.SECONDARY,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    ) {
        DezzyRowContent(
            title = title,
            subtitle = subtitle,
            leading = { DezzySettingsIcon(icon, tone) },
            trailing = {
                Switch(
                    checked = checked,
                    onCheckedChange = null,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            },
        )
    }
}

/** Grupo redondeado de entradas de navegación (lista principal). */
@Composable
fun DezzySettingsGroupCard(
    entries: List<DezzySettingsEntry>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        entries.forEachIndexed { index, entry ->
            DezzySettingsRow(
                title = entry.title,
                subtitle = entry.subtitle,
                shape = dezzyItemShape(index, entries.size),
                leading = { DezzySettingsIcon(entry.icon, entry.tone) },
                trailing = if (entry.showDot) {
                    {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error)
                                .semantics { contentDescription = "Hay una actualización disponible" },
                        )
                    }
                } else null,
                onClick = entry.onClick,
            )
        }
    }
}

/** Píldora «Buscar en Ajustes»: abre la búsqueda que encuentra todas las opciones. */
@Composable
fun DezzySettingsSearchPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.search),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = "Buscar en Ajustes",
                style = MaterialTheme.typography.bodyLarge,
                fontSize = 16.sp,
            )
        }
    }
}

/** Tarjeta de cuenta en primaryContainer: lleva a «Cuentas y servicios». */
@Composable
fun DezzySettingsAccountCard(
    name: String,
    imageUrl: String?,
    isLoggedIn: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = cs.primaryContainer,
        contentColor = cs.onPrimaryContainer,
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(cs.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                if (isLoggedIn && !imageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.person),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isLoggedIn) name else "Inicia sesión",
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (isLoggedIn) {
                        "Cuenta de YouTube Music conectada"
                    } else {
                        "Conecta YouTube Music, Spotify y más"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 13.sp,
                    color = cs.onPrimaryContainer.copy(alpha = 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                painter = painterResource(R.drawable.navigate_next),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * HomeViewModel con ámbito de Activity (el mismo que ya crea MainActivity para el avatar):
 * evita una segunda instancia que relanzaría la carga completa de Inicio.
 */
@Composable
fun rememberActivityHomeViewModel(): HomeViewModel {
    val context = LocalContext.current
    val activity = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<ComponentActivity>()
            .firstOrNull()
    }
    return if (activity != null) {
        hiltViewModel(viewModelStoreOwner = activity)
    } else {
        hiltViewModel()
    }
}

/** Abre «Abrir de forma predeterminada» del sistema (Android 12+). */
internal fun openDefaultLinksSettings(context: Context) {
    try {
        val intent = Intent(
            Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.open_app_settings_error, Toast.LENGTH_LONG).show()
    } catch (e: SecurityException) {
        Toast.makeText(context, R.string.open_app_settings_error, Toast.LENGTH_LONG).show()
    }
}

/**
 * Grupos de la lista principal con el valor actual de cada ajuste como subtítulo.
 * Ninguna opción se pierde: lo que no está aquí sigue en su pantalla y en la búsqueda.
 */
@Composable
fun rememberDezzySettingsGroups(
    navController: NavController,
    isAndroid12OrLater: Boolean,
    hasUpdate: Boolean,
    latestVersionName: String,
): List<List<DezzySettingsEntry>> {
    val context = LocalContext.current
    val (dynamicTheme) = rememberPreference(DynamicThemeKey, defaultValue = false)
    val (darkMode) = rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
    val (audioQuality) = rememberEnumPreference(AudioQualityKey, defaultValue = AudioQuality.AUTO)
    val (normalization) = rememberPreference(AudioNormalizationKey, defaultValue = true)
    val (autoTranslate) = rememberPreference(AutoTranslateLyricsKey, defaultValue = true)
    val (contentLanguage) = rememberPreference(ContentLanguageKey, defaultValue = "system")
    val (pauseHistory) = rememberPreference(PauseListenHistoryKey, defaultValue = false)
    val (spotifySpDc) = rememberPreference(SpotifySpDcKey, "")
    val (discordToken) = rememberPreference(DiscordTokenKey, "")
    val (lastFmSession) = rememberPreference(LastFMSessionKey, "")
    val (listenBrainzToken) = rememberPreference(ListenBrainzTokenKey, "")

    val spotifyConnected = remember(spotifySpDc) {
        runCatching { SessionCrypto.decrypt(spotifySpDc).isNotEmpty() }.getOrDefault(false)
    }

    val appearanceValue = buildList {
        add(
            when (darkMode) {
                DarkMode.AUTO -> "Tema del sistema"
                DarkMode.ON -> "Tema oscuro"
                DarkMode.OFF -> "Tema claro"
            },
        )
        if (dynamicTheme) add("color dinámico")
    }.joinToString(", ")

    val audioValue = buildList {
        add(
            when (audioQuality) {
                AudioQuality.AUTO -> "Calidad automática"
                AudioQuality.HIGH -> "Calidad alta"
                AudioQuality.HIGHEST -> "Calidad máxima"
                AudioQuality.LOW -> "Calidad baja"
            },
        )
        if (normalization) add("normalizar volumen")
    }.joinToString(", ")

    val languageValue = if (contentLanguage.isBlank() || contentLanguage == "system") {
        "Idioma y región del sistema"
    } else {
        val name = Locale.forLanguageTag(contentLanguage).getDisplayLanguage(Locale.forLanguageTag("es"))
        "Idioma: " + name.replaceFirstChar { it.titlecase(Locale.forLanguageTag("es")) }
    }

    val connected = buildList {
        if (spotifyConnected) add("Spotify")
        if (discordToken.isNotEmpty()) add("Discord")
        if (lastFmSession.isNotEmpty()) add("Last.fm")
        if (listenBrainzToken.isNotEmpty()) add("ListenBrainz")
    }
    val servicesValue = if (connected.isEmpty()) {
        "YouTube Music, Spotify, Discord y más"
    } else {
        connected.joinToString(", ")
    }

    val go: (String) -> () -> Unit = { route -> { navController.navigate(route) } }

    return listOf(
        listOf(
            DezzySettingsEntry("appearance", R.drawable.palette, "Apariencia", appearanceValue, DezzyIconTone.PRIMARY, onClick = go("settings/appearance")),
            DezzySettingsEntry("player", R.drawable.play, "Reproductor y audio", audioValue, DezzyIconTone.TERTIARY, onClick = go("settings/player")),
            DezzySettingsEntry(
                "lyrics_ai", R.drawable.lyrics, "Letras e IA",
                if (autoTranslate) "Traducción automática activada" else "Traducción y proveedor de IA",
                DezzyIconTone.SECONDARY, onClick = go("settings/ai"),
            ),
            DezzySettingsEntry("content", R.drawable.language, "Contenido", languageValue, DezzyIconTone.PRIMARY, onClick = go("settings/content")),
            DezzySettingsEntry(
                "aod", R.drawable.bedtime, "Pantalla siempre activa", "Reloj y letras con la pantalla en reposo",
                DezzyIconTone.NEUTRAL, onClick = go("settings/appearance/always_on_display"),
            ),
        ),
        listOf(
            DezzySettingsEntry("accounts", R.drawable.person, "Cuentas y servicios", servicesValue, DezzyIconTone.PRIMARY, onClick = go("settings/accounts")),
            DezzySettingsEntry("storage", R.drawable.storage, "Descargas y almacenamiento", "Caché, descargas y espacio usado", DezzyIconTone.SECONDARY, onClick = go("settings/storage")),
            DezzySettingsEntry("backup", R.drawable.restore, "Copia de seguridad", "Exportar o restaurar tus datos", DezzyIconTone.TERTIARY, onClick = go("settings/backup_restore")),
            DezzySettingsEntry(
                "privacy", R.drawable.security, "Privacidad",
                if (pauseHistory) "Historial en pausa" else "Historial y capturas de pantalla",
                DezzyIconTone.ERROR, onClick = go("settings/privacy"),
            ),
        ),
        buildList {
            if (isAndroid12OrLater) {
                add(
                    DezzySettingsEntry(
                        "default_links", R.drawable.link, "Enlaces predeterminados", "Abrir enlaces de YouTube Music con Dezzy",
                        DezzyIconTone.NEUTRAL, onClick = { openDefaultLinksSettings(context) },
                    ),
                )
            }
            add(DezzySettingsEntry("po_token", R.drawable.token, "Token de reproducción", "PoToken y datos del cliente web", DezzyIconTone.NEUTRAL, onClick = go("settings/po_token")))
            add(DezzySettingsEntry("misc", R.drawable.experiment, "Experimentos", "Funciones en pruebas y depuración", DezzyIconTone.NEUTRAL, onClick = go("settings/misc")))
            add(
                DezzySettingsEntry(
                    "updates", R.drawable.update, "Actualizaciones",
                    if (hasUpdate) "Versión $latestVersionName disponible" else "Estás al día, versión ${BuildConfig.VERSION_NAME}",
                    if (hasUpdate) DezzyIconTone.TERTIARY else DezzyIconTone.NEUTRAL,
                    showDot = hasUpdate,
                    onClick = go("settings/update"),
                ),
            )
            add(DezzySettingsEntry("about", R.drawable.info, "Acerca de", "Dezzy ${BuildConfig.VERSION_NAME}", DezzyIconTone.NEUTRAL, onClick = go("settings/about")))
        },
    )
}

/** Ancho máximo de la columna en tablets y ventanas anchas. */
private val MaxContentWidth = 720.dp

/** Lista principal de Ajustes (sin búsqueda activa). */
@Composable
fun DezzySettingsHome(
    groups: List<List<DezzySettingsEntry>>,
    accountName: String,
    accountImageUrl: String?,
    isLoggedIn: Boolean,
    showPermissionBanner: Boolean,
    onRequestPermission: () -> Unit,
    onSearchClick: () -> Unit,
    onAccountClick: () -> Unit,
    listState: LazyListState,
    topPadding: Dp,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                ),
            ),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topPadding, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item(key = "search", contentType = "search") {
            DezzySettingsSearchPill(
                onClick = onSearchClick,
                modifier = Modifier.widthIn(max = MaxContentWidth).padding(top = 4.dp, bottom = 16.dp),
            )
        }
        item(key = "account", contentType = "account") {
            DezzySettingsAccountCard(
                name = accountName,
                imageUrl = accountImageUrl,
                isLoggedIn = isLoggedIn,
                onClick = onAccountClick,
                modifier = Modifier.widthIn(max = MaxContentWidth),
            )
        }
        if (showPermissionBanner) {
            item(key = "permission", contentType = "banner") {
                SettingsPermissionBanner(
                    onRequestPermission = onRequestPermission,
                    modifier = Modifier.widthIn(max = MaxContentWidth).padding(top = 16.dp),
                )
            }
        }
        itemsIndexed(
            items = groups,
            key = { index, group -> "group_${index}_${group.firstOrNull()?.key}" },
            contentType = { _, _ -> "group" },
        ) { _, group ->
            DezzySettingsGroupCard(
                entries = group,
                modifier = Modifier.widthIn(max = MaxContentWidth).padding(top = 20.dp),
            )
        }
    }
}
