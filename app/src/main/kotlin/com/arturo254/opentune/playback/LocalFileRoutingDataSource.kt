/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.arturo254.opentune.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import com.arturo254.opentune.utils.LocalMusicImporter

/**
 * Enruta la reproducción según el mediaId: los LOCAL_* (música importada) se
 * leen directamente del archivo con [FileDataSource]; todo lo demás sigue por
 * el pipeline remoto (cache + resolución de stream de YouTube), que queda
 * intacto — las canciones locales nunca tocan la caché ni la red.
 */
@UnstableApi
internal class LocalFileRoutingDataSource(
    private val remote: DataSource,
    private val context: Context,
) : DataSource {

    private val local = FileDataSource()
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        remote.addTransferListener(transferListener)
        local.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val mediaId = dataSpec.key
        if (mediaId != null && LocalMusicImporter.isLocalId(mediaId)) {
            val file = LocalMusicImporter.audioFileFor(context, mediaId)
                ?: throw DataSourceException(
                    "Archivo local no encontrado para $mediaId",
                    null,
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                )
            active = local
            return local.open(dataSpec.buildUpon().setUri(Uri.fromFile(file)).build())
        }
        active = remote
        return remote.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: 0

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        active?.responseHeaders ?: emptyMap()

    override fun close() {
        val toClose = active
        active = null
        toClose?.close()
    }
}
