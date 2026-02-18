package org.linphone.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manages audio bridging between a Linphone call and ElevenLabs agent
 * using WAV files that Linphone's MSFilePlayer can read.
 *
 * Optimized for minimum latency:
 * - Writer thread uses wait/notify (reacts instantly to incoming audio)
 * - Reader thread uses tight 1ms polling with batch reads
 * - Zero pre-buffer — agent audio plays as soon as it arrives
 * - Small 10ms chunks for finer granularity
 */
class ElevenLabsCallBridge(private val context: Context) {
    companion object {
        private const val TAG = "ElevenLabsCallBridge"
        private const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        private const val BYTE_RATE = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8 // 32000
        private const val BLOCK_ALIGN = CHANNELS * BITS_PER_SAMPLE / 8 // 2

        private const val TONE_DURATION_SECONDS = 10

        // 10ms chunk = 320 bytes at 16kHz 16-bit mono (smaller = lower latency)
        private const val CHUNK_MS = 10L
        private const val CHUNK_BYTES = (SAMPLE_RATE * BLOCK_ALIGN * CHUNK_MS / 1000).toInt() // 320

        // Zero pre-buffer: MSFilePlayer starts reading immediately
        // We stay ahead by writing silence continuously when no agent audio is available
        private const val PRE_BUFFER_BYTES = 0
    }

    var toneFilePath: String? = null
        private set
    var agentPlayFilePath: String? = null
        private set
    var recordFilePath: String? = null
        private set

    @Volatile private var isRunning = false

    @Volatile private var agentConnected = false

    private var readThread: Thread? = null
    private var writerThread: Thread? = null
    private var agentFileOutputStream: FileOutputStream? = null
    private var agentDataBytesWritten: Long = 0

    // Queue for incoming agent audio chunks — writer thread wakes up on notify
    private val agentAudioQueue = ConcurrentLinkedQueue<ByteArray>()
    private val writerLock = ReentrantLock()
    private val writerCondition = writerLock.newCondition()
    private val silence = ByteArray(CHUNK_BYTES) // pre-allocated silence buffer

    private var onCallerAudio: ((ByteArray) -> Unit)? = null

    // Callback to swap core.playFile when agent connects
    var onSwapPlayFile: ((String) -> Unit)? = null

    /**
     * Generate the waiting tone WAV file and create the record file path.
     * Returns the tone file path (for core.playFile).
     */
    fun prepare(): String {
        val dir = context.cacheDir

        toneFilePath = "${dir.absolutePath}/elevenlabs_tone.wav"
        generateToneWavFile(toneFilePath!!)

        agentPlayFilePath = "${dir.absolutePath}/elevenlabs_agent_play.wav"

        recordFilePath = "${dir.absolutePath}/elevenlabs_record.wav"
        // Delete any stale file - let Linphone create a fresh one when core.recordFile is set
        File(recordFilePath!!).delete()

        Log.i(TAG, "Prepared: tone=$toneFilePath, record=$recordFilePath")
        return toneFilePath!!
    }

    /**
     * Start the file tail reader on the record file.
     * Call AFTER core.recordFile is set.
     */
    fun startRecording(onCallerAudioData: (ByteArray) -> Unit) {
        isRunning = true
        onCallerAudio = onCallerAudioData
        startReadThread()
    }

    /**
     * Called when ElevenLabs agent WebSocket connects.
     * Creates agent play file, starts continuous writer, triggers playFile swap.
     */
    fun onAgentConnected() {
        Log.i(TAG, "Agent connected, preparing agent audio file")
        agentConnected = true

        try {
            val file = File(agentPlayFilePath!!)
            agentFileOutputStream = FileOutputStream(file)
            agentDataBytesWritten = 0

            // Write WAV header with max data size (continuous streaming)
            writeWavHeader(agentFileOutputStream!!, Int.MAX_VALUE)

            // No pre-buffer — start the writer thread immediately
            // The writer will produce silence at the target rate to keep MSFilePlayer fed
            Log.i(TAG, "Agent play file created (zero pre-buffer, 10ms chunks)")

            // Start the continuous writer thread BEFORE swapping playFile
            startWriterThread()

            // Now swap core.playFile — MSFilePlayer will start reading from the beginning
            onSwapPlayFile?.invoke(agentPlayFilePath!!)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating agent play file", e)
        }
    }

    /**
     * Clear queued agent audio (called on interruption events).
     */
    fun clearAgentAudioQueue() {
        val cleared = agentAudioQueue.size
        agentAudioQueue.clear()
        Log.i(TAG, "Cleared $cleared chunks from agent audio queue (interruption)")
    }

    /**
     * Queue agent audio for writing (called from WebSocket thread).
     * Immediately wakes the writer thread to process it.
     */
    fun writeAgentAudio(audioData: ByteArray) {
        agentAudioQueue.offer(audioData)
        // Wake writer thread immediately — no waiting for the next sleep cycle
        writerLock.withLock {
            writerCondition.signal()
        }
    }

