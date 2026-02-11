package dev.wads.motoridecallconnect.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wads.motoridecallconnect.R
import dev.wads.motoridecallconnect.ui.components.BigButton
import dev.wads.motoridecallconnect.ui.components.ButtonVariant
import dev.wads.motoridecallconnect.ui.components.StatusCard
import dev.wads.motoridecallconnect.ui.components.UserProfileView

import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import dev.wads.motoridecallconnect.stt.SttEngine
import dev.wads.motoridecallconnect.stt.WhisperModelCatalog
import dev.wads.motoridecallconnect.ui.activetrip.OperatingMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    operatingMode: OperatingMode,
    startCommand: String,
    stopCommand: String,
    isRecordingTranscript: Boolean,
    sttEngine: SttEngine,
    whisperModelId: String,
    vadStartDelaySeconds: Float,
    vadStopDelaySeconds: Float,
    onModeChange: (OperatingMode) -> Unit,
    onStartCommandChange: (String) -> Unit,
    onStopCommandChange: (String) -> Unit,
    onRecordingToggle: (Boolean) -> Unit,
    onSttEngineChange: (SttEngine) -> Unit,
    onWhisperModelChange: (String) -> Unit,
    onVadStartDelayChange: (Float) -> Unit,
    onVadStopDelayChange: (Float) -> Unit,
    onNavigateBack: () -> Unit,
    onTestAudio: () -> Unit,
    onLogout: () -> Unit
) {
    val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
    val isLoggedIn = user != null
    var whisperModelExpanded by remember { mutableStateOf(false) }
    val selectedWhisperModel = remember(whisperModelId) {
        WhisperModelCatalog.findById(whisperModelId) ?: WhisperModelCatalog.defaultOption
    }
    
    // Mock State - In a real app, this would come from a ViewModel/DataStore
    var preferBluetooth by remember { mutableStateOf(true) }
    var duckingIntensity by remember { mutableFloatStateOf(80f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        // Account Section
        StatusCard(title = stringResource(R.string.settings_account_section), icon = Icons.Default.Person) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                UserProfileView(userId = user?.uid)
                
                BigButton(
                    text = if (isLoggedIn) stringResource(R.string.sign_out) else stringResource(R.string.sign_in),
                    onClick = onLogout,
                    variant = ButtonVariant.Secondary,
                    icon = if (isLoggedIn) Icons.AutoMirrored.Filled.Logout else Icons.Default.Person,
                    fullWidth = true
                )
            }
        }

        // --- Configuration (Moved from Home) ---
        StatusCard(title = stringResource(R.string.config_header), icon = Icons.Default.Settings) {
            Text(stringResource(R.string.operation_mode_header), style = MaterialTheme.typography.titleSmall)
            
            OperatingMode.entries.forEach { mode ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = (operatingMode == mode),
                            onClick = { onModeChange(mode) }
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = (operatingMode == mode),
                        onClick = { onModeChange(mode) }
                    )
                    val modeText = when(mode) {
                        OperatingMode.VOICE_COMMAND -> stringResource(R.string.mode_voice_command)
                        OperatingMode.VOICE_ACTIVITY_DETECTION -> stringResource(R.string.mode_vad)
                        OperatingMode.CONTINUOUS_TRANSMISSION -> stringResource(R.string.mode_continuous)
                    }
                    Text(text = modeText)
                }
            }

            if (operatingMode == OperatingMode.VOICE_COMMAND) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = startCommand,
                    onValueChange = onStartCommandChange,
                    label = { Text(stringResource(R.string.start_command_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = stopCommand,
                    onValueChange = onStopCommandChange,
                    label = { Text(stringResource(R.string.stop_command_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (operatingMode == OperatingMode.VOICE_ACTIVITY_DETECTION) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.vad_delays_header),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = stringResource(R.string.vad_delay_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )

                Text(
                    text = stringResource(R.string.vad_start_delay_label, vadStartDelaySeconds),
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = vadStartDelaySeconds,
                    onValueChange = onVadStartDelayChange,
                    valueRange = 0f..5f,
                    steps = 49
                )

                Text(
                    text = stringResource(R.string.vad_stop_delay_label, vadStopDelaySeconds),
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = vadStopDelaySeconds,
                    onValueChange = onVadStopDelayChange,
                    valueRange = 0f..5f,
                    steps = 49
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.stt_engine_header), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(R.string.stt_engine_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )

            SttEngine.entries.forEach { engine ->
                val selected = sttEngine == engine
                val labelRes = if (engine == SttEngine.WHISPER) {
                    R.string.stt_engine_whisper
                } else {
                    R.string.stt_engine_native
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selected,
                            onClick = { onSttEngineChange(engine) }
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = { onSttEngineChange(engine) }
                    )
                    Text(
                        text = stringResource(labelRes),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }

            if (sttEngine == SttEngine.WHISPER) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(stringResource(R.string.whisper_model_header), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stringResource(R.string.whisper_model_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                )

                ExposedDropdownMenuBox(
                    expanded = whisperModelExpanded,
                    onExpandedChange = { whisperModelExpanded = !whisperModelExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedWhisperModel.displayName,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        label = { Text(stringResource(R.string.whisper_model_header)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = whisperModelExpanded)
                        }
                    )
                    DropdownMenu(
                        expanded = whisperModelExpanded,
                        onDismissRequest = { whisperModelExpanded = false }
                    ) {
                        WhisperModelCatalog.options.forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = model.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (model.id == selectedWhisperModel.id) {
                                                FontWeight.SemiBold
                                            } else {
                                                FontWeight.Normal
                                            }
                                        )
                                        Text(
                                            text = model.details,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    onWhisperModelChange(model.id)
                                    whisperModelExpanded = false
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = selectedWhisperModel.details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.record_transcript_label))
                Spacer(modifier = Modifier.weight(1f))
                Switch(
                    checked = isRecordingTranscript, 
                    onCheckedChange = onRecordingToggle
                )
            }
        }

        // Audio Section
        StatusCard(title = stringResource(R.string.audio_section), icon = Icons.AutoMirrored.Filled.VolumeUp) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.prefer_bluetooth_auto),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = preferBluetooth, onCheckedChange = { preferBluetooth = it })
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onTestAudio)
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.test_audio), style = MaterialTheme.typography.bodyMedium)
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Column {
                    Text(
                        text = stringResource(R.string.ducking_intensity_label, duckingIntensity.toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Slider(
                        value = duckingIntensity,
                        onValueChange = { duckingIntensity = it },
                        valueRange = 0f..100f
                    )
                }

                HorizontalDivider()

                Column {
                    Text(
                        text = stringResource(R.string.recommended_headphones_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Column(
                         modifier = Modifier
                             .fillMaxWidth()
                             .clip(RoundedCornerShape(12.dp))
                             .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                             .padding(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.headphones_samsung_buds3),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Text(
                            text = stringResource(R.string.headphones_samsung_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                         Text(
                            text = stringResource(R.string.headphones_others),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SettingsScreenPreview() {
    dev.wads.motoridecallconnect.ui.theme.MotoRideCallConnectTheme {
        androidx.compose.material3.Surface {
            SettingsScreen(
                operatingMode = OperatingMode.VOICE_COMMAND,
                startCommand = "iniciar",
                stopCommand = "parar",
                isRecordingTranscript = true,
                sttEngine = SttEngine.WHISPER,
                whisperModelId = WhisperModelCatalog.defaultOption.id,
                vadStartDelaySeconds = 0f,
                vadStopDelaySeconds = 1.5f,
                onModeChange = {},
                onStartCommandChange = {},
                onStopCommandChange = {},
                onRecordingToggle = {},
                onSttEngineChange = {},
                onWhisperModelChange = {},
                onVadStartDelayChange = {},
                onVadStopDelayChange = {},
                onTestAudio = {},
                onNavigateBack = {},
                onLogout = {}
            )
        }
    }
}
