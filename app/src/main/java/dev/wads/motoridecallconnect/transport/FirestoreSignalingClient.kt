package dev.wads.motoridecallconnect.transport

import android.util.Base64
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import dev.wads.motoridecallconnect.data.remote.FirestorePaths
import java.util.UUID

class FirestoreSignalingClient(
    private val listener: SignalingClient.SignalingListener
) {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    private var inboxRegistration: ListenerRegistration? = null
    private var listeningSinceMs: Long = System.currentTimeMillis()
    private var activeSessionId: String? = null
    private var activePeerUid: String? = null
    private var started = false
    private val processedMessageIds = LinkedHashSet<String>()

    fun start() {
        if (inboxRegistration != null) {
            return
        }
        started = true
        listeningSinceMs = System.currentTimeMillis()
        subscribeToInbox()
    }

    fun connectToPeer(peerUid: String, localPeerInfoPayload: String) {
        if (auth.currentUser?.uid.isNullOrBlank()) {
            listener.onSignalingError(IllegalStateException("Usuário precisa estar autenticado para conexão via internet."))
            return
        }
        if (peerUid.isBlank()) {
            listener.onSignalingError(IllegalArgumentException("UID remoto inválido para conexão via internet."))
            return
        }
        start()
        activeSessionId = UUID.randomUUID().toString()
        activePeerUid = peerUid
        listener.onPeerConnected(true)
        sendInternal("HELLO:$localPeerInfoPayload")
    }

    fun sendMessage(message: String) {
        if (!started) {
            start()
        }
        sendInternal(message)
    }

    fun clearSession(notify: Boolean = false) {
        activeSessionId = null
        activePeerUid = null
        if (notify) {
            listener.onPeerDisconnected()
        }
    }

    fun hasActiveSession(): Boolean {
        return !activeSessionId.isNullOrBlank() && !activePeerUid.isNullOrBlank()
    }

    fun close() {
        inboxRegistration?.remove()
        inboxRegistration = null
        activeSessionId = null
        activePeerUid = null
        started = false
        processedMessageIds.clear()
    }

    private fun subscribeToInbox() {
        val myUid = auth.currentUser?.uid
        if (myUid.isNullOrBlank()) {
            started = false
            Log.w(TAG, "Cannot subscribe Firestore signaling inbox: user not authenticated.")
            return
        }
        inboxRegistration?.remove()
        inboxRegistration = firestore.collection(FirestorePaths.ACCOUNTS)
            .document(myUid)
            .collection(FirestorePaths.SIGNAL_INBOX)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    listener.onSignalingError(error)
                    return@addSnapshotListener
                }
                val changes = snapshot?.documentChanges.orEmpty()
                changes.forEach { change ->
                    if (change.type != DocumentChange.Type.ADDED) {
                        return@forEach
                    }
                    val document = change.document
                    val docId = document.id
                    if (!markMessageAsProcessed(docId)) {
                        return@forEach
                    }
                    val createdAtMs = document.getLong("createdAtMs") ?: 0L
                    if (createdAtMs in 1 until (listeningSinceMs - STALE_MESSAGE_WINDOW_MS)) {
                        runCatching { document.reference.delete() }
                        return@forEach
                    }

                    val fromUid = document.getString("fromUid")
                    val sessionId = document.getString("sessionId")
                    val message = document.getString("message")
                    if (fromUid.isNullOrBlank() || sessionId.isNullOrBlank() || message.isNullOrBlank()) {
                        runCatching { document.reference.delete() }
                        return@forEach
                    }

                    handleInboundMessage(
                        fromUid = fromUid,
                        sessionId = sessionId,
                        message = message
                    )
                    runCatching { document.reference.delete() }
                }
            }
    }

    private fun handleInboundMessage(fromUid: String, sessionId: String, message: String) {
        val myUid = auth.currentUser?.uid
        if (fromUid == myUid) {
            return
        }

        val currentSessionId = activeSessionId
        if (currentSessionId == null) {
            activeSessionId = sessionId
            activePeerUid = fromUid
            val payload = message.substringAfter("HELLO:", missingDelimiterValue = "")
            if (payload.isNotBlank()) {
                listener.onPeerInfoReceived(payload)
            }
            listener.onPeerConnected(false)
            if (message == "HELLO" || message.startsWith("HELLO:")) {
                return
            }
        }

        val activeSession = activeSessionId
        if (sessionId != activeSession || activePeerUid != fromUid) {
            return
        }

        when {
            message.startsWith("HELLO:") -> {
                val payload = message.substringAfter("HELLO:", missingDelimiterValue = "")
                if (payload.isNotBlank()) {
                    listener.onPeerInfoReceived(payload)
                }
            }

            message == "BYE" -> {
                clearSession(notify = true)
            }

            message.startsWith("NAME:") -> listener.onPeerInfoReceived(message.substringAfter("NAME:"))
            message.startsWith("OFFER64:") -> {
                val decoded = decodePayload(message.substringAfter("OFFER64:"))
                listener.onOfferReceived(decoded)
            }
            message.startsWith("OFFER:") -> listener.onOfferReceived(message.substringAfter("OFFER:"))
            message.startsWith("ANSWER64:") -> {
                val decoded = decodePayload(message.substringAfter("ANSWER64:"))
                listener.onAnswerReceived(decoded)
            }
            message.startsWith("ANSWER:") -> listener.onAnswerReceived(message.substringAfter("ANSWER:"))
            message.startsWith("ICE64:") -> {
                val parts = message.split(":", limit = 4)
                if (parts.size == 4) {
                    val mid = parts[1]
                    val index = parts[2]
                    val sdp = decodePayload(parts[3])
                    listener.onIceCandidateReceived("$mid:$index:$sdp")
                }
            }
            message.startsWith("ICE:") -> listener.onIceCandidateReceived(message.substringAfter("ICE:"))
            message.startsWith("TX:") -> {
                val raw = message.substringAfter("TX:").trim().lowercase()
                val transmitting = raw == "on" || raw == "1" || raw == "true"
                listener.onPeerTransmissionStateReceived(transmitting)
            }
            message.startsWith("TRIP:START") -> {
                val parts = message.split(":", limit = 5)
                val tripId = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
                val hostUid = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
                val tripPath = parts.getOrNull(4)?.takeIf { it.isNotBlank() }
                listener.onTripStatusReceived(true, tripId, hostUid, tripPath)
            }
            message.startsWith("TRIP:STOP") -> {
                val parts = message.split(":", limit = 5)
                val tripId = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
                val hostUid = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
                val tripPath = parts.getOrNull(4)?.takeIf { it.isNotBlank() }
                listener.onTripStatusReceived(false, tripId, hostUid, tripPath)
            }
        }
    }

    private fun sendInternal(message: String) {
        val myUid = auth.currentUser?.uid
        val peerUid = activePeerUid
        val sessionId = activeSessionId
        if (myUid.isNullOrBlank() || peerUid.isNullOrBlank() || sessionId.isNullOrBlank()) {
            Log.w(TAG, "Dropped internet signaling message: no active internet session.")
            return
        }

        val payload = mapOf(
            "fromUid" to myUid,
            "sessionId" to sessionId,
            "message" to message,
            "createdAtMs" to System.currentTimeMillis()
        )

        firestore.collection(FirestorePaths.ACCOUNTS)
            .document(peerUid)
            .collection(FirestorePaths.SIGNAL_INBOX)
            .add(payload)
            .addOnFailureListener { error ->
                listener.onSignalingError(error)
            }
    }

    private fun markMessageAsProcessed(docId: String): Boolean {
        if (processedMessageIds.contains(docId)) {
            return false
        }
        processedMessageIds += docId
        if (processedMessageIds.size > MAX_PROCESSED_IDS) {
            val iterator = processedMessageIds.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        return true
    }

    private fun decodePayload(encoded: String): String {
        return String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8)
    }

    companion object {
        private const val TAG = "FirestoreSignaling"
        private const val STALE_MESSAGE_WINDOW_MS = 5 * 60_000L
        private const val MAX_PROCESSED_IDS = 500
    }
}
