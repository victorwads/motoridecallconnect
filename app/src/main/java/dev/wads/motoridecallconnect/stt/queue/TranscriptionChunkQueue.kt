package dev.wads.motoridecallconnect.stt.queue

enum class TranscriptionChunkStatus {
    PENDING,
    PROCESSING,
    FAILED,
    SUCCESS
}

data class QueuedTranscriptionChunk(
    val id: String,
    val tripId: String,
    val hostUid: String?,
    val tripPath: String?,
    val createdAtMs: Long,
    val durationMs: Long,
    val status: TranscriptionChunkStatus,
    val attempts: Int,
    val audioFilePath: String,
    val failureReason: String? = null
)

data class TranscriptionQueueSnapshot(
    val pendingCount: Int,
    val processingCount: Int,
    val failedCount: Int,
    val items: List<QueuedTranscriptionChunk>
) {
    val totalCount: Int
        get() = pendingCount + processingCount + failedCount
}

interface TranscriptionChunkQueue {
    fun enqueue(
        chunk: ByteArray,
        tripId: String,
        hostUid: String?,
        tripPath: String?,
        createdAtMs: Long,
        durationMs: Long
    ): QueuedTranscriptionChunk?

    fun pollNextPending(): QueuedTranscriptionChunk?

    fun readAudioBytes(chunk: QueuedTranscriptionChunk): ByteArray?

    fun markSucceeded(chunkId: String)

    fun markFailed(chunkId: String, reason: String)

    fun markRetry(chunkId: String)

    /**
     * Resets all PROCESSING and FAILED items back to PENDING.
     * Used when the STT engine changes and all items need to be re-processed.
     */
    fun resetAllToPending()

    fun snapshot(): TranscriptionQueueSnapshot
}
