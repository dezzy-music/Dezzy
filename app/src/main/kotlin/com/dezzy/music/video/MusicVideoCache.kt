/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.video

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource

/**
 * Caché exclusiva del modo video, separada de las cachés de audio (`exoplayer` y `download`)
 * para que los videoclips nunca desplacen ni corrompan canciones guardadas.
 */
@OptIn(UnstableApi::class)
object MusicVideoCache {
    private const val DIRECTORY = "video_cache"
    private const val MAX_BYTES = 256L * 1024 * 1024

    @Volatile private var cache: SimpleCache? = null

    private fun cache(context: Context, databaseProvider: DatabaseProvider): SimpleCache =
        cache ?: synchronized(this) {
            cache ?: SimpleCache(
                context.applicationContext.filesDir.resolve(DIRECTORY),
                LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                databaseProvider,
            ).also { cache = it }
        }

    /**
     * Las URLs de googlevideo cambian de firma en cada resolución; la clave estable es
     * videoId+itag+tamaño, así un videoclip ya visto se reutiliza aunque su URL sea nueva.
     */
    private val cacheKeyFactory = CacheKeyFactory { dataSpec ->
        val uri: Uri = dataSpec.uri
        val host = uri.host.orEmpty()
        val id = runCatching { uri.getQueryParameter("id") }.getOrNull()
        val itag = runCatching { uri.getQueryParameter("itag") }.getOrNull()
        if (host.endsWith("googlevideo.com") && !id.isNullOrBlank() && !itag.isNullOrBlank()) {
            val clen = runCatching { uri.getQueryParameter("clen") }.getOrNull().orEmpty()
            val sq = runCatching { uri.getQueryParameter("sq") }.getOrNull().orEmpty()
            "gv:$id:$itag:$clen:$sq"
        } else {
            dataSpec.key ?: uri.toString()
        }
    }

    /** Fuente de datos para el reproductor de video: red (cliente propio) + caché de video. */
    fun dataSourceFactory(context: Context, databaseProvider: DatabaseProvider): DataSource.Factory {
        val upstream = DefaultDataSource.Factory(
            context.applicationContext,
            OkHttpDataSource.Factory(MusicVideoResolver.httpClient)
                .setUserAgent(MusicVideoResolver.USER_AGENT),
        )
        return CacheDataSource.Factory()
            .setCache(cache(context, databaseProvider))
            .setCacheKeyFactory(cacheKeyFactory)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}
