package dev.wads.motoridecallconnect

import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.wads.motoridecallconnect.ui.theme.MotoRideCallConnectTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

class StorageManagementActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MotoRideCallConnectTheme {
                StorageManagementRoute(
                    onBack = { finish() },
                    scanStorage = { scanStorageSnapshot() }
                )
            }
        }
    }

    private suspend fun scanStorageSnapshot(): StorageSnapshot = withContext(Dispatchers.IO) {
        val dataRoot = applicationContext.dataDir
        val appDataUsageBytes = directorySize(dataRoot)
        val audioFiles = collectAudioFiles(dataRoot)
        val audioUsageBytes = audioFiles.sumOf { it.sizeBytes }
        StorageSnapshot(
            appDataUsageBytes = appDataUsageBytes,
            audioUsageBytes = audioUsageBytes,
            dataRootPath = dataRoot.absolutePath,
            audioFiles = audioFiles,
            scannedAtMs = System.currentTimeMillis()
        )
    }

    private fun directorySize(root: File): Long {
        if (!root.exists()) return 0L
        val stack = ArrayDeque<File>()
        stack.add(root)
        var total = 0L
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (current.isFile) {
                total += current.length().coerceAtLeast(0L)
                continue
            }
            current.listFiles()?.forEach { child ->
                stack.add(child)
            }
        }
        return total
    }

    private fun collectAudioFiles(root: File): List<AudioFileInfo> {
        if (!root.exists()) return emptyList()

        val supportedExtensions = setOf(
            "pcm", "wav", "m4a", "aac", "mp3", "opus", "ogg", "amr", "3gp", "flac", "webm"
        )
        val stack = ArrayDeque<File>()
        val collected = mutableListOf<AudioFileInfo>()
        stack.add(root)

        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (current.isDirectory) {
                current.listFiles()?.forEach { child ->
                    stack.add(child)
                }
                continue
            }
            if (!current.isFile) continue

            val extension = current.extension.lowercase(Locale.US)
            if (extension !in supportedExtensions) continue

            val relativePath = current.absolutePath
                .removePrefix(root.absolutePath)
                .trimStart(File.separatorChar)

            collected.add(
                AudioFileInfo(
                    name = current.name,
                    relativePath = relativePath,
                    sizeBytes = current.length().coerceAtLeast(0L),
                    lastModifiedMs = current.lastModified()
                )
            )
        }

        return collected.sortedByDescending { it.lastModifiedMs }
    }
}

private data class StorageSnapshot(
    val appDataUsageBytes: Long,
    val audioUsageBytes: Long,
    val dataRootPath: String,
    val audioFiles: List<AudioFileInfo>,
    val scannedAtMs: Long
)

private data class AudioFileInfo(
    val name: String,
    val relativePath: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long
)

private data class StorageUiState(
    val isLoading: Boolean = true,
    val snapshot: StorageSnapshot? = null,
    val errorMessage: String? = null
)

@Composable
private fun StorageManagementRoute(
    onBack: () -> Unit,
    scanStorage: suspend () -> StorageSnapshot
) {
    var uiState by androidx.compose.runtime.remember { mutableStateOf(StorageUiState()) }
    val scope = rememberCoroutineScope()
    val genericError = stringResource(R.string.storage_scan_error_generic)

    fun refresh() {
        scope.launch {
            uiState = uiState.copy(isLoading = true, errorMessage = null)
            uiState = runCatching { scanStorage() }
                .fold(
                    onSuccess = { snapshot ->
                        StorageUiState(isLoading = false, snapshot = snapshot, errorMessage = null)
                    },
                    onFailure = { error ->
                        StorageUiState(
                            isLoading = false,
                            snapshot = null,
                            errorMessage = error.message?.takeIf { it.isNotBlank() } ?: genericError
                        )
                    }
                )
        }
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    StorageManagementScreen(
        uiState = uiState,
        onBack = onBack,
        onRefresh = ::refresh
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorageManagementScreen(
    uiState: StorageUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.storage_management_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.storage_refresh_desc)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        if (uiState.isLoading && uiState.snapshot == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            uiState.snapshot?.let { snapshot ->
                item {
                    Text(
                        text = stringResource(
                            R.string.storage_last_scanned,
                            formatDateTime(snapshot.scannedAtMs)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                item {
                    Text(
                        text = stringResource(R.string.storage_data_path, snapshot.dataRootPath),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                item {
                    StorageMetricCard(
                        icon = Icons.Default.Storage,
                        title = stringResource(R.string.storage_total_usage),
                        sizeBytes = snapshot.appDataUsageBytes
                    )
                }

                item {
                    StorageMetricCard(
                        icon = Icons.Default.Folder,
                        title = stringResource(R.string.storage_audio_usage),
                        sizeBytes = snapshot.audioUsageBytes
                    )
                }

                item {
                    Text(
                        text = stringResource(R.string.storage_audio_count, snapshot.audioFiles.size),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                item {
                    Text(
                        text = stringResource(R.string.storage_audio_list_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (snapshot.audioFiles.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.storage_no_audio_files),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(snapshot.audioFiles, key = { it.relativePath }) { audio ->
                        AudioFileCard(audio = audio)
                    }
                }
            }

            uiState.errorMessage?.let { message ->
                item {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageMetricCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    sizeBytes: Long
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = Formatter.formatShortFileSize(context, sizeBytes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun AudioFileCard(audio: AudioFileInfo) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = audio.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = audio.relativePath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(
                    R.string.storage_file_size,
                    Formatter.formatShortFileSize(context, audio.sizeBytes)
                ),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = stringResource(
                    R.string.storage_file_date,
                    formatDateTime(audio.lastModifiedMs)
                ),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun formatDateTime(timestampMs: Long): String {
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
        .format(Date(timestampMs))
}
