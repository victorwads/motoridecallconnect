package dev.wads.motoridecallconnect.stt

import android.content.Context
import android.util.Log
import com.argmaxinc.whisperkit.WhisperKit
import com.argmaxinc.whisperkit.TranscriptionResult
import com.argmaxinc.whisperkit.ExperimentalWhisperKit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@OptIn(ExperimentalWhisperKit::class)
class WhisperKitWrapper(private val context: Context) {
    companion object {
        private const val TAG = "WhisperKitWrapper"
    }

    private var whisperKit: WhisperKit? = null
    private var isInitialized = false
    
    // Used to bridge the callback to the suspend function
    private var currentDeferred: CompletableDeferred<String>? = null

    private val textOutputCallback = object : WhisperKit.TextOutputCallback {
        override fun onTextOutput(what: Int, result: TranscriptionResult) {
            if (what == WhisperKit.TextOutputCallback.MSG_TEXT_OUT) {
                // If we have a pending request, complete it
                currentDeferred?.complete(result.text)
            }
        }
    }

    suspend fun initialize(modelId: String): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized && whisperKit != null) return@withContext true

        try {
            Log.d(TAG, "Initializing WhisperKit with model: $modelId")

            whisperKit = WhisperKit.Builder()
                .setApplicationContext(context.applicationContext)
                .setModel(modelId)
                .setCallback(textOutputCallback)
                .build()

            // Some versions require separate init or load
            whisperKit?.loadModel()

            isInitialized = true
            Log.i(TAG, "WhisperKit initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WhisperKit", e)
            isInitialized = false
            false
        }
    }

    suspend fun transcribe(audioData: ByteArray): String = withContext(Dispatchers.IO) {
        if (!isInitialized || whisperKit == null) {
            Log.e(TAG, "WhisperKit not initialized")
            return@withContext ""
        }

        // Create a new deferred for this request
        val deferred = CompletableDeferred<String>()
        currentDeferred = deferred

        try {
            whisperKit?.transcribe(audioData)
            // Wait for the callback to complete the deferred
            deferred.await()
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            ""
        } finally {
            currentDeferred = null
        }
    }

    fun release() {
        try {
            whisperKit = null // No distinct close/release on some versions?
            isInitialized = false
            currentDeferred?.cancel()
            currentDeferred = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WhisperKit", e)
        }
    }
}
