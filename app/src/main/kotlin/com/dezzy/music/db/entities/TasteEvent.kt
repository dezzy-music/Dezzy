/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.db.entities

import androidx.compose.runtime.Immutable
import java.time.LocalDateTime

/** Escucha suelta (qué, cuándo y cuánto) para el perfil de gustos del DJ. */
@Immutable
data class TasteEvent(
    val songId: String,
    val timestamp: LocalDateTime,
    val playTime: Long,
)
