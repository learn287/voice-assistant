package com.example.edgevoiceassistant

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.sqrt

class AudioCapture {
    private val sampleRate = 16000
    private val windowSamples = 16000   // 1.0 second inference window
    private val hopSamples = 8000        // 0.5 second slide step (50% overlap)

    private var audioRecord: AudioRecord? = null
    @Volatile var isRecording = false
        private set

    // Circular ring buffer
    private val ringBuffer = FloatArray(windowSamples)
    private var bufferFilled = false

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (isRecording) return
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBufferSize, windowSamples * 2)
        )
        audioRecord?.startRecording()
        isRecording = true
        bufferFilled = false
        ringBuffer.fill(0.0f)
    }

    /**
     * Reads a 500ms hop block and slides the 1.0s window forward.
     * Prevents words from ever getting split across chunk boundaries.
     */
    fun readOverlappingWindow(): Pair<FloatArray, Float>? {
        val record = audioRecord ?: return null
        if (!isRecording) return null

        val hopShorts = ShortArray(hopSamples)
        var readTotal = 0

        while (readTotal < hopSamples && isRecording) {
            val count = record.read(hopShorts, readTotal, hopSamples - readTotal)
            if (count > 0) {
                readTotal += count
            } else {
                break
            }
        }

        if (readTotal < hopSamples) return null

        // Shift old audio back by hopSamples
        System.arraycopy(ringBuffer, hopSamples, ringBuffer, 0, windowSamples - hopSamples)

        // Append new hop block normalized to [-1.0, 1.0]
        var sumSquares = 0.0
        for (i in 0 until hopSamples) {
            val sample = hopShorts[i] / 32768.0f
            ringBuffer[windowSamples - hopSamples + i] = sample
            sumSquares += (sample * sample)
        }

        val rmsEnergy = sqrt(sumSquares / hopSamples).toFloat()
        return Pair(ringBuffer.clone(), rmsEnergy)
    }

    fun stopRecording() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }
}
