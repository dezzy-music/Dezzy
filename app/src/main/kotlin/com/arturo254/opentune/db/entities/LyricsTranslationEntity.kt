package com.arturo254.opentune.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lyrics_translation")
data class LyricsTranslationEntity(
    @PrimaryKey
    val cacheKey: String,
    val translatedLyrics: String
)
