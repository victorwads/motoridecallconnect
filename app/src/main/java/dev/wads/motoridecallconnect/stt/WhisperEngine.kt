package dev.wads.motoridecallconnect.stt

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class WhisperEngine(private val context: Context) {
    private val TAG = "WhisperEngine"
    private var modelPath: String? = null

    companion object {
        init {
            try {
                // Load dependencies first if needed
                // System.loadLibrary("ggml") // If ggml is shared
                // System.loadLibrary("whisper") // If whisper is shared
                System.loadLibrary("appnative")
                Log.d("WhisperEngine", "Native library appnative loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("WhisperEngine", "Failed to load native library appnative", e)
            }
        }
    }

    external fun init(modelPath: String): Long
    external fun transcribe(wavPath: String): String
    external fun transcribeBuffer(buffer: FloatArray): String
    external fun free()

    fun initialize(modelPath: String): Boolean {
        this.modelPath = modelPath
        Log.d(TAG, "Initializing with model: $modelPath")
        val result = init(modelPath)
        return if (result != 0L) {
            Log.d(TAG, "Whisper initialized successfully. Context: $result")
            true
        } else {
            Log.e(TAG, "Failed to initialize Whisper.")
            false
        }
    }

    fun transcribeFile(file: File): String {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "Starting transcription of ${file.absolutePath}")
        val result = transcribe(file.absolutePath)
        val endTime = System.currentTimeMillis()
        Log.d(TAG, "Transcription took ${endTime - startTime}ms")
        return result
    }
}
