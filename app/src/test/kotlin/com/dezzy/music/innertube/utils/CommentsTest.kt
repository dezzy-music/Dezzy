package com.dezzy.music.innertube.utils

import com.dezzy.music.innertube.YouTube
import kotlinx.coroutines.runBlocking
import org.junit.Test

class CommentsTest {
    @Test
    fun testComments() = runBlocking {
        val videoId = "dQw4w9WgXcQ"
        println("--- DEBUG COMMENTS ---")
        println("Fetching comments token for $videoId...")
        val res = YouTube.getVideoCommentsContinuation(videoId)
        println("Result success: ${res.isSuccess}")
        if (res.isSuccess) {
            val token = res.getOrNull()
            println("Token: $token")
            if (token != null) {
                val comments = YouTube.getComments(token)
                println("Comments success: ${comments.isSuccess}")
                if (comments.isSuccess) {
                    val list = comments.getOrNull()?.items
                    println("Comments count: ${list?.size}")
                } else {
                    println("Comments failure: ${comments.exceptionOrNull()}")
                }
            }
        } else {
            println("Failed: ${res.exceptionOrNull()}")
        }
        println("--- END DEBUG COMMENTS ---")
    }
}
