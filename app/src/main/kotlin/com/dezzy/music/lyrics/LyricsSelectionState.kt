package com.dezzy.music.lyrics

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object LyricsSelectionState {
    var isSelectingForShare by mutableStateOf(false)
    var pendingShareSong by mutableStateOf<Any?>(null)
}
