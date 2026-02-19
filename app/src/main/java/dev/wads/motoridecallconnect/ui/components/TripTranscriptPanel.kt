package dev.wads.motoridecallconnect.ui.components

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wads.motoridecallconnect.R

enum class TranscriptQueueViewStatus {
    PENDING,
    PROCESSING,
    FAILED,
    SUCCESS,
}

data class TranscriptQueueViewItem(
    val id: String,
    val timestampMs: Long,
    val status: TranscriptQueueViewStatus,
    val failureReason: String? = null
)

@Composable
fun TripTranscriptPanel(
    transcriptItems: List<TranscriptFeedItem>,
    emptyText: String,
    queueStatusText: String? = null,
    queueItems: List<TranscriptQueueViewItem> = emptyList(),
    maxTranscriptItems: Int = 50,
    maxQueueItems: Int = 10,
    onPlayAudio: ((String) -> Unit)? = null,
    onRetry: ((String) -> Unit)? = null
) {
    androidx.compose.foundation.layout.Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!queueStatusText.isNullOrBlank()) {
            Text(
                text = queueStatusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (queueItems.isNotEmpty()) {
            queueItems.takeLast(maxQueueItems).forEach { queueItem ->
                TranscriptQueueLine(item = queueItem)
            }
        }

        TranscriptFeed(
            items = transcriptItems,
            emptyText = emptyText,
            maxItems = maxTranscriptItems,
            onPlayAudio = onPlayAudio,
            onRetry = onRetry
        )
    }
}

@Composable
private fun TranscriptQueueLine(item: TranscriptQueueViewItem) {
    val timeLabel = remember(item.timestampMs) {
        DateFormat.format("HH:mm:ss", item.timestampMs).toString()
    }
    val statusLabel = when (item.status) {
        TranscriptQueueViewStatus.PENDING -> androidx.compose.ui.res.stringResource(R.string.transcription_item_pending)
        TranscriptQueueViewStatus.PROCESSING -> androidx.compose.ui.res.stringResource(R.string.transcription_item_processing)
        TranscriptQueueViewStatus.FAILED -> androidx.compose.ui.res.stringResource(R.string.transcription_item_failed)
        TranscriptQueueViewStatus.SUCCESS -> "" // TODO: Replace with actual string
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = timeLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (item.status) {
                TranscriptQueueViewStatus.PROCESSING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                }
                TranscriptQueueViewStatus.FAILED -> {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp)
                    )
                }
                TranscriptQueueViewStatus.PENDING -> Unit
                TranscriptQueueViewStatus.SUCCESS -> Unit
            }
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.bodySmall,
                color = when (item.status) {
                    TranscriptQueueViewStatus.FAILED -> MaterialTheme.colorScheme.error
                    TranscriptQueueViewStatus.PROCESSING -> MaterialTheme.colorScheme.primary
                    TranscriptQueueViewStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                    TranscriptQueueViewStatus.SUCCESS -> MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }

    if (item.status == TranscriptQueueViewStatus.FAILED && !item.failureReason.isNullOrBlank()) {
        Text(
            text = item.failureReason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1
        )
    }
}
