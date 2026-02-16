package org.linphone.core

import android.util.Log

class ElevenLabsCallHandler(
    private val core: Core,
    private val call: Call,
    private val agentId: String
) {
    companion object {
        private const val TAG = "ElevenLabsCallHandler"
    }

    private var recordFilePath: String? = null

    init {
        Log.i(TAG, "Created ElevenLabsCallHandler for call: $call with agent: $agentId")
        setupCallRecording()
    }

    private fun setupCallRecording() {
        try {
            // Ensure we have a valid path for recording
            val params = call.currentParams
            if (params != null) {
                // The recording file path should be set in CallParams by CoreContext or CallManager
                val existingPath = params.recordFile
                if (!existingPath.isNullOrEmpty()) {
                    recordFilePath = existingPath
                    Log.i(TAG, "Recording path already set: $recordFilePath")
                    
                    // Start recording if not already started
                    if (call.params?.recordFile != null) {
                         call.startRecording()
                         Log.i(TAG, "Started recording to $recordFilePath")
                    }
                } else {
                    Log.w(TAG, "No recording path set in call params")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up recording", e)
        }
    }

    fun handleCallState(state: Call.State) {
        Log.i(TAG, "Handling call state: $state")
        when (state) {
            Call.State.StreamsRunning -> {
                Log.i(TAG, "Call established with ElevenLabs agent")
                // Ensure audio is routed correctly or any specific logic for the agent
            }
            Call.State.End, Call.State.Error -> {
                Log.i(TAG, "Call ended or error")
                // Cleanup if necessary
            }
            else -> {
                // Handle other states
            }
        }
    }
}
