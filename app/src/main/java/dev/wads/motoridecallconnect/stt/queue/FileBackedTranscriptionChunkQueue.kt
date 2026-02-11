package dev.wads.motoridecallconnect.stt.queue

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

class FileBackedTranscriptionChunkQueue(context: Context) : TranscriptionChunkQueue {

    companion object {
        private const val TAG = "TranscriptionQueue"
        private const val QUEUE_DIR_NAME = "transcription_queue"
        private const val META_EXTENSION = "json"
        private const val AUDIO_EXTENSION = "pcm"

        private const val KEY_ID = "id"
        private const val KEY_TRIP_ID = "tripId"
        private const val KEY_HOST_UID = "hostUid"
        private const val KEY_TRIP_PATH = "tripPath"
        private const val KEY_CREATED_AT = "createdAtMs"
        private const val KEY_DURATION_MS = "durationMs"
        private const val KEY_STATUS = "status"
        private const val KEY_ATTEMPTS = "attempts"
        private const val KEY_AUDIO_PATH = "audioFilePath"
        private const val KEY_FAILURE_REASON = "failureReason"
    }

    private val queueDir: File = File(context.filesDir, QUEUE_DIR_NAME).apply {
        mkdirs()
    }

    private val lock = Any()
    private val items = mutableListOf<QueuedTranscriptionChunk>()

    init {
        loadFromDisk()
    }

    override fun enqueue(
        chunk: ByteArray,
        tripId: String,
        hostUid: String?,
        tripPath: String?,
        createdAtMs: Long,
        durationMs: Long
    ): QueuedTranscriptionChunk? {
        if (chunk.isEmpty()) {
            return null
        }
        if (tripId.isBlank()) {
            Log.w(TAG, "Dropping queue enqueue request with blank tripId")
            return null
        }

        synchronized(lock) {
            val id = UUID.randomUUID().toString()
            val audioFile = audioFileFor(id)
            val metaFile = metaFileFor(id)

            return try {
                audioFile.outputStream().use { output ->
                    output.write(chunk)
                    output.flush()
                }

                val queued = QueuedTranscriptionChunk(
                    id = id,
                    tripId = tripId,
                    hostUid = hostUid,
                    tripPath = tripPath,
                    createdAtMs = createdAtMs,
                    durationMs = durationMs,
                    status = TranscriptionChunkStatus.PENDING,
                    attempts = 0,
                    audioFilePath = audioFile.absolutePath,
                    failureReason = null
                )
                writeMetadata(metaFile, queued)
                items.add(queued)
                sortItemsLocked()
                queued
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to persist queued chunk", t)
                runCatching { audioFile.delete() }
                runCatching { metaFile.delete() }
                null
            }
        }
    }

    override fun pollNextPending(): QueuedTranscriptionChunk? {
        synchronized(lock) {
            val index = items.indexOfFirst { it.status == TranscriptionChunkStatus.PENDING }
            if (index < 0) {
                return null
            }

            val current = items[index]
            val updated = current.copy(
                status = TranscriptionChunkStatus.PROCESSING,
                attempts = current.attempts + 1,
                failureReason = null
            )
            items[index] = updated
            writeMetadata(metaFileFor(updated.id), updated)
            return updated
        }
    }

    override fun readAudioBytes(chunk: QueuedTranscriptionChunk): ByteArray? {
        synchronized(lock) {
            val file = File(chunk.audioFilePath)
            if (!file.exists()) {
                return null
            }
            return runCatching { file.readBytes() }
                .onFailure { error ->
                    Log.e(TAG, "Failed reading queued audio chunk id=${chunk.id}", error)
                }
                .getOrNull()
        }
    }

    override fun markSucceeded(chunkId: String) {
        synchronized(lock) {
            val index = items.indexOfFirst { it.id == chunkId }
            if (index < 0) {
                return
            }

            val item = items.removeAt(index)
            deleteFilesFor(item.id)
        }
    }

    override fun markFailed(chunkId: String, reason: String) {
        synchronized(lock) {
            val index = items.indexOfFirst { it.id == chunkId }
            if (index < 0) {
                return
            }

            val current = items[index]
            val updated = current.copy(
                status = TranscriptionChunkStatus.FAILED,
                failureReason = reason.ifBlank { "Unknown STT failure" }
            )
            items[index] = updated
            writeMetadata(metaFileFor(updated.id), updated)
        }
    }