    fun stop() {
        isRunning = false
        agentConnected = false

        // Wake writer thread so it can exit
        writerLock.withLock {
            writerCondition.signal()
        }

        readThread?.interrupt()
        writerThread?.interrupt()
        try { readThread?.join(2000) } catch (_: Exception) {}
        try { writerThread?.join(2000) } catch (_: Exception) {}

        try { agentFileOutputStream?.close() } catch (_: Exception) {}
        agentFileOutputStream = null

        agentAudioQueue.clear()

        // Clean up files
        toneFilePath?.let { File(it).delete() }
        agentPlayFilePath?.let { File(it).delete() }
        recordFilePath?.let { File(it).delete() }

        onCallerAudio = null
        onSwapPlayFile = null
        Log.i(TAG, "Bridge stopped and files cleaned up")
    }

    /**
     * Reactive writer thread: writes agent audio INSTANTLY when it arrives.
     * Uses wait/notify instead of sleep to eliminate latency.
     *
     * When no audio is queued, writes silence every CHUNK_MS to keep
     * MSFilePlayer fed and prevent EOF/looping.
     */
    private fun startWriterThread() {
        writerThread = Thread {
            Log.i(TAG, "Writer thread started (reactive, ${CHUNK_MS}ms silence interval)")
            var nextSilenceTime = System.nanoTime() + CHUNK_MS * 1_000_000

            try {
                while (isRunning) {
                    val fos = agentFileOutputStream ?: break

                    // Drain ALL queued agent audio immediately (no delay between chunks)
                    var wrote = false
                    var agentData = agentAudioQueue.poll()
                    while (agentData != null) {
                        fos.write(agentData)
                        fos.flush()
                        agentDataBytesWritten += agentData.size
                        wrote = true
                        agentData = agentAudioQueue.poll()
                    }

                    if (wrote) {
                        // Reset silence timer — we just wrote real audio
                        nextSilenceTime = System.nanoTime() + CHUNK_MS * 1_000_000
                    } else {
                        // No audio data — check if it's time to write silence
                        val now = System.nanoTime()
                        if (now >= nextSilenceTime) {
                            fos.write(silence)
                            fos.flush()
                            agentDataBytesWritten += silence.size
                            nextSilenceTime = now + CHUNK_MS * 1_000_000
                        }
                    }

                    // Wait for new audio or next silence interval (whichever is sooner)
                    if (agentAudioQueue.isEmpty()) {
                        writerLock.withLock {
                            if (agentAudioQueue.isEmpty() && isRunning) {
                                val waitNanos = nextSilenceTime - System.nanoTime()
                                if (waitNanos > 0) {
                                    writerCondition.awaitNanos(waitNanos)
                                }
                            }
                        }
                    }
                }
            } catch (e: InterruptedException) {
                Log.i(TAG, "Writer thread interrupted")
            } catch (e: Exception) {
                if (isRunning) Log.e(TAG, "Writer thread error", e)
            }
            Log.i(TAG, "Writer thread ended, total bytes written: $agentDataBytesWritten")
        }
        writerThread?.name = "ElevenLabs-WAV-Write"
        writerThread?.priority = Thread.MAX_PRIORITY
        writerThread?.start()
    }

