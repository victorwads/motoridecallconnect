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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wads.motoridecallconnect.R
import dev.wads.motoridecallconnect.ui.components.BigButton
import dev.wads.motoridecallconnect.ui.components.ButtonVariant
import dev.wads.motoridecallconnect.ui.components.StatusCard
import dev.wads.motoridecallconnect.ui.components.UserProfileView

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onTestAudio: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
    val isLoggedIn = user != null
    
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

        // Audio Section
        StatusCard(title = stringResource(R.string.audio_section), icon = Icons.Default.VolumeUp) {
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
            SettingsScreen(onTestAudio = {}, onNavigateBack = {}, onLogout = {})
        }
    }
}
