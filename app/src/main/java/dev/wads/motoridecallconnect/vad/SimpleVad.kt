package dev.wads.motoridecallconnect.vad

import kotlin.math.sqrt

class SimpleVad(private val threshold: Short = 500) {

    fun isSpeech(audioData: ByteArray): Boolean {
        // A simple VAD based on Root Mean Square (RMS) energy
        var sumOfSquares = 0.0
        for (i in audioData.indices step 2) {
            val sample = (audioData[i+1].toInt() shl 8 or audioData[i].toInt()).toShort()
            sumOfSquares += (sample * sample).toDouble()
        }
        val rms = sqrt(sumOfSquares / (audioData.size / 2))
        return rms > threshold
    }
}