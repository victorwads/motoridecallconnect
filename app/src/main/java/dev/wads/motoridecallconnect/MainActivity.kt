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
import androidx.room.Room
import dev.wads.motoridecallconnect.data.local.AppDatabase
import dev.wads.motoridecallconnect.data.repository.TripRepository
import dev.wads.motoridecallconnect.service.AudioService
import dev.wads.motoridecallconnect.ui.common.ViewModelFactory
import dev.wads.motoridecallconnect.ui.navigation.AppNavigation
import dev.wads.motoridecallconnect.ui.theme.MotoRideCallConnectTheme
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), AudioService.ServiceCallback {

    private var audioService: AudioService? = null
    private var isBound = false
    private var isBindingService = false
    private val pendingServiceActions = mutableListOf<(AudioService) -> Unit>()

    private val database by lazy {
        Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java, "motoride_database"
        ).build()
    }
    private val repository by lazy { TripRepository { database.tripDao() } }
    private val socialRepository by lazy { dev.wads.motoridecallconnect.data.repository.SocialRepository() }
    private val deviceDiscoveryRepository by lazy { dev.wads.motoridecallconnect.data.repository.DeviceDiscoveryRepository(applicationContext) }
    private val viewModelFactory by lazy { ViewModelFactory(repository, socialRepository, deviceDiscoveryRepository) }
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
                settingsState.whisperModelId,
                tripState.isTripActive,
                tripState.currentTripId,
                tripState.hostUid,
                tripState.tripPath
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
                    settingsState.whisperModelId,
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
                        settingsState.whisperModelId,
                        tripState.isTripActive,
                        tripState.currentTripId,
                        tripState.hostUid,
                        tripState.tripPath
                    )
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
                                settings.whisperModelId,
                                trip.isTripActive,
                                trip.currentTripId,
                                trip.hostUid,
                                trip.tripPath
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
                    }
                )
            }
        }
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

    private fun stopAndUnbindAudioService() {
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        isBindingService = false
        pendingServiceActions.clear()
        audioService = null
        Intent(this, AudioService::class.java).also { intent ->
            stopService(intent)
        }
    }

    override fun onTranscriptUpdate(transcript: String, isFinal: Boolean) {
        runOnUiThread {
            activeTripViewModel.updateTranscript(transcript, isFinal)
        }
    }

    override fun onConnectionStatusChanged(status: dev.wads.motoridecallconnect.ui.activetrip.ConnectionStatus, peer: dev.wads.motoridecallconnect.data.model.Device?) {
        runOnUiThread {
            activeTripViewModel.onConnectionStatusChanged(status, peer)
            pairingViewModel.updateConnectionStatus(status, peer)
        }
    }

    override fun onTripStatusChanged(isActive: Boolean, tripId: String?, hostUid: String?, tripPath: String?) {
        runOnUiThread {
            activeTripViewModel.onTripStatusChanged(isActive, tripId, hostUid, tripPath)
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

    override fun onResume() {
        super.onResume()
        if (pairingViewModel.isHosting.value) {
            pairingViewModel.stopDiscovery()
        } else {
            pairingViewModel.startDiscovery()
        }
    }

    override fun onPause() {
        super.onPause()
        pairingViewModel.stopDiscovery()
    }

    override fun onStart() {
        super.onStart()
        firebaseAuth.addAuthStateListener(authStateListener)
    }

    override fun onStop() {
        super.onStop()
        firebaseAuth.removeAuthStateListener(authStateListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAndUnbindAudioService()
    }
}
