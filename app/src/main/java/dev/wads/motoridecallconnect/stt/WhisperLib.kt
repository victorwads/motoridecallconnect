package dev.wads.motoridecallconnect.stt

import android.content.Context
import android.util.Log
import java.io.File

class WhisperLib {
    companion object {
        init {
            try {
                System.loadLibrary("whisper")
                Log.i("WhisperLib", "Native library loaded.")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("WhisperLib", "Native library not found. Whisper will not work.")
            }
        }
    }

    external fun initModel(modelPath: String): Long
    external fun freeContext(context: Long)
    external fun fullTranscribe(context: Long, audioData: FloatArray): String
    external fun getSystemInfo(): String

    private var whisperContext: Long = 0

    fun initialize(context: Context, modelName: String) {
        val modelFile = File(context.filesDir, modelName)
        if (modelFile.exists()) {
            whisperContext = initModel(modelFile.absolutePath)
            Log.i("WhisperLib", "System Info: ${getSystemInfo()}")
            checkHardwareUsage()
        } else {
            Log.e("WhisperLib", "Model file not found at ${modelFile.absolutePath}")
        }
    }

    private fun checkHardwareUsage() {
        val info = getSystemInfo()
        val hardware = when {
            info.contains("CANN") || info.contains("NPU") -> "🚀 NPU (Aceleração de Rede Neural)"
            info.contains("VULKAN") || info.contains("GPU") || info.contains("METAL") -> "⚡ GPU (Aceleração Gráfica)"
            info.contains("NEON") || info.contains("AVX") -> "💻 CPU (Otimizado com NEON/AVX)"
            else -> "⚠️ CPU (Sem otimizações detectadas)"
        }
        
        Log.i("WhisperLib", "========================================")
        Log.i("WhisperLib", "MOTOR DE TRANSCRIÇÃO: $hardware")
        Log.i("WhisperLib", "SYSTEM INFO: $info")
        Log.i("WhisperLib", "========================================")
    }

    fun transcribe(audioData: FloatArray): String {
        if (whisperContext == 0L) return ""
        return fullTranscribe(whisperContext, audioData)
    }

    fun release() {
        if (whisperContext != 0L) {
            freeContext(whisperContext)
            whisperContext = 0L
        }
    }
}
