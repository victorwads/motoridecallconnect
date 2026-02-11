package dev.wads.motoridecallconnect.vad

import kotlin.math.sqrt

class SimpleVad(private val threshold: Short = 500) {

    fun isSpeech(audioData: ByteArray): Boolean {
        // A simple VAD based on Root Mean Square (RMS) energy
        var sumOfSquares = 0.0
        val usableSize = audioData.size - (audioData.size % 2)
        for (i in 0 until usableSize step 2) {
            val sample = ((audioData[i + 1].toInt() shl 8) or (audioData[i].toInt() and 0xFF)).toShort()
            sumOfSquares += (sample * sample).toDouble()
        }
        if (usableSize == 0) return false
        val rms = sqrt(sumOfSquares / (usableSize / 2))
        return rms > threshold
    }
}
