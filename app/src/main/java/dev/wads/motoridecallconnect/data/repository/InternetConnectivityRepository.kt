package dev.wads.motoridecallconnect.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import com.google.gson.GsonBuilder
import dev.wads.motoridecallconnect.data.model.ConnectionTransportMode
import dev.wads.motoridecallconnect.data.model.Device
import dev.wads.motoridecallconnect.data.model.InternetPeerDetails
import dev.wads.motoridecallconnect.data.model.UserProfile
import dev.wads.motoridecallconnect.data.model.WifiDirectState
import dev.wads.motoridecallconnect.data.remote.FirestorePaths
import dev.wads.motoridecallconnect.utils.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class InternetConnectivityRepository(
    private val socialRepository: SocialRepository,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeFriendInternetPeers(refreshIntervalMs: Long = DEFAULT_REFRESH_INTERVAL_MS): Flow<List<InternetPeerDetails>> {
        return socialRepository.getFriends()
            .flatMapLatest { friends ->
                val filteredFriends = friends.filter { it.uid.isNotBlank() }
                if (filteredFriends.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    flow {
                        while (currentCoroutineContext().isActive) {
                            emit(fetchInternetPeersSnapshot(filteredFriends))
                            delay(refreshIntervalMs)
                        }
                    }
                }
            }
            .catch { emit(emptyList()) }
            .flowOn(Dispatchers.IO)
    }

    suspend fun refreshFriendInternetPeersOnce(): List<InternetPeerDetails> {
        return withContext(Dispatchers.IO) {
            val friends = socialRepository.getFriendsOnce()
                .filter { it.uid.isNotBlank() }
            if (friends.isEmpty()) {
                return@withContext emptyList()
            }
            fetchInternetPeersSnapshot(friends)
        }
    }

    suspend fun publishLocalConnectionInfo(
        snapshot: NetworkUtils.NetworkSnapshot,
        wifiDirectState: WifiDirectState,
        isLocalHosting: Boolean
    ) {
        withContext(Dispatchers.IO) {
            val user = auth.currentUser ?: return@withContext
            val uid = user.uid
            val displayName = user.displayName?.trim().takeUnless { it.isNullOrBlank() } ?: "Moto Rider"
            val now = System.currentTimeMillis()

            val publicConnection = mapOf(
                "updatedAtMs" to now,
                "acceptingInternetCalls" to true,
                "signalingMode" to SIGNALING_MODE_FIRESTORE_INBOX,
                "primaryIp" to snapshot.primaryIpv4,
                "ipCandidates" to snapshot.ipv4Candidates.take(MAX_IP_CANDIDATES),
                "webrtc" to mapOf(
                    "signalInboxPath" to "${FirestorePaths.ACCOUNTS}/$uid/${FirestorePaths.SIGNAL_INBOX}",
                    "iceServers" to listOf(DEFAULT_STUN_SERVER)
                ),
                "interfaces" to snapshot.interfaceAddresses
                    .take(MAX_INTERFACE_ROWS)
                    .map { iface ->
                        mapOf(
                            "interfaceName" to iface.interfaceName,
                            "displayName" to iface.displayName,
                            "address" to iface.address,
                            "score" to iface.score,
                            "isIpv4" to iface.isIpv4
                        )
                    }
            )

            val privateConnection = mapOf(
                "updatedAtMs" to now,
                "acceptingInternetCalls" to true,
                "signalingMode" to SIGNALING_MODE_FIRESTORE_INBOX,
                "localHostingActive" to isLocalHosting,
                "localNetwork" to mapOf(
                    "primaryIp" to snapshot.primaryIpv4,
                    "ipCandidates" to snapshot.ipv4Candidates.take(MAX_IP_CANDIDATES)
                ),
                "wifiDirect" to mapOf(
                    "enabled" to wifiDirectState.enabled,
                    "connected" to wifiDirectState.connected,
                    "groupFormed" to wifiDirectState.groupFormed,
                    "groupOwner" to wifiDirectState.groupOwner,
                    "groupOwnerIp" to wifiDirectState.groupOwnerIp,
                    "infrastructureWifiConnected" to wifiDirectState.infrastructureWifiConnected
                ),
                "webrtc" to mapOf(
                    "signalingTransport" to SIGNALING_MODE_FIRESTORE_INBOX,
                    "signalInboxPath" to "${FirestorePaths.ACCOUNTS}/$uid/${FirestorePaths.SIGNAL_INBOX}",
                    "iceServers" to listOf(DEFAULT_STUN_SERVER)
                )
            )

            firestore.collection(FirestorePaths.ACCOUNTS_PUBLIC_INFO)
                .document(uid)
                .set(
                    mapOf(
                        "uid" to uid,
                        "displayName" to displayName,
                        "photoUrl" to user.photoUrl?.toString(),
                        "lastOnlineTime" to now,
                        "connectionPublic" to publicConnection
                    ),
                    SetOptions.merge()
                )
                .await()

            firestore.collection(FirestorePaths.ACCOUNTS)
                .document(uid)
                .collection(FirestorePaths.CONNECTION_PRIVATE)
                .document(CONNECTION_PRIVATE_DOC_ID)
                .set(privateConnection, SetOptions.merge())
                .await()
        }
    }

    private suspend fun fetchInternetPeersSnapshot(friends: List<UserProfile>): List<InternetPeerDetails> {
        val now = System.currentTimeMillis()
        return friends.map { friend ->
            runCatching {
                fetchInternetPeer(friend, now)
            }.getOrElse { error ->
                val fallbackName = friend.displayName.ifBlank { friend.uid }
                InternetPeerDetails(
                    uid = friend.uid,
                    displayName = fallbackName,
                    device = Device(
                        id = friend.uid,
                        name = fallbackName,
                        deviceName = fallbackName,
                        connectionTransport = ConnectionTransportMode.INTERNET
                    ),
                    canConnect = false,
                    usingPublicFallback = false,
                    privateDataAccessible = false,
                    acceptingInternetCalls = false,
                    signalingMode = null,
                    lastOnlineMs = null,
                    lastConnectionUpdateMs = null,
                    publicIpCandidates = emptyList(),
                    privateIpCandidates = emptyList(),
                    debugPublicJson = "{}",
                    debugPrivateJson = "{}",
                    warningMessage = error.message ?: "Falha ao carregar dados de conexão do amigo."
                )
            }
        }.sortedWith(
            compareByDescending<InternetPeerDetails> { it.canConnect }
                .thenByDescending { it.lastOnlineMs ?: 0L }
                .thenBy { it.displayName.lowercase() }
        )
    }

    private suspend fun fetchInternetPeer(friend: UserProfile, now: Long): InternetPeerDetails {
        val uid = friend.uid
        val publicSnapshot = firestore.collection(FirestorePaths.ACCOUNTS_PUBLIC_INFO)
            .document(uid)
            .get()
            .await()

        val privateSnapshotResult = runCatching {
            firestore.collection(FirestorePaths.ACCOUNTS)
                .document(uid)
                .collection(FirestorePaths.CONNECTION_PRIVATE)
                .document(CONNECTION_PRIVATE_DOC_ID)
                .get()
                .await()
        }
        val privateSnapshot = privateSnapshotResult.getOrNull()
        val privateSnapshotError = privateSnapshotResult.exceptionOrNull()

        val publicData = publicSnapshot.data.orEmpty().normalizeMap()
        val privateDocExists = privateSnapshot?.exists() == true
        val privateData = privateSnapshot?.data.orEmpty().normalizeMap()
        val privateDataAccessible = privateSnapshotError == null && privateDocExists

        val displayName = (publicData["displayName"] as? String)
            ?.takeIf { it.isNotBlank() }
            ?: friend.displayName.ifBlank { uid }

        val connectionPublic = publicData["connectionPublic"].asTypedMap()
        val localNetworkPrivate = privateData["localNetwork"].asTypedMap()
        val publicAcceptingCalls = connectionPublic["acceptingInternetCalls"] as? Boolean
        val publicSignalingMode = connectionPublic["signalingMode"].asTrimmedString()

        val publicIps = buildList {
            addAll(connectionPublic["ipCandidates"].asStringList())
            connectionPublic["primaryIp"].asTrimmedString()?.let(::add)
        }.distinct()

        val privateIps = buildList {
            addAll(localNetworkPrivate["ipCandidates"].asStringList())
            localNetworkPrivate["primaryIp"].asTrimmedString()?.let(::add)
        }.distinct()

        val lastOnlineMs = publicData["lastOnlineTime"].asLong()
        val lastConnectionUpdateMs = privateData["updatedAtMs"].asLong()
            ?: connectionPublic["updatedAtMs"].asLong()

        val recentlyOnline = lastOnlineMs != null && now - lastOnlineMs <= ONLINE_WINDOW_MS
        val canPublicFallback = (privateSnapshotError != null || !privateDocExists) &&
            recentlyOnline &&
            (publicAcceptingCalls != false) &&
            (publicSignalingMode == null || publicSignalingMode == SIGNALING_MODE_FIRESTORE_INBOX)
        val resolvedAcceptingCalls = (privateData["acceptingInternetCalls"] as? Boolean)
            ?: publicAcceptingCalls
            ?: canPublicFallback
        val resolvedSignalingMode = privateData["signalingMode"].asTrimmedString()
            ?: publicSignalingMode
            ?: if (canPublicFallback) SIGNALING_MODE_FIRESTORE_INBOX else null
        val strictPrivateReady = resolvedAcceptingCalls &&
            resolvedSignalingMode == SIGNALING_MODE_FIRESTORE_INBOX &&
            recentlyOnline
        val canConnect = strictPrivateReady || canPublicFallback

        val warning = when {
            privateSnapshotError is FirebaseFirestoreException &&
                privateSnapshotError.code == FirebaseFirestoreException.Code.PERMISSION_DENIED &&
                canPublicFallback -> "Sem acesso às informações privadas de conexão deste amigo. Usando fallback público."
            privateSnapshotError is FirebaseFirestoreException &&
                privateSnapshotError.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                "Sem acesso às informações privadas de conexão deste amigo. Revise as Firebase Rules."
            privateSnapshotError != null && canPublicFallback ->
                "Falha ao ler informações privadas de conexão. Usando fallback público."
            privateSnapshotError != null ->
                "Falha ao ler informações privadas de conexão deste amigo."
            !privateDocExists && canPublicFallback ->
                "Informações privadas ainda não publicadas por este amigo. Usando fallback público."
            !privateDocExists ->
                "Informações privadas de conexão deste amigo ainda não foram publicadas."
            !resolvedAcceptingCalls -> "Amigo não está aceitando chamadas via internet no momento."
            resolvedSignalingMode != SIGNALING_MODE_FIRESTORE_INBOX ->
                "Modo de sinalização incompatível: ${resolvedSignalingMode ?: "desconhecido"}."
            !recentlyOnline -> "Amigo offline ou desatualizado."
            else -> null
        }

        val allCandidates = (publicIps + privateIps).distinct()
        val device = Device(
            id = uid,
            name = displayName,
            deviceName = displayName,
            ip = allCandidates.firstOrNull(),
            port = null,
            candidateIps = allCandidates,
            connectionTransport = ConnectionTransportMode.INTERNET
        )

        return InternetPeerDetails(
            uid = uid,
            displayName = displayName,
            device = device,
            canConnect = canConnect,
            usingPublicFallback = canPublicFallback,
            privateDataAccessible = privateDataAccessible,
            acceptingInternetCalls = resolvedAcceptingCalls,
            signalingMode = resolvedSignalingMode,
            lastOnlineMs = lastOnlineMs,
            lastConnectionUpdateMs = lastConnectionUpdateMs,
            publicIpCandidates = publicIps,
            privateIpCandidates = privateIps,
            debugPublicJson = gson.toJson(publicData),
            debugPrivateJson = gson.toJson(privateData),
            warningMessage = warning
        )
    }

    private fun Map<String, Any?>.normalizeMap(): Map<String, Any?> {
        return entries.associate { (key, value) ->
            key to when (value) {
                is Map<*, *> -> value.entries
                    .filter { it.key is String }
                    .associate { it.key as String to it.value }
                    .normalizeMap()
                is List<*> -> value.map { item ->
                    if (item is Map<*, *>) {
                        item.entries
                            .filter { it.key is String }
                            .associate { it.key as String to it.value }
                            .normalizeMap()
                    } else {
                        item
                    }
                }
                else -> value
            }
        }
    }

    private fun Any?.asTypedMap(): Map<String, Any?> {
        return (this as? Map<*, *>)
            ?.entries
            ?.filter { it.key is String }
            ?.associate { it.key as String to it.value }
            ?: emptyMap()
    }

    private fun Any?.asStringList(): List<String> {
        return (this as? List<*>)
            ?.mapNotNull { value ->
                when (value) {
                    is String -> value.trim().takeIf { it.isNotEmpty() }
                    else -> value?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                }
            }
            ?: emptyList()
    }

    private fun Any?.asTrimmedString(): String? {
        return when (this) {
            is String -> trim().takeIf { it.isNotEmpty() }
            else -> this?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun Any?.asLong(): Long? {
        return when (this) {
            is Long -> this
            is Int -> toLong()
            is Double -> toLong()
            is Float -> toLong()
            is Number -> toLong()
            else -> null
        }
    }

    companion object {
        private const val CONNECTION_PRIVATE_DOC_ID = "current"
        private const val SIGNALING_MODE_FIRESTORE_INBOX = "firestore_inbox_v1"
        private const val DEFAULT_STUN_SERVER = "stun:stun.l.google.com:19302"
        private const val MAX_IP_CANDIDATES = 12
        private const val MAX_INTERFACE_ROWS = 12
        private const val ONLINE_WINDOW_MS = 90_000L
        private const val DEFAULT_REFRESH_INTERVAL_MS = 12_000L
        private val gson = GsonBuilder().setPrettyPrinting().create()
    }
}
