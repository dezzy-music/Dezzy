/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.innertube.pages

import com.dezzy.music.innertube.models.*

data class ChartsPage(
    val sections: List<ChartSection>,
    val continuation: String?,
) {
    data class ChartSection(
        val title: String,
        val items: List<YTItem>,
        val chartType: ChartType,
    )

    enum class ChartType {
        TRENDING,
        TOP,
        GENRE,
        NEW_RELEASES,
    }
}
