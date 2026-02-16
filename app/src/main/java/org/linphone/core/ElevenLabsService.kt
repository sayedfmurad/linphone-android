package org.linphone.core

import android.util.Log

object ElevenLabsService {
    private const val TAG = "ElevenLabsService"
    // ID provided by user: agent_9001khkq2tk7egta8c05h6aj1hxx
    private const val AGENT_ID = "agent_9001khkq2tk7egta8c05h6aj1hxx"

    private var webSocketClient: ElevenLabsWebSocketClient? = null
    private var audioHandler: ElevenLabsAudioHandler? = null

    fun connectToAgent(core: Core) {
        Log.i(TAG, "Connecting to ElevenLabs Agent: $AGENT_ID via WebSocket")
        
        // Stop any existing connection
        disconnect()

        webSocketClient = ElevenLabsWebSocketClient(
            agentId = AGENT_ID,
            onAudioReceived = { audioData ->
                audioHandler?.playAudio(audioData)
            },
            onAgentResponse = { response ->
                Log.d(TAG, "Agent response: $response")
            },
            onError = { error ->
                Log.e(TAG, "WebSocket error", error)
                disconnect()
            }
        )
        
        audioHandler = ElevenLabsAudioHandler { audioData ->
            webSocketClient?.sendAudio(audioData)
        }

        try {
            webSocketClient?.connect()
            audioHandler?.start()
        } catch (e: Exception) {
             Log.e(TAG, "Failed to start ElevenLabs session", e)
             disconnect()
        }
    }

    fun disconnect() {
        try {
            webSocketClient?.disconnect()
            audioHandler?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
        } finally {
            webSocketClient = null
            audioHandler = null
        }
    }
}
