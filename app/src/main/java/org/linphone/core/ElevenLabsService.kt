package org.linphone.core

import android.content.Context
import android.util.Log

object ElevenLabsService {
    private const val TAG = "ElevenLabsService"
    private const val AGENT_ID = "agent_8701khp3v3s3ffqrtxmv80qae79m"

    private var webSocketClient: ElevenLabsWebSocketClient? = null
    private var callBridge: ElevenLabsCallBridge? = null

    // For manual button mode (mic/speaker)
    private var audioHandler: ElevenLabsAudioHandler? = null

    /**
     * Phase 1: Generate tone WAV file and prepare record file.
     * Call BEFORE answerCall(). Returns the tone file path for core.playFile.
     */
    fun prepareBridge(context: Context): String {
        disconnect()
        callBridge = ElevenLabsCallBridge(context)
        val tonePath = callBridge!!.prepare()
        Log.i(TAG, "Bridge prepared with tone file: $tonePath")
        return tonePath
    }

    /** Get the record file path (for core.recordFile, set during StreamsRunning). */
    fun getRecordPath(): String? = callBridge?.recordFilePath

    /**
     * Phase 2: Start file tail reader + WebSocket connection.
     * Call AFTER core.recordFile is set (during StreamsRunning).
     * @param core The Linphone Core instance, needed to swap core.playFile when agent connects.
     */
    fun startBridge(core: Core) {
        Log.i(TAG, "Starting ElevenLabs bridge phase 2")

        val bridge = callBridge
        if (bridge == null) {
            Log.e(TAG, "Bridge not prepared! Call prepareBridge() first.")
            return
        }

        // Set up the callback to swap core.playFile when agent audio starts
        bridge.onSwapPlayFile = { agentFilePath ->
            Log.i(TAG, "Swapping core.playFile to agent audio: $agentFilePath")
            core.playFile = agentFilePath
        }

        webSocketClient = ElevenLabsWebSocketClient(
            agentId = AGENT_ID,
            onConnected = {
                Log.i(TAG, "Agent connected, switching to live audio")
                bridge.onAgentConnected()
            },
            onAudioReceived = { audioData ->
                bridge.writeAgentAudio(audioData)
            },
            onAgentResponse = { response ->
                Log.d(TAG, "Agent response: $response")
            },
            onError = { error ->
                Log.e(TAG, "WebSocket error", error)
            }
        )

        // Start reading caller audio from the record file
        bridge.startRecording { callerAudioData ->
            webSocketClient?.sendAudio(callerAudioData)
        }

        try {
            webSocketClient?.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to ElevenLabs agent", e)
            disconnect()
        }
    }

    /**
     * Manual button mode — uses mic/speaker directly (no WAV files).
     */
    fun connectToAgent(core: Core) {
        Log.i(TAG, "Connecting to ElevenLabs Agent: $AGENT_ID via WebSocket (manual mode)")
        disconnect()

        webSocketClient = ElevenLabsWebSocketClient(
            agentId = AGENT_ID,
            onConnected = {
                Log.i(TAG, "Connected to agent (manual mode)")
                audioHandler = ElevenLabsAudioHandler { audioData ->
                    webSocketClient?.sendAudio(audioData)
                }
                audioHandler?.start()
            },
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

        try {
            webSocketClient?.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ElevenLabs session", e)
            disconnect()
        }
    }

    fun disconnect() {
        try {
            webSocketClient?.disconnect()
            callBridge?.stop()
            audioHandler?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
        } finally {
            webSocketClient = null
            callBridge = null
            audioHandler = null
        }
    }
}
