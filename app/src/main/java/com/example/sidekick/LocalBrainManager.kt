package com.example.sidekick

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

class LocalBrainManager(val context: Context) {
    private val modelFileName = "phi2.bin"
    private val modelFile = File(context.filesDir, modelFileName)
    // Small efficient model for Android (Phi-2 is ~2.2GB in 4-bit)
    private val modelUrl = "https://huggingface.co/google/gemma-2b-it-gpu-int4/resolve/main/gemma-2b-it-gpu-int4.bin"

    private var llmInference: LlmInference? = null
    private var session: LlmInferenceSession? = null

    fun isModelDownloaded(): Boolean = modelFile.exists()

    suspend fun downloadModel(onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        try {
            val url = URL(modelUrl)
            val connection = url.openConnection()
            connection.connect()
            val fileLength = connection.contentLength
            val input = connection.getInputStream()
            val output = modelFile.outputStream()
            val data = ByteArray(1024 * 64)
            var total = 0L
            var count: Int
            while (input.read(data).also { count = it } != -1) {
                total += count
                onProgress(total.toFloat() / fileLength)
                output.write(data, 0, count)
            }
            output.flush()
            output.close()
            input.close()
        } catch (e: Exception) {
            Log.e("SIDEKICK", "Neural download failed", e)
        }
    }

    fun initializeLlm() {
        if (!isModelDownloaded()) return
        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(512)
                .build()
            llmInference = LlmInference.createFromOptions(context, options)

            val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(40)
                .setTemperature(0.8f)
                .build()
            session = LlmInferenceSession.createFromOptions(llmInference!!, sessionOptions)
        } catch (e: Exception) {
            Log.e("SIDEKICK", "LLM Init failed", e)
        }
    }

    suspend fun runLocalInference(prompt: String): String = withContext(Dispatchers.Default) {
        if (llmInference == null) initializeLlm()
        try {
            session?.addQueryChunk(prompt)
            session?.generateResponse() ?: "Brain sectors unreachable, Stevie. External link required."
        } catch (e: Exception) {
            "Neural glitch in the local sector: ${e.message}"
        }
    }
}
