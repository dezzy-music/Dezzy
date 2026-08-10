package com.arturo254.opentune.utils

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

object ShareUtils {

    fun saveBitmapToCache(context: Context, bitmap: Bitmap): Uri? {
        return try {
            val file = File(context.cacheDir, "share_image.png")
            val outputStream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            outputStream.flush()
            outputStream.close()

            FileProvider.getUriForFile(
                context,
                "${context.packageName}.FileProvider",
                file
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun shareToInstagramStory(context: Context, uri: Uri, mimeType: String = "image/*") {
        val intent = Intent("com.instagram.share.ADD_TO_STORY").apply {
            setDataAndType(uri, mimeType)
            putExtra("interactive_asset_uri", uri)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback
            shareGeneric(context, uri, mimeType = mimeType)
        }
    }

    fun shareToWhatsApp(context: Context, uri: Uri, text: String? = null, mimeType: String = "image/png") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            if (text != null) {
                putExtra(Intent.EXTRA_TEXT, text)
            }
            setPackage("com.whatsapp")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            shareGeneric(context, uri, text, mimeType)
        }
    }

    fun shareGeneric(context: Context, uri: Uri, text: String? = null, mimeType: String = "image/png") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            if (text != null) {
                putExtra(Intent.EXTRA_TEXT, text)
            }
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(Intent.createChooser(intent, "Compartir"))
    }

    /** URI del FileProvider para un archivo ya guardado en cacheDir. */
    fun uriForCacheFile(context: Context, file: File): Uri? =
        runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.FileProvider",
                file,
            )
        }.getOrNull()

    /** Comparte un video (p. ej. el canvas de la canción) desde caché. */
    fun shareVideo(context: Context, uri: Uri, text: String? = null) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            if (text != null) {
                putExtra(Intent.EXTRA_TEXT, text)
            }
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(Intent.createChooser(intent, "Compartir canvas"))
    }

    fun saveImageToGallery(context: Context, bitmap: Bitmap, title: String): Uri? {
        return try {
            val sanitizedTitle = title.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(30)
            val fileName = "Dezzy_${sanitizedTitle}_${System.currentTimeMillis()}.png"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/OpenTune")
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                values.clear()
                values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun saveVideoToGallery(context: Context, videoFile: File, title: String): Uri? {
        return try {
            val sanitizedTitle = title.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(30)
            val fileName = "Dezzy_${sanitizedTitle}_${System.currentTimeMillis()}.mp4"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_MOVIES + "/OpenTune")
                    put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                videoFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                values.clear()
                values.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
