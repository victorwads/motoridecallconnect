package dev.wads.motoridecallconnect.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import java.io.File
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SpeechRecognizerHelper(private val context: Context, private val listener: SpeechRecognitionListener) {

    companion object {
        private const val TAG = "SpeechRecognizerHelper"
        private const val CAPTURE_SAMPLE_RATE_HZ = 48_000
        private const val WHISPER_SAMPLE_RATE_HZ = 16_000
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private val whisperLib = WhisperLib(context)
    var isUsingWhisper = false
        private set
    private var whisperChunkCount = 0L
    private var emptyWhisperResultCount = 0L

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
    private val downsampleFactor = CAPTURE_SAMPLE_RATE_HZ / WHISPER_SAMPLE_RATE_HZ

    init {
        checkAndInitWhisper()
    }

    private fun checkAndInitWhisper() {
        if (modelFile.exists()) {
            Log.i(TAG, "Model found at: ${modelFile.absolutePath}")
            try {
                if (whisperLib.initialize(modelFile.absolutePath)) {
                    isUsingWhisper = true
                    Log.i(TAG, "Whisper initialized successfully.")
                } else {
                    isUsingWhisper = false
                    Log.e(TAG, "Failed to initialize Whisper engine logic.")
                    setupSystemRecognizer()
                }
            } catch (e: Throwable) {
                isUsingWhisper = false
                Log.e(TAG, "Failed to initialize Whisper", e)
                Toast.makeText(context, "Erro ao inicializar Whisper: ${e.message}", Toast.LENGTH_LONG).show()
                setupSystemRecognizer()
            }
        } else {
            isUsingWhisper = false
            Log.w(TAG, "Whisper model not found. System STT will be used until model is downloaded.")
            setupSystemRecognizer()
        }
    }

    private fun setupSystemRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(createRecognitionListener())
            Log.i(TAG, "System SpeechRecognizer initialized.")
        } else {
            listener.onError("Speech recognition not available.")
            Log.e(TAG, "Speech recognition not available on this device.")
        }
    }

    suspend fun downloadModelIfNeeded() = withContext(Dispatchers.IO) {
        if (modelFile.exists()) return@withContext

        try {
            listener.onModelDownloadStarted()
            Log.i(TAG, "Starting model download...")
            
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
            
            Log.i(TAG, "Model download finished.")
            withContext(Dispatchers.Main) {
                listener.onModelDownloadFinished(true)
                checkAndInitWhisper() // Retry initialization after download
            }
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed", e)
            if (modelFile.exists()) modelFile.delete()
            withContext(Dispatchers.Main) {
                listener.onModelDownloadFinished(false)
            }
        }
    }

    fun startListening() {
        if (isUsingWhisper) {
             Log.i(TAG, "Using Whisper mode. Waiting for PCM chunks from AudioService.")
             return
        }
        Log.i(TAG, "Using Android system SpeechRecognizer mode.")
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        speechRecognizer?.startListening(intent)
        Log.d(TAG, "Started listening...")
    }

    fun stopListening() {
        if (!isUsingWhisper) {
            speechRecognizer?.stopListening()
        }
        Log.d(TAG, "Stopped listening.")
    }

    fun processAudio(data: ByteArray) {
        if (!isUsingWhisper) {
            Log.v(TAG, "Ignoring audio chunk because Whisper mode is disabled.")
            return
        }

        if (data.size < 2) {
            Log.w(TAG, "Ignoring tiny audio chunk. bytes=${data.size}")
            return
        }

        val sampleCount48k = data.size / 2
        val pcm48k = FloatArray(sampleCount48k)
        var peak = 0f
        for (i in pcm48k.indices) {
            val sample = ((data[i * 2 + 1].toInt() shl 8) or (data[i * 2].toInt() and 0xFF)).toShort()
            val normalized = sample.toFloat() / 32768.0f
            pcm48k[i] = normalized
            val absSample = if (normalized >= 0f) normalized else -normalized
            if (absSample > peak) {
                peak = absSample
            }
        }

        val pcm16k = downsampleToWhisperRate(pcm48k)
        whisperChunkCount++
        val startMs = System.currentTimeMillis()
        val result = whisperLib.transcribeBuffer(pcm16k)
        val elapsedMs = System.currentTimeMillis() - startMs

        if (result.isBlank()) {
            emptyWhisperResultCount++
            Log.d(
                TAG,
                "Whisper chunk #$whisperChunkCount produced empty text. " +
                    "bytes=${data.size}, samples48k=$sampleCount48k, samples16k=${pcm16k.size}, " +
                    "peak=${"%.3f".format(peak)}, elapsedMs=$elapsedMs, emptyCount=$emptyWhisperResultCount"
            )
            return
        }

        Log.i(
            TAG,
            "Whisper chunk #$whisperChunkCount textLen=${result.length}, " +
                "samples16k=${pcm16k.size}, peak=${"%.3f".format(peak)}, elapsedMs=$elapsedMs"
        )
        listener.onFinalResults(result)
    }

    private fun downsampleToWhisperRate(input: FloatArray): FloatArray {
        if (input.isEmpty() || downsampleFactor <= 1) {
            return input
        }

        val outputSize = input.size / downsampleFactor
        if (outputSize <= 0) {
            return input
        }

        val output = FloatArray(outputSize)
        var inputIndex = 0
        for (i in 0 until outputSize) {
            var sum = 0f
            for (j in 0 until downsampleFactor) {
                sum += input[inputIndex + j]
            }
            output[i] = sum / downsampleFactor
            inputIndex += downsampleFactor
        }
        return output
    }

    fun destroy() {
        speechRecognizer?.destroy()
        whisperLib.free()
        Log.i(TAG, "SpeechRecognizerHelper destroyed.")
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
                Log.e(TAG, "onError: $errorMessage")
            }

            override fun onResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0)?.let {
                    Log.d(TAG, "System recognizer final result len=${it.length}")
                    listener.onFinalResults(it)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0)?.let {
                    Log.v(TAG, "System recognizer partial result len=${it.length}")
                    listener.onPartialResults(it)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }
}
