package com.example.edgevoiceassistant
import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer

class SpeechToIntentEngine(context: Context) {
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val ortSession: OrtSession
    val commands = listOf("on", "off", "up", "down", "stop", "go")

    init {
        val modelBytes = context.assets.open("speech_to_intent_int8.onnx").readBytes()
        val sessionOptions = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
        ortSession = ortEnv.createSession(modelBytes, sessionOptions)
    }

    suspend fun predict(audioBuffer: FloatArray): Pair<Int, String> = withContext(Dispatchers.Default) {
        val inputShape = longArrayOf(1, 16000)
        val floatBuffer = FloatBuffer.wrap(audioBuffer)
        val inputTensor = OnnxTensor.createTensor(ortEnv, floatBuffer, inputShape)
        inputTensor.use { tensor ->
            val results = ortSession.run(mapOf("audio_input" to tensor))
            results.use { outputMap ->
                @Suppress("UNCHECKED_CAST")
                val logits = (outputMap[0].value as Array<FloatArray>)[0]
                var maxIdx = 0
                var maxScore = logits[0]
                for (i in 1 until logits.size) {
                    if (logits[i] > maxScore) {
                        maxScore = logits[i]
                        maxIdx = i
                    }
                }
                Pair(maxIdx, commands[maxIdx])
            }
        }
    }

    fun close() {
        ortSession.close()
        ortEnv.close()
    }
}
