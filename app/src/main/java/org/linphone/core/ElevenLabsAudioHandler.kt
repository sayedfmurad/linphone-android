package org.linphone.core

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.util.Log

class ElevenLabsAudioHandler(
    private val onAudioData: (ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "ElevenLabsAudioHandler"
        private const val SAMPLE_RATE = 16000 // ElevenLabs typically supports 16kHz or 24kHz
        private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isRecording = false
    private var recordingThread: Thread? = null

    @SuppressLint("MissingPermission") // Permissions should be checked before calling start
    fun start() {
        startRecording()
        startPlaying()
    }

    fun stop() {
        isRecording = false
        try {
            recordingThread?.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while waiting for recording thread to stop", e)
        }
        
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }

    fun playAudio(audioData: ByteArray) {
        audioTrack?.write(audioData, 0, audioData.size)
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT)
        
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // Use voice communication for AEC
                SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT,
                minBufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized")
                return
            }

            audioRecord?.startRecording()
            isRecording = true

            recordingThread = Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                val buffer = ByteArray(minBufferSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        val data = buffer.copyOf(read)
                        onAudioData(data)
                    }
                }
            }.apply { start() }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
        }
    }

    private fun startPlaying() {
         val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT)
         
         try {
             audioTrack = AudioTrack.Builder()
                 .setAudioAttributes(
                     AudioAttributes.Builder()
                     .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                     .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                     .build()
                 )
                 .setAudioFormat(
                     AudioFormat.Builder()
                     .setEncoding(AUDIO_FORMAT)
                     .setSampleRate(SAMPLE_RATE)
                     .setChannelMask(CHANNEL_CONFIG_OUT)
                     .build()
                 )
                 .setBufferSizeInBytes(minBufferSize * 2)
                 .setTransferMode(AudioTrack.MODE_STREAM)
                 .build()
                 
             if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                 Log.e(TAG, "AudioTrack not initialized")
                 return
             }
             
             audioTrack?.play()
             
         } catch (e: Exception) {
             Log.e(TAG, "Error starting playback", e)
         }
    }
}
