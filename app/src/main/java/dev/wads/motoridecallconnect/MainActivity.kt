package dev.wads.motoridecallconnect

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.room.Room
import dev.wads.motoridecallconnect.data.local.AppDatabase
import dev.wads.motoridecallconnect.data.repository.TripRepository
import dev.wads.motoridecallconnect.data.repository.InternetConnectivityRepository
import dev.wads.motoridecallconnect.data.repository.WifiDirectRepository
import dev.wads.motoridecallconnect.service.AudioService
import dev.wads.motoridecallconnect.data.model.Device
import dev.wads.motoridecallconnect.stt.queue.TranscriptionQueueSnapshot
import dev.wads.motoridecallconnect.ui.common.ViewModelFactory
import dev.wads.motoridecallconnect.ui.navigation.AppNavigation
import dev.wads.motoridecallconnect.ui.theme.MotoRideCallConnectTheme
import dev.wads.motoridecallconnect.ui.activetrip.ConnectionStatus
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), AudioService.ServiceCallback {
    companion object {
        private const val AUTO_CONNECT_COOLDOWN_MS = 15_000L
    }

    private data class AutoConnectContext(
        val enabled: Boolean,
        val transportSupportsAutoConnect: Boolean,
        val discoveredDevices: List<Device>,
        val connectionStatus: ConnectionStatus,
        val connectedPeer: Device?,
        val isHosting: Boolean,
        val friendIds: Set<String>
    )

    private var audioService: AudioService? = null
    private var currentlyPlayingChunkId by mutableStateOf<String?>(null)
    private var isBound = false
    private var isBindingService = false
    private val pendingServiceActions = mutableListOf<(AudioService) -> Unit>()
    private var friendsObserverJob: Job? = null
    private var autoConnectObserverJob: Job? = null
    private val friendIdsState = MutableStateFlow<Set<String>>(emptySet())
    private val autoConnectAttemptTimestamps = mutableMapOf<String, Long>()

    private val database by lazy {
        Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java, "motoride_database"
        ).build()
    }
    private val repository by lazy { TripRepository { database.tripDao() } }
    private val socialRepository by lazy { dev.wads.motoridecallconnect.data.repository.SocialRepository() }
    private val deviceDiscoveryRepository by lazy { dev.wads.motoridecallconnect.data.repository.DeviceDiscoveryRepository(applicationContext) }
    private val wifiDirectRepository by lazy { WifiDirectRepository(applicationContext) }
    private val internetConnectivityRepository by lazy { InternetConnectivityRepository(socialRepository) }
    private val viewModelFactory by lazy {
        ViewModelFactory(
            repository,
            socialRepository,
            deviceDiscoveryRepository,
            wifiDirectRepository,
            internetConnectivityRepository
        )
    }
    private val firebaseAuth by lazy { FirebaseAuth.getInstance() }
    private var lastObservedAuthUid: String? = null

    private val activeTripViewModel by viewModels<dev.wads.motoridecallconnect.ui.activetrip.ActiveTripViewModel> { viewModelFactory }
    private val tripHistoryViewModel by viewModels<dev.wads.motoridecallconnect.ui.history.TripHistoryViewModel> { viewModelFactory }
    private val tripDetailViewModel by viewModels<dev.wads.motoridecallconnect.ui.history.TripDetailViewModel> { viewModelFactory }
    private val loginViewModel by viewModels<dev.wads.motoridecallconnect.ui.login.LoginViewModel> { viewModelFactory }
    private val socialViewModel by viewModels<dev.wads.motoridecallconnect.ui.social.SocialViewModel> { viewModelFactory }
    private val pairingViewModel by viewModels<dev.wads.motoridecallconnect.ui.pairing.PairingViewModel> { viewModelFactory }
    private val settingsViewModel by viewModels<dev.wads.motoridecallconnect.ui.settings.SettingsViewModel>()
    private val authStateListener = FirebaseAuth.AuthStateListener { auth ->
        val currentUid = auth.currentUser?.uid
        if (currentUid == null) {
            lastObservedAuthUid = null
            friendIdsState.value = emptySet()
            friendsObserverJob?.cancel()
            friendsObserverJob = null
            return@AuthStateListener
        }

        if (currentUid == lastObservedAuthUid) return@AuthStateListener

        lastObservedAuthUid = currentUid
        lifecycleScope.launch {
            runCatching { socialRepository.updateMyPublicProfile() }
                .onFailure { error ->
                    Log.w("MainActivity", "Failed to update public profile for $currentUid", error)
                }
        }
        pairingViewModel.publishPresenceNow()
        restartFriendsObserver()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as AudioService.LocalBinder
            val boundService = binder.getService()
            audioService = boundService
            isBound = true
            isBindingService = false
            boundService.registerCallback(this@MainActivity)
            
            // Sync current state to newly connected service
            val tripState = activeTripViewModel.uiState.value
            val settingsState = settingsViewModel.uiState.value
            boundService.updateConfiguration(
                settingsState.operatingMode,
                settingsState.startCommand,
                settingsState.stopCommand,
                settingsState.sttEngine,
                settingsState.nativeSpeechLanguageTag,
                settingsState.whisperModelId,
                settingsState.vadStartDelaySeconds,
                settingsState.vadStopDelaySeconds,
                tripState.isTripActive,
                tripState.currentTripId,
                tripState.hostUid,
                tripState.tripPath,
                preferBluetoothAutomatically = settingsState.preferBluetoothAutomatically
            )
            boundService.setHostingEnabled(pairingViewModel.isHosting.value)

            if (pendingServiceActions.isNotEmpty()) {
                val actions = pendingServiceActions.toList()
                pendingServiceActions.clear()
                actions.forEach { action -> action(boundService) }
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            isBindingService = false
            audioService = null
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startAndBindAudioService()
        } else {
            pendingServiceActions.clear()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            MotoRideCallConnectTheme {
                val tripState by activeTripViewModel.uiState.collectAsState()
                val settingsState by settingsViewModel.uiState.collectAsState()
                val isHosting by pairingViewModel.isHosting.collectAsState()

                LaunchedEffect(
                    settingsState.operatingMode,
                    settingsState.startCommand,
                    settingsState.stopCommand,
                    settingsState.sttEngine,
                    settingsState.nativeSpeechLanguageTag,
                    settingsState.whisperModelId,
                    settingsState.vadStartDelaySeconds,
                    settingsState.vadStopDelaySeconds,
                    settingsState.preferBluetoothAutomatically,
                    tripState.isTripActive,
                    tripState.currentTripId,
                    tripState.hostUid,
                    tripState.tripPath
                ) {
                    audioService?.updateConfiguration(
                        settingsState.operatingMode,
                        settingsState.startCommand,
                        settingsState.stopCommand,
                        settingsState.sttEngine,
                        settingsState.nativeSpeechLanguageTag,
                        settingsState.whisperModelId,
                        settingsState.vadStartDelaySeconds,
                        settingsState.vadStopDelaySeconds,
                        tripState.isTripActive,
                        tripState.currentTripId,
                        tripState.hostUid,
                        tripState.tripPath,
                        preferBluetoothAutomatically = settingsState.preferBluetoothAutomatically
                    )
                }

                LaunchedEffect(settingsState.presenceUpdateIntervalSeconds) {
                    pairingViewModel.updatePresencePublishIntervalSeconds(
                        settingsState.presenceUpdateIntervalSeconds
                    )
                    pairingViewModel.publishPresenceNow()
                }

                LaunchedEffect(isHosting) {
                    if (isHosting) {
                        ensureAudioServiceReady { service ->
                            service.setHostingEnabled(true)
                        }
                    } else {
                        audioService?.setHostingEnabled(false)
                    }
                }

                AppNavigation(
                    activeTripViewModel = activeTripViewModel,
                    tripHistoryViewModel = tripHistoryViewModel,
                    tripDetailViewModel = tripDetailViewModel,
                    loginViewModel = loginViewModel,
                    socialViewModel = socialViewModel,
                    pairingViewModel = pairingViewModel,
                    settingsViewModel = settingsViewModel,
                    onStartTripClick = {
                        activeTripViewModel.startTrip()
                        ensureAudioServiceReady { service ->
                            val trip = activeTripViewModel.uiState.value
                            val settings = settingsViewModel.uiState.value
                            service.updateConfiguration(
                                settings.operatingMode,
                                settings.startCommand,
                                settings.stopCommand,
                                settings.sttEngine,
                                settings.nativeSpeechLanguageTag,
                                settings.whisperModelId,
                                settings.vadStartDelaySeconds,
                                settings.vadStopDelaySeconds,
                                trip.isTripActive,
                                trip.currentTripId,
                                trip.hostUid,
                                trip.tripPath,
                                preferBluetoothAutomatically = settings.preferBluetoothAutomatically
                            )
                        }
                    },
                    onEndTripClick = { 
                        activeTripViewModel.endTrip()
                    },
                    onConnectToDevice = { device ->
                        ensureAudioServiceReady { service ->
                            service.connectToPeer(device)
                        }
                    },
                    onDisconnectClick = {
                        audioService?.disconnect()
                    },
                    onPlayAudio = { id ->
                        Log.d("MainActivity", "onPlayAudio: id=$id, audioService=${audioService != null}")
                        ensureAudioServiceReady { service ->
                            service.playTranscriptionChunk(id)
                        }
                    },
                    onStopAudio = {
                        Log.d("MainActivity", "onStopAudio")
                        audioService?.stopPlayback()
                    },
                    onRetryTranscription = { id ->
                        Log.d("MainActivity", "onRetryTranscription: id=$id, audioService=${audioService != null}")
                        ensureAudioServiceReady { service ->
                            val trip = activeTripViewModel.uiState.value
                            val settings = settingsViewModel.uiState.value
                            service.updateConfiguration(
                                settings.operatingMode,
                                settings.startCommand,
                                settings.stopCommand,
                                settings.sttEngine,
                                settings.nativeSpeechLanguageTag,
                                settings.whisperModelId,
                                settings.vadStartDelaySeconds,
                                settings.vadStopDelaySeconds,
                                trip.isTripActive,
                                trip.currentTripId,
                                trip.hostUid,
                                trip.tripPath,
                                preferBluetoothAutomatically = settings.preferBluetoothAutomatically
                            )
                            service.retryTranscription(id)
                        }
                    },
                    currentlyPlayingId = currentlyPlayingChunkId
                )
            }
        }

        startAutoConnectObserver()
        restartFriendsObserver()
    }

    private fun ensureAudioServiceReady(action: (AudioService) -> Unit) {
        audioService?.let { service ->
            action(service)
            return
        }

        pendingServiceActions.add(action)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startAndBindAudioService()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startAndBindAudioService() {
        if (audioService != null || isBound || isBindingService) {
            return
        }
        isBindingService = true
        Intent(this, AudioService::class.java).also { intent ->
            startService(intent)
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun unbindAudioService() {
        if (isBound) {
            audioService?.unregisterCallback(this)
            unbindService(connection)
            isBound = false
        }
        isBindingService = false
        pendingServiceActions.clear()
        audioService = null
    }

    override fun onTranscriptUpdate(
        transcript: String,
        isFinal: Boolean,
        tripId: String?,
        hostUid: String?,
        tripPath: String?,
        timestampMs: Long?
    ) {
        runOnUiThread {
            activeTripViewModel.updateTranscript(
                newTranscript = transcript,
                isFinal = isFinal,
                targetTripId = tripId,
                transcriptTimestampMs = timestampMs
            )
        }
    }

    override fun onConnectionStatusChanged(status: dev.wads.motoridecallconnect.ui.activetrip.ConnectionStatus, peer: dev.wads.motoridecallconnect.data.model.Device?) {
        runOnUiThread {
            activeTripViewModel.onConnectionStatusChanged(status, peer)
            pairingViewModel.updateConnectionStatus(status, peer)
        }
    }

    override fun onTransmissionStateChanged(isLocalTransmitting: Boolean, isRemoteTransmitting: Boolean) {
        runOnUiThread {
            activeTripViewModel.onTransmissionStateChanged(isLocalTransmitting, isRemoteTransmitting)
        }
    }

    override fun onTripStatusChanged(isActive: Boolean, tripId: String?, hostUid: String?, tripPath: String?) {
        runOnUiThread {
            activeTripViewModel.onTripStatusChanged(isActive, tripId, hostUid, tripPath)
        }
    }

    override fun onTranscriptionQueueUpdated(snapshot: TranscriptionQueueSnapshot) {
        runOnUiThread {
            activeTripViewModel.onTranscriptionQueueUpdated(snapshot)
        }
    }

    override fun onAudioRouteChanged(routeLabel: String, isBluetoothActive: Boolean, isBluetoothRequired: Boolean) {
        runOnUiThread {
            activeTripViewModel.onAudioRouteChanged(routeLabel, isBluetoothActive, isBluetoothRequired)
        }
    }

    override fun onModelDownloadProgress(progress: Int) {
        runOnUiThread {
            activeTripViewModel.updateModelDownloadStatus(true, progress)
        }
    }

    override fun onModelDownloadStateChanged(isDownloading: Boolean, isSuccess: Boolean?) {
        runOnUiThread {
            activeTripViewModel.updateModelDownloadStatus(isDownloading, if(isDownloading) 0 else 100)
        }
    }

    override fun onPlaybackStateChanged(chunkId: String, isPlaying: Boolean) {
        runOnUiThread {
            currentlyPlayingChunkId = if (isPlaying) chunkId else null
        }
    }

    override fun onResume() {
        super.onResume()
        pairingViewModel.publishPresenceNow()
        if (pairingViewModel.isHosting.value) {
            pairingViewModel.stopDiscovery()
            pairingViewModel.stopWifiDirectDiscovery()
        } else {
            pairingViewModel.startDiscovery()
        }
    }

    override fun onPause() {
        super.onPause()
        val autoConnectEnabled = settingsViewModel.uiState.value.autoConnectNearbyFriends
        val hosting = pairingViewModel.isHosting.value
        if (!autoConnectEnabled && !hosting) {
            pairingViewModel.stopDiscovery()
        }
    }

    override fun onStart() {
        super.onStart()
        firebaseAuth.addAuthStateListener(authStateListener)
        audioService?.registerCallback(this)
    }

    override fun onStop() {
        super.onStop()
        firebaseAuth.removeAuthStateListener(authStateListener)
        audioService?.unregisterCallback(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        autoConnectObserverJob?.cancel()
        friendsObserverJob?.cancel()
        unbindAudioService()
    }

    private fun startAutoConnectObserver() {
        if (autoConnectObserverJob != null) {
            return
        }
        autoConnectObserverJob = lifecycleScope.launch {
            combine(
                settingsViewModel.uiState.map { it.autoConnectNearbyFriends },
                pairingViewModel.discoveredDevices,
                pairingViewModel.connectionStatus,
                pairingViewModel.connectedPeer,
                pairingViewModel.isHosting
            ) { enabled, discoveredDevices, connectionStatus, connectedPeer, isHosting ->
                AutoConnectContext(
                    enabled = enabled,
                    transportSupportsAutoConnect = false,
                    discoveredDevices = discoveredDevices,
                    connectionStatus = connectionStatus,
                    connectedPeer = connectedPeer,
                    isHosting = isHosting,
                    friendIds = emptySet()
                )
            }
                .combine(pairingViewModel.selectedTransport) { context, selectedTransport ->
                    context.copy(
                        transportSupportsAutoConnect =
                            selectedTransport == dev.wads.motoridecallconnect.data.model.ConnectionTransportMode.LOCAL_NETWORK
                    )
                }
                .combine(friendIdsState) { context, friendIds ->
                    context.copy(friendIds = friendIds)
                }
                .collect { context ->
                    maybeAutoConnectNearbyFriend(context)
                }
        }
    }

    private fun restartFriendsObserver() {
        val currentUid = firebaseAuth.currentUser?.uid
        if (currentUid.isNullOrBlank()) {
            friendIdsState.value = emptySet()
            friendsObserverJob?.cancel()
            friendsObserverJob = null
            return
        }

        friendsObserverJob?.cancel()
        friendsObserverJob = lifecycleScope.launch {
            socialRepository.getFriends()
                .map { friends ->
                    friends.mapNotNull { friend ->
                        friend.uid.takeIf { it.isNotBlank() }
                    }.toSet()
                }
                .catch { error ->
                    Log.w("MainActivity", "Failed to observe friends for auto-connect", error)
                    emit(emptySet())
                }
                .collect { friendIds ->
                    friendIdsState.value = friendIds
                }
        }
    }

    private fun maybeAutoConnectNearbyFriend(context: AutoConnectContext) {
        if (!context.enabled || !context.transportSupportsAutoConnect || context.isHosting) {
            return
        }
        if (context.connectionStatus == ConnectionStatus.CONNECTED || context.connectionStatus == ConnectionStatus.CONNECTING) {
            return
        }
        if (context.friendIds.isEmpty()) {
            return
        }

        val localUid = firebaseAuth.currentUser?.uid
        val candidate = context.discoveredDevices.firstOrNull { device ->
            !device.id.isBlank() &&
                context.friendIds.contains(device.id) &&
                device.id != localUid &&
                !device.ip.isNullOrBlank() &&
                device.port != null
        } ?: return

        val now = System.currentTimeMillis()
        val lastAttempt = autoConnectAttemptTimestamps[candidate.id] ?: 0L
        if (now - lastAttempt < AUTO_CONNECT_COOLDOWN_MS) {
            return
        }
        autoConnectAttemptTimestamps[candidate.id] = now
        trimAutoConnectAttempts()

        Log.i("MainActivity", "Auto-connecting to nearby friend ${candidate.id}/${candidate.name}")
        pairingViewModel.updateConnectionStatus(ConnectionStatus.CONNECTING, candidate)
        ensureAudioServiceReady { service ->
            service.connectToPeer(candidate)
        }
    }

    private fun trimAutoConnectAttempts() {
        if (autoConnectAttemptTimestamps.size <= 64) {
            return
        }
        val cutoff = System.currentTimeMillis() - AUTO_CONNECT_COOLDOWN_MS * 2
        autoConnectAttemptTimestamps.entries.removeAll { it.value < cutoff }
    }
}
