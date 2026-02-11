package dev.wads.motoridecallconnect.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import dev.wads.motoridecallconnect.data.model.Trip
import dev.wads.motoridecallconnect.data.model.TranscriptLine
import kotlinx.coroutines.flow.Flow

data class TripWithTranscripts(
    @Embedded
    val trip: Trip,
    @Relation(
        parentColumn = "id",
        entityColumn = "tripId"
    )
    val transcripts: List<TranscriptLine>
)

@Dao
interface TripDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrip(trip: Trip): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTranscriptLine(transcriptLine: TranscriptLine)

    @Query("SELECT * FROM trips ORDER BY startTime DESC")
    fun getAllTrips(): Flow<List<Trip>>

    @Query("SELECT EXISTS(SELECT 1 FROM transcript_lines WHERE tripId = :tripId LIMIT 1)")
    fun hasTranscriptForTrip(tripId: String): Flow<Boolean>

    @Query("DELETE FROM trips WHERE id = :tripId")
    suspend fun deleteTripById(tripId: String)

    @Transaction
    @Query("SELECT * FROM trips WHERE id = :tripId")
    fun getTripWithTranscripts(tripId: String): Flow<TripWithTranscripts>
}
