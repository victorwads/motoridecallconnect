package dev.wads.motoridecallconnect.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import dev.wads.motoridecallconnect.data.local.TripDao
import dev.wads.motoridecallconnect.data.local.TripWithTranscripts
import dev.wads.motoridecallconnect.data.model.TranscriptLine
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
