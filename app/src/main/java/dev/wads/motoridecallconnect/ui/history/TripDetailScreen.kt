package dev.wads.motoridecallconnect.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.wads.motoridecallconnect.R
import dev.wads.motoridecallconnect.data.model.Trip
import dev.wads.motoridecallconnect.ui.components.BigButton
import dev.wads.motoridecallconnect.ui.components.ButtonVariant
import dev.wads.motoridecallconnect.ui.components.StatusCard
import dev.wads.motoridecallconnect.ui.components.UserProfileView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TripDetailScreen(
    tripId: String,
    onNavigateBack: () -> Unit
) {
    // Mock trip data fetch based on ID
    // In real app, consume ViewModel
    val trip = Trip(
        id = tripId,
        startTime = System.currentTimeMillis() - 3600000,
        endTime = System.currentTimeMillis(),
        duration = 3600000,
        peerDevice = "Galaxy S23 Ultra"
    )
    val mockTranscript = listOf(
        "10:00:00" to "Motor ligado, iniciando",
        "10:05:00" to "Vira a direita na próxima",
        "10:05:10" to "Ok, entendi"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onNavigateBack)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.history_title), color = MaterialTheme.colorScheme.primary)
        }

        StatusCard(title = stringResource(R.string.summary_title), icon = Icons.Default.Schedule) {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                     DetailItem(stringResource(R.string.date_label), formatDate(trip.startTime))
                     DetailItem(stringResource(R.string.duration_label), "01:00:00") // Mock
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                     Column {
                         Text(text = stringResource(R.string.with_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                         UserProfileView(userId = trip.participants.firstOrNull(), showId = false, avatarSize = 24)
                     }
                     DetailItem(stringResource(R.string.mode_label), stringResource(R.string.mode_automatic))
                }
            }
        }

        StatusCard(title = stringResource(R.string.full_transcript_title), icon = Icons.Default.Description) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                mockTranscript.forEach { (time, text) ->
                    Row {
                        Text(
                            text = "[$time]", 
                            style = MaterialTheme.typography.bodySmall, 
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(80.dp)
                        )
                        Text(text = text, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

         Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
             BigButton(
                 text = "Exportar",
                 onClick = {},
                 variant = ButtonVariant.Outline,
                 icon = Icons.Default.Download,
                 fullWidth = false,
                 modifier = Modifier.weight(1f)
             )
             BigButton(
                 text = "Compartilhar",
                 onClick = {},
                 variant = ButtonVariant.Outline,
                 icon = Icons.Default.Share,
                 fullWidth = false,
                 modifier = Modifier.weight(1f)
             )
         }
         
         BigButton(
             text = "Apagar viagem",
             onClick = {},
             variant = ButtonVariant.Secondary, // Ghost
             icon = Icons.Default.Delete,
             fullWidth = true
         )
    }
}

@Composable
fun DetailItem(label: String, value: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun TripDetailScreenPreview() {
    dev.wads.motoridecallconnect.ui.theme.MotoRideCallConnectTheme {
        androidx.compose.material3.Surface {
            TripDetailScreen(tripId = "1", onNavigateBack = {})
        }
    }
}
