package com.arturo254.opentune.di

import androidx.media3.datasource.cache.Cache
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CacheEntryPoint {
    @PlayerCache
    fun playerCache(): Cache

    @DownloadCache
    fun downloadCache(): Cache
}
