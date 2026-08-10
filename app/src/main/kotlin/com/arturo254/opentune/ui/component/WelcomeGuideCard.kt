/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.arturo254.opentune.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arturo254.opentune.constants.WelcomeCardDismissedKey
import com.arturo254.opentune.utils.rememberPreference

/**
 * Orientation card for first-time users, shown at the top of Home until dismissed.
 * Collapsed: a short welcome + "Ver guía" / "Entendido". Expanded: a precise guide
 * to the app's less obvious features (canvas vs video, storage, downloads, etc.).
 */
@Composable
fun WelcomeGuideCard(modifier: Modifier = Modifier) {
    val (dismissed, onDismissedChange) = rememberPreference(WelcomeCardDismissedKey, defaultValue = false)
    if (dismissed) return
    var expanded by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "👋 ¡Bienvenido a Dezzy!",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Nuevo usuario: aquí tienes una guía rápida y precisa para sacarle todo el jugo a la app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
            )

            AnimatedVisibility(visible = expanded) {
                Column {
                    GuideSection(
                        title = "🎨 Canvas vs. 🎬 Video — no son lo mismo",
                        body = "Al activar un Canvas, verás una miniatura animada en bucle sobre la carátula " +
                            "de la canción (como en Spotify). En cambio, el botón de \"Cambiar a video\" " +
                            "reproduce el video oficial de YouTube de la canción que estás escuchando. " +
                            "El Canvas es decorativo y silencioso; el video es el videoclip real. " +
                            "En los reproductores con Canvas verás un pequeño selector Apple/Spotify sobre " +
                            "la carátula para elegir la fuente del Canvas.",
                    )
                    GuideSection(
                        title = "💾 Descargas y almacenamiento",
                        body = "Puedes elegir dónde se guardan tus canciones descargadas: en el almacenamiento " +
                            "local del dispositivo (se exportan a tu carpeta de Música) o en la caché interna " +
                            "de la app. Lo configuras en Ajustes → Almacenamiento. Muy recomendado: aumenta " +
                            "el espacio máximo permitido para las descargas y la caché de canciones — si el " +
                            "límite se queda corto, las canciones pueden cortarse a media descarga.",
                    )
                    GuideSection(
                        title = "💬 Comentarios",
                        body = "En el reproductor hay un botón de comentarios: tócalo para leer lo que dice la " +
                            "gente sobre la canción que está sonando.",
                    )
                    GuideSection(
                        title = "🧪 Letras V2 (experimental)",
                        body = "El modo de letras V2 (estilos animados como Chromatic, Spotlight, etc.) todavía " +
                            "es experimental: puede tener fallos visuales en algunas canciones. Si algo se ve " +
                            "raro, vuelve a las letras clásicas en Ajustes → Apariencia.",
                    )
                    GuideSection(
                        title = "🖼️ Fondo de pantalla de inicio",
                        body = "Puedes ponerle tu propio fondo (imagen o incluso video) a la pantalla de inicio " +
                            "desde Ajustes → Apariencia → Fondo de pantalla.",
                    )
                    GuideSection(
                        title = "🌐 Traducción de letras por IA (recomendada)",
                        body = "Activa la traducción por IA para ver las letras en tu idioma en tiempo real. " +
                            "Funciona especialmente bien con canciones en inglés, coreano o japonés.",
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Ocultar guía" else "Ver guía")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { onDismissedChange(true) }) {
                    Text("Entendido, no volver a mostrar")
                }
            }
        }
    }
}

@Composable
private fun GuideSection(title: String, body: String) {
    Spacer(Modifier.height(12.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
    Spacer(Modifier.height(2.dp))
    Text(
        text = body,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
    )
}
