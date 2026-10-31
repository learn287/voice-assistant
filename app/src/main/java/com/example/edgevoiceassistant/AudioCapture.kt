package com.example.edgevoiceassistant

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.sqrt

class AudioCapture {
    private val sampleRate = 16000
    private val windowSamples = 16000
    private val hopSamples = 8000

    private var audioRecord: AudioRecord? = null
    @Volatile var isRecording = false
        private set

    private val ringBuffer = FloatArray(windowSamples)

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
        ringBuffer.fill(0.0f)
    }

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

        System.arraycopy(ringBuffer, hopSamples, ringBuffer, 0, windowSamples - hopSamples)

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
