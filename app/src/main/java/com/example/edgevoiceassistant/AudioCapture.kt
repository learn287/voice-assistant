package com.example.edgevoiceassistant
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AudioCapture {
    private val sampleRate = 16000
    private val targetSamples = 16000

    @SuppressLint("MissingPermission")
    suspend fun recordOneSecond(): FloatArray = withContext(Dispatchers.IO) {
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBufferSize, targetSamples * 2))
        val rawShortBuffer = ShortArray(targetSamples)
        audioRecord.startRecording()
        var readCount = 0
        while (readCount < targetSamples) {
            val count = audioRecord.read(rawShortBuffer, readCount, targetSamples - readCount)
            if (count > 0) readCount += count
        }
        audioRecord.stop()
        audioRecord.release()
        FloatArray(targetSamples) { i -> rawShortBuffer[i] / 32768.0f }
    }
}