    override fun snapshot(): TranscriptionQueueSnapshot {
        synchronized(lock) {
            sortItemsLocked()
            val pendingCount = items.count { it.status == TranscriptionChunkStatus.PENDING }
            val processingCount = items.count { it.status == TranscriptionChunkStatus.PROCESSING }
            val failedCount = items.count { it.status == TranscriptionChunkStatus.FAILED }
            return TranscriptionQueueSnapshot(
                pendingCount = pendingCount,
                processingCount = processingCount,
                failedCount = failedCount,
                items = items.toList()
            )
        }
    }

    private fun loadFromDisk() {
        synchronized(lock) {
            items.clear()
            val metaFiles = queueDir.listFiles { file ->
                file.isFile && file.extension.equals(META_EXTENSION, ignoreCase = true)
            }?.toList().orEmpty()

            metaFiles.forEach { metaFile ->
                val parsed = runCatching { readMetadata(metaFile) }
                    .onFailure { error ->
                        Log.w(TAG, "Failed reading queue metadata file=${metaFile.absolutePath}", error)
                    }
                    .getOrNull()
                    ?: run {
                        metaFile.delete()
                        return@forEach
                    }

                val audioFile = File(parsed.audioFilePath)
                if (!audioFile.exists()) {
                    Log.w(TAG, "Removing orphan queue metadata id=${parsed.id} without audio file")
                    metaFile.delete()
                    return@forEach
                }

                val normalized = if (parsed.status == TranscriptionChunkStatus.PROCESSING) {
                    parsed.copy(status = TranscriptionChunkStatus.PENDING)
                } else {
                    parsed
                }

                if (normalized != parsed) {
                    writeMetadata(metaFile, normalized)
                }
                items.add(normalized)
            }

            sortItemsLocked()
            Log.i(TAG, "Loaded queued transcription items from disk. count=${items.size}")
        }
    }

    private fun sortItemsLocked() {
        items.sortWith(compareBy<QueuedTranscriptionChunk> { it.createdAtMs }.thenBy { it.id })
    }

    private fun readMetadata(file: File): QueuedTranscriptionChunk {
        val raw = file.readText(Charsets.UTF_8)
        val json = JSONObject(raw)
        val statusName = json.optString(KEY_STATUS, TranscriptionChunkStatus.PENDING.name)

        return QueuedTranscriptionChunk(
            id = json.getString(KEY_ID),
            tripId = json.getString(KEY_TRIP_ID),
            hostUid = json.optString(KEY_HOST_UID).takeIf { it.isNotBlank() },
            tripPath = json.optString(KEY_TRIP_PATH).takeIf { it.isNotBlank() },
            createdAtMs = json.optLong(KEY_CREATED_AT, System.currentTimeMillis()),
            durationMs = json.optLong(KEY_DURATION_MS, 0L),
            status = runCatching { TranscriptionChunkStatus.valueOf(statusName) }
                .getOrElse { TranscriptionChunkStatus.PENDING },
            attempts = json.optInt(KEY_ATTEMPTS, 0),
            audioFilePath = json.getString(KEY_AUDIO_PATH),
            failureReason = json.optString(KEY_FAILURE_REASON).takeIf { it.isNotBlank() }
        )
    }

    private fun writeMetadata(file: File, item: QueuedTranscriptionChunk) {
        try {
            val json = JSONObject().apply {
                put(KEY_ID, item.id)
                put(KEY_TRIP_ID, item.tripId)
                put(KEY_HOST_UID, item.hostUid)
                put(KEY_TRIP_PATH, item.tripPath)
                put(KEY_CREATED_AT, item.createdAtMs)
                put(KEY_DURATION_MS, item.durationMs)
                put(KEY_STATUS, item.status.name)
                put(KEY_ATTEMPTS, item.attempts)
                put(KEY_AUDIO_PATH, item.audioFilePath)
                put(KEY_FAILURE_REASON, item.failureReason)
            }
            file.writeText(json.toString(), Charsets.UTF_8)
        } catch (error: IOException) {
            Log.e(TAG, "Failed writing queue metadata for id=${item.id}", error)
        }
    }

    private fun deleteFilesFor(id: String) {
        runCatching { audioFileFor(id).delete() }
            .onFailure { error -> Log.w(TAG, "Failed to delete audio file for queued chunk id=$id", error) }
        runCatching { metaFileFor(id).delete() }
            .onFailure { error -> Log.w(TAG, "Failed to delete metadata for queued chunk id=$id", error) }
    }

    private fun audioFileFor(id: String): File {
        return File(queueDir, "$id.$AUDIO_EXTENSION")
    }

    private fun metaFileFor(id: String): File {
        return File(queueDir, "$id.$META_EXTENSION")
    }
}
