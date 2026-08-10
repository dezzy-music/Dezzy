package com.arturo254.opentune.utils

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Extracts the base URL from a YouTube/Google CDN thumbnail URL.
 * Removes resolution or sizing parameters so the same core image evaluates to the same base string.
 */
fun String.toArtworkBaseUrl(): String {
    return this.substringBefore("?")
        .substringBefore("=w")
        .substringBefore("=s")
        .substringBefore("hqdefault.jpg")
        .substringBefore("maxresdefault.jpg")
        .substringBefore("sddefault.jpg")
        .substringBefore("mqdefault.jpg")
        .substringBefore("default.jpg")
}

/**
 * Computes an MD5 hash of the artwork's base URL, used to link downloaded files 
 * with the various high/low resolution URLs requested by the UI.
 */
fun String.toArtworkHash(): String {
    return this.toArtworkBaseUrl().md5()
}

/**
 * Standard MD5 hashing extension.
 */
fun String.md5(): String {
    val md = MessageDigest.getInstance("MD5")
    return BigInteger(1, md.digest(this.toByteArray())).toString(16).padStart(32, '0')
}
