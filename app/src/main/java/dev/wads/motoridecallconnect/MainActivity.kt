package dev.wads.motoridecallconnect

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.IBinder
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
import dev.wads.motoridecallconnect.transport.NsdHelper
import dev.wads.motoridecallconnect.ui.common.ViewModelFactory
import dev.wads.motoridecallconnect.ui.navigation.AppNavigation
import dev.wads.motoridecallconnect.ui.theme.MotoRideCallConnectTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), AudioService.ServiceCallback {

    private var audioService: AudioService? = null
    private var isBound = false

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

    private val activeTripViewModel by viewModels<dev.wads.motoridecallconnect.ui.activetrip.ActiveTripViewModel> { viewModelFactory }
    private val tripHistoryViewModel by viewModels<dev.wads.motoridecallconnect.ui.history.TripHistoryViewModel> { viewModelFactory }
    private val tripDetailViewModel by viewModels<dev.wads.motoridecallconnect.ui.history.TripDetailViewModel> { viewModelFactory }
    private val loginViewModel by viewModels<dev.wads.motoridecallconnect.ui.login.LoginViewModel> { viewModelFactory }
    private val socialViewModel by viewModels<dev.wads.motoridecallconnect.ui.social.SocialViewModel> { viewModelFactory }
    private val pairingViewModel by viewModels<dev.wads.motoridecallconnect.ui.pairing.PairingViewModel> { viewModelFactory }
    private val settingsViewModel by viewModels<dev.wads.motoridecallconnect.ui.settings.SettingsViewModel>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as AudioService.LocalBinder
            val boundService = binder.getService()
            audioService = boundService
            isBound = true
            boundService.registerCallback(this@MainActivity)
            
            // Sync current state to newly connected service
            val tripState = activeTripViewModel.uiState.value
            val settingsState = settingsViewModel.uiState.value
            boundService.updateConfiguration(
                settingsState.operatingMode,
                settingsState.startCommand,
                settingsState.stopCommand,
                tripState.isTripActive,
                tripState.currentTripId
            )
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            audioService = null
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startAndBindAudioService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            socialRepository.updateMyPublicProfile()
        }
        
        // Ensure AudioService is ready for discovery/pairing
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startAndBindAudioService()
        }

        setContent {
            MotoRideCallConnectTheme {
                val tripState by activeTripViewModel.uiState.collectAsState()
                val settingsState by settingsViewModel.uiState.collectAsState()
                val isHosting by pairingViewModel.isHosting.collectAsState()

                LaunchedEffect(
                    settingsState.operatingMode,
                    settingsState.startCommand,
                    settingsState.stopCommand,
                    tripState.isTripActive,
                    tripState.currentTripId
                ) {
                    audioService?.updateConfiguration(
                        settingsState.operatingMode, 
                        settingsState.startCommand, 
                        settingsState.stopCommand,
                        tripState.isTripActive,
                        tripState.currentTripId
                    )
                }

                LaunchedEffect(isHosting) {
                    if (isHosting) {
                        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
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
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            startAndBindAudioService()
                        } else {
                            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                        activeTripViewModel.startTrip()
                    },
                    onEndTripClick = { 
                        stopAndUnbindAudioService()
                        activeTripViewModel.endTrip()
                    },
                    onConnectToDevice = { device ->
                        if (audioService == null) {
                            startAndBindAudioService()
                            // Service binding is asynchronous. In a real app, we'd queue this or wait.
                            // For now, let's try calling it, but it might still be null for a few ms.
                            lifecycleScope.launch {
                                // Simple retry mechanism to wait for binding
                                for (i in 1..10) {
                                    if (audioService != null) {
                                        audioService?.connectToPeer(device)
                                        break
                                    }
                                    kotlinx.coroutines.delay(100)
                                }
                            }
                        } else {
                            audioService?.connectToPeer(device)
                        }
                    },
                    onDisconnectClick = {
                        audioService?.disconnect()
                    }
                )
            }
        }
    }

    private fun startAndBindAudioService() {
        Intent(this, AudioService::class.java).also { intent ->
            startService(intent)
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun stopAndUnbindAudioService() {
        if (isBound) {
            unbindService(connection)
        }
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
            pairingViewModel.updateConnectionStatus(status == dev.wads.motoridecallconnect.ui.activetrip.ConnectionStatus.CONNECTED)
        }
    }

    override fun onTripStatusChanged(isActive: Boolean, tripId: String?) {
        runOnUiThread {
            activeTripViewModel.onTripStatusChanged(isActive, tripId)
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
        deviceDiscoveryRepository.startDiscovery()
    }

    override fun onPause() {
        super.onPause()
        deviceDiscoveryRepository.stopDiscovery()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAndUnbindAudioService()
    }
}