    /**
     * Tail-read the record WAV file for new audio data.
     * Linphone writes caller audio; we read new bytes as they appear.
     * Uses tight 1ms polling for minimum latency.
     */
    private fun startReadThread() {
        readThread = Thread {
            try {
                Log.i(TAG, "Read thread: waiting for record file to have data...")
                val file = File(recordFilePath!!)

                // Wait for Linphone to start writing (WAV header = 44 bytes)
                var waited = 0
                while (isRunning && file.length() < 44 && waited < 30000) {
                    Thread.sleep(50) // Check more frequently during startup
                    waited += 50
                }

                if (!isRunning || file.length() < 44) {
                    Log.w(TAG, "Read thread: record file not ready after ${waited}ms, exiting")
                    return@Thread
                }

                Log.i(TAG, "Read thread: record file ready (${file.length()} bytes)")
                val raf = RandomAccessFile(file, "r")

                // Read actual sample rate from WAV header (bytes 24-27, little-endian)
                raf.seek(24)
                val b0 = raf.read()
                val b1 = raf.read()
                val b2 = raf.read()
                val b3 = raf.read()
                val fileSampleRate = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
                Log.i(TAG, "Read thread: WAV sample rate = $fileSampleRate Hz")

                // Use 10ms chunks for lower latency
                val fileChunkBytes = (fileSampleRate * BLOCK_ALIGN * CHUNK_MS / 1000).toInt()
                val needsDownsample = fileSampleRate != SAMPLE_RATE
                val downsampleRatio = if (needsDownsample) fileSampleRate / SAMPLE_RATE else 1
                Log.i(TAG, "Read thread: fileChunkBytes=$fileChunkBytes, needsDownsample=$needsDownsample, ratio=$downsampleRatio")

                // Seek past WAV header to PCM data
                raf.seek(44)

                val buffer = ByteArray(fileChunkBytes)
                var readPosition = 44L
                var totalChunksSent = 0
                var lastLogTime = System.currentTimeMillis()

                while (isRunning) {
                    val available = file.length() - readPosition

                    if (available >= buffer.size) {
                        // Read ALL available chunks in a tight loop (batch processing)
                        var chunksThisRound = 0
                        while (isRunning && file.length() - readPosition >= buffer.size) {
                            val read = raf.read(buffer)
                            if (read > 0) {
                                readPosition += read

                                val audioToSend = if (needsDownsample && read >= BLOCK_ALIGN * downsampleRatio) {
                                    // Downsample: take every Nth sample (16-bit = 2 bytes per sample)
                                    val inputSamples = read / BLOCK_ALIGN
                                    val outputSamples = inputSamples / downsampleRatio
                                    val downsampled = ByteArray(outputSamples * BLOCK_ALIGN)
                                    for (i in 0 until outputSamples) {
                                        val srcIdx = i * downsampleRatio * BLOCK_ALIGN
                                        val dstIdx = i * BLOCK_ALIGN
                                        // Copy 2 bytes (16-bit sample)
                                        downsampled[dstIdx] = buffer[srcIdx]
                                        downsampled[dstIdx + 1] = buffer[srcIdx + 1]
                                    }
                                    downsampled
                                } else {
                                    if (read == buffer.size) buffer else buffer.copyOf(read)
                                }

                                onCallerAudio?.invoke(audioToSend)
                                totalChunksSent++
                                chunksThisRound++
                            }

                            // Don't process too many chunks at once to avoid blocking
                            if (chunksThisRound >= 10) break
                        }
                    } else {
                        // Tight poll — 1ms sleep to catch new data ASAP
                        Thread.sleep(1)
                    }

                    // Log diagnostics every 5 seconds
                    val now = System.currentTimeMillis()
                    if (now - lastLogTime >= 5000) {
                        Log.i(TAG, "Read thread: fileSize=${file.length()}, readPos=$readPosition, chunksSent=$totalChunksSent, rate=$fileSampleRate")
                        lastLogTime = now
                    }
                }

                raf.close()
            } catch (e: InterruptedException) {
                Log.i(TAG, "Read thread interrupted")
            } catch (e: Exception) {
                if (isRunning) Log.e(TAG, "Read thread error", e)
            }
        }
        readThread?.name = "ElevenLabs-WAV-Read"
        readThread?.priority = Thread.MAX_PRIORITY
        readThread?.start()
    }

    /**
     * Generate a WAV file with a repeating 440Hz tone (1s tone, 2s silence).
     */
    private fun generateToneWavFile(path: String) {
        val totalSamples = SAMPLE_RATE * TONE_DURATION_SECONDS
        val dataSize = totalSamples * BLOCK_ALIGN

        val fos = FileOutputStream(path)
        writeWavHeader(fos, dataSize)

        val toneSamples = SAMPLE_RATE * 1
        val silenceSamples = SAMPLE_RATE * 2
        val cycleLength = toneSamples + silenceSamples
        val smallBuffer = ByteArray(1024)

        var sampleIndex = 0
        while (sampleIndex < totalSamples) {
            val bufferSamples = minOf(smallBuffer.size / 2, totalSamples - sampleIndex)
            for (i in 0 until bufferSamples) {
                val posInCycle = (sampleIndex + i) % cycleLength
                val sample: Short = if (posInCycle < toneSamples) {
                    (
                        Short.MAX_VALUE * 0.3 * Math.sin(
                        2.0 * Math.PI * 440.0 * (sampleIndex + i) / SAMPLE_RATE
                    )
                    ).toInt().toShort()
                } else {
                    0
                }
                smallBuffer[i * 2] = (sample.toInt() and 0xFF).toByte()
                smallBuffer[i * 2 + 1] = (sample.toInt() shr 8 and 0xFF).toByte()
            }
            fos.write(smallBuffer, 0, bufferSamples * 2)
            sampleIndex += bufferSamples
        }

        fos.flush()
        fos.close()
        Log.i(TAG, "Generated tone WAV: $path (${dataSize + 44} bytes, ${TONE_DURATION_SECONDS}s)")
    }

    private fun writeWavHeader(out: FileOutputStream, dataSize: Int) {
        out.write("RIFF".toByteArray())
        out.write(intToLE(36 + dataSize))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(intToLE(16))
        out.write(shortToLE(1))
        out.write(shortToLE(CHANNELS.toShort()))
        out.write(intToLE(SAMPLE_RATE))
        out.write(intToLE(BYTE_RATE))
        out.write(shortToLE(BLOCK_ALIGN.toShort()))
        out.write(shortToLE(BITS_PER_SAMPLE.toShort()))
        out.write("data".toByteArray())
        out.write(intToLE(dataSize))
        out.flush()
    }

    private fun intToLE(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        (value shr 8 and 0xFF).toByte(),
        (value shr 16 and 0xFF).toByte(),
        (value shr 24 and 0xFF).toByte()
    )

    private fun shortToLE(value: Short): ByteArray = byteArrayOf(
        (value.toInt() and 0xFF).toByte(),
        (value.toInt() shr 8 and 0xFF).toByte()
    )
}
