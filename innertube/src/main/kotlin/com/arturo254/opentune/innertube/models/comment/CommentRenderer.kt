/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.arturo254.opentune.innertube.models.comment

import kotlinx.serialization.Serializable

/**
 * Represents a single YouTube comment as returned by the InnerTube API.
 */
@Serializable
data class CommentRenderer(
    val authorText: TextRuns? = null,
    val authorThumbnail: AuthorThumbnail? = null,
    val contentText: TextRuns? = null,
    val publishedTimeText: TextRuns? = null,
    val voteCount: TextRuns? = null,
    val replyCount: ReplyCount? = null,
    val authorIsChannelOwner: Boolean = false,
    val authorBadges: List<AuthorBadge>? = null,
    // Comment id ("xxxx" for top-level, "parent.reply" for replies). Used to
    // deep-link the exact comment on YouTube (reply / report flows).
    val commentId: String? = null,
) {
    @Serializable
    data class TextRuns(
        val runs: List<Run>? = null,
        val simpleText: String? = null,
    ) {
        fun text(): String = simpleText ?: runs?.joinToString("") { it.text } ?: ""
    }

    @Serializable
    data class Run(
        val text: String = "",
    )

    @Serializable
    data class AuthorThumbnail(
        val thumbnails: List<Thumbnail>? = null,
    ) {
        @Serializable
        data class Thumbnail(
            val url: String = "",
            val width: Int = 0,
            val height: Int = 0,
        )

        fun bestUrl(): String? = thumbnails?.maxByOrNull { it.width }?.url
    }

    @Serializable
    data class ReplyCount(
        val replyCount: Int = 0,
    )

    @Serializable
    data class AuthorBadge(
        val metadataBadgeRenderer: MetadataBadgeRenderer? = null,
    ) {
        @Serializable
        data class MetadataBadgeRenderer(
            val icon: Icon? = null,
            val tooltip: String? = null,
        ) {
            @Serializable
            data class Icon(val iconType: String? = null)
        }
    }

    /** Returns the full display name of the author. */
    fun authorName(): String = authorText?.text() ?: ""

    /** Returns the comment body text. */
    fun body(): String = contentText?.text() ?: ""

    /** Returns formatted publish date e.g. "hace 2 días". */
    fun publishedTime(): String = publishedTimeText?.text() ?: ""

    /** Returns the like count string e.g. "1.2K". */
    fun likes(): String = voteCount?.text() ?: "0"
}
