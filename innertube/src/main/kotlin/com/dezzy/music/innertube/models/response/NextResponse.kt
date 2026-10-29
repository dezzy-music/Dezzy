/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.innertube.models.response

import com.dezzy.music.innertube.models.NavigationEndpoint
import com.dezzy.music.innertube.models.PlaylistPanelRenderer
import com.dezzy.music.innertube.models.Tabs
import com.dezzy.music.innertube.models.YouTubeDataPage
import kotlinx.serialization.Serializable

@Serializable
data class NextResponse(
    val contents: Contents,
    val continuationContents: ContinuationContents?,
    val currentVideoEndpoint: NavigationEndpoint?,
    val engagementPanels: List<EngagementPanel>? = null,
) {
    @Serializable
    data class EngagementPanel(
        val engagementPanelSectionListRenderer: EngagementPanelSectionListRenderer? = null,
    ) {
        @Serializable
        data class EngagementPanelSectionListRenderer(
            val panelIdentifier: String? = null,
            val content: Content? = null,
            val header: Header? = null,
        ) {
            /**
             * Cabecera del panel de comentarios. `contextualInfo` trae el conteo
             * total ("254 K"), que es lo que YouTube Music muestra en la tarjeta
             * de "más comentadas".
             */
            @Serializable
            data class Header(
                val engagementPanelTitleHeaderRenderer: EngagementPanelTitleHeaderRenderer? = null,
            ) {
                @Serializable
                data class EngagementPanelTitleHeaderRenderer(
                    val contextualInfo: Runs? = null,
                ) {
                    @Serializable
                    data class Runs(
                        val runs: List<Run>? = null,
                    ) {
                        @Serializable
                        data class Run(val text: String? = null)
                    }
                }
            }

            @Serializable
            data class Content(
                // YouTube Music path
                val yContinuationComponent: YContinuationComponent? = null,
                // YouTube Web path (used for comments on regular youtube.com)
                val sectionListRenderer: SectionListRenderer? = null,
            ) {
                @Serializable
                data class YContinuationComponent(
                    val nextContinuationData: NextContinuationData? = null,
                ) {
                    @Serializable
                    data class NextContinuationData(
                        val continuation: String? = null,
                    )
                }

                @Serializable
                data class SectionListRenderer(
                    val contents: List<ItemSection>? = null,
                ) {
                    @Serializable
                    data class ItemSection(
                        val itemSectionRenderer: ItemSectionRenderer? = null,
                    ) {
                        @Serializable
                        data class ItemSectionRenderer(
                            val continuations: List<Continuation>? = null,
                            val contents: List<ContentItem>? = null,
                        ) {
                            @Serializable
                            data class Continuation(
                                val nextContinuationData: NextContinuationData? = null,
                                val reloadContinuationData: NextContinuationData? = null,
                            ) {
                                @Serializable
                                data class NextContinuationData(
                                    val continuation: String? = null,
                                )
                            }

                            @Serializable
                            data class ContentItem(
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
                        }
                    }
                }
            }
        }
    }

    @Serializable
    data class Contents(
        val singleColumnMusicWatchNextResultsRenderer: SingleColumnMusicWatchNextResultsRenderer?,
        val twoColumnWatchNextResults: YouTubeDataPage.Contents.TwoColumnWatchNextResults?,
    ) {
        @Serializable
        data class SingleColumnMusicWatchNextResultsRenderer(
            val tabbedRenderer: TabbedRenderer?,
        ) {
            @Serializable
            data class TabbedRenderer(
                val watchNextTabbedResultsRenderer: WatchNextTabbedResultsRenderer?,
            ) {
                @Serializable
                data class WatchNextTabbedResultsRenderer(
                    val tabs: List<Tabs.Tab>,
                )
            }
        }
    }

    @Serializable
    data class ContinuationContents(
        val playlistPanelContinuation: PlaylistPanelRenderer,
    )
}
