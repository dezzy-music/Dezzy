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

    fun shareToInstagramStory(context: Context, uri: Uri) {
        val intent = Intent("com.instagram.share.ADD_TO_STORY").apply {
            setDataAndType(uri, "image/*")
            putExtra("interactive_asset_uri", uri)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback
            shareGeneric(context, uri)
        }
    }

    fun shareToWhatsApp(context: Context, uri: Uri, text: String? = null) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
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
            shareGeneric(context, uri, text)
        }
    }

    fun shareGeneric(context: Context, uri: Uri, text: String? = null) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            if (text != null) {
                putExtra(Intent.EXTRA_TEXT, text)
            }
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(Intent.createChooser(intent, "Compartir"))
    }
}
