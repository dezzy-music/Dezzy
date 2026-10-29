package com.dezzy.music.ai

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class EdgeTtsClient(private val client: OkHttpClient) {

    private val EDGE_URL = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    suspend fun synthesize(
        text: String,
        voiceName: String,
        outputFile: File
    ): Result<File> = suspendCancellableCoroutine { continuation ->
        
        val requestId = UUID.randomUUID().toString().replace("-", "")
        
        val request = Request.Builder()
            .url(EDGE_URL)
            .addHeader("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .build()

        var outputStream: FileOutputStream? = null
        var isCompleted = false
        
        val webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                try {
                    outputStream = FileOutputStream(outputFile)
                    
                    val configMessage = "Content-Type:application/json; charset=utf-8\r\n" +
                            "Path:speech.config\r\n\r\n" +
                            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"true\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
                    webSocket.send(configMessage)
                    
                    val escapedText = text
                        .replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        
                    val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='es-MX'>" +
                            "<voice name='$voiceName'>" +
                            "<prosody pitch='+0Hz' rate='+0%' volume='+0%'>$escapedText</prosody>" +
                            "</voice></speak>"
                            
                    val ssmlMessage = "X-RequestId:$requestId\r\n" +
                            "Content-Type:application/ssml+xml\r\n" +
                            "Path:ssml\r\n\r\n" +
                            ssml
                            
                    webSocket.send(ssmlMessage)
                } catch (e: Exception) {
                    outputStream?.close()
                    if (!isCompleted) {
                        isCompleted = true
                        continuation.resumeWithException(e)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    outputStream?.close()
                    webSocket.close(1000, "Done")
                    if (!isCompleted) {
                        isCompleted = true
                        continuation.resume(Result.success(outputFile))
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    val data = bytes.toByteArray()
                    // Extract payload. Binary messages have headers ending in \r\n\r\n
                    val headerEndStr = "\r\n\r\n"
                    val headerEndBytes = headerEndStr.toByteArray()
                    
                    var headerEndIndex = -1
                    for (i in 0 until data.size - 3) {
                        if (data[i] == headerEndBytes[0] &&
                            data[i+1] == headerEndBytes[1] &&
                            data[i+2] == headerEndBytes[2] &&
                            data[i+3] == headerEndBytes[3]) {
                            headerEndIndex = i + 4
                            break
                        }
                    }
                    
                    if (headerEndIndex != -1 && headerEndIndex < data.size) {
                        // Sometimes there are text frames wrapped in binary (e.g. metadata)
                        val headers = String(data, 0, headerEndIndex)
                        if (headers.contains("Path:audio")) {
                            outputStream?.write(data, headerEndIndex, data.size - headerEndIndex)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                outputStream?.close()
                if (!isCompleted) {
                    isCompleted = true
                    continuation.resume(Result.failure(t))
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                outputStream?.close()
                if (!isCompleted) {
                    isCompleted = true
                    // If closed unexpectedly
                    continuation.resume(Result.failure(Exception("Closed: $reason")))
                }
            }
        })
        
        continuation.invokeOnCancellation {
            webSocket.cancel()
            outputStream?.close()
        }
    }
}

