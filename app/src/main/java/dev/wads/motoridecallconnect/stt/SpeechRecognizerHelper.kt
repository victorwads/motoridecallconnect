package dev.wads.motoridecallconnect.stt

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import java.io.File
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlinx.coroutines.launch

class SpeechRecognizerHelper(
    private val context: Context,
    private val listener: SpeechRecognitionListener,
    initialModelId: String = WhisperModelCatalog.defaultOption.id,
    initialEngine: SttEngine = SttEngine.WHISPER,
    initialNativeLanguageTag: String = NativeSpeechLanguageCatalog.defaultOption.tag
) {

    companion object {
        private const val TAG = "SpeechRecognizerHelper"
        private const val CAPTURE_SAMPLE_RATE_HZ = 48_000
        private const val WHISPER_SAMPLE_RATE_HZ = 16_000
        private const val MIN_WHISPER_SAMPLES = 16_000 // 1 second at 16 kHz
        private const val MIN_SYSTEM_SAMPLES = 24_000 // 0.5 second at 48 kHz
        private const val SYSTEM_STT_TIMEOUT_SECONDS = 18L
        private const val LEGACY_RESTART_DELAY_MS = 300L

        private const val EXTRA_AUDIO_SOURCE = "android.speech.extra.AUDIO_SOURCE"
        private const val EXTRA_AUDIO_SOURCE_CHANNEL_COUNT = "android.speech.extra.AUDIO_SOURCE_CHANNEL_COUNT"
        private const val EXTRA_AUDIO_SOURCE_ENCODING = "android.speech.extra.AUDIO_SOURCE_ENCODING"
        private const val EXTRA_AUDIO_SOURCE_SAMPLING_RATE = "android.speech.extra.AUDIO_SOURCE_SAMPLING_RATE"
    }

    private val whisperLib = WhisperLib(context)
    private val whisperLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)

    private var whisperKitWrapper: WhisperKitWrapper? = null
    private var isUsingWhisperKit = false
    private var isWhisperKitInitializing = false

    private var selectedModel: WhisperModelOption =
        WhisperModelCatalog.findById(initialModelId) ?: WhisperModelCatalog.defaultOption
    private var selectedEngine: SttEngine = initialEngine
    private var selectedNativeLanguageTag: String = NativeSpeechLanguageCatalog.normalizeTag(initialNativeLanguageTag)

    var isUsingWhisper = false
        private set

    private var isListening = false
    private var isDestroyed = false
    private var whisperChunkCount = 0L
    private var systemChunkCount = 0L
    private var emptyWhisperResultCount = 0L
    private var legacyNativeRecognizer: SpeechRecognizer? = null
    private var isLegacyNativeSessionRunning = false

    data class ChunkTranscriptionResult(
        val text: String? = null,
        val error: String? = null
    )

    interface SpeechRecognitionListener {
        fun onPartialResults(results: String)
        fun onFinalResults(results: String)
        fun onError(error: String)
        fun onModelDownloadStarted() {}
        fun onModelDownloadProgress(progress: Int) {}
        fun onModelDownloadFinished(success: Boolean) {}
    }

    private val modelFile: File
        get() = File(context.filesDir, selectedModel.fileName)
    private val downsampleFactor = CAPTURE_SAMPLE_RATE_HZ / WHISPER_SAMPLE_RATE_HZ

    init {
        Log.i(
            TAG,
            "SpeechRecognizerHelper ready. engine=$selectedEngine, model=${selectedModel.id}, " +
                "nativeLanguage=$selectedNativeLanguageTag, " +
                "file=${selectedModel.fileName}, lazyInitDuringTrip=true"
        )
    }

    fun setEngine(engine: SttEngine) {
        if (selectedEngine == engine) {
            return
        }
        val previous = selectedEngine
        selectedEngine = engine

        synchronized(whisperLock) {
            if (isUsingWhisper) {
                whisperLib.free()
                isUsingWhisper = false
            }
        }

        if (previous == SttEngine.NATIVE && engine != SttEngine.NATIVE) {
            stopLegacyNativeRecognizer()
        } else if (engine == SttEngine.NATIVE && isListening && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            startLegacyNativeRecognizer()
        }

        Log.i(TAG, "STT engine changed from $previous to $selectedEngine")
    }

    fun setWhisperModel(modelId: String) {
        val newModel = WhisperModelCatalog.findById(modelId)
        if (newModel == null) {
            Log.w(TAG, "Ignoring unknown model id: $modelId")
            return
        }
        if (newModel.id == selectedModel.id) {
            return
        }

        val previousModel = selectedModel
        synchronized(whisperLock) {
            if (isUsingWhisper) {
                whisperLib.free()
                isUsingWhisper = false
            }
        }

        selectedModel = newModel
        whisperChunkCount = 0L
        systemChunkCount = 0L
        emptyWhisperResultCount = 0L
        Log.i(
            TAG,
            "Whisper model changed from ${previousModel.id} to ${selectedModel.id}. " +
                "file=${selectedModel.fileName}"
        )
    }

    fun setNativeLanguageTag(languageTag: String) {
        val normalized = NativeSpeechLanguageCatalog.normalizeTag(languageTag)
        if (selectedNativeLanguageTag == normalized) {
            return
        }
        selectedNativeLanguageTag = normalized
        Log.i(TAG, "Native STT language changed to $selectedNativeLanguageTag")
        if (selectedEngine == SttEngine.NATIVE && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            restartLegacyNativeRecognizer()
        }
    }

    private fun checkAndInitWhisper(): Boolean {
        val currentModel = selectedModel
        val currentModelFile = modelFile
        if (!currentModelFile.exists()) {
            Log.w(
                TAG,
                "Whisper model not found for selected engine. " +
                    "model=${currentModel.id}, file=${currentModelFile.absolutePath}"
            )
            return false
        }

        synchronized(whisperLock) {
            if (isDestroyed) {
                return false
            }
            if (isUsingWhisper) {
                return true
            }

            val modelSizeMb = currentModelFile.length().toDouble() / (1024.0 * 1024.0)
            Log.i(
                TAG,
                "Initializing Whisper with model=${currentModel.id}, path=${currentModelFile.absolutePath}, " +
                    "sizeMb=${"%.2f".format(modelSizeMb)}"
            )

            try {
                val initialized = whisperLib.initialize(currentModelFile.absolutePath)
                isUsingWhisper = initialized
                if (initialized) {
                    Log.i(TAG, "Whisper initialized successfully with model=${currentModel.id}.")
                } else {
                    Log.e(TAG, "Failed to initialize Whisper engine logic.")
                }
                return initialized
            } catch (e: Throwable) {
                isUsingWhisper = false
                Log.e(TAG, "Failed to initialize Whisper", e)
                Toast.makeText(context, "Erro ao inicializar Whisper: ${e.message}", Toast.LENGTH_LONG).show()
                return false
            }
        }
    }

    suspend fun downloadModelIfNeeded() = withContext(Dispatchers.IO) {
        if (selectedEngine != SttEngine.WHISPER) {
            Log.d(TAG, "Skipping Whisper model download because engine=$selectedEngine")
            return@withContext
        }

        val targetModel = selectedModel
        val targetModelFile = File(context.filesDir, targetModel.fileName)
        val targetModelUrl = targetModel.downloadUrl
        if (targetModelFile.exists()) return@withContext

        try {
            listener.onModelDownloadStarted()
            Log.i(TAG, "Starting model download... model=${targetModel.id}")

            val url = URL(targetModelUrl)
            val connection = url.openConnection()
            connection.connect()

            val fileLength = connection.contentLength
            val input = connection.getInputStream()
            val output = targetModelFile.outputStream()

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

            Log.i(TAG, "Model download finished. model=${targetModel.id}")
            withContext(Dispatchers.Main) {
                listener.onModelDownloadFinished(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed", e)
            if (targetModelFile.exists()) targetModelFile.delete()
            withContext(Dispatchers.Main) {
                listener.onModelDownloadFinished(false)
            }
        }
    }

    fun startListening() {
        isListening = true

        if (selectedEngine == SttEngine.WHISPER) {
            val whisperReady = checkAndInitWhisper()
            if (whisperReady) {
                Log.i(
                    TAG,
                    "Using Whisper mode (${selectedModel.id}) with automatic language detection. " +
                        "Waiting for PCM chunks from AudioService."
                )
                return
            }

            Log.w(
                TAG,
                "Whisper unavailable for model=${selectedModel.id}. Falling back to Android native STT chunk mode."
            )
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            startLegacyNativeRecognizer()
            return
        }

        Log.i(
            TAG,
            "Using Android native SpeechRecognizer chunk mode. engine=$selectedEngine, " +
                "language=$selectedNativeLanguageTag"
        )
    }

    fun stopListening() {
        isListening = false
        stopLegacyNativeRecognizer()
        synchronized(whisperLock) {
            if (isUsingWhisper) {
                whisperLib.free()
                isUsingWhisper = false
                Log.i(TAG, "Whisper context released.")
            }
        }
        if (isUsingWhisperKit) {
            whisperKitWrapper?.release()
            isUsingWhisperKit = false
        }
        Log.d(TAG, "Stopped listening.")
    }

    fun processAudio(data: ByteArray) {
        val result = transcribeChunkInternal(data, allowLegacyNativeFallback = true)
        if (!result.error.isNullOrBlank()) {
            listener.onError(result.error)
            return
        }
        val text = result.text.orEmpty().trim()
        if (text.isNotBlank()) {
            listener.onFinalResults(text)
        }
    }

    fun transcribeChunk(data: ByteArray): ChunkTranscriptionResult {
        return transcribeChunkInternal(data, allowLegacyNativeFallback = false)
    }

    private fun transcribeChunkInternal(
        data: ByteArray,
        allowLegacyNativeFallback: Boolean
    ): ChunkTranscriptionResult {
        if (!isListening) {
            return ChunkTranscriptionResult(error = "Speech recognizer helper is not listening.")
        }

        if (data.size < 2) {
            return ChunkTranscriptionResult(error = "Tiny audio chunk (bytes=${data.size}).")
        }

        if (selectedEngine == SttEngine.WHISPER && !isUsingWhisper) {
            if (!checkAndInitWhisper()) {
                Log.w(
                    TAG,
                    "Whisper unavailable for model=${selectedModel.id}. Falling back to native chunk STT."
                )
            }
        }

        if (selectedEngine == SttEngine.WHISPER_KIT && !isUsingWhisperKit) {
            checkAndInitWhisperKit()
        }

        val shouldUseWhisper = selectedEngine == SttEngine.WHISPER && isUsingWhisper
        if (shouldUseWhisper) {
            return processWhisperChunk(data)
        }

        if (selectedEngine == SttEngine.WHISPER_KIT) {
            return processWhisperKitChunk(data)
        }

        if (selectedEngine == SttEngine.NATIVE && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (allowLegacyNativeFallback) {
                if (!isLegacyNativeSessionRunning) {
                    startLegacyNativeRecognizer()
                }
                return ChunkTranscriptionResult()
            }
            return ChunkTranscriptionResult(
                error = "Native chunk STT requires Android 13+ for injected PCM audio source."
            )
        }

        return processNativeChunk(data)
    }

    private fun checkAndInitWhisperKit() {
        if (isUsingWhisperKit || isWhisperKitInitializing) return
        
        isWhisperKitInitializing = true
        if (whisperKitWrapper == null) {
            whisperKitWrapper = WhisperKitWrapper(context)
        }

        scope.launch {
            // Map model ID or use a default compatible with WhisperKit
            val model = if (selectedModel.id.contains("tiny")) "openai/whisper-tiny.en" else "openai/whisper-base.en" // Mapping logic needed?
            val success = whisperKitWrapper?.initialize(model) == true
            isUsingWhisperKit = success
            isWhisperKitInitializing = false
            if (success) {
                Log.i(TAG, "WhisperKit initialized successfully")
            } else {
                Log.e(TAG, "WhisperKit initialization failed")
            }
        }
    }

    private fun processWhisperKitChunk(data: ByteArray): ChunkTranscriptionResult {
        // Ensure 16kHz conversion because AudioService sends 48kHz
        val sampleCount48k = data.size / 2
        val pcm48k = FloatArray(sampleCount48k)
        for (i in pcm48k.indices) {
            val sample = ((data[i * 2 + 1].toInt() shl 8) or (data[i * 2].toInt() and 0xFF)).toShort()
            val normalized = sample.toFloat() / 32768.0f
            pcm48k[i] = normalized
        }
        val pcm16k = downsampleToWhisperRate(pcm48k)
        
        // Convert back to ByteArray PCM 16bit 16kHz for WhisperKit if needed
        // Or if WhisperKit supports floats, use that. Our wrapper takes ByteArray.
        // Let's convert float array back to PCM 16bit byte array.
        val buffer = ByteBuffer.allocate(pcm16k.size * 2)
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (sample in pcm16k) {
            val shortSample = (sample * 32767).toInt().coerceIn(-32768, 32767).toShort()
            buffer.putShort(shortSample)
        }
        val pcm16kBytes = buffer.array()

        val text = kotlinx.coroutines.runBlocking {
            whisperKitWrapper?.transcribe(pcm16kBytes)
        }
        
        if (!text.isNullOrBlank()) {
             return ChunkTranscriptionResult(text = text)
        }
        
        return ChunkTranscriptionResult()
    }

    private fun startLegacyNativeRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            listener.onError("Speech recognition not available on this device.")
            return
        }
        if (isLegacyNativeSessionRunning) {
            return
        }

        mainHandler.post {
            if (isDestroyed || !isListening || selectedEngine != SttEngine.NATIVE) {
                return@post
            }

            try {
                legacyNativeRecognizer?.cancel()
            } catch (_: Throwable) {
            }
            try {
                legacyNativeRecognizer?.destroy()
            } catch (_: Throwable) {
            }

            try {
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                var lastPartialResult: String? = null
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        val errorMessage = mapSpeechRecognizerError(error)
                        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                            val fallbackText = lastPartialResult?.trim().orEmpty()
                            if (fallbackText.isNotBlank()) {
                                listener.onFinalResults(fallbackText)
                            }
                        } else {
                            listener.onError(errorMessage)
                        }
                        restartLegacyNativeRecognizer()
                    }

                    override fun onResults(results: Bundle?) {
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                        if (!text.isNullOrBlank()) {
                            listener.onFinalResults(text)
                        } else {
                            val fallbackText = lastPartialResult?.trim().orEmpty()
                            if (fallbackText.isNotBlank()) {
                                listener.onFinalResults(fallbackText)
                            }
                        }
                        restartLegacyNativeRecognizer()
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?.let { partial ->
                                lastPartialResult = partial
                                listener.onPartialResults(partial)
                            }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                recognizer.startListening(createLegacyNativeIntent())
                legacyNativeRecognizer = recognizer
                isLegacyNativeSessionRunning = true
                Log.i(
                    TAG,
                    "Using legacy native SpeechRecognizer mode (API < 33). language=$selectedNativeLanguageTag"
                )
            } catch (t: Throwable) {
                isLegacyNativeSessionRunning = false
                listener.onError("Native recognizer start failed: ${t.message}")
            }
        }
    }

    private fun restartLegacyNativeRecognizer() {
        isLegacyNativeSessionRunning = false
        if (!isListening || isDestroyed || selectedEngine != SttEngine.NATIVE) {
            stopLegacyNativeRecognizer()
            return
        }
        mainHandler.postDelayed(
            { startLegacyNativeRecognizer() },
            LEGACY_RESTART_DELAY_MS
        )
    }

    private fun stopLegacyNativeRecognizer() {
        isLegacyNativeSessionRunning = false
        val recognizer = legacyNativeRecognizer ?: return
        legacyNativeRecognizer = null
        mainHandler.post {
            try {
                recognizer.cancel()
            } catch (_: Throwable) {
            }
            try {
                recognizer.destroy()
            } catch (_: Throwable) {
            }
        }
    }

    private fun createLegacyNativeIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            applyNativeLanguage(this)
        }
    }

    private fun processWhisperChunk(data: ByteArray): ChunkTranscriptionResult {
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
        if (pcm16k.size < MIN_WHISPER_SAMPLES) {
            Log.d(
                TAG,
                "Skipping tiny Whisper chunk. bytes=${data.size}, samples16k=${pcm16k.size}, " +
                    "required=$MIN_WHISPER_SAMPLES"
            )
            return ChunkTranscriptionResult()
        }

        whisperChunkCount++
        val startMs = System.currentTimeMillis()
        val result = synchronized(whisperLock) {
            if (!isUsingWhisper || isDestroyed || !isListening) {
                null
            } else {
                whisperLib.transcribeBuffer(pcm16k)
            }
        }
        val elapsedMs = System.currentTimeMillis() - startMs

        if (result == null) {
            Log.d(TAG, "Skipping Whisper chunk because context is unavailable.")
            return ChunkTranscriptionResult(error = "Whisper context unavailable.")
        }

        if (result.startsWith("Error:", ignoreCase = true)) {
            Log.e(
                TAG,
                "Whisper chunk #$whisperChunkCount failed. " +
                    "samples16k=${pcm16k.size}, elapsedMs=$elapsedMs, error=$result"
            )
            return ChunkTranscriptionResult(error = result)
        }

        if (result.isBlank()) {
            emptyWhisperResultCount++
            Log.d(
                TAG,
                "Whisper chunk #$whisperChunkCount produced empty text. " +
                    "bytes=${data.size}, samples48k=$sampleCount48k, samples16k=${pcm16k.size}, " +
                    "peak=${"%.3f".format(peak)}, elapsedMs=$elapsedMs, emptyCount=$emptyWhisperResultCount"
            )
            return ChunkTranscriptionResult()
        }

        Log.i(
            TAG,
            "Whisper chunk #$whisperChunkCount textLen=${result.length}, " +
                "samples16k=${pcm16k.size}, peak=${"%.3f".format(peak)}, elapsedMs=$elapsedMs, " +
                "preview=${result.take(120)}"
        )
        return ChunkTranscriptionResult(text = result)
    }

    private fun processNativeChunk(data: ByteArray): ChunkTranscriptionResult {
        val sampleCount = data.size / 2
        if (sampleCount < MIN_SYSTEM_SAMPLES) {
            Log.d(
                TAG,
                "Skipping tiny native chunk. bytes=${data.size}, samples48k=$sampleCount, " +
                    "required=$MIN_SYSTEM_SAMPLES"
            )
            return ChunkTranscriptionResult()
        }

        systemChunkCount++
        val chunkId = systemChunkCount
        val startMs = System.currentTimeMillis()
        val result = transcribeNativeChunk(data)
        val elapsedMs = System.currentTimeMillis() - startMs

        if (result.error != null) {
            Log.e(TAG, "Native STT chunk #$chunkId failed in ${elapsedMs}ms: ${result.error}")
            return ChunkTranscriptionResult(error = result.error)
        }

        val text = result.text.orEmpty().trim()
        if (text.isBlank()) {
            Log.d(TAG, "Native STT chunk #$chunkId produced empty text. elapsedMs=$elapsedMs")
            return ChunkTranscriptionResult()
        }

        Log.i(
            TAG,
            "Native STT chunk #$chunkId textLen=${text.length}, elapsedMs=$elapsedMs, preview=${text.take(120)}"
        )
        return ChunkTranscriptionResult(text = text)
    }

    private fun transcribeNativeChunk(data: ByteArray): ChunkTranscriptionResult {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return ChunkTranscriptionResult(error = "Speech recognition not available on this device.")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return ChunkTranscriptionResult(
                error = "Native chunk STT requires Android 13+ for injected PCM audio source."
            )
        }

        val startLatch = CountDownLatch(1)
        val resultLatch = CountDownLatch(1)
        val textRef = AtomicReference<String?>(null)
        val errorRef = AtomicReference<String?>(null)
        val partialRef = AtomicReference<String?>(null)

        var recognizer: SpeechRecognizer? = null
        var readPipe: ParcelFileDescriptor? = null
        var writePipe: ParcelFileDescriptor? = null

        mainHandler.post {
            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context)

                val pipe = ParcelFileDescriptor.createPipe()
                readPipe = pipe[0]
                writePipe = pipe[1]

                recognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        val errorMessage = mapSpeechRecognizerError(error)
                        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                            val fallbackText = partialRef.get()?.trim().orEmpty()
                            if (fallbackText.isNotBlank()) {
                                textRef.compareAndSet(null, fallbackText)
                            } else {
                                errorRef.compareAndSet(null, errorMessage)
                            }
                        } else {
                            errorRef.compareAndSet(null, errorMessage)
                        }
                        resultLatch.countDown()
                    }

                    override fun onResults(results: Bundle?) {
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        textRef.compareAndSet(null, text)
                        resultLatch.countDown()
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.let { partial ->
                                if (partial.isNotBlank()) {
                                    partialRef.set(partial)
                                    listener.onPartialResults(partial)
                                }
                            }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    putExtra(EXTRA_AUDIO_SOURCE, readPipe)
                    putExtra(EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(EXTRA_AUDIO_SOURCE_SAMPLING_RATE, CAPTURE_SAMPLE_RATE_HZ)
                    applyNativeLanguage(this)
                }

                recognizer?.startListening(intent)
            } catch (t: Throwable) {
                errorRef.compareAndSet(null, "Native chunk recognizer setup failed: ${t.message}")
                resultLatch.countDown()
            } finally {
                startLatch.countDown()
            }
        }

        if (!startLatch.await(2, TimeUnit.SECONDS)) {
            return ChunkTranscriptionResult(error = "Native chunk recognizer setup timeout.")
        }

        val writer = writePipe
        if (writer == null) {
            cleanupNativeRecognizer(recognizer, readPipe, writePipe)
            return ChunkTranscriptionResult(
                error = errorRef.get() ?: "Native chunk recognizer did not create audio pipe."
            )
        }

        try {
            ParcelFileDescriptor.AutoCloseOutputStream(writer).use { output ->
                output.write(data)
                output.flush()
            }
        } catch (t: Throwable) {
            cleanupNativeRecognizer(recognizer, readPipe, writePipe)
            return ChunkTranscriptionResult(error = "Failed to stream PCM chunk to native recognizer: ${t.message}")
        }

        if (!resultLatch.await(SYSTEM_STT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            cleanupNativeRecognizer(recognizer, readPipe, writePipe)
            return ChunkTranscriptionResult(error = "Native chunk recognition timeout.")
        }

        cleanupNativeRecognizer(recognizer, readPipe, writePipe)

        return ChunkTranscriptionResult(
            text = textRef.get(),
            error = errorRef.get()
        )
    }

    private fun cleanupNativeRecognizer(
        recognizer: SpeechRecognizer?,
        readPipe: ParcelFileDescriptor?,
        writePipe: ParcelFileDescriptor?
    ) {
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                recognizer?.cancel()
            } catch (_: Throwable) {
            }
            try {
                recognizer?.destroy()
            } catch (_: Throwable) {
            }
            try {
                readPipe?.close()
            } catch (_: Throwable) {
            }
            try {
                writePipe?.close()
            } catch (_: Throwable) {
            }
            latch.countDown()
        }
        latch.await(1, TimeUnit.SECONDS)
    }

    private fun mapSpeechRecognizerError(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Speech recognizer client error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Language not supported"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Language unavailable"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Recognizer server error"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Recognizer server disconnected"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many requests"
            else -> "Unknown recognizer error"
        }
    }

    private fun applyNativeLanguage(intent: Intent) {
        val languageTag = selectedNativeLanguageTag
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
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
        isListening = false
        isDestroyed = true
        stopLegacyNativeRecognizer()
        synchronized(whisperLock) {
            whisperLib.free()
            isUsingWhisper = false
        }
        Log.i(TAG, "SpeechRecognizerHelper destroyed.")
    }

}
