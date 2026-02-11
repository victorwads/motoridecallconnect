package dev.wads.motoridecallconnect.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "transcript_lines",
    foreignKeys = [ForeignKey(
        entity = Trip::class,
        parentColumns = ["id"],
        childColumns = ["tripId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [androidx.room.Index("tripId")]
)
data class TranscriptLine(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val tripId: String = "",
    val authorId: String = "", // Firestore UID of the author
    val authorName: String = "", // Display name for UI convenience
    val text: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val isPartial: Boolean = false
) {
    // Required by Firestore object mapper for safe schema evolution.
    constructor() : this(0, "", "", "", "", System.currentTimeMillis(), false)
}
