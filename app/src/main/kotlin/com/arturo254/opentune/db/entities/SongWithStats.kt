/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.arturo254.opentune.db.entities

import androidx.compose.runtime.Immutable

@Immutable
data class SongWithStats(
    val id: String,
    val title: String,
    // Nullable a propósito: la columna song.thumbnailUrl admite NULL (canciones
    // locales/antiguas). Con String no-nulo, UNA sola canción sin carátula en el
    // periodo hacía reventar el mapeo de Room y las listas de Estadísticas se
    // quedaban vacías en continuo/meses/años (semanas se salvaba porque su
    // ventana de un día rara vez incluía esas canciones).
    val thumbnailUrl: String?,
    val songCountListened: Int,
    val timeListened: Long?,
)
