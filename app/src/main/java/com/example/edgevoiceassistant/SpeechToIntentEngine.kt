package com.example.edgevoiceassistant

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import kotlin.math.exp

data class PredictionResult(
    val commandId: Int,
    val commandName: String,
    val confidence: Float
)

class SpeechToIntentEngine(context: Context) {
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val ortSession: OrtSession
    val commands: List<String>

    init {
        val modelBytes = context.assets.open("speech_to_intent_int8.onnx").readBytes()
        val sessionOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
        }
        ortSession = ortEnv.createSession(modelBytes, sessionOptions)

        val outputType = ortSession.outputTypeInfo.values.firstOrNull()
        val numClasses = try {
            val tensorInfo = outputType as? ai.onnxruntime.TensorInfo
            tensorInfo?.shape?.getOrNull(1)?.toInt() ?: 6
        } catch (_: Exception) {
            6
        }

        commands = if (numClasses >= 7) {
            listOf("hey", "on", "off", "up", "down", "stop", "go")
        } else {
            listOf("on", "off", "up", "down", "stop", "go")
        }
    }

    suspend fun predict(audioBuffer: FloatArray): PredictionResult = withContext(Dispatchers.Default) {
        val inputShape = longArrayOf(1, 16000)
        val floatBuffer = FloatBuffer.wrap(audioBuffer)
        val inputTensor = OnnxTensor.createTensor(ortEnv, floatBuffer, inputShape)

        inputTensor.use { tensor ->
            val results = ortSession.run(mapOf("audio_input" to tensor))
            results.use { outputMap ->
                @Suppress("UNCHECKED_CAST")
                val logits = (outputMap[0].value as Array<FloatArray>)[0]

                var maxLogit = Float.NEGATIVE_INFINITY
                for (l in logits) {
                    if (l > maxLogit) maxLogit = l
                }

                var sumExp = 0.0f
                val exps = FloatArray(logits.size)
                for (i in logits.indices) {
                    exps[i] = exp(logits[i] - maxLogit)
                    sumExp += exps[i]
                }

                var maxIdx = 0
                var maxProb = 0.0f
                for (i in exps.indices) {
                    val prob = exps[i] / sumExp
                    if (prob > maxProb) {
                        maxProb = prob
                        maxIdx = i
                    }
                }

                val recognizedCommand = commands.getOrElse(maxIdx) { "unknown" }
                PredictionResult(maxIdx, recognizedCommand, maxProb)
            }
        }
    }

    fun close() {
        ortSession.close()
        ortEnv.close()
    }
}
