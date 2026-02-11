package dev.wads.motoridecallconnect.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import dev.wads.motoridecallconnect.data.model.TranscriptEntry
import dev.wads.motoridecallconnect.data.local.TripDao
import dev.wads.motoridecallconnect.data.local.TripWithTranscripts
import dev.wads.motoridecallconnect.data.model.TranscriptLine
import dev.wads.motoridecallconnect.data.model.TranscriptStatus
import dev.wads.motoridecallconnect.data.model.Trip
import dev.wads.motoridecallconnect.data.remote.FirestorePaths
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TripRepository(private val tripDaoProvider: () -> TripDao) {

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    fun getAllTrips(): Flow<List<Trip>> {
        val user = auth.currentUser
        return if (user != null) {
            callbackFlow {
                val listenerInfo = firestore.collection(FirestorePaths.ACCOUNTS).document(user.uid).collection(FirestorePaths.RIDES)
                    .orderBy("startTime", Query.Direction.DESCENDING)
                    .addSnapshotListener { value, error ->
                        if (error != null) {
                            close(error)
                            return@addSnapshotListener
                        }
                        if (value != null) {
                            val trips = value.toObjects(Trip::class.java)
                            trySend(trips)
                        }
                    }
                awaitClose { listenerInfo.remove() }
            }
        } else {
            tripDaoProvider().getAllTrips()
        }
    }

    fun observeTranscriptAvailability(tripIds: List<String>): Flow<Map<String, Boolean>> {
        val distinctTripIds = tripIds.distinct()
        if (distinctTripIds.isEmpty()) return flowOf(emptyMap())

        val user = auth.currentUser
        return if (user != null) {
            callbackFlow {
                val transcriptStatus = distinctTripIds.associateWith { false }.toMutableMap()
                val listeners = mutableListOf<ListenerRegistration>()

                trySend(transcriptStatus.toMap())

                distinctTripIds.forEach { tripId ->
                    val listener = firestore.collection(FirestorePaths.ACCOUNTS)
                        .document(user.uid)
                        .collection(FirestorePaths.RIDES)
                        .document(tripId)
                        .collection(FirestorePaths.RIDE_TRANSCRIPTS)
                        .limit(1)
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                close(error)
                                return@addSnapshotListener
                            }

                            transcriptStatus[tripId] = snapshot?.isEmpty == false
                            trySend(transcriptStatus.toMap())
                        }

                    listeners.add(listener)
                }

                awaitClose { listeners.forEach { it.remove() } }
            }
        } else {
            combine(
                distinctTripIds.map { tripId ->
                    tripDaoProvider().hasTranscriptForTrip(tripId)
                }
            ) { availability ->
                distinctTripIds
                    .mapIndexed { index, tripId -> tripId to availability[index] }
                    .toMap()
            }
        }
    }

    fun getTripWithTranscripts(tripId: String): Flow<TripWithTranscripts?> {
        val user = auth.currentUser
        return if (user != null) {
             callbackFlow {
                 val tripRef = firestore.collection(FirestorePaths.ACCOUNTS).document(user.uid).collection(FirestorePaths.RIDES).document(tripId)
                 val listener = tripRef.addSnapshotListener { snapshot, error ->
                     if (error != null) {
                         close(error)
                         return@addSnapshotListener
                     }
                     if (snapshot != null && snapshot.exists()) {
                         val trip = snapshot.toObject(Trip::class.java)
                         if (trip != null) {
                             trySend(TripWithTranscripts(trip, emptyList()))
                         }
                     } else {
                         trySend(null)
                     }
                 }
                 awaitClose { listener.remove() }
             }
        } else {
            tripDaoProvider().getTripWithTranscripts(tripId).map { it }
        }
    }

    suspend fun insertTrip(trip: Trip): String {
        val user = auth.currentUser
        return if (user != null) {
            val normalizedTrip = trip.copy(
                participants = (trip.participants + user.uid)
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
            )
            val docRef = firestore.collection(FirestorePaths.ACCOUNTS).document(user.uid).collection(FirestorePaths.RIDES).document(trip.id)
            suspendCancellableCoroutine { continuation ->
                docRef.set(normalizedTrip)
                    .addOnSuccessListener { continuation.resume(trip.id) }
                    .addOnFailureListener { continuation.resumeWithException(it) }
            }
        } else {
            tripDaoProvider().insertTrip(trip)
            trip.id
        }
    }

    fun getTranscripts(hostUid: String, tripId: String): Flow<List<TranscriptLine>> {
        return callbackFlow {
            val listener = firestore.collection(FirestorePaths.ACCOUNTS)
                .document(hostUid)
                .collection(FirestorePaths.RIDES)
                .document(tripId)
                .collection(FirestorePaths.RIDE_TRANSCRIPTS)
                .orderBy("timestamp", Query.Direction.ASCENDING)
                .addSnapshotListener { value, error ->
                    if (error != null) {
                        close(error)
                        return@addSnapshotListener
                    }
                    if (value != null) {
                        val lines = value.toObjects(TranscriptLine::class.java)
                        trySend(lines)
                    }
                }
            awaitClose { listener.remove() }
        }
    }

    fun getTranscriptEntries(hostUid: String, tripId: String): Flow<List<TranscriptEntry>> {
        return callbackFlow {
            val listener = firestore.collection(FirestorePaths.ACCOUNTS)
                .document(hostUid)
                .collection(FirestorePaths.RIDES)
                .document(tripId)
                .collection(FirestorePaths.RIDE_TRANSCRIPTS)
                .orderBy("timestamp", Query.Direction.ASCENDING)
                .addSnapshotListener { value, error ->
                    if (error != null) {
                        close(error)
                        return@addSnapshotListener
                    }
                    if (value != null) {
                        val entries = value.documents.mapNotNull { document ->
                            val line = document.toObject(TranscriptLine::class.java) ?: return@mapNotNull null
                            val statusRaw = document.getString("status")?.uppercase()
                            val resolvedStatus = when (statusRaw) {
                                TranscriptStatus.PROCESSING.name -> TranscriptStatus.PROCESSING
                                TranscriptStatus.ERROR.name -> TranscriptStatus.ERROR
                                TranscriptStatus.SUCCESS.name -> TranscriptStatus.SUCCESS
                                else -> if (line.isPartial) TranscriptStatus.PROCESSING else TranscriptStatus.SUCCESS
                            }
                            val resolvedText = when {
                                line.text.isNotBlank() -> line.text
                                resolvedStatus == TranscriptStatus.PROCESSING -> "Processing audio..."
                                resolvedStatus == TranscriptStatus.ERROR -> "Transcription error"
                                else -> ""
                            }
                            TranscriptEntry(
                                id = document.id,
                                tripId = line.tripId,
                                authorId = line.authorId,
                                authorName = line.authorName.ifBlank { "Unknown" },
                                text = resolvedText,
                                timestamp = line.timestamp,
                                status = resolvedStatus,
                                errorMessage = document.getString("errorMessage")?.takeIf { it.isNotBlank() }
                            )
                        }
                        trySend(entries)
                    }
                }
            awaitClose { listener.remove() }
        }
    }

    suspend fun insertTranscriptLine(transcriptLine: TranscriptLine, targetHostUid: String? = null) {
        val user = auth.currentUser
        if (user != null) {
            val destinationUid = targetHostUid ?: user.uid
            val lineWithAuth = transcriptLine.copy(
                authorId = user.uid,
                authorName = user.displayName ?: "Unknown",
                timestamp = transcriptLine.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis()
            )
            
            val tripRef = firestore.collection(FirestorePaths.ACCOUNTS).document(destinationUid).collection(FirestorePaths.RIDES).document(transcriptLine.tripId)
            val lineRef = tripRef.collection(FirestorePaths.RIDE_TRANSCRIPTS).document()
            suspendCancellableCoroutine<Unit> { continuation ->
                lineRef.set(lineWithAuth)
                    .addOnSuccessListener { continuation.resume(Unit) }
                    .addOnFailureListener { continuation.resumeWithException(it) }
            }
        } else {
            tripDaoProvider().insertTranscriptLine(transcriptLine)
        }
    }

    suspend fun upsertTranscriptEntryStatus(
        transcriptId: String,
        tripId: String,
        status: TranscriptStatus,
        text: String,
        timestamp: Long,
        targetHostUid: String? = null,
        errorMessage: String? = null
    ) {
        val user = auth.currentUser ?: return
        val destinationUid = targetHostUid ?: user.uid
        val payload: MutableMap<String, Any?> = mutableMapOf(
            "tripId" to tripId,
            "authorId" to user.uid,
            "authorName" to (user.displayName ?: "Unknown"),
            "text" to text,
            "timestamp" to timestamp,
            "isPartial" to (status == TranscriptStatus.PROCESSING),
            "status" to status.name
        )
        payload["errorMessage"] = errorMessage

        val tripRef = firestore.collection(FirestorePaths.ACCOUNTS)
            .document(destinationUid)
            .collection(FirestorePaths.RIDES)
            .document(tripId)
        val lineRef = tripRef.collection(FirestorePaths.RIDE_TRANSCRIPTS).document(transcriptId)
        lineRef.set(payload, SetOptions.merge()).await()
    }

    suspend fun deleteTrip(tripId: String) {
        val user = auth.currentUser
        if (user != null) {
            val tripRef = firestore.collection(FirestorePaths.ACCOUNTS)
                .document(user.uid)
                .collection(FirestorePaths.RIDES)
                .document(tripId)

            deleteCollectionInBatches(tripRef.collection(FirestorePaths.RIDE_TRANSCRIPTS))
            tripRef.delete().await()
        } else {
            tripDaoProvider().deleteTripById(tripId)
        }
    }

    private suspend fun deleteCollectionInBatches(
        collectionRef: CollectionReference,
        batchSize: Long = 200L
    ) {
        while (true) {
            val snapshot = collectionRef.limit(batchSize).get().await()
            if (snapshot.isEmpty) break

            val batch = firestore.batch()
            snapshot.documents.forEach { document ->
                batch.delete(document.reference)
            }
            batch.commit().await()

            if (snapshot.size() < batchSize.toInt()) {
                break
            }
        }
    }
}
