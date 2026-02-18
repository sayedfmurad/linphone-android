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
    private val onConnected: () -> Unit,
    private val onAudioReceived: (ByteArray) -> Unit,
    private val onAgentResponse: (String) -> Unit,
    private val onInterruption: () -> Unit,
    private val onError: (Throwable) -> Unit
) {
    companion object {
        private const val TAG = "ElevenLabsWSClient"
        private const val BASE_URL = "wss://api.elevenlabs.io/v1/convai/conversation"
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep the connection open
        .connectTimeout(5, TimeUnit.SECONDS) // Fast connection timeout
        .build()

    private var webSocket: WebSocket? = null
    private val gson = Gson()
    private var audioChunksSent = 0

    fun connect() {
        // Add optimize_streaming_latency=4 for maximum speed
        val url = "$BASE_URL?agent_id=$agentId&optimize_streaming_latency=4"
        val request = Request.Builder().url(url).build()
        Log.i(TAG, "Connecting to: $url")

        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket Connected, sending latency-optimized config")
                sendInitialConfig(webSocket)
                onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = gson.fromJson(text, JsonObject::class.java)
                    handleMessage(message)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing message", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Binary audio — decode directly (more efficient than base64)
                Log.d(TAG, "Received binary audio: ${bytes.size} bytes")
                onAudioReceived(bytes.toByteArray())
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

    /**
     * Send initial client configuration to optimize for low latency.
     * This tells ElevenLabs to:
     * - Use PCM 16kHz output (no transcoding needed on our side)
     * - Optimize TTS for streaming latency over quality
     */
    private fun sendInitialConfig(ws: WebSocket) {
        val config = JsonObject().apply {
            addProperty("type", "conversation_initiation_client_data")

            val conversationConfig = JsonObject()

            // Request PCM 16kHz audio output — matches our WAV file format exactly
            val audio = JsonObject()
            audio.addProperty("output_format", "pcm_16000")
            audio.addProperty("input_format", "pcm_16000")
            conversationConfig.add("audio", audio)

            // Optimize TTS for minimum latency
            val tts = JsonObject()
            tts.addProperty("optimize_streaming_latency", 4)
            conversationConfig.add("tts", tts)

            add("conversation_config_override", conversationConfig)
        }

        val configJson = gson.toJson(config)
        ws.send(configJson)
        Log.i(TAG, "Sent latency-optimized config: $configJson")
    }

    private fun handleMessage(message: JsonObject) {
        val type = message.get("type")?.asString

        when (type) {
            "conversation_initiation_metadata" -> {
                Log.i(TAG, "Conversation initialized: ${message.get("conversation_id")?.asString}")
            }
            "agent_response" -> {
                val audioBase64 = message.get("audio_event")?.asJsonObject?.get("audio_base_64")?.asString

                if (audioBase64 != null) {
                   try {
                       val audioBytes = android.util.Base64.decode(audioBase64, android.util.Base64.NO_WRAP)
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
                        val audioBytes = android.util.Base64.decode(audioBase64, android.util.Base64.NO_WRAP)
                        onAudioReceived(audioBytes)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error decoding audio", e)
                    }
                 }
            }
             "interruption" -> {
                Log.i(TAG, "Interruption event, clearing audio buffers")
                onInterruption()
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
                 Log.d(TAG, "Message type: $type")
            }
        }
    }

    fun sendAudio(audioData: ByteArray) {
        if (webSocket == null) return

        val message = JsonObject()
        message.addProperty("user_audio_chunk", android.util.Base64.encodeToString(audioData, android.util.Base64.NO_WRAP))

        val sent = webSocket?.send(gson.toJson(message)) ?: false
        audioChunksSent++
        if (audioChunksSent % 100 == 1) {
            Log.i(TAG, "sendAudio: chunk #$audioChunksSent, ${audioData.size} bytes, sent=$sent")
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
    }
}
