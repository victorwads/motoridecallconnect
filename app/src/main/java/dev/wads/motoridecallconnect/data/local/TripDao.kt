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

    @Transaction
    @Query("SELECT * FROM trips WHERE id = :tripId")
    fun getTripWithTranscripts(tripId: Long): Flow<TripWithTranscripts>
}