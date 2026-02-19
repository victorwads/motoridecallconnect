package dev.wads.motoridecallconnect.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Panel that shows a queue status line (if provided) followed by the
 * transcript feed list.  Queue items are **not** rendered individually
 * because they already appear in the Firestore-backed transcript feed
 * once processed.
 */
@Composable
fun TripTranscriptPanel(
    transcriptItems: List<TranscriptFeedItem>,
    emptyText: String,
    queueStatusText: String? = null,
    maxTranscriptItems: Int = 50,
    onPlayAudio: ((String) -> Unit)? = null,
    onStopAudio: (() -> Unit)? = null,
    onRetry: ((String) -> Unit)? = null,
    currentlyPlayingId: String? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!queueStatusText.isNullOrBlank()) {
            Text(
                text = queueStatusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        TranscriptFeed(
            items = transcriptItems,
            emptyText = emptyText,
            maxItems = maxTranscriptItems,
            onPlayAudio = onPlayAudio,
            onStopAudio = onStopAudio,
            onRetry = onRetry,
            currentlyPlayingId = currentlyPlayingId
        )
    }
}
