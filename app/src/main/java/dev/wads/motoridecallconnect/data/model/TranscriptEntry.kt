package dev.wads.motoridecallconnect.data.model

enum class TranscriptStatus {
    PROCESSING,
    SUCCESS,
    ERROR
}

data class TranscriptEntry(
    val id: String,
    val tripId: String,
    val authorId: String,
    val authorName: String,
    val text: String,
    val timestamp: Long,
    val status: TranscriptStatus,
    val errorMessage: String? = null
)
