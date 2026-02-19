package dev.wads.motoridecallconnect.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TripRepository(private val tripDaoProvider: () -> TripDao) {
    companion object {
        private const val TAG = "TripRepository"
        private const val REFERENCE_TRIP_ID_PREFIX = "ref__"
        private const val REFERENCE_TRIP_ID_SEPARATOR = "__"
    }

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    private data class ParticipatedRideReference(
        val hostUid: String,
        val tripId: String,
        val tripPath: String?,
        val joinedAtMs: Long
    )

    private data class TripTarget(
        val hostUid: String,
        val canonicalTripId: String,
        val isReference: Boolean,
        val referenceDocId: String?
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getAllTrips(): Flow<List<Trip>> {
        val user = auth.currentUser
        return if (user != null) {
            combine(
                observeOwnedTrips(user.uid),
                observeParticipatedRideReferences(user.uid)
                    .flatMapLatest { references -> observeTripsFromParticipatedReferences(references) }
            ) { ownedTrips, participatedTrips ->
                (ownedTrips + participatedTrips)
                    .distinctBy { it.id }
                    .sortedByDescending { it.startTime }
            }
        } else {
            tripDaoProvider().getAllTrips()
        }
    }

    private fun observeOwnedTrips(uid: String): Flow<List<Trip>> {
        return callbackFlow {
            val listener = firestore.collection(FirestorePaths.ACCOUNTS)
                .document(uid)
                .collection(FirestorePaths.RIDES)
                .orderBy("startTime", Query.Direction.DESCENDING)
                .addSnapshotListener { value, error ->
                    if (error != null) {
                        close(error)
                        return@addSnapshotListener
                    }
                    val trips = value?.documents?.mapNotNull { document ->
                        safeTrip(document)
                    }.orEmpty()
                    trySend(trips)
                }
            awaitClose { listener.remove() }
        }
    }

    private fun observeParticipatedRideReferences(uid: String): Flow<List<ParticipatedRideReference>> {
        return callbackFlow {
            val listener = firestore.collection(FirestorePaths.ACCOUNTS)
                .document(uid)
                .collection(FirestorePaths.PARTICIPATED_RIDES)
                .addSnapshotListener { value, error ->
                    if (error != null) {
                        close(error)
                        return@addSnapshotListener
                    }
                    val refs = value?.documents?.mapNotNull { document ->
                        val hostUid = document.getString("hostUid")?.trim().orEmpty()
                        val tripId = document.getString("tripId")?.trim().orEmpty()
                        if (hostUid.isBlank() || tripId.isBlank()) {
                            return@mapNotNull null
                        }
                        ParticipatedRideReference(
                            hostUid = hostUid,
                            tripId = tripId,
                            tripPath = document.getString("tripPath")?.takeIf { it.isNotBlank() },
                            joinedAtMs = document.getLong("joinedAtMs") ?: System.currentTimeMillis()
                        )
                    }.orEmpty()
                    trySend(refs)
                }
            awaitClose { listener.remove() }
        }
    }

    private fun observeTripsFromParticipatedReferences(
        references: List<ParticipatedRideReference>
    ): Flow<List<Trip>> {
        if (references.isEmpty()) {
            return flowOf(emptyList())
        }

        return callbackFlow {
            val refsByKey = references.associateBy { ref ->
                buildParticipatedRideRefDocId(ref.hostUid, ref.tripId)
            }
            val tripsByRefKey = mutableMapOf<String, Trip>()
            val listeners = mutableListOf<ListenerRegistration>()

            trySend(emptyList())

            refsByKey.forEach { (refKey, ref) ->
                val listener = firestore.collection(FirestorePaths.ACCOUNTS)
                    .document(ref.hostUid)
                    .collection(FirestorePaths.RIDES)
                    .document(ref.tripId)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            close(error)
                            return@addSnapshotListener
                        }

                        val parsedTrip = snapshot?.takeIf { it.exists() }?.let { safeTrip(it) }
                        if (parsedTrip == null) {
                            tripsByRefKey.remove(refKey)
                        } else {
                            tripsByRefKey[refKey] = parsedTrip.copy(
                                id = buildReferenceTripId(ref.hostUid, parsedTrip.id.ifBlank { ref.tripId })
                            )
                        }

                        val ordered = tripsByRefKey.values
                            .toList()
                            .sortedByDescending { it.startTime }
                        trySend(ordered)
                    }
                listeners.add(listener)
            }

            awaitClose { listeners.forEach { it.remove() } }
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

                distinctTripIds.forEach { historyTripId ->
                    val target = parseTripTarget(historyTripId, user.uid)
                    val listener = firestore.collection(FirestorePaths.ACCOUNTS)
                        .document(target.hostUid)
                        .collection(FirestorePaths.RIDES)
                        .document(target.canonicalTripId)
                        .collection(FirestorePaths.RIDE_TRANSCRIPTS)
                        .limit(1)
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                close(error)
                                return@addSnapshotListener
                            }

                            transcriptStatus[historyTripId] = snapshot?.isEmpty == false
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
                val target = parseTripTarget(tripId, user.uid)
                val tripRef = firestore.collection(FirestorePaths.ACCOUNTS)
                    .document(target.hostUid)
                    .collection(FirestorePaths.RIDES)
                    .document(target.canonicalTripId)
                val listener = tripRef.addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        close(error)
                        return@addSnapshotListener
                    }
                    if (snapshot != null && snapshot.exists()) {
                        val trip = safeTrip(snapshot)
                        if (trip != null) {
                            // Keep the requested history identifier so downstream actions
                            // (detail route, delete reference, etc.) stay deterministic.
                            trySend(TripWithTranscripts(trip.copy(id = tripId), emptyList()))
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
                        val lines = value.documents.mapNotNull { document ->
                            safeTranscriptLine(document, fallbackTripId = tripId)
                        }.sortedBy { line -> line.timestamp }
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
                            val line = safeTranscriptLine(document, fallbackTripId = tripId) ?: return@mapNotNull null
                            val statusRaw = document.getString("status")?.uppercase()
                            val resolvedStatus = when (statusRaw) {
                                TranscriptStatus.QUEUED.name -> TranscriptStatus.QUEUED
                                TranscriptStatus.PROCESSING.name -> TranscriptStatus.PROCESSING
                                TranscriptStatus.ERROR.name -> TranscriptStatus.ERROR
                                TranscriptStatus.SUCCESS.name -> TranscriptStatus.SUCCESS
                                else -> if (line.isPartial) TranscriptStatus.PROCESSING else TranscriptStatus.SUCCESS
                            }
                            val resolvedText = when {
                                line.text.isNotBlank() -> line.text
                                resolvedStatus == TranscriptStatus.QUEUED -> "Queued for transcription..."
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
                                errorMessage = document.getString("errorMessage")?.takeIf { it.isNotBlank() },
                                audioFileName = document.getString("audioFileName")?.takeIf { it.isNotBlank() }
                            )
                        }
                        trySend(entries)
                    }
                }
            awaitClose { listener.remove() }
        }
    }

    fun getTranscriptEntriesForHistoryTrip(historyTripId: String): Flow<List<TranscriptEntry>> {
        val user = auth.currentUser ?: return flowOf(emptyList())
        val target = parseTripTarget(historyTripId, user.uid)
        return getTranscriptEntries(target.hostUid, target.canonicalTripId)
    }

    suspend fun upsertParticipatedRideReference(
        hostUid: String,
        tripId: String,
        tripPath: String?,
        joinedAtMs: Long = System.currentTimeMillis()
    ) {
        val user = auth.currentUser ?: return
        val normalizedHostUid = hostUid.trim()
        val normalizedTripId = tripId.trim()
        if (normalizedHostUid.isBlank() || normalizedTripId.isBlank()) {
            return
        }
        if (normalizedHostUid == user.uid) {
            return
        }

        val payload = mapOf(
            "hostUid" to normalizedHostUid,
            "tripId" to normalizedTripId,
            "tripPath" to (tripPath?.takeIf { it.isNotBlank() }),
            "joinedAtMs" to joinedAtMs
        )
        firestore.collection(FirestorePaths.ACCOUNTS)
            .document(user.uid)
            .collection(FirestorePaths.PARTICIPATED_RIDES)
            .document(buildParticipatedRideRefDocId(normalizedHostUid, normalizedTripId))
            .set(payload, SetOptions.merge())
            .await()
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
            val target = parseTripTarget(tripId, user.uid)
            if (target.isReference) {
                target.referenceDocId?.let { referenceDocId ->
                    firestore.collection(FirestorePaths.ACCOUNTS)
                        .document(user.uid)
                        .collection(FirestorePaths.PARTICIPATED_RIDES)
                        .document(referenceDocId)
                        .delete()
                        .await()
                }
                return
            }

            val tripRef = firestore.collection(FirestorePaths.ACCOUNTS)
                .document(target.hostUid)
                .collection(FirestorePaths.RIDES)
                .document(target.canonicalTripId)

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

    private fun safeTrip(document: DocumentSnapshot): Trip? {
        val parsed = runCatching { document.toObject(Trip::class.java) }
            .onFailure { error ->
                Log.w(TAG, "Trip parsing fallback for document ${document.id}", error)
            }
            .getOrNull()

        if (parsed != null) {
            return parsed.copy(
                id = parsed.id.ifBlank { document.id }
            )
        }

        val raw = document.data ?: return null
        val participants = (raw["participants"] as? List<*>)
            ?.mapNotNull { item -> item as? String }
            ?.map { value -> value.trim() }
            ?.filter { value -> value.isNotBlank() }
            ?.distinct()
            ?: emptyList()

        return Trip(
            id = raw["id"].asStringOrNull()?.ifBlank { document.id } ?: document.id,
            startTime = raw["startTime"].asLong(default = System.currentTimeMillis()),
            endTime = raw["endTime"].asLongOrNull(),
            duration = raw["duration"].asLongOrNull(),
            peerDevice = raw["peerDevice"].asStringOrNull(),
            participants = participants
        )
    }

    private fun safeTranscriptLine(document: DocumentSnapshot, fallbackTripId: String): TranscriptLine? {
        val parsed = runCatching { document.toObject(TranscriptLine::class.java) }
            .onFailure { error ->
                Log.w(TAG, "TranscriptLine parsing fallback for document ${document.id}", error)
            }
            .getOrNull()

        if (parsed != null) {
            return parsed.copy(
                tripId = parsed.tripId.ifBlank { fallbackTripId },
                authorName = parsed.authorName.ifBlank { "Unknown" },
                timestamp = parsed.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis()
            )
        }

        val raw = document.data ?: return null
        val statusRaw = raw["status"].asStringOrNull()?.uppercase()
        val inferredPartial = when (statusRaw) {
            TranscriptStatus.PROCESSING.name -> true
            TranscriptStatus.ERROR.name,
            TranscriptStatus.SUCCESS.name -> false
            else -> raw["isPartial"].asBoolean(default = false)
        }
        return TranscriptLine(
            id = raw["id"].asLongOrNull() ?: document.id.toLongOrNull() ?: 0L,
            tripId = raw["tripId"].asStringOrNull()?.ifBlank { fallbackTripId } ?: fallbackTripId,
            authorId = raw["authorId"].asStringOrNull().orEmpty(),
            authorName = raw["authorName"].asStringOrNull()?.ifBlank { "Unknown" } ?: "Unknown",
            text = raw["text"].asStringOrNull().orEmpty(),
            timestamp = raw["timestamp"].asLong(default = System.currentTimeMillis()),
            isPartial = inferredPartial
        )
    }

    private fun buildParticipatedRideRefDocId(hostUid: String, tripId: String): String {
        return "$hostUid$REFERENCE_TRIP_ID_SEPARATOR$tripId"
    }

    private fun buildReferenceTripId(hostUid: String, tripId: String): String {
        return "$REFERENCE_TRIP_ID_PREFIX$hostUid$REFERENCE_TRIP_ID_SEPARATOR$tripId"
    }

    private fun parseTripTarget(historyTripId: String, fallbackHostUid: String): TripTarget {
        if (!historyTripId.startsWith(REFERENCE_TRIP_ID_PREFIX)) {
            return TripTarget(
                hostUid = fallbackHostUid,
                canonicalTripId = historyTripId,
                isReference = false,
                referenceDocId = null
            )
        }

        val encoded = historyTripId.removePrefix(REFERENCE_TRIP_ID_PREFIX)
        val separatorIndex = encoded.indexOf(REFERENCE_TRIP_ID_SEPARATOR)
        if (separatorIndex <= 0 || separatorIndex >= encoded.length - REFERENCE_TRIP_ID_SEPARATOR.length) {
            Log.w(TAG, "Invalid reference trip identifier '$historyTripId'. Falling back to owner trip lookup.")
            return TripTarget(
                hostUid = fallbackHostUid,
                canonicalTripId = historyTripId,
                isReference = false,
                referenceDocId = null
            )
        }

        val hostUid = encoded.substring(0, separatorIndex).trim()
        val canonicalTripId = encoded.substring(separatorIndex + REFERENCE_TRIP_ID_SEPARATOR.length).trim()
        if (hostUid.isBlank() || canonicalTripId.isBlank()) {
            return TripTarget(
                hostUid = fallbackHostUid,
                canonicalTripId = historyTripId,
                isReference = false,
                referenceDocId = null
            )
        }

        return TripTarget(
            hostUid = hostUid,
            canonicalTripId = canonicalTripId,
            isReference = true,
            referenceDocId = buildParticipatedRideRefDocId(hostUid, canonicalTripId)
        )
    }

    private fun Any?.asStringOrNull(): String? {
        return when (this) {
            is String -> this
            else -> null
        }
    }

    private fun Any?.asLong(default: Long): Long {
        return asLongOrNull() ?: default
    }

    private fun Any?.asLongOrNull(): Long? {
        return when (this) {
            is Long -> this
            is Int -> this.toLong()
            is Double -> this.toLong()
            is Float -> this.toLong()
            is String -> this.toLongOrNull()
            else -> null
        }
    }

    private fun Any?.asBoolean(default: Boolean): Boolean {
        return when (this) {
            is Boolean -> this
            is String -> this.equals("true", ignoreCase = true)
            is Number -> this.toInt() != 0
            else -> default
        }
    }
}
