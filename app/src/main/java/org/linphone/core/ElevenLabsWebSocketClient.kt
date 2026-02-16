package org.linphone.core

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

class ElevenLabsWebSocketClient(
    private val agentId: String,
    private val onAudioReceived: (ByteArray) -> Unit,
    private val onAgentResponse: (String) -> Unit,
    private val onError: (Throwable) -> Unit
) {
    companion object {
        private const val TAG = "ElevenLabsWSClient"
        private const val BASE_URL = "wss://api.elevenlabs.io/v1/convai/conversation"
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep the connection open
        .build()

    private var webSocket: WebSocket? = null
    private val gson = Gson()

    fun connect() {
        val url = "$BASE_URL?agent_id=$agentId"
        val request = Request.Builder().url(url).build()
        Log.i(TAG, "Connecting to: $url")

        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket Connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received message: $text")
                try {
                    val message = gson.fromJson(text, JsonObject::class.java)
                    handleMessage(message)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing message", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(TAG, "Received binary message: ${bytes.size} bytes")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket Closing: $code / $reason")
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket Failure", t)
                onError(t)
            }
        }
        )
    }

    private fun handleMessage(message: JsonObject) {
        val type = message.get("type")?.asString
        
        when (type) {
            "agent_response" -> {
                val response = message.get("agent_response_event")?.asJsonObject
                val audioBase64 = message.get("audio_event")?.asJsonObject?.get("audio_base_64")?.asString

                if (audioBase64 != null) {
                   try {
                       val audioBytes = android.util.Base64.decode(audioBase64, android.util.Base64.DEFAULT)
                       onAudioReceived(audioBytes)
                   } catch (e: Exception) {
                       Log.e(TAG, "Error decoding audio", e)
                   }
                }
            }
            "audio" -> {
                 val audioBase64 = message.get("audio_event")?.asJsonObject?.get("audio_base_64")?.asString
                 if (audioBase64 != null) {
                    try {
                        val audioBytes = android.util.Base64.decode(audioBase64, android.util.Base64.DEFAULT)
                        onAudioReceived(audioBytes)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error decoding audio", e)
                    }
                 }
            }
             "interruption" -> {
                Log.i(TAG, "Interruption event")
                // Handle interruption (clear audio buffer)
            }
            "ping" -> {
                val pingEvent = message.get("ping_event")?.asJsonObject
                val eventId = pingEvent?.get("event_id")?.asInt
                
                val pong = JsonObject()
                pong.addProperty("type", "pong")
                if (eventId != null) {
                    pong.addProperty("event_id", eventId)
                }
                webSocket?.send(gson.toJson(pong))
            }
            else -> {
                 Log.d(TAG, "Unknown message type: $type")
            }
        }
    }

    fun sendAudio(audioData: ByteArray) {
        if (webSocket == null) return
        
        val message = JsonObject()
        // Correct format: just {"user_audio_chunk": "base64String"}
        message.addProperty("user_audio_chunk", android.util.Base64.encodeToString(audioData, android.util.Base64.NO_WRAP))

        webSocket?.send(gson.toJson(message))
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
    }
}
