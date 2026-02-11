package dev.wads.motoridecallconnect.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.File
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SpeechRecognizerHelper(private val context: Context, private val listener: SpeechRecognitionListener) {

    private var speechRecognizer: SpeechRecognizer? = null
    private val whisperLib = WhisperLib()
    var isUsingWhisper = false
        private set

    interface SpeechRecognitionListener {
        fun onPartialResults(results: String)
        fun onFinalResults(results: String)
        fun onError(error: String)
        fun onModelDownloadStarted() {}
        fun onModelDownloadProgress(progress: Int) {}
        fun onModelDownloadFinished(success: Boolean) {}
    }

    private val modelName = "whisper-large-v3-turbo-q5_0.bin"
    private val modelFile = File(context.filesDir, modelName)
    private val modelUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin?download=true"

    init {
        checkAndInitWhisper()
    }

    private fun checkAndInitWhisper() {
        if (modelFile.exists()) {
            try {
                whisperLib.initialize(context, modelName)
                isUsingWhisper = true
                Log.i("SpeechRecognizerHelper", "Whisper initialized successfully.")
            } catch (e: Exception) {
                Log.e("SpeechRecognizerHelper", "Failed to initialize Whisper", e)
                setupSystemRecognizer()
            }
        } else {
            Log.w("SpeechRecognizerHelper", "Whisper model not found. System STT will be used until model is downloaded.")
            setupSystemRecognizer()
        }
    }

    private fun setupSystemRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(createRecognitionListener())
        } else {
            listener.onError("Speech recognition not available.")
        }
    }

    suspend fun downloadModelIfNeeded() = withContext(Dispatchers.IO) {
        if (modelFile.exists()) return@withContext

        try {
            listener.onModelDownloadStarted()
            Log.i("SpeechRecognizerHelper", "Starting model download...")
            
            val url = URL(modelUrl)
            val connection = url.openConnection()
            connection.connect()
            
            val fileLength = connection.contentLength
            val input = connection.getInputStream()
            val output = modelFile.outputStream()
            
            val data = ByteArray(8192)
            var total: Long = 0
            var count: Int
            
            while (input.read(data).also { count = it } != -1) {
                total += count.toLong()
                if (fileLength > 0) {
                    val progress = (total * 100 / fileLength).toInt()
                    listener.onModelDownloadProgress(progress)
                }
                output.write(data, 0, count)
            }
            
            output.flush()
            output.close()
            input.close()
            
            Log.i("SpeechRecognizerHelper", "Model download finished.")
            withContext(Dispatchers.Main) {
                listener.onModelDownloadFinished(true)
                checkAndInitWhisper() // Retry initialization after download
            }
        } catch (e: Exception) {
            Log.e("SpeechRecognizerHelper", "Model download failed", e)
            if (modelFile.exists()) modelFile.delete()
            withContext(Dispatchers.Main) {
                listener.onModelDownloadFinished(false)
            }
        }
    }

    fun startListening() {
        if (isUsingWhisper) {
             Log.d("SpeechRecognizerHelper", "Whisper is ready to process audio chunks.")
             return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        speechRecognizer?.startListening(intent)
        Log.d("SpeechRecognizerHelper", "Started listening...")
    }

    fun stopListening() {
        if (!isUsingWhisper) {
            speechRecognizer?.stopListening()
        }
        Log.d("SpeechRecognizerHelper", "Stopped listening.")
    }

    fun processAudio(data: ByteArray) {
        if (isUsingWhisper) {
            // Convert PCM 16-bit to Float -1.0 to 1.0 (expected by whisper)
            val floatData = FloatArray(data.size / 2)
            for (i in floatData.indices) {
                val sample = ((data[i * 2 + 1].toInt() shl 8) or (data[i * 2].toInt() and 0xFF)).toShort()
                floatData[i] = sample.toFloat() / 32768.0f
            }
            
            val result = whisperLib.transcribe(floatData)
            if (result.isNotBlank()) {
                listener.onFinalResults(result)
            }
        }
    }

    fun destroy() {
        speechRecognizer?.destroy()
        whisperLib.release()
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No match"
                    else -> "An unknown error occurred"
                }
                listener.onError(errorMessage)
                Log.e("SpeechRecognizerHelper", "onError: $errorMessage")
            }

            override fun onResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0)?.let {
                    listener.onFinalResults(it)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0)?.let {
                    listener.onPartialResults(it)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }
}