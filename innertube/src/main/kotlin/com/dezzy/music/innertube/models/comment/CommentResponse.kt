/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.innertube.models.comment

import kotlinx.serialization.Serializable

@Serializable
data class CommentResponse(
    val continuationContents: ContinuationContents? = null,
    val onResponseReceivedEndpoints: List<ResponseEndpoint>? = null,
    /**
     * Modern WEB responses no longer inline comment data in the thread renderers: each thread
     * carries only a commentViewModel reference (commentId) and the actual text/author/likes
     * arrive here as commentEntityPayload mutations. Without parsing this, real-world comments
     * decode as empty threads.
     */
    val frameworkUpdates: FrameworkUpdates? = null,
) {
    @Serializable
    data class ResponseEndpoint(
        val reloadContinuationItemsCommand: ContinuationItemsCommand? = null,
        val appendContinuationItemsAction: ContinuationItemsCommand? = null,
    ) {
        @Serializable
        data class ContinuationItemsCommand(
            val continuationItems: List<CommentThread>? = null,
        )
    }

    @Serializable
    data class ContinuationContents(
        val commentRepliesContinuation: CommentRepliesContinuation? = null,
        val itemSectionContinuation: ItemSectionContinuation? = null,
    ) {
        @Serializable
        data class CommentRepliesContinuation(
            val contents: List<CommentThread>? = null,
            val continuations: List<Continuation>? = null,
        )

        @Serializable
        data class ItemSectionContinuation(
            val contents: List<CommentThread>? = null,
            val continuations: List<Continuation>? = null,
        )
    }

    @Serializable
    data class CommentThread(
        val commentThreadRenderer: CommentThreadRenderer? = null,
        val commentRenderer: CommentRenderer? = null, // for replies
        val continuationItemRenderer: ContinuationItemRenderer? = null,
    ) {
        @Serializable
        data class ContinuationItemRenderer(
            val continuationEndpoint: ContinuationEndpoint? = null,
        ) {
            @Serializable
            data class ContinuationEndpoint(
                val continuationCommand: ContinuationCommand? = null,
            ) {
                @Serializable
                data class ContinuationCommand(
                    val token: String? = null,
                )
            }
        }
    }

    @Serializable
    data class CommentThreadRenderer(
        val comment: Comment? = null,
        val replies: Replies? = null,
        /** Modern format: a reference to the comment entity in [FrameworkUpdates]. */
        val commentViewModel: CommentViewModelWrapper? = null,
    ) {
        @Serializable
        data class Comment(
            val commentRenderer: CommentRenderer? = null,
        )

        @Serializable
        data class CommentViewModelWrapper(
            val commentViewModel: CommentViewModelData? = null,
        ) {
            @Serializable
            data class CommentViewModelData(
                val commentId: String? = null,
            )
        }

        @Serializable
        data class Replies(
            val commentRepliesRenderer: CommentRepliesRenderer? = null,
        ) {
            @Serializable
            data class CommentRepliesRenderer(
                val contents: List<CommentThread>? = null,
                val continuations: List<Continuation>? = null,
            )
        }
    }

    @Serializable
    data class Continuation(
        val nextContinuationData: NextContinuationData? = null,
        val reloadContinuationData: ReloadContinuationData? = null,
    ) {
        @Serializable
        data class NextContinuationData(
            val continuation: String = "",
        )
        @Serializable
        data class ReloadContinuationData(
            val continuation: String = "",
        )
    }

    @Serializable
    data class FrameworkUpdates(
        val entityBatchUpdate: EntityBatchUpdate? = null,
    ) {
        @Serializable
        data class EntityBatchUpdate(
            val mutations: List<Mutation>? = null,
        ) {
            @Serializable
            data class Mutation(
                val payload: Payload? = null,
            ) {
                @Serializable
                data class Payload(
                    val commentEntityPayload: CommentEntityPayload? = null,
                )
            }
        }
    }

    /** The modern comment entity: text, author and toolbar data referenced by commentId. */
    @Serializable
    data class CommentEntityPayload(
        val properties: Properties? = null,
        val author: Author? = null,
        val toolbar: Toolbar? = null,
    ) {
        @Serializable
        data class Properties(
            val commentId: String? = null,
            val content: Content? = null,
            val publishedTime: String? = null,
        ) {
            @Serializable
            data class Content(
                val content: String? = null,
            )
        }

        @Serializable
        data class Author(
            val displayName: String? = null,
            val avatarThumbnailUrl: String? = null,
            val isCreator: Boolean = false,
        )

        @Serializable
        data class Toolbar(
            val likeCountNotliked: String? = null,
            val likeCountLiked: String? = null,
            val replyCount: String? = null,
        )
    }
}

data class CommentsResult(
    val items: List<CommentResponse.CommentThread>,
    val continuation: String?,
)

/** Resumen real de comentarios de un video para la tarjeta de "más comentadas". */
data class VideoCommentSummary(
    val videoId: String,
    val commentCount: Long?,
    val topCommentAuthor: String?,
    val topCommentText: String?,
    val topCommentAvatarUrl: String?,
)
