package dev.wads.motoridecallconnect.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.wads.motoridecallconnect.R

/**
 * Shared "Full Transcript" card used by both the active-trip screen and the
 * trip-detail (history) screen.  It wraps a [StatusCard] around a
 * [TripTranscriptPanel] so the look-and-feel stays identical across pages.
 */
@Composable
fun FullTranscriptCard(
    transcriptItems: List<TranscriptFeedItem>,
    emptyText: String = stringResource(R.string.transcription_waiting_speech),
    queueStatusText: String? = null,
    maxTranscriptItems: Int = 50,
    onPlayAudio: ((String) -> Unit)? = null,
    onStopAudio: (() -> Unit)? = null,
    onRetry: ((String) -> Unit)? = null,
    currentlyPlayingId: String? = null
) {
    StatusCard(
        title = stringResource(R.string.full_transcript_title),
        icon = Icons.Default.Description
    ) {
        TripTranscriptPanel(
            transcriptItems = transcriptItems,
            emptyText = emptyText,
            queueStatusText = queueStatusText,
            maxTranscriptItems = maxTranscriptItems,
            onPlayAudio = onPlayAudio,
            onStopAudio = onStopAudio,
            onRetry = onRetry,
            currentlyPlayingId = currentlyPlayingId
        )
    }
}
