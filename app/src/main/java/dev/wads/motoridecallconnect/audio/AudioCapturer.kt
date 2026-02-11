package dev.wads.motoridecallconnect.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AudioCapturer(private val listener: AudioCapturerListener) {

    private var audioRecord: AudioRecord? = null
    private var isCapturing = false
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var minBufferSize = 0

    interface AudioCapturerListener {
        fun onAudioData(data: ByteArray, size: Int)
    }

    companion object {
        private const val TAG = "AudioCapturer"
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    @SuppressLint("MissingPermission")
    fun startCapture() {
        minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid AudioRecord parameters.")
            return
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            minBufferSize * 2
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed.")
            return
        }

        audioRecord?.startRecording()
        isCapturing = true
        Log.d(TAG, "Audio capture started.")

        executor.execute { readAudioData() }
    }

    fun stopCapture() {
        isCapturing = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        executor.shutdown()
        Log.d(TAG, "Audio capture stopped.")
    }

    private fun readAudioData() {
        val buffer = ByteArray(minBufferSize)
        while (isCapturing) {
            val readResult = audioRecord?.read(buffer, 0, buffer.size) ?: 0
            if (readResult > 0) {
                listener.onAudioData(buffer, readResult)
            }
        }
    }
}