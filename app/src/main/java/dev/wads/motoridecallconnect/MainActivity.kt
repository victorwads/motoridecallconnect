package dev.wads.motoridecallconnect

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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

class MainActivity : ComponentActivity(), NsdHelper.NsdListener, AudioService.ServiceCallback {

    private var audioService: AudioService? = null
    private var isBound = false
    private lateinit var nsdHelper: NsdHelper

    private val database by lazy {
        Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java, "motoride_database"
        ).build()
    }
    private val repository by lazy { TripRepository(database.tripDao()) }
    private val socialRepository by lazy { dev.wads.motoridecallconnect.data.repository.SocialRepository() }
    private val viewModelFactory by lazy { ViewModelFactory(repository, socialRepository) }

    private val activeTripViewModel by viewModels<dev.wads.motoridecallconnect.ui.activetrip.ActiveTripViewModel> { viewModelFactory }
    private val tripHistoryViewModel by viewModels<dev.wads.motoridecallconnect.ui.history.TripHistoryViewModel> { viewModelFactory }
    private val loginViewModel by viewModels<dev.wads.motoridecallconnect.ui.login.LoginViewModel> { viewModelFactory }
    private val socialViewModel by viewModels<dev.wads.motoridecallconnect.ui.social.SocialViewModel> { viewModelFactory }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as AudioService.LocalBinder
            audioService = binder.getService()
            isBound = true
            audioService?.registerCallback(this@MainActivity)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
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
        nsdHelper = NsdHelper(this, this)

        setContent {
            MotoRideCallConnectTheme {
                val uiState by activeTripViewModel.uiState.collectAsState()

                LaunchedEffect(uiState.operatingMode, uiState.startCommand, uiState.stopCommand) {
                    audioService?.updateConfiguration(uiState.operatingMode, uiState.startCommand, uiState.stopCommand)
                }

                AppNavigation(
                    activeTripViewModel = activeTripViewModel,
                    tripHistoryViewModel = tripHistoryViewModel,
                    loginViewModel = loginViewModel,
                    socialViewModel = socialViewModel,
                    onStartTripClick = { requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onEndTripClick = { stopAndUnbindAudioService() },
                    onStartDiscoveryClick = { nsdHelper.discoverServices() }
                )
            }
        }
    }

    private fun startAndBindAudioService() {
        Intent(this, AudioService::class.java).also { intent ->
            startService(intent)
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
        nsdHelper.registerService(8080) // Using a fixed port for now
    }

    private fun stopAndUnbindAudioService() {
        if (isBound) {
            unbindService(connection)
        }
        Intent(this, AudioService::class.java).also { intent ->
            stopService(intent)
        }
        nsdHelper.tearDown()
    }

    override fun onTranscriptUpdate(transcript: String, isFinal: Boolean) {
        runOnUiThread {
            activeTripViewModel.updateTranscript(transcript, isFinal)
        }
    }

    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
        activeTripViewModel.addService(serviceInfo)
    }

    override fun onServiceLost(serviceInfo: NsdServiceInfo) {
        activeTripViewModel.removeService(serviceInfo)
    }

    override fun onResume() {
        super.onResume()
        nsdHelper.discoverServices()
    }

    override fun onPause() {
        super.onPause()
        nsdHelper.stopDiscovery()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAndUnbindAudioService()
    }
}